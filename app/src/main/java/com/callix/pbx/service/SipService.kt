package com.callix.pbx.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.callix.pbx.CallixApplication
import com.callix.pbx.R
import com.callix.pbx.data.local.ExtensionDirectory
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.CallState
import com.callix.pbx.data.model.SipRegistrationState
import com.callix.pbx.data.remote.CallerIdentity
import com.callix.pbx.ui.call.CallerText
import com.callix.pbx.ui.call.IncomingCallActivity
import com.callix.pbx.ui.call.CallActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

class SipService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val ACTION_ANSWER = "action_answer"
        const val ACTION_DECLINE = "action_decline"
        const val ACTION_HANGUP = "action_hangup"
        private const val NOTIFICATION_ID = 1001
        private const val INCOMING_CALL_NOTIFICATION_ID = 1002
    }

    private val binder = SipServiceBinder()
    private lateinit var sipManager: SipManager
    private lateinit var prefs: PreferenceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Held so it can be handed back in [onDestroy]; null when the system refused it. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * The single source of the incoming-call ring.
     *
     * The ring lives here, in the service, and deliberately NOT in
     * IncomingCallActivity, for three reasons that between them are the whole
     * "double ring tone" bug:
     *
     * 1. An activity can be created more than once for one call -- the service
     *    starts it AND the full-screen-intent PendingIntent points at it, and a
     *    configuration change or a process restart recreates it. Every creation
     *    started another looping MediaPlayer, so one call could ring twice or
     *    three times over.
     * 2. An activity is not always allowed to start from the background at all
     *    (Android 10+), so a ring that depends on it can simply not happen.
     * 3. This service is already running whenever a call arrives -- it is the
     *    component that posts the notification -- so ringing from here is tied to
     *    the exact same event, and cannot be duplicated by anything.
     *
     * Only ever one instance of this service exists, so these two fields are the
     * only ring in the app.
     */
    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    inner class SipServiceBinder : Binder() {
        fun getService(): SipService = this@SipService
    }

    override fun onCreate() {
        super.onCreate()
        sipManager = SipManager.getInstance(this)
        prefs = PreferenceManager.getInstance(this)
        observeCallStates()
        watchNetworkChanges()
        Timber.d("SipService created")
    }

    /**
     * Put the extension back online, if this phone is supposed to be online.
     *
     * Registration belongs to the service whose entire purpose is to hold it. It
     * cannot live in the login screen: a reboot, an app update, a process reclaim or
     * a battery manager killing the app all end the login screen's involvement while
     * the phone is still expected to receive calls.
     *
     * Deliberately quiet about failure. A phone that has been signed out must NOT
     * re-register, and a missing secret means registration is impossible rather than
     * broken -- both are logged and neither is surfaced to the user, because neither
     * is something they can act on from a notification.
     */
    private fun ensureRegistered() {
        try {
            val prefs = PreferenceManager.getInstance(this)
            if (!prefs.isLoggedIn) {
                Timber.d("Not registering: no signed-in session")
                return
            }
            if (sipManager.isRegistered()) {
                Timber.d("Already registered; leaving the registration alone")
                return
            }
            // A registration may already be in flight -- the login flow registers and
            // then navigates here, which starts the service. Re-registering on top of
            // an attempt that is still running would tear down and rebuild the proxy
            // config underneath it, so let the one in progress finish.
            if (sipManager.registrationState.value == SipRegistrationState.REGISTERING) {
                Timber.d("A registration is already in progress; not starting another")
                return
            }
            // The registrar needs a secret. prefs.sipPassword is the per-extension SIP
            // password from the API; prefs.password is the fallback the PBX also
            // accepts. Without either, a REGISTER can only be answered with 401.
            if (prefs.sipPassword.isEmpty() && prefs.password.isEmpty()) {
                Timber.w("Not registering: no stored SIP secret for this session")
                return
            }
            sipManager.retryRegistration()
            Timber.d("SIP registration requested by SipService start")
        } catch (t: Throwable) {
            // Never take the service down over this: START_STICKY and the next
            // connectivity change both offer another attempt.
            Timber.w(t, "Could not request SIP registration on service start")
        }
    }

    /**
     * Re-register the moment the network changes.
     *
     * Moving between Wi-Fi and mobile data replaces the device's address and kills
     * every flow built on the old one, while the PBX keeps the old contact until the
     * registration lapses. Without this the phone is unreachable for that whole
     * window even though it looks perfectly online on screen.
     */
    private fun watchNetworkChanges() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Timber.w("No ConnectivityManager; network changes will not re-register SIP")
            return
        }
        val request = NetworkRequest.Builder().build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("Default network available; re-registering SIP")
                sipManager.refreshRegistration()
            }
        }
        try {
            manager.registerNetworkCallback(request, networkCallback!!)
        } catch (t: Throwable) {
            // A device that refuses the callback still has a working registration; it
            // only loses the immediate re-register on a network switch.
            Timber.w(t, "Could not register the network callback")
            networkCallback = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                // A foreground service that is not REGISTERED is not a phone. This
                // service is brought up by the boot receiver, by the system after it
                // reclaims the process, and by every app launch -- and until now none
                // of those paths registered the SIP account, because the only place
                // that ever did was the login flow. The extension therefore went
                // offline and stayed offline until the user logged in again.
                ensureRegistered()
            }
            ACTION_ANSWER -> {
                // Stop the ring immediately rather than waiting for the call-state
                // callback: the user has acted, so the phone must go quiet now.
                stopRinging()
                sipManager.answerCall()
                dismissIncomingCallNotification()
            }
            ACTION_DECLINE -> {
                stopRinging()
                sipManager.declineCall()
                dismissIncomingCallNotification()
            }
            ACTION_HANGUP -> {
                stopRinging()
                sipManager.hangUp()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Enter the foreground, and never crash while doing it.
     *
     * From Android 12 the system may refuse a foreground-service start that comes
     * from the background, and it refuses by THROWING. An exception here would take
     * the whole app down, which is the exact opposite of "always available". So the
     * explicit type is tried first, the manifest-declared type second, and if the
     * system refuses both the service stands down cleanly -- START_STICKY plus the
     * next foreground entry point bring it back, whereas a service that fails to go
     * foreground is killed anyway and would meanwhile have crashed.
     *
     * The explicit type matters on Android 14, where the running type must match
     * what the service is actually doing: this service holds a self-managed VoIP
     * connection (phoneCall, permitted by MANAGE_OWN_CALLS) and captures audio once
     * a call is up (microphone).
     */
    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Timber.d("SipService is in the foreground")
            return
        } catch (t: Throwable) {
            Timber.w(t, "Typed startForeground was refused; retrying with the declared type")
        }

        try {
            startForeground(NOTIFICATION_ID, notification)
            Timber.d("SipService is in the foreground (manifest-declared type)")
        } catch (t: Throwable) {
            Timber.w(t, "SipService could not enter the foreground; standing down")
            stopSelf()
        }
    }

    /**
     * Start ringing, at most once.
     *
     * Guarded instead of assumed idempotent: the SIP stack can report the
     * incoming-call state more than once, and a second MediaPlayer would be a
     * second ring -- the exact bug this replaces. A ring already running is left
     * alone.
     */
    private fun startRinging() {
        if (ringtonePlayer != null) return

        try {
            val selectedUri = prefs.ringtoneUri.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val uri = selectedUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri == null) {
                // A device with no ringtone at all still has to show the call.
                Timber.w("This device has no default ringtone; ringing silently")
            } else {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@SipService, uri)
                    isLooping = true
                }
                ringtonePlayer = player
                // Loading is asynchronous on purpose: a blocking prepare() here
                // would stall the service's main thread, and a slow ringtone
                // source would then ANR a service that is in the middle of
                // handling a call. The identity check matters -- the call can end
                // while the ringtone is still loading, and stopRinging() will have
                // released this player and cleared the field by then, so a queued
                // start must be dropped rather than run on a released player.
                player.setOnPreparedListener { prepared ->
                    if (ringtonePlayer === prepared) {
                        try {
                            prepared.start()
                        } catch (t: Throwable) {
                            Timber.w(t, "The ringtone could not be started")
                        }
                    }
                }
                player.prepareAsync()
            }
        } catch (t: Throwable) {
            // Never let an audio failure hide the call.
            Timber.w(t, "Could not start the ringtone")
            ringtonePlayer = null
        }

        startVibrating()
    }

    private fun startVibrating() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 500, 500, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Could not start call vibration")
        }
    }

    /**
     * Stop the ring.
     *
     * Called from every terminal call state as well as from answer, decline and
     * hang-up, so the ring can never outlive the call. Safe to call when nothing
     * is ringing.
     */
    private fun stopRinging() {
        try {
            ringtonePlayer?.stop()
        } catch (t: Throwable) {
            Timber.w(t, "Stopping the ringtone failed")
        }
        try {
            ringtonePlayer?.release()
        } catch (t: Throwable) {
            Timber.w(t, "Releasing the ringtone player failed")
        }
        ringtonePlayer = null

        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
            Timber.w(t, "Cancelling call vibration failed")
        }
        vibrator = null
    }

    private fun observeCallStates() {
        serviceScope.launch {
            sipManager.callState.collectLatest { state ->
                when (state) {
                    CallState.INCOMING -> {
                        // The strings on CallInfo are the RAW SIP identity. They are
                        // resolved once, here, so the notification and the incoming
                        // screen can never disagree -- and so neither ever shows a
                        // transport address or an internal account id to the person
                        // being called.
                        val callInfo = sipManager.incomingCallInfo.value
                        // Resolved once, and then handed to BOTH surfaces in the SAME
                        // shape. They used to disagree: the notification was given the
                        // formatted label plus `secondary`, and `secondary` is null
                        // whenever there is no name -- so an outside call's number
                        // travelled in the NAME slot, the screen read the NUMBER slot,
                        // found nothing, and printed "Unknown number".
                        val display = CallerIdentity.withDirectoryName(
                            CallerIdentity.fromInbound(callInfo?.remoteName, callInfo?.remoteAddress)
                        ) { ext -> ExtensionDirectory.nameFor(this@SipService, ext) }
                        val label = CallerText.primary(this@SipService, display)

                        // The ring starts here, beside the notification, because
                        // this service is the one component guaranteed to be alive
                        // and to see this event exactly once. It is started before
                        // the UI so a slow activity start can never delay it.
                        startRinging()

                        showIncomingCallNotification(display, label)
                        // Launch incoming call UI
                        val incomingIntent = Intent(this@SipService, IncomingCallActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, display.name ?: "")
                            putExtra(IncomingCallActivity.EXTRA_CALLER_NUMBER, display.number ?: "")
                        }
                        startActivity(incomingIntent)
                    }
                    CallState.OUTGOING, CallState.CONNECTED -> {
                        // Answered (or placed): the ring has done its job.
                        stopRinging()
                        dismissIncomingCallNotification()
                        updateForegroundNotification(state)
                    }
                    CallState.ENDED, CallState.ERROR, CallState.IDLE -> {
                        // Unanswered, declined, or failed: the ring must stop in all
                        // three cases. A missed call is then raised by SipManager,
                        // which owns that decision, not this service.
                        stopRinging()
                        dismissIncomingCallNotification()
                        updateForegroundNotification(state)
                    }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            sipManager.registrationState.collectLatest { state ->
                updateForegroundNotification(null, state)
            }
        }
    }

    private fun buildForegroundNotification(
        text: String = "Ready to receive calls"
    ): Notification {
        val notificationIntent = Intent(this, CallActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // CHANNEL_ID_SERVICE, not CHANNEL_ID_CALL. This notification is permanent, and
        // the call channel has badges enabled for real incoming calls, so putting this
        // one there badged the app icon for ever -- a badge the user could not clear by
        // reading anything, because the notification never leaves. The incoming-call
        // alert below still uses the call channel: it genuinely is news.
        return NotificationCompat.Builder(this, CallixApplication.CHANNEL_ID_SERVICE)
            .setContentTitle("Callix PBX")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateForegroundNotification(
        callState: CallState? = null,
        regState: SipRegistrationState? = null
    ) {
        val text = when {
            callState == CallState.CONNECTED -> "Call in progress"
            callState == CallState.OUTGOING -> "Calling..."
            callState == CallState.ENDED -> "Call ended"
            regState == SipRegistrationState.REGISTERED -> "Ready to receive calls"
            regState == SipRegistrationState.REGISTERING -> "Connecting..."
            regState == SipRegistrationState.FAILED -> "Connection failed"
            else -> "Callix PBX"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    /**
     * The incoming-call alert.
     *
     * @param display the caller, already resolved. The FULL-SCREEN intent carries the
     *   same resolved pair, in the same extra names, as this service's own
     *   startActivity below. Those two were the mismatch: the full-screen intent is
     *   the one that runs on a LOCKED phone, and it was handing the screen the
     *   formatted label in the name slot and `display.secondary` -- null whenever the
     *   caller had no name -- in the number slot, so the number itself never arrived.
     * @param label the single line this notification shows. Only the notification
     *   uses it; the screen is never given a pre-formatted string to parse.
     */
    private fun showIncomingCallNotification(
        display: CallerIdentity.Display,
        label: String
    ) {
        val answerIntent = Intent(this, SipService::class.java).apply {
            action = ACTION_ANSWER
        }
        val declineIntent = Intent(this, SipService::class.java).apply {
            action = ACTION_DECLINE
        }

        val answerPendingIntent = PendingIntent.getService(
            this, 1, answerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val declinePendingIntent = PendingIntent.getService(
            this, 2, declineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, display.name ?: "")
            putExtra(IncomingCallActivity.EXTRA_CALLER_NUMBER, display.number ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CallixApplication.CHANNEL_ID_CALL)
            .setContentTitle("Incoming Call")
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(R.drawable.ic_call_answer, "Answer", answerPendingIntent)
            .addAction(R.drawable.ic_call_decline, "Decline", declinePendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .apply {
                // The number is only drawn when it says something the line above it
                // does not -- an empty or repeated sub-text would just add a blank row.
                val number = display.number
                if (!number.isNullOrBlank() && number != label) setSubText(number)
            }
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    private fun dismissIncomingCallNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    /**
     * The user swiped the app out of recents.
     *
     * `stopWithTask="false"` in the manifest normally keeps this service running
     * through that, so in the common case this callback is a no-op and the service
     * never stopped ringing. Some vendor ROMs kill the service anyway, so the
     * restart is attempted as well: it is idempotent, and an extra ACTION_START
     * costs nothing while a missed phone call costs a customer.
     *
     * The restart is allowed to fail: from Android 12 a foreground service cannot
     * always be started from the background, and the system's own START_STICKY
     * restart plus the boot receiver are the paths that recover it when this one
     * is refused.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restart = Intent(applicationContext, SipService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(applicationContext, restart)
            Timber.d("SipService asked to restart after the task was removed")
        } catch (t: Throwable) {
            Timber.w(t, "SipService could not be restarted after the task was removed")
        }
    }

    override fun onDestroy() {
        // The ring belongs to this service, so it dies with it. Without this a
        // service killed mid-ring would leave a MediaPlayer looping with no owner.
        stopRinging()
        serviceScope.cancel()
        networkCallback?.let { callback ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(callback)
            } catch (t: Throwable) {
                Timber.w(t, "Could not unregister the network callback")
            }
        }
        networkCallback = null

        // Being torn down is NOT the same as signing out.
        //
        // This used to de-register unconditionally, so every ordinary teardown of this
        // service -- the system reclaiming the process, an app update, the task being
        // swiped away -- sent a de-registration to the PBX and took the extension
        // OFFLINE. Paired with a start path that never re-registered, the phone could
        // not come back without a fresh login. That is precisely "leave the app closed
        // for a while and the extension goes offline, and calls stop arriving".
        //
        // Signing out is an explicit act and both sign-out screens perform it: they
        // clear the session (or call unregister() directly) themselves. The test below
        // is the safety net for a teardown that happens after the session was cleared,
        // so a signed-out handset can never be left registered on the PBX.
        if (!PreferenceManager.getInstance(this).isLoggedIn) {
            sipManager.unregister()
        } else {
            Timber.d("SipService torn down while signed in; keeping the registration")
        }

        super.onDestroy()
        Timber.d("SipService destroyed")
    }
}
