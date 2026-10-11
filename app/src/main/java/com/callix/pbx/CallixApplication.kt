package com.callix.pbx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.callix.pbx.data.local.PreferenceManager
import timber.log.Timber

class CallixApplication : Application() {

    companion object {
        /**
         * Channel for the live-call notifications.
         *
         * The id carries a `_v2` suffix on purpose. A notification channel's
         * alerting behaviour -- its sound and its vibration -- is fixed the moment
         * the channel is created and can never be changed by the app afterwards,
         * only by the user in system settings. The previous channel was created
         * with the DEFAULT notification sound (a HIGH-importance channel with no
         * explicit sound rings the notification tone), which played on top of the
         * app's own ringtone and produced two sounds for one incoming call. A
         * behaviour fix therefore cannot reuse the old id: an existing install
         * would keep the old channel and keep the second ring. Changing the id
         * makes the corrected channel take effect on upgrade. The old channel is
         * left behind, unused and silent.
         */
        const val CHANNEL_ID_CALL = "callix_call_channel_v2"

        /**
         * The foreground-service indicator ("Ready to receive calls").
         *
         * It has its OWN channel because sharing one with the incoming-call alert made
         * this app badge permanently. That channel has badges enabled -- correctly, an
         * incoming call deserves one -- so the always-on service notification badged
         * too, and the launcher showed a badge on this app at all times with nothing
         * for the user to look at. Opening the call log could never clear it, because
         * the notification it came from never goes away.
         *
         * A NEW id rather than an edit of the old channel: a channel's behaviour is
         * fixed when it is first created, so re-creating the old one with badges off
         * is not guaranteed to take effect on a handset that already has it.
         */
        const val CHANNEL_ID_SERVICE = "callix_service_channel"

        const val CHANNEL_ID_NOTIFICATION = "callix_notification_channel"
        // This deliberately has a new id. Android remembers the importance of an
        // existing notification channel, so changing the old id would leave an
        // already-installed app on its previous, more prominent setting.
        const val CHANNEL_ID_LOCATION = "callix_location_status_v2"

        /** Missed calls get their own channel so they can be muted without also
         *  muting live-call alerts, which are the ones that must never be lost. */
        const val CHANNEL_ID_MISSED = "callix_missed_channel"

        lateinit var instance: CallixApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Timber for logging
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        } catch (t: Throwable) {
            android.util.Log.e("CallixApp", "Timber init failed", t)
        }

        // Create notification channels
        try {
            createNotificationChannels()
        } catch (t: Throwable) {
            android.util.Log.e("CallixApp", "Notification channels init failed", t)
        }

        // Do not load the native SIP engine while launching the app. A device
        // with an incompatible native library must still be able to open the
        // login screen; SIP is initialized only when the user signs in.
        android.util.Log.d("CallixApp", "Callix PBX Application started")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Call Channel - High priority, but SILENT. The ring the user hears is
            // played by SipService, which can adjust it, loop it and always stop it
            // at the right moment; a channel sound is a one-shot notification tone
            // that cannot be either. Leaving the channel sounding meant one ring
            // from the channel and one from the app: "double ring tone".
            //
            // Vibration is off here for the same reason -- SipService runs its own
            // ring pattern, and a channel pattern on top of it is a second,
            // conflicting buzz.
            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming SIP calls"
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                setShowBadge(true)
            }

            // Foreground-Service Channel. LOW importance and NO BADGE, which is the
            // whole point of it being separate from the call channel: this
            // notification is on screen for as long as the phone can take calls, so
            // anything it badges is permanent. Silent, so it never competes with the
            // ringtone, and never heads-up, because it is not news.
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the SIP phone registered and ready for calls"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            // General Notification Channel
            val notifChannel = NotificationChannel(
                CHANNEL_ID_NOTIFICATION,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
            }

            // Location Service Channel
            val locationChannel = NotificationChannel(
                CHANNEL_ID_LOCATION,
                "Location status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Shown quietly only while location sharing is active"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }

            // Missed Call Channel. Badges are ON so the launcher can show the
            // count of calls the user has not listened to yet.
            val missedChannel = NotificationChannel(
                CHANNEL_ID_MISSED,
                getString(R.string.missed_call_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.missed_call_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, callChannel, notifChannel, locationChannel, missedChannel)
            )
        }
    }
}
