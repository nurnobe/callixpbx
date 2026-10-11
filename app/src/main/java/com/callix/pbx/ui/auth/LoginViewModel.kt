package com.callix.pbx.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.LoginRequest
import com.callix.pbx.data.remote.RetrofitClient
import com.callix.pbx.service.SipManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val message: String) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

class LoginViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState

    fun login(
        apiUrl: String,
        username: String,
        password: String,
        rememberMe: Boolean,
        prefs: PreferenceManager,
        sipManager: SipManager
    ) {
        val targetUrl = apiUrl.ifBlank { prefs.apiUrl }.ifBlank { "https://app.callix.com.bd" }

        if (username.isBlank()) {
            _uiState.value = LoginUiState.Error("Please enter your extension or username")
            return
        }
        if (password.isBlank()) {
            _uiState.value = LoginUiState.Error("Please enter your password")
            return
        }

        _uiState.value = LoginUiState.Loading

        viewModelScope.launch {
            try {
                val apiService = RetrofitClient.getApiService(targetUrl)

                val trimmedUsername = username.trim()
                // The PBX endpoint authenticates with extension/username only.
                // Never send an extension value in the email field.
                val request = LoginRequest(
                    username = trimmedUsername,
                    password = password
                )

                val response = apiService.login(request)

                if (response.isSuccessful) {
                    val loginResponse = response.body()
                    val token = loginResponse?.token ?: loginResponse?.data?.token

                    if (!token.isNullOrEmpty() || loginResponse?.success == true) {
                        val effectiveToken = token ?: ""
                        val userObj = loginResponse?.user
                        val dataObj = loginResponse?.data

                        val effectiveEmail = userObj?.email ?: dataObj?.email ?: ""
                        // The softphone login carries the EXTENSION's own name in data.displayName
                        // (server: buildSipAccount().display_name = agent_name || extension), whereas
                        // user.name is the tenant ACCOUNT name -- every extension in production has a
                        // different agent_name, so preferring user.name showed the account name on the
                        // dialer, in call logs and on the call screen. A `data` object exists ONLY for a
                        // softphone login (an email login never returns one), so an email login still
                        // falls back to user.name exactly as before -- this flip cannot regress it.
                        val displayName = dataObj?.displayName ?: userObj?.name ?: dataObj?.name ?: trimmedUsername
                        val userId = userObj?.id?.toString() ?: dataObj?.id?.toString() ?: ""

                        // Derive extension (e.g. "101" from "101@number" or direct input)
                        var ext = dataObj?.extension ?: userObj?.extension ?: if (trimmedUsername.contains("@")) {
                            trimmedUsername.substringBefore("@")
                        } else {
                            trimmedUsername
                        }

                        // Save session
                        prefs.apiUrl = targetUrl
                        prefs.rememberMe = rememberMe
                        prefs.saveUserSession(
                            username = trimmedUsername,
                            password = password,
                            extension = ext,
                            displayName = displayName,
                            token = effectiveToken,
                            userId = userId,
                            email = effectiveEmail
                        )
                        prefs.locationExtension = ext

                        // Save SIP configuration
                        // The web/API host is not the SIP registrar. Use the
                        // PBX domain whenever the login response omits it.
                        val sipServer = dataObj?.sipServer?.takeIf { it.isNotBlank() }
                            ?: "sip.callix.com.bd"
                        val sipPort = dataObj?.sipPort ?: 5060
                        // SIP credentials are per-extension. The API should
                        // return them after authenticating extension@tenant.
                        //
                        // The fallback is the identity the user actually typed
                        // ("<ext>@<main number>"), never the bare extension: this PBX
                        // names every Asterisk endpoint "<ext>@<number>" because
                        // extension numbers repeat across tenants, so registering with
                        // a bare number matches no endpoint and is answered with 401.
                        val sipIdentityFallback = if (trimmedUsername.contains("@")) trimmedUsername else ext
                        val sipUsername = dataObj?.sipUsername?.takeIf { it.isNotBlank() } ?: sipIdentityFallback
                        val sipPassword = dataObj?.sipPassword?.takeIf { it.isNotBlank() } ?: password
                        val transport = dataObj?.transport ?: "UDP"

                        prefs.sipServer = sipServer
                        prefs.sipUsername = sipUsername
                        prefs.sipPassword = sipPassword
                        prefs.sipPort = sipPort
                        prefs.sipTransport = transport

                        // Fetch extension list if token is available
                        if (effectiveToken.isNotEmpty()) {
                            try {
                                val extResponse = apiService.getPbxExtensions("Bearer $effectiveToken")
                                if (extResponse.isSuccessful) {
                                    val extensions = extResponse.body()?.data
                                    val matchedExt = extensions?.firstOrNull { it.extension == ext } ?: extensions?.firstOrNull()
                                    if (matchedExt != null) {
                                        ext = matchedExt.extension
                                        prefs.extension = ext
                                        prefs.locationExtension = ext
                                        Timber.d("PBX extension verified from server: $ext")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Could not fetch pbx extensions list")
                            }
                        }

                        // Register SIP
                        sipManager.register(
                            sipServer = sipServer,
                            sipPort = sipPort,
                            username = sipUsername,
                            password = sipPassword,
                            displayName = displayName,
                            transport = transport
                        )

                        _uiState.value = LoginUiState.Success("Login successful!")
                    } else {
                        _uiState.value = LoginUiState.Error(
                            loginResponse?.message ?: loginResponse?.error ?: "Login failed"
                        )
                    }
                } else {
                    val errorMsg = when (response.code()) {
                        400 -> "Invalid credentials format or bad request"
                        401 -> "Invalid username or password"
                        403 -> "PBX service or organization is inactive"
                        404 -> "Server endpoint not found"
                        429 -> "Too many login attempts. Please wait 15 minutes."
                        500 -> "Internal server error"
                        else -> "Login failed (code ${response.code()})"
                    }
                    _uiState.value = LoginUiState.Error(errorMsg)
                }
            } catch (e: Exception) {
                Timber.e(e, "Login error")
                val errorMsg = when {
                    e.message?.contains("Unable to resolve host") == true -> "Unable to connect to server. Check URL and internet connection."
                    e.message?.contains("timeout") == true -> "Connection timed out"
                    else -> "Network error: ${e.message}"
                }
                _uiState.value = LoginUiState.Error(errorMsg)
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}
