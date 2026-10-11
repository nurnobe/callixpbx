package com.callix.pbx.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.callix.pbx.BuildConfig
import com.callix.pbx.CallixApplication
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.ExtensionLocationRequest
import com.callix.pbx.data.model.LoginRequest
import com.callix.pbx.data.remote.RetrofitClient
import com.callix.pbx.ui.main.MainActivity
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import timber.log.Timber
import java.time.Instant

/**
 * LocationService handles:
 *  - FusedLocationProviderClient GPS tracking
 *  - Filtering via usable() gate before sending
 *  - POST /api/pbx/extension-locations with extension
 *  - On-duty heartbeat: every 5 minutes, without requiring a call
 *  - Call trigger: an optional extra fresh fix when a call answer/ring occurs
 *  - 401 -> re-login (throttled, no blind retry)
 *  - 400 / 404 -> discard invalid fix, no retry
 *  - 403 -> logged / user notified
 *  - 500 -> retry on next heartbeat
 */
class LocationService : Service() {

    companion object {
        const val ACTION_START = "action_start_location"
        const val ACTION_STOP  = "action_stop_location"
        /** Triggered on incoming/outgoing call event */
        const val ACTION_CALL_FIX = "action_call_fix_location"

        private const val NOTIFICATION_ID = 2001
        /** Server location heartbeat; independent of outgoing/incoming calls. */
        private const val LOCATION_UPDATE_INTERVAL_MS = 5 * 60_000L
        // A fix is still a FACT when it is a rough one. The old gate threw away every
        // reading worse than 150 m, but BALANCED power on mobile data with no WiFi
        // routinely reports 100-2000 m -- so a phone could sit on-duty for hours and
        // never once clear the gate while the dashboard honestly read
        // "সর্বশেষ লোকেশন নেই". Imperfect fixes are now SENT, and `accuracy_m` travels
        // with them, so the dashboard can label them ("±300 মি") instead of showing
        // nothing. Only a reading that carries no usable position at all -- a cell-only
        // estimate kilometres out -- is refused.
        private const val MAX_ACCURACY_METERS        = 5_000f
        // Specification: fixes older than 5 minutes are discarded
        private const val MAX_FIX_AGE_MS             = 5 * 60_000L

        // Singleton reference for instant access from SipManager
        @Volatile private var instance: LocationService? = null
        fun getInstance(): LocationService? = instance
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationHeartbeatJob: Job? = null
    private lateinit var prefs: PreferenceManager

    // Last usable fix for immediate call-fix reporting
    @Volatile private var lastUsableLocation: Location? = null

    // 401 re-login throttle timestamp
    @Volatile private var lastLoginAttemptMs = 0L
    private val MIN_LOGIN_INTERVAL_MS = 2 * 60_000L // Max once per 2 minutes

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferenceManager.getInstance(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        Timber.d("LocationService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                prefs.isOnDuty = true
                prefs.isLocationTracking = true
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationUpdates()
                Timber.d("LocationService: On-duty started")
            }
            ACTION_STOP -> {
                prefs.isOnDuty = false
                prefs.isLocationTracking = false
                stopLocationUpdates()
                stopSelf()
                Timber.d("LocationService: Off-duty stopped")
            }
            ACTION_CALL_FIX -> {
                triggerCallFix()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // GPS Callback
    // =========================================================

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (!usable(loc)) {
                    Timber.d("LocationService: skipping unusable fix (acc=${loc.accuracy})")
                    return
                }
                lastUsableLocation = loc
                // Post periodic on-duty heartbeat
                if (prefs.isOnDuty) {
                    serviceScope.launch { postFix(loc) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationHeartbeatJob?.cancel()
        locationHeartbeatJob = serviceScope.launch {
            while (isActive && prefs.isOnDuty) {
                // getCurrentLocation is an active request, so a stationary handset still
                // sends a real current fix every five minutes. A distance-filtered
                // callback could otherwise remain silent indefinitely.
                requestFreshCallFix()
                delay(LOCATION_UPDATE_INTERVAL_MS)
            }
        }
        Timber.d("LocationService: five-minute location heartbeat started")
    }

    private fun stopLocationUpdates() {
        locationHeartbeatJob?.cancel()
        locationHeartbeatJob = null
        fusedClient.removeLocationUpdates(locationCallback)
        Timber.d("LocationService: location updates stopped")
    }

    // =========================================================
    // Validation gate: usable()
    // =========================================================

    private fun usable(loc: Location?): Boolean {
        if (loc == null) return false
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false // 0,0 is discarded by server (400)
        if (System.currentTimeMillis() - loc.time > MAX_FIX_AGE_MS) return false // > 5 minutes old

        // Accuracy is a LABEL, not a filter. A reading with no accuracy value at all is
        // still a real position: send it with accuracy_m = null and the dashboard simply
        // omits its "±" chip. Only a reading so coarse that it describes no place at all
        // is refused.
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_METERS) return false
        return true
    }

    // =========================================================
    // POST /api/pbx/extension-locations
    // =========================================================

    fun triggerCallFix() {
        val loc = lastUsableLocation
        if (loc != null) {
            serviceScope.launch { postFix(loc) }
            Timber.d("LocationService: call fix triggered and posted")
        }
        requestFreshCallFix()
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshCallFix() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Timber.w("LocationService: call fix skipped because location permission is not granted")
            return
        }

        val tokenSource = CancellationTokenSource()
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { freshLocation ->
                if (usable(freshLocation)) {
                    lastUsableLocation = freshLocation
                    serviceScope.launch { postFix(freshLocation) }
                    Timber.d("LocationService: fresh call location posted")
                } else {
                    Timber.w("LocationService: no usable fresh location for call")
                }
            }
            .addOnFailureListener { error ->
                Timber.w(error, "LocationService: could not obtain fresh call location")
            }
    }

    private suspend fun postFix(loc: Location) {
        val token = prefs.authToken
        if (token.isEmpty()) {
            Timber.w("LocationService: no auth token, skipping postFix")
            return
        }

        val extension = prefs.effectiveExtension
        if (extension.isEmpty()) {
            Timber.w("LocationService: no extension configured, skipping postFix")
            return
        }

        // Field limits: source <= 20, device_id <= 160, app_version <= 40
        val body = ExtensionLocationRequest(
            extension  = extension,
            latitude   = loc.latitude,
            longitude  = loc.longitude,
            accuracyM  = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            altitudeM  = if (loc.hasAltitude()) loc.altitude else null,
            speedMps   = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            headingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            // The device's own provider ("gps" / "fused" / "network"), never a blanket
            // "gps": a cell-tower estimate is not a satellite fix, and the stored row is
            // the audit trail for where a position came from.
            source     = (loc.provider ?: "gps").take(20),
            deviceId   = readDeviceId().take(160),
            appVersion = BuildConfig.VERSION_NAME.take(40),
            capturedAt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Instant.ofEpochMilli(loc.time).toString()
            else
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date(loc.time))
        )

        try {
            val apiService = RetrofitClient.getApiService("https://app.callix.com.bd")
            val response = apiService.postExtensionLocation("Bearer $token", body)

            when (response.code()) {
                200, 201 -> {
                    val r = response.body()
                    Timber.d("LocationService: fix sent OK action=${r?.action} id=${r?.id}")
                }
                400 -> {
                    Timber.w("LocationService: 400 bad fix (0,0 or invalid field) — discarding, no retry")
                }
                401 -> {
                    Timber.w("LocationService: 401 unauthorized — attempting re-login")
                    reLogin()
                }
                403 -> {
                    Timber.w("LocationService: 403 — PBX service inactive or org inactive")
                }
                404 -> {
                    Timber.w("LocationService: 404 — extension '$extension' not found in this account")
                }
                in 500..599 -> {
                    Timber.w("LocationService: ${response.code()} server error — will retry next heartbeat")
                }
                else -> {
                    Timber.w("LocationService: unexpected response ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LocationService: network error posting fix")
        }
    }

    // =========================================================
    // 401 -> re-login (throttled)
    // =========================================================

    private suspend fun reLogin() {
        val now = System.currentTimeMillis()
        if (now - lastLoginAttemptMs < MIN_LOGIN_INTERVAL_MS) {
            Timber.w("LocationService: re-login throttled (too soon)")
            return
        }
        lastLoginAttemptMs = now

        val username = prefs.username
        val password = prefs.password
        if (username.isEmpty() || password.isEmpty()) {
            Timber.w("LocationService: no credentials for re-login")
            return
        }

        try {
            val apiService = RetrofitClient.getApiService("https://app.callix.com.bd")
            val request = LoginRequest(
                username = username,
                password = password
            )
            val response = apiService.login(request)
            if (response.isSuccessful) {
                val newToken = response.body()?.token ?: response.body()?.data?.id?.toString() ?: ""
                if (newToken.isNotEmpty()) {
                    prefs.authToken = newToken
                    Timber.d("LocationService: re-login successful, new token saved")
                }
            } else {
                Timber.w("LocationService: re-login failed ${response.code()}")
            }
        } catch (e: Exception) {
            Timber.e(e, "LocationService: re-login network error")
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    @SuppressLint("HardwareIds")
    private fun readDeviceId(): String = Settings.Secure.getString(
        contentResolver, Settings.Secure.ANDROID_ID
    ) ?: "unknown"

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CallixApplication.CHANNEL_ID_LOCATION)
            .setContentTitle("Callix location")
            .setContentText("Sharing active")
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onDestroy() {
        instance = null
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
        Timber.d("LocationService destroyed")
    }
}
