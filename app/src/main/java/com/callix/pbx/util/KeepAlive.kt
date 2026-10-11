package com.callix.pbx.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.service.SipService
import timber.log.Timber

/**
 * The system settings a VoIP phone depends on but cannot grant itself.
 *
 * All three of these are things the platform, not the app, decides:
 *
 *  1. POST_NOTIFICATIONS  -- without it a missed-call alert is silently dropped.
 *  2. USE_FULL_SCREEN_INTENT -- without it an incoming call cannot take the
 *     screen from the background. From Android 14 the grant is revocable, so it
 *     must be checked rather than assumed.
 *  3. Battery-optimisation exemption -- without it aggressive vendors freeze a
 *     background service overnight and calls quietly stop arriving.
 *
 * Nothing here nags: each method only reports the state or returns the intent
 * that opens the relevant settings page. Deciding when to ask is a UI concern
 * and lives with the UI.
 */
object KeepAlive {

    /** True when the app may post notifications at all. */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True when an incoming call may take over the screen while the app is in the
     * background.
     *
     * Below Android 14 the permission is install-time, so it is held whenever it
     * is declared. On Android 14 and above the user can revoke it, and asking the
     * system is the only reliable answer.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return manager.canUseFullScreenIntent()
    }

    /**
     * The settings page where the user grants full-screen-intent access.
     *
     * Android 14 has a dedicated page for exactly this. On older releases there is
     * no such page, so the app's own details screen is the closest equivalent.
     */
    fun fullScreenIntentSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            appDetailsIntent(context)
        }

    /** True when the system is already forbidden from freezing this app. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The direct "don't optimise this app" system dialog.
     *
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the only way to get the one-tap
     * dialog; the lint suppression is because Google's own guidance prefers the
     * gentler ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS list, which for a phone
     * that must ring at 3am is a worse user experience, not a safer one.
     */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /** The app's own details page, used as a fallback on releases with no dedicated page. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Bring the SIP service up, but only for a signed-in user.
     *
     * Starting it for a signed-out user would put a permanent "Ready to receive
     * calls" notification on a phone that cannot receive any.
     */
    fun startSipService(context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        if (!prefs.isLoggedIn) {
            Timber.d("SIP service not started: no signed-in user")
            return
        }
        try {
            val intent = Intent(context, SipService::class.java).apply {
                action = SipService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // A missing permission or a restricted background start must not crash
            // the caller; it is reported and the next entry point will retry.
            Timber.w(t, "SIP service could not be started")
        }
    }
}
