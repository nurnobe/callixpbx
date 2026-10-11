package com.callix.pbx.ui.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.callix.pbx.BuildConfig
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.databinding.ActivityLoginBinding
import com.callix.pbx.service.LocationService
import com.callix.pbx.service.SipManager
import com.callix.pbx.service.SipService
import com.callix.pbx.ui.main.MainActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()
    private lateinit var prefs: PreferenceManager
    private val callPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startServicesAndNavigate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getInstance(this)
        setupUI()
        observeViewModel()
        loadSavedData()
    }

    private fun setupUI() {
        // The footer states the version this build actually is. It used to be a
        // hardcoded "v1.0.4" in the layout, which the build had long outgrown.
        binding.tvVersion.text =
            getString(R.string.login_version_format, BuildConfig.VERSION_NAME)

        // Login button click
        binding.btnLogin.setOnClickListener {
            val apiUrl = binding.etApiUrl.text.toString().trim()
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val rememberMe = binding.checkRememberMe.isChecked

            val sipManager = try {
                SipManager.getInstance(this)
            } catch (t: Throwable) {
                showError("SIP service could not start on this phone")
                return@setOnClickListener
            }

            viewModel.login(
                apiUrl = apiUrl,
                username = username,
                password = password,
                rememberMe = rememberMe,
                prefs = prefs,
                sipManager = sipManager
            )
        }

        // Settings icon for API URL visibility toggle
        binding.tvShowApiUrl.setOnClickListener {
            val isVisible = binding.tilApiUrl.visibility == View.VISIBLE
            binding.tilApiUrl.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvShowApiUrl.text = if (isVisible) "▼ Server Settings" else "▲ Server Settings"
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is LoginUiState.Idle -> {
                        setLoading(false)
                    }
                    is LoginUiState.Loading -> {
                        setLoading(true)
                    }
                    is LoginUiState.Success -> {
                        setLoading(false)
                        requestCallPermissionsThenContinue()
                    }
                    is LoginUiState.Error -> {
                        setLoading(false)
                        showError(state.message)
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.text = if (loading) "" else "Sign In"
    }

    private fun loadSavedData() {
        if (prefs.rememberMe && prefs.apiUrl.isNotEmpty()) {
            binding.etApiUrl.setText(prefs.apiUrl)
            binding.etUsername.setText(prefs.username)
            binding.etPassword.setText(prefs.password)
            binding.checkRememberMe.isChecked = true
        } else if (prefs.apiUrl.isNotEmpty()) {
            binding.etApiUrl.setText(prefs.apiUrl)
        }
    }

    private fun startSipService() {
        val serviceIntent = Intent(this, SipService::class.java).apply {
            action = SipService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun requestCallPermissionsThenContinue() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // Location is captured every five minutes while the user is signed in,
            // and the owner requires it to stay on: once
            // granted, nothing inside the app can switch it back off. Accepting
            // this prompt is therefore the ONE unavoidable user action, and it is
            // asked for here, at first login, rather than from a settings screen.
            //
            // ACCESS_BACKGROUND_LOCATION is deliberately NOT part of this batch:
            // since Android 11 the system refuses it when it is requested together
            // with a foreground location permission. It has to be granted on its
            // own from system Settings, which LocationFragment offers.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (required.isEmpty()) startServicesAndNavigate()
        else callPermissionsLauncher.launch(required.toTypedArray())
    }

    private fun startServicesAndNavigate() {
        startSipService()
        // Unconditional. This used to be gated on prefs.isOnDuty, which a member of
        // staff could leave false from the Location screen -- so the app could run
        // all day and never report a single fix. Reporting location is now a
        // property of being signed in, not a choice the user makes.
        startLocationService()
        navigateToMain()
    }

    private fun startLocationService() {
        prefs.isOnDuty = true
        prefs.isLocationTracking = true
        val locationIntent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        ContextCompat.startForegroundService(this, locationIntent)
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.error_color))
            .setTextColor(getColor(android.R.color.white))
            .show()
    }
}
