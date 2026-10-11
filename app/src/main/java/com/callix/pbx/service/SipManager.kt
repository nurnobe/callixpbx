package com.callix.pbx.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.CallState
import com.callix.pbx.data.model.SipCallInfo
import com.callix.pbx.data.model.SipRegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.*
import timber.log.Timber
import java.io.File

class SipManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SipManager"
        private const val SIP_REALM = "asterisk"

        /**
         * How long the PBX may consider this phone registered before we prove it again.
         *
         * Short on purpose: a UDP NAT binding on a mobile network commonly dies within
         * a few minutes, and the only thing that keeps it open is traffic. A one-hour
         * registration leaves the PBX dialling a path that has been dead for 55 minutes.
         */
        private const val REGISTRATION_EXPIRES_SECONDS = 120

        @Volatile
        private var instance: SipManager? = null

        fun getInstance(context: Context): SipManager {
            return instance ?: synchronized(this) {
                instance ?: SipManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = PreferenceManager.getInstance(context)

    private var core: Core? = null
    private var currentCall: Call? = null
    private val iterateHandler = Handler(Looper.getMainLooper())

    // ── missed-call tracking ──────────────────────────────────────────────────
    // A call is reported as MISSED only when all three hold: it really rang this
    // device, it was never answered, and the user did not decline it. Declining is
    // a deliberate action, not a miss, so it must not raise an alert. These are
    // reset when a new incoming call arrives and cleared when the call terminates.
    private var inboundRinging = false
    private var inboundAnswered = false
    private var inboundDeclined = false
    private var inboundPeerName: String? = null
    private var inboundPeerNumber: String? = null
    private val iterateRunnable = object : Runnable {
        override fun run() {
            try {
                core?.iterate()
            } catch (t: Throwable) {
                Timber.w(t, "Linphone core iteration failed")
            }
            if (core != null) iterateHandler.postDelayed(this, 20L)
        }
    }

    /** True when Linphone core is ready for use. */
    private val isCoreReady: Boolean get() = core != null

    // State Flows for UI observation
    private val _registrationState = MutableStateFlow(SipRegistrationState.NONE)
    val registrationState: StateFlow<SipRegistrationState> = _registrationState

    private val _registrationMessage = MutableStateFlow<String?>(null)
    val registrationMessage: StateFlow<String?> = _registrationMessage

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _incomingCallInfo = MutableStateFlow<SipCallInfo?>(null)
    val incomingCallInfo: StateFlow<SipCallInfo?> = _incomingCallInfo

    private val _currentCallInfo = MutableStateFlow<SipCallInfo?>(null)
    val currentCallInfo: StateFlow<SipCallInfo?> = _currentCallInfo

    private val _callDuration = MutableStateFlow(0)
    val callDuration: StateFlow<Int> = _callDuration

    private val coreListener = object : CoreListenerStub() {
        override fun onRegistrationStateChanged(
            core: Core,
            proxyConfig: ProxyConfig,
            state: RegistrationState,
            message: String
        ) {
            Timber.d("SIP Registration State: $state - $message")
            _registrationState.value = when (state) {
                RegistrationState.Ok -> SipRegistrationState.REGISTERED
                RegistrationState.Progress -> SipRegistrationState.REGISTERING
                RegistrationState.Failed -> SipRegistrationState.FAILED
                RegistrationState.Cleared -> SipRegistrationState.CLEARED
                else -> SipRegistrationState.NONE
            }
            // Keep only an actual registration failure. "Registration
            // disabled" is emitted while replacing an old proxy config and
            // is not an authentication/network failure.
            _registrationMessage.value = if (state == RegistrationState.Failed) {
                message.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Timber.d("SIP Call State: $state - $message")
            currentCall = call

            // The peer identity is read DEFENSIVELY, and it can no longer abort this
            // callback.
            //
            // Linphone hands back a null remote address on some states: an outgoing call
            // has none until the far end answers, and a released call loses it again.
            // Dereferencing it directly threw inside this listener, and because that
            // throw happened BEFORE the `when` below, the CallState was never published
            // at all -- the state machine went silent for the entire call, so the in-call
            // screen sat on its initial values with the timer frozen at 00:00 and the
            // status stuck on "Calling...". A callback that owns the UI state must never
            // be able to die on a display string.
            val remoteAddress = readRemoteAddress(call)
            val remoteName = readRemoteName(call, remoteAddress)

            when (state) {
                Call.State.IncomingReceived -> {
                    // The caller is written down BEFORE the state is published. A StateFlow
                    // wakes its collectors on assignment, and this callback runs on the
                    // Linphone core thread while the UI collects on the main thread -- so a
                    // consumer reacting to INCOMING could run before the identity was
                    // stored and resolve a null peer, which is how the screen and the
                    // notification ended up disagreeing about who was calling.
                    _incomingCallInfo.value = SipCallInfo(
                        callId = call.callLog?.callId ?: "",
                        remoteAddress = remoteAddress,
                        remoteName = remoteName,
                        direction = "inbound",
                        state = CallState.INCOMING
                    )
                    _callState.value = CallState.INCOMING
                    // Arm the missed-call detector. The peer identity is captured
                    // NOW, because by the time the call ends the Call object may no
                    // longer carry it -- and a missed-call alert that cannot name
                    // the caller is not worth raising.
                    inboundRinging = true
                    inboundAnswered = false
                    inboundDeclined = false
                    inboundPeerName = remoteName
                    inboundPeerNumber = call.remoteAddress?.username ?: remoteAddress
                    // Trigger 1: Instant GPS fix on incoming call ring
                    triggerLocationFix()
                }
                Call.State.OutgoingInit,
                Call.State.OutgoingProgress,
                Call.State.OutgoingRinging -> {
                    // Info first, state second -- see the note on IncomingReceived.
                    _currentCallInfo.value = SipCallInfo(
                        callId = call.callLog?.callId ?: "",
                        remoteAddress = remoteAddress,
                        remoteName = remoteName,
                        direction = "outbound",
                        state = CallState.OUTGOING
                    )
                    _callState.value = CallState.OUTGOING
                }
                Call.State.Connected,
                Call.State.StreamsRunning -> {
                    // Same rule again: the call is marked connected before CONNECTED is
                    // published, so whoever reacts to CONNECTED already sees the connected
                    // call -- and the in-call timer can start from a complete state.
                    _currentCallInfo.value = _currentCallInfo.value?.copy(state = CallState.CONNECTED)
                        ?: _incomingCallInfo.value?.copy(state = CallState.CONNECTED)
                    _callState.value = CallState.CONNECTED
                    // Answered: this call can never be a missed call, however it ends.
                    if (inboundRinging) inboundAnswered = true
                    // Trigger 1: Instant GPS fix when call is answered (OFFHOOK)
                    triggerLocationFix()
                }
                Call.State.Paused,
                Call.State.PausedByRemote -> {
                    _callState.value = CallState.PAUSED
                }
                Call.State.End,
                Call.State.Released -> {
                    _callState.value = CallState.ENDED
                    _callDuration.value = call.duration
                    _currentCallInfo.value = null
                    _incomingCallInfo.value = null
                    currentCall = null
                    // This is the moment an unanswered inbound call becomes a MISSED
                    // call: the INVITE is gone and was never answered.
                    reportMissedIfAny()
                }
                Call.State.Error -> {
                    _callState.value = CallState.ERROR
                    _currentCallInfo.value = null
                    _incomingCallInfo.value = null
                    currentCall = null
                    // A call that failed never rang the user, so it is not a miss.
                    clearInboundTracking()
                }
                else -> {}
            }
        }
    }

    /**
     * The remote address as a displayable string, or "" when there is none yet.
     *
     * Never throws and never returns null: the caller is a SIP state callback whose
     * state publication must not depend on the peer being known.
     */
    private fun readRemoteAddress(call: Call): String = try {
        call.remoteAddress?.asStringUriOnly().orEmpty()
    } catch (t: Throwable) {
        Timber.w(t, "Could not read the remote address")
        ""
    }

    /**
     * The best available name for the peer, falling back to [fallback] (the address).
     *
     * Same contract as [readRemoteAddress]: total, so the callback always survives it.
     */
    private fun readRemoteName(call: Call, fallback: String): String = try {
        call.remoteAddress?.displayName
            ?: call.remoteAddress?.username
            ?: fallback
    } catch (t: Throwable) {
        Timber.w(t, "Could not read the remote name")
        fallback
    }

    /**
     * Raise the missed-call notification when the call that just ended was an
     * inbound call that rang and was neither answered nor declined.
     *
     * Tracking is consumed here (not merely read) so that a later state callback
     * for the same call cannot raise a second notification.
     */
    private fun reportMissedIfAny() {
        val rang = inboundRinging
        val answered = inboundAnswered
        val declined = inboundDeclined
        val name = inboundPeerName
        val number = inboundPeerNumber
        clearInboundTracking()

        if (rang && !answered && !declined) {
            MissedCallNotifier.notifyMissed(context, name, number)
        }
    }

    private fun clearInboundTracking() {
        inboundRinging = false
        inboundAnswered = false
        inboundDeclined = false
        inboundPeerName = null
        inboundPeerNumber = null
    }

    init {
        initializeLinphone()
    }

    /**
     * Trigger 1: Send ACTION_CALL_FIX to LocationService or invoke directly.
     */
    private fun triggerLocationFix() {
        try {
            val running = com.callix.pbx.service.LocationService.getInstance()
            if (running != null) {
                // The service is alive (the user is on duty) and already holds a fix, so this
                // is an immediate in-process call and always works.
                running.triggerCallFix()
                Timber.d("SipManager: location call-fix invoked directly on service")
            } else if (prefs.isOnDuty) {
                // On duty, but the OS killed the service. Start it with ACTION_START -- never
                // with the bare ACTION_CALL_FIX, because ACTION_START is the branch that calls
                // startForeground(), which is MANDATORY when a service is started from the
                // background on API 26+. It also restores the heartbeat.
                //
                // The old code called context.startService(ACTION_CALL_FIX) here: on a
                // backgrounded app (which is exactly what an INCOMING call is) that throws
                // IllegalStateException, the catch below swallowed it, and the call captured
                // no location at all -- "কল রিসিভ করার পরেও লোকেশন আসছে না".
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, com.callix.pbx.service.LocationService::class.java)
                        .setAction(com.callix.pbx.service.LocationService.ACTION_START)
                )
                Timber.d("SipManager: location service restarted for call fix")
            } else {
                // Off duty: the user has not consented to tracking, so an incoming call must
                // never silently switch it on.
                Timber.d("SipManager: call fix skipped — off duty (no tracking consent)")
            }
        } catch (e: Exception) {
            Timber.w(e, "SipManager: could not trigger location fix")
        }
    }

    private fun initializeLinphone() {
        try {
            // Copy assistant config files
            copyAssistantConfig()

            val factory = Factory.instance()
            // liblinphone's own debug logging is OFF in the shipped app. It was left on,
            // which means every SIP packet, registration and media event was formatted
            // and written to logcat for the life of the process -- real CPU and wake-up
            // cost on the older handsets this app has to run on, and it buries this
            // app's own logs. Flip the first argument to true when a device needs
            // diagnosing; nothing else depends on it.
            factory.setDebugMode(false, "Callix PBX")

            val configPath = context.filesDir.absolutePath + "/linphonerc"
            val factoryConfigPath = context.filesDir.absolutePath + "/linphonerc_factory"

            val newCore = factory.createCore(configPath, factoryConfigPath, context)
            newCore.addListener(coreListener)
            core = newCore

            // Configure audio codecs, jitter buffer and echo canceller
            configureAudio()

            // Decide who rings. Must happen before start() so the setting is in
            // force for the very first call.
            configureRinging()

            newCore.start()
            // A standalone Linphone Core needs a regular iteration loop to
            // process SIP registration, incoming INVITEs and call events.
            iterateHandler.removeCallbacks(iterateRunnable)
            iterateHandler.post(iterateRunnable)
            Timber.d("Linphone Core initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Linphone Core")
        } catch (e: UnsatisfiedLinkError) {
            // Native library failed to load (ABI mismatch / device not supported).
            // App must still open — SIP features will be unavailable.
            Timber.e(e, "Linphone native library load failed")
            android.util.Log.e(TAG, "Linphone native lib failed to load", e)
        } catch (e: Throwable) {
            // Catch-all so Application startup can never be killed by SIP init.
            Timber.e(e, "Unexpected error initializing Linphone")
            android.util.Log.e(TAG, "Unexpected Linphone init error", e)
        }
    }

    private fun copyAssistantConfig() {
        // Copy default config if needed
        val configFile = File(context.filesDir, "linphonerc")
        if (!configFile.exists()) {
            configFile.createNewFile()
        }
    }

    /**
     * Voice-quality configuration.
     *
     * Three things are set here, and only three, because each is provable:
     *
     * 1. CODEC PRIORITY. The previous version merely ENABLED a set of codecs and
     *    left their ORDER to chance. Order is what actually decides a call:
     *    Linphone offers `audioPayloadTypes` in list order, so having Opus enabled
     *    while a narrowband codec sits ahead of it in the list still produces a
     *    narrowband call. Wideband is placed first (Opus, then G.722), narrowband
     *    after, and the array is assigned back because on this binding the order
     *    lives in the array itself. Nothing is REMOVED from the candidate set --
     *    every codec the SDK ships stays as a fallback, it is only demoted.
     *
     * 2. JITTER + RATE ADAPTATION. These decide whether a mobile-network call
     *    sounds continuous or chopped. A fixed-size jitter buffer under-runs the
     *    moment the network hiccups; adaptive sizing (plus adaptive rate control)
     *    lets the stream absorb it. Both are OFF on a freshly created core, which
     *    is precisely the "voice quality low" case.
     *
     * 3. ECHO. Not forced blindly: a handset that has its own hardware canceller
     *    is better left to it, and Linphone's own canceller is only switched on
     *    where the platform provides none. The state is logged either way.
     */
    private fun configureAudio() {
        val core = core ?: return

        // ── codec priority (lower index = offered first) ──────────────────────
        val priority = listOf("OPUS", "G722", "PCMU", "PCMA", "G729")
        val wanted = priority.toSet()

        try {
            val all = core.audioPayloadTypes
            for (payload in all) {
                payload.enable(wanted.contains(payload.mimeType.uppercase()))
            }
            val ordered = all.sortedBy { p ->
                val i = priority.indexOf(p.mimeType.uppercase())
                if (i >= 0) i else priority.size
            }
            // Kotlin exposes the getter as a read-only property for this pair, so the
            // setter is called by name rather than through property syntax. Both the
            // getter and setter exist on org.linphone.core.Core (verified with javap).
            core.setAudioPayloadTypes(ordered.toTypedArray())

            Timber.d(
                "Audio codecs in preference order: %s",
                ordered.filter { it.enabled() }
                    .joinToString(", ") { "${it.mimeType}/${it.clockRate}" }
            )
        } catch (t: Throwable) {
            // Never let an audio preference stop the core from starting.
            Timber.w(t, "Could not set codec priority; keeping SDK defaults")
        }

        // ── network adaptation ────────────────────────────────────────────────
        try {
            core.isAudioAdaptiveJittcompEnabled = true
            Timber.d("Adaptive jitter buffer enabled")
        } catch (t: Throwable) {
            Timber.w(t, "Adaptive jitter buffer unavailable")
        }
        try {
            core.isAdaptiveRateControlEnabled = true
            Timber.d("Adaptive rate control enabled (algorithm=%s)", core.adaptiveRateAlgorithm)
        } catch (t: Throwable) {
            Timber.w(t, "Adaptive rate control unavailable")
        }

        // ── echo / comfort noise ──────────────────────────────────────────────
        try {
            val builtin = core.hasBuiltinEchoCanceller()
            val current = core.isEchoCancellationEnabled
            Timber.d("Echo canceller: builtin=%s, enabled=%s", builtin, current)
            if (!builtin && !current) {
                core.isEchoCancellationEnabled = true
                Timber.d("Echo cancellation enabled (this device has no hardware canceller)")
            }
            core.isGenericComfortNoiseEnabled = true
        } catch (t: Throwable) {
            Timber.w(t, "Echo canceller configuration failed")
        }
    }

    /**
     * Decide who plays the ringtone for an incoming call -- and there must be
     * exactly one answer to that question.
     *
     * Linphone ships its own ring sounds (they are the wav files under
     * share/sounds/linphone/rings in the SDK) and, on a freshly created core,
     * plays one itself for the whole time a call is ringing. This app also rings,
     * from SipService. Two independent ringers looping at once is precisely
     * "double ring tone", and neither knew about the other, so stopping one left
     * the other playing.
     *
     * Disabling `nativeRinging` tells liblinphone that the APPLICATION owns
     * ringing, so it plays nothing of its own. That leaves exactly one
     * ringer: SipService, which loops the device's real ringtone and stops it on
     * answer, decline, hang-up or timeout.
     *
     * The core also rings via the ringback tone for OUTGOING calls, which is a
     * different setting entirely and is deliberately left alone.
     */
    private fun configureRinging() {
        val core = core ?: return

        try {
            // `true` enables Linphone's own ringtone. SipService already owns a
            // single MediaPlayer for the device ringtone, so enabling this created
            // two simultaneous ringers on some phones.
            core.setNativeRingingEnabled(false)
            Timber.d(
                "Ringing ownership: application (core native ringing=%s)",
                core.isNativeRingingEnabled
            )
        } catch (t: Throwable) {
            // If this cannot be set, the core may still ring on its own, so every
            // terminal path below also calls stopRinging() as a second line of
            // defence rather than relying on this one property.
            Timber.w(t, "Could not take ownership of ringing; the core may also ring")
        }
    }

    fun register(
        sipServer: String,
        sipPort: Int,
        username: String,
        password: String,
        displayName: String,
        transport: String = "UDP"
    ) {
        try {
            val core = core ?: run {
                Timber.e("SIP register called but Linphone core is not initialized")
                _registrationState.value = SipRegistrationState.FAILED
                return
            }
            // Remove existing proxy configs
            core.proxyConfigList.forEach { core.removeProxyConfig(it) }
            core.authInfoList.forEach { core.removeAuthInfo(it) }

            // Asterisk identifies a device by matching the user part of the From/To URI
            // against an ENDPOINT NAME -- and extension numbers repeat across tenants,
            // so this PBX names every endpoint "<extension>@<main number>"
            // (e.g. 101@09699800222). A bare extension matches no endpoint at all, so
            // the REGISTER is answered with 401 and the phone never comes online.
            //
            // The "@" inside the user part must be percent-encoded ("%40"). That is
            // exactly what the server's own provisioning block emits (`username_encoded`,
            // built by buildSipAccount) and what every working device on this PBX
            // already sends. The registrar stays a SEPARATE field on purpose: joining
            // the two would build "sip:101@09699800222@sip.callix.com.bd", which is
            // malformed and is silently dropped by Asterisk (no reply at all).
            val cleanUser = username.trim()
            val accountUser: String
            val addressUser: String
            if (cleanUser.contains("@")) {
                val extPart = cleanUser.substringBefore("@").trim().removePrefix("sip:")
                val didPart = cleanUser.substringAfter("@").trim()
                accountUser = "$extPart@$didPart"
                addressUser = "$extPart%40$didPart"
            } else {
                accountUser = cleanUser
                addressUser = cleanUser
            }

            // Create auth info (the digest username Asterisk expects: "<ext>@<number>")
            val authInfo = Factory.instance().createAuthInfo(
                accountUser, null, password, null, SIP_REALM, sipServer
            )
            core.addAuthInfo(authInfo)

            // Create proxy config
            val proxyConfig = core.createProxyConfig()
            val identity = "sip:$addressUser@$sipServer"
            val proxyAddress = "sip:$sipServer:$sipPort;transport=${transport.lowercase()}"

            proxyConfig.edit()
            proxyConfig.identityAddress = Factory.instance().createAddress(identity)?.apply {
                this.displayName = displayName
            }
            proxyConfig.serverAddr = proxyAddress
            proxyConfig.isRegisterEnabled = true
            // 120 s, not 3600. On a mobile handset a one-hour registration is a lie:
            // the carrier's or router's NAT binding for a UDP flow typically times out
            // in two to five minutes, so after that the PBX still holds a contact that
            // no longer reaches the phone. Inbound calls are then delivered down a dead
            // path -- they never ring here and never reach voicemail, which is exactly
            // "the extension is online but calls do not come through".
            //
            // A short registration is refreshed well before it lapses, and each refresh
            // re-opens the NAT binding, so the path stays live. The second benefit is
            // detection: when the phone really is gone, the PBX learns within a couple
            // of minutes instead of keeping a stale contact for an hour.
            proxyConfig.expires = REGISTRATION_EXPIRES_SECONDS
            proxyConfig.done()

            core.addProxyConfig(proxyConfig)
            core.defaultProxyConfig = proxyConfig

            Timber.d("SIP Registration initiated for $accountUser (address $addressUser@$sipServer:$sipPort)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register SIP account")
            _registrationState.value = SipRegistrationState.FAILED
        }
    }

    fun unregister() {
        try {
            core?.proxyConfigList?.forEach { proxyConfig ->
                proxyConfig.edit()
                proxyConfig.isRegisterEnabled = false
                proxyConfig.done()
            }
            Timber.d("SIP unregistered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister")
        }
    }

    /**
     * The audio parameters for a call -- for BOTH directions.
     *
     * A call has two CallParams objects on this device: one when we place the call,
     * and one when we accept it. They used to be built by two different blocks of
     * statements, and only the OUTGOING one disabled video. That is exactly how an
     * incoming call ends up negotiating against different local media constraints
     * than an outgoing one, and therefore sounding different, even when the PBX
     * offers the identical codec list to both. There is now one place that decides,
     * so the two directions cannot drift apart again.
     *
     * The codec LIST and its ORDER are deliberately NOT set here. They are set once
     * on the core in [configureAudio], which is what makes every call in either
     * direction offer the same payload types in the same preference order. This
     * method only fixes the per-call shape of the media.
     *
     * @param call null when placing a new call (Linphone wants no call yet), or the
     *   ringing call when answering it.
     */
    private fun buildCallParams(call: Call?): CallParams? {
        val core = core ?: return null
        val params = core.createCallParams(call) ?: return null
        try {
            params.isAudioEnabled = true
            params.isVideoEnabled = false
        } catch (t: Throwable) {
            // A media preference must never be the reason a call cannot be answered.
            Timber.w(t, "Could not configure call media parameters")
        }
        return params
    }

    fun makeCall(address: String) {
        try {
            val core = core ?: run {
                Timber.e("makeCall called but Linphone core is not initialized")
                return
            }
            val target = address.trim().removePrefix("sip:")
            // Directory rows normally carry just "105", which Asterisk routes at
            // the PBX host. Also support the tenant-qualified form
            // "105@09699800222": its inner @ belongs to the endpoint name and
            // therefore must be percent-encoded before the SIP server host is
            // appended. This makes extension-to-extension calls work for every
            // tenant without treating an online/offline display badge as a block.
            val sipAddress = when {
                address.startsWith("sip:") -> address
                target.matches(Regex("\\d+@\\d+")) -> {
                    "sip:${target.substringBefore("@")}%40${target.substringAfter("@")}@${prefs.sipServer}"
                }
                else -> "sip:$target@${prefs.sipServer}"
            }

            val remoteAddress = Factory.instance().createAddress(sipAddress)
            if (remoteAddress == null) {
                Timber.e("Invalid SIP address: $sipAddress")
                return
            }

            val params = buildCallParams(null) ?: run {
                Timber.e("Could not create call parameters for $sipAddress")
                return
            }

            core.inviteAddressWithParams(remoteAddress, params)
            Timber.d("Calling: $sipAddress")
        } catch (e: Exception) {
            Timber.e(e, "Failed to make call")
        }
    }

    fun answerCall() {
        try {
            val core = core
            currentCall?.let { call ->
                val params = buildCallParams(call)
                if (params != null) {
                    call.acceptWithParams(params)
                    Timber.d("Call answered")
                } else {
                    // Refusing to answer because a media parameter could not be built
                    // would be worse than answering with the SDK's own defaults.
                    Timber.w("No call parameters for answer; accepting with core defaults")
                    call.accept()
                }
            }
            // A no-op once native ringing is disabled, and the guarantee that the
            // core's own ring stops even if it could not be disabled.
            core?.stopRinging()
        } catch (e: Exception) {
            Timber.e(e, "Failed to answer call")
        }
    }

    fun declineCall() {
        try {
            // Declining is a deliberate choice, so this call must not later be
            // reported as a missed call the user failed to notice.
            if (inboundRinging) inboundDeclined = true
            currentCall?.decline(Reason.Declined)
            core?.stopRinging()
            Timber.d("Call declined")
        } catch (e: Exception) {
            Timber.e(e, "Failed to decline call")
        }
    }

    fun hangUp() {
        try {
            currentCall?.terminate()
            core?.stopRinging()
            Timber.d("Call hung up")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hang up")
        }
    }

    fun holdCall() {
        try {
            currentCall?.pause()
            Timber.d("Call paused/held")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hold call")
        }
    }

    fun resumeCall() {
        try {
            currentCall?.resume()
            Timber.d("Call resumed")
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume call")
        }
    }

    fun transferCall(transferToAddress: String) {
        try {
            val sipAddress = if (transferToAddress.startsWith("sip:")) transferToAddress
            else "sip:$transferToAddress@${prefs.sipServer}"

            val address = Factory.instance().createAddress(sipAddress)
            if (address != null) {
                currentCall?.transfer(address.asStringUriOnly())
                Timber.d("Call transferred to: $sipAddress")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to transfer call")
        }
    }

    fun toggleMute(): Boolean {
        return try {
            val core = core ?: return false
            val isMuted = core.isMicEnabled
            core.isMicEnabled = !isMuted
            !isMuted
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle mute")
            false
        }
    }

    fun isMuted(): Boolean = try {
        core?.let { !it.isMicEnabled } ?: false
    } catch (e: Exception) { false }

    fun toggleSpeaker(): Boolean {
        return try {
            val core = core ?: return false
            val currentDevice = core.outputAudioDevice
            val availableDevices = core.audioDevices

            if (currentDevice?.type == AudioDevice.Type.Speaker) {
                // Switch to earpiece
                val earpiece = availableDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
                earpiece?.let { core.outputAudioDevice = it }
                false
            } else {
                // Switch to speaker
                val speaker = availableDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
                speaker?.let { core.outputAudioDevice = it }
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle speaker")
            false
        }
    }

    fun sendDtmf(digit: Char) {
        try {
            currentCall?.sendDtmf(digit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send DTMF")
        }
    }

    fun isRegistered(): Boolean = _registrationState.value == SipRegistrationState.REGISTERED

    /**
     * Prove the registration again right now, without rebuilding the account.
     *
     * This is what a network change needs. Moving between Wi-Fi and mobile data
     * replaces the local address and kills every flow built on the old one, but the
     * PBX keeps the old contact until the registration lapses. Re-registering on the
     * spot means the extension is reachable again in about a second instead of after
     * the expiry, and the new NAT binding is opened immediately.
     *
     * Falls back to a full re-registration when no proxy config survived (a fresh
     * process, which is the same reason the service now registers on start).
     */
    fun refreshRegistration() {
        try {
            val configs = core?.proxyConfigList
            if (configs.isNullOrEmpty()) {
                retryRegistration()
                return
            }
            configs.forEach { it.refreshRegister() }
            Timber.d("SIP registration refreshed (network change)")
        } catch (e: Exception) {
            Timber.w(e, "Could not refresh SIP registration")
        }
    }

    fun retryRegistration() {
        // The identity must stay "<ext>@<main number>". prefs.extension holds the
        // BARE extension (the location feature needs that exact form) and Asterisk
        // has no endpoint by that name, so registering with it is answered with 401
        // and the phone stays offline for ever.
        val identity = when {
            prefs.sipUsername.contains("@") -> prefs.sipUsername
            prefs.username.contains("@") -> prefs.username.trim()
            prefs.sipUsername.isNotEmpty() -> prefs.sipUsername
            prefs.extension.isNotEmpty() -> prefs.extension
            else -> prefs.username
        }
        register(
            sipServer = prefs.sipServer,
            sipPort = prefs.sipPort,
            username = identity,
            password = prefs.sipPassword.ifEmpty { prefs.password },
            displayName = prefs.displayName.ifEmpty { prefs.username },
            transport = prefs.sipTransport
        )
    }

    fun hasActiveCall(): Boolean = currentCall != null

    /**
     * Seconds the CURRENT call has been connected, read straight off the live Linphone
     * Call -- 0 while there is no call, or before it is answered.
     *
     * Unlike [callDuration], which is only published when the call is torn down, this is
     * meaningful DURING the call. The in-call timer polls it so the display cannot sit at
     * 00:00 for a whole call if the single CONNECTED state change were ever missed,
     * conflated by the StateFlow, or delivered before the call screen attached a
     * collector -- none of which the timer can detect on its own.
     */
    fun currentCallDurationSeconds(): Int =
        try {
            currentCall?.duration ?: 0
        } catch (e: Exception) {
            Timber.e(e, "Could not read live call duration")
            0
        }

    fun getCore(): Core? = core

    fun destroy() {
        try {
            iterateHandler.removeCallbacks(iterateRunnable)
            core?.stop()
            core?.removeListener(coreListener)
            instance = null
        } catch (e: Exception) {
            Timber.e(e, "Error destroying SipManager")
        }
    }
}
