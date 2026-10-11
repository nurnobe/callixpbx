package com.callix.pbx.ui.call

import android.content.Intent
import android.os.*
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.callix.pbx.R
import com.callix.pbx.data.remote.CallerIdentity
import com.callix.pbx.databinding.ActivityCallBinding
import com.callix.pbx.data.model.CallState
import com.callix.pbx.service.SipManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var sipManager: SipManager
    private var isMuted = false
    private var isSpeaker = false
    private var isOnHold = false
    /** Monotonic timestamp of the moment the remote party actually answered. */
    private var connectedAtElapsedMs: Long? = null
    private var timerJob: kotlinx.coroutines.Job? = null
    private var timerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sipManager = SipManager.getInstance(this)

        val direction = intent.getStringExtra("call_direction") ?: "outbound"
        val rawName = intent.getStringExtra("caller_name")
        val rawNumber = intent.getStringExtra("caller_number")
            ?: intent.getStringExtra("call_number")

        val (callerName, callerNumber) = resolveCaller(rawName, rawNumber, direction)

        setupUI(callerName, callerNumber, direction)
        observeCallState()
        resetTimer()
    }

    /**
     * The name/number pair this screen shows, for either direction.
     *
     * Extracted so onCreate and onNewIntent cannot drift: the activity is singleTop, so
     * a second call reuses this instance and only onNewIntent runs.
     */
    private fun resolveCaller(
        rawName: String?,
        rawNumber: String?,
        direction: String
    ): Pair<String, String> {
        if (direction == "inbound") {
            // Inbound extras carry the resolved pair handed over by the incoming
            // screen. Re-resolving is idempotent by design, and it is what keeps the
            // two screens showing the same thing -- including a colleague's name
            // together with their extension number.
            val display = CallerIdentity.fromInbound(rawName, rawNumber)
            return CallerText.primary(this, display) to CallerText.secondary(this, display).orEmpty()
        }
        // Outbound is deliberately NOT resolved: the user dialled this, so it is
        // shown exactly as dialled, even when short. An internal extension is a
        // real thing to have dialled and must not be filtered away -- and a
        // number must not be printed twice, once as the name and once below it.
        val dialled = rawNumber?.trim().orEmpty().ifEmpty { rawName?.trim().orEmpty() }
        val name = rawName?.trim()?.takeIf { it.isNotBlank() && it != dialled }
        val primary = name ?: dialled.ifEmpty { getString(R.string.incoming_unknown_caller) }
        return primary to (if (name != null) dialled else "")
    }

    /**
     * The activity is singleTop, so a SECOND call reuses this instance and only
     * onNewIntent runs -- onCreate does not. Without this, the screen kept the previous
     * caller's identity and, worse, the previous call's timer: it was never reset, so a
     * new call began life already counting from the last one's duration.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val direction = intent.getStringExtra("call_direction") ?: "outbound"
        val rawName = intent.getStringExtra("caller_name")
        val rawNumber = intent.getStringExtra("caller_number")
            ?: intent.getStringExtra("call_number")

        val (callerName, callerNumber) = resolveCaller(rawName, rawNumber, direction)
        setupUI(callerName, callerNumber, direction)

        // A new call gets a new timer. The old one is stopped and the counter returned
        // to zero before the new call can connect -- otherwise this call would begin
        // life already counting from the previous one's duration.
        resetTimer()
    }

    private fun setupUI(callerName: String, callerNumber: String, direction: String) {
        binding.tvCallerName.text = callerName
        // An empty second line would leave a gap under the name, so it is hidden
        // outright when it has nothing to add.
        if (callerNumber.isBlank()) {
            binding.tvCallerNumber.visibility = View.GONE
        } else {
            binding.tvCallerNumber.text = callerNumber
            binding.tvCallerNumber.visibility = View.VISIBLE
        }
        binding.tvCallStatus.text = if (direction == "outbound") "Calling..." else "Connecting..."

        // Hang up
        binding.btnHangUp.setOnClickListener {
            sipManager.hangUp()
            finish()
        }

        // Mute
        binding.btnMute.setOnClickListener {
            isMuted = sipManager.toggleMute()
            binding.btnMute.setIconResource(
                if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
            )
            binding.tvMuteLabel.text = if (isMuted) "Unmute" else "Mute"
        }

        // Speaker
        binding.btnSpeaker.setOnClickListener {
            isSpeaker = sipManager.toggleSpeaker()
            binding.btnSpeaker.setIconResource(
                if (isSpeaker) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off
            )
            binding.tvSpeakerLabel.text = if (isSpeaker) "Earpiece" else "Speaker"
        }

        // Hold
        binding.btnHold.setOnClickListener {
            if (isOnHold) {
                sipManager.resumeCall()
                isOnHold = false
                binding.btnHold.setIconResource(R.drawable.ic_pause)
                binding.tvHoldLabel.text = "Hold"
                binding.tvCallStatus.text = "Connected"
            } else {
                sipManager.holdCall()
                isOnHold = true
                binding.btnHold.setIconResource(R.drawable.ic_play)
                binding.tvHoldLabel.text = "Resume"
                binding.tvCallStatus.text = "On Hold"
            }
        }

        // Transfer
        binding.btnTransfer.setOnClickListener {
            showTransferDialog()
        }

        // Dialpad toggle
        binding.btnDialpad.setOnClickListener {
            val isVisible = binding.layoutDialpad.visibility == View.VISIBLE
            binding.layoutDialpad.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // In-call dialpad digits
        setupInCallDialpad()
    }

    private fun setupInCallDialpad() {
        val dtmfButtons = mapOf(
            binding.dtmf0 to '0', binding.dtmf1 to '1',
            binding.dtmf2 to '2', binding.dtmf3 to '3',
            binding.dtmf4 to '4', binding.dtmf5 to '5',
            binding.dtmf6 to '6', binding.dtmf7 to '7',
            binding.dtmf8 to '8', binding.dtmf9 to '9',
            binding.dtmfStar to '*', binding.dtmfHash to '#'
        )
        dtmfButtons.forEach { (btn, digit) ->
            btn.setOnClickListener { sipManager.sendDtmf(digit) }
        }
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            sipManager.callState.collectLatest { state ->
                when (state) {
                    CallState.CONNECTED -> {
                        binding.tvCallStatus.text = "Connected"
                        startConnectedTimer()
                    }
                    CallState.OUTGOING -> {
                        binding.tvCallStatus.text = "Ringing..."
                    }
                    CallState.PAUSED -> {
                        binding.tvCallStatus.text = "On Hold"
                    }
                    CallState.ENDED, CallState.ERROR -> {
                        timerJob?.cancel()
                        timerJob = null
                        timerRunning = false
                        binding.tvCallStatus.text = if (state == CallState.ENDED) "Call Ended" else "Call Failed"
                        delay(1500)
                        finish()
                    }
                    else -> {}
                }
            }
        }
    }

    /** Reset before a new outgoing/incoming call is answered. */
    private fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        timerRunning = false
        connectedAtElapsedMs = null
        binding.tvCallTimer.text = "00:00"
    }

    /**
     * Starts exactly once, and only after SIP reports CONNECTED. Linphone's
     * Call.duration includes ringing on some versions, so it must not be used for
     * the UI timer: otherwise an unanswered outgoing call appears connected and a
     * second counter starts when it is finally answered.
     */
    private fun startConnectedTimer() {
        if (timerRunning) return
        timerRunning = true
        connectedAtElapsedMs = SystemClock.elapsedRealtime()
        timerJob = lifecycleScope.launch {
            while (true) {
                renderTimer()
                delay(1000)
            }
        }
    }

    private fun renderTimer() {
        val connectedAt = connectedAtElapsedMs ?: return
        val callSeconds = ((SystemClock.elapsedRealtime() - connectedAt) / 1000L).toInt()
        val hours = callSeconds / 3600
        val minutes = (callSeconds % 3600) / 60
        val seconds = callSeconds % 60
        binding.tvCallTimer.text = if (hours > 0)
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else
            String.format("%02d:%02d", minutes, seconds)
    }

    private fun showTransferDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
        val input = android.widget.EditText(this).apply {
            hint = "Transfer to (extension/number)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 24, 48, 24)
        }
        dialog.setTitle("Call Transfer")
            .setView(input)
            .setPositiveButton("Transfer") { _, _ ->
                val transferTo = input.text.toString()
                if (transferTo.isNotEmpty()) {
                    sipManager.transferCall(transferTo)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Don't close on back press during active call
        if (sipManager.callState.value == CallState.CONNECTED) {
            // Minimize instead
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        timerJob = null
        timerRunning = false
        super.onDestroy()
    }
}
