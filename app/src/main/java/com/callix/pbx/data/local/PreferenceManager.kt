package com.callix.pbx.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class PreferenceManager private constructor(context: Context) {

    /*
     * Some older/vendor Android keystores can fail while MasterKey itself is
     * being constructed. Keep that work inside the guarded factory so a bad
     * keystore can never prevent the launcher activity from opening.
     */
    private val prefs: SharedPreferences = createPreferences(context)

    private fun createPreferences(context: Context): SharedPreferences {
        fun encryptedPreferences(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        return try {
            encryptedPreferences()
        } catch (_: Throwable) {
            // A corrupt keyset can be recovered once. If the device keystore
            // still rejects it, use normal app-private storage rather than
            // crashing before the user can reach the login screen.
            try {
                deleteEncryptedPrefs(context)
                encryptedPreferences()
            } catch (_: Throwable) {
                context.getSharedPreferences("callix_prefs", Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * Remove the encrypted preference file on EVERY api level this app supports.
     *
     * `Context.deleteSharedPreferences()` arrived in API 24, and this app now reaches
     * back to 23 for the older handsets. Unqualified, that call throws
     * NoSuchMethodError on Android 6 -- in the single code path whose entire purpose
     * is to recover from a broken keystore. The recovery would have crashed the app
     * instead of rescuing it, on exactly the phones that need the rescue most.
     *
     * On 23 the store is emptied through the ordinary preferences API and then its
     * backing file is unlinked, which is what the platform implementation does itself.
     */
    private fun deleteEncryptedPrefs(context: Context) {
        val name = ENCRYPTED_PREFS_NAME
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(name)
            return
        }
        // Clear the cached values first so the dead keyset is not handed back to the
        // current process, then drop the file so it cannot be reread on the next start.
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.applicationContext.filesDir.parentFile, "shared_prefs/$name.xml").delete()
    }

    companion object {
        /** One spelling for the encrypted store, shared by create and delete. */
        private const val ENCRYPTED_PREFS_NAME = "callix_secure_prefs"

        private const val KEY_API_URL = "api_url"
        private const val KEY_SIP_SERVER = "sip_server"
        private const val KEY_SIP_USERNAME = "sip_username"
        private const val KEY_SIP_PASSWORD = "sip_password"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_EXTENSION = "extension"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LOCATION_TRACKING = "location_tracking"
        private const val KEY_IS_ON_DUTY = "is_on_duty"
        private const val KEY_LOCATION_EXTENSION = "location_extension"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_SIP_PORT = "sip_port"
        private const val KEY_SIP_TRANSPORT = "sip_transport"
        private const val KEY_RINGTONE_URI = "ringtone_uri"

        @Volatile
        private var instance: PreferenceManager? = null

        fun getInstance(context: Context): PreferenceManager {
            return instance ?: synchronized(this) {
                instance ?: PreferenceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // API Configuration
    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, "https://app.callix.com.bd") ?: "https://app.callix.com.bd"
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    var sipServer: String
        get() = prefs.getString(KEY_SIP_SERVER, "sip.callix.com.bd") ?: "sip.callix.com.bd"
        set(value) = prefs.edit().putString(KEY_SIP_SERVER, value).apply()

    /** Credentials supplied by the API for the authenticated PBX extension. */
    var sipUsername: String
        get() = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIP_USERNAME, value).apply()

    var sipPassword: String
        get() = prefs.getString(KEY_SIP_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIP_PASSWORD, value).apply()

    var sipPort: Int
        get() = prefs.getInt(KEY_SIP_PORT, 5060)
        set(value) = prefs.edit().putInt(KEY_SIP_PORT, value).apply()

    var sipTransport: String
        get() = prefs.getString(KEY_SIP_TRANSPORT, "UDP") ?: "UDP"
        set(value) = prefs.edit().putString(KEY_SIP_TRANSPORT, value).apply()

    /** User-selected incoming-call ringtone URI; blank means Android default. */
    var ringtoneUri: String
        get() = prefs.getString(KEY_RINGTONE_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RINGTONE_URI, value).apply()

    // User Credentials
    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var extension: String
        get() = prefs.getString(KEY_EXTENSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTENSION, value).apply()

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String
        get() = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_ME, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_ME, value).apply()

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var isLocationTracking: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_TRACKING, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_TRACKING, value).apply()

    /** On duty = location heartbeat active. Off duty = service stopped. */
    var isOnDuty: Boolean
        get() = prefs.getBoolean(KEY_IS_ON_DUTY, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ON_DUTY, value).apply()

    /** Extension used for location reporting (default: SIP username) */
    var locationExtension: String
        get() = prefs.getString(KEY_LOCATION_EXTENSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOCATION_EXTENSION, value).apply()

    /** Effective extension: locationExtension > extension > username */
    val effectiveExtension: String
        get() = locationExtension.ifEmpty { extension.ifEmpty { username } }

    fun clearUserData() {
        prefs.edit().apply {
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_EXTENSION)
            remove(KEY_DISPLAY_NAME)
            remove(KEY_AUTH_TOKEN)
            remove(KEY_IS_LOGGED_IN)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_SIP_USERNAME)
            remove(KEY_SIP_PASSWORD)
            apply()
        }
    }

    fun saveUserSession(
        username: String,
        password: String,
        extension: String,
        displayName: String,
        token: String,
        userId: String,
        email: String
    ) {
        prefs.edit().apply {
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putString(KEY_EXTENSION, extension)
            putString(KEY_DISPLAY_NAME, displayName)
            putString(KEY_AUTH_TOKEN, token)
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            apply()
        }
    }
}
