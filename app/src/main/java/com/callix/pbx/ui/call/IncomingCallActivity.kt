package com.callix.pbx.ui.call

import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.callix.pbx.R
import com.callix.pbx.data.local.ExtensionDirectory
import com.callix.pbx.data.model.CallState
import com.callix.pbx.data.remote.CallerIdentity
import com.callix.pbx.databinding.ActivityIncomingCallBinding
import com.callix.pbx.service.SipManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

/**
 * The full-screen incoming-call screen.
 *
 * SWIPE UP TO ACT
 * Each target is dragged towards the top of the screen: the green circle answers,
 * the red one declines. The drag is what makes the gesture safe on a locked phone
 * in a pocket -- a swipe needs intent, where a tap can happen by accident. Both
 * targets remain TAPPABLE, because a gesture must never be the only way to answer
 * a call.
 *
 * This screen deliberately does NOT play the ringtone and does NOT vibrate.
 * [SipService] owns both: it is the component that actually sees the incoming
 * call, it exists exactly once, and an activity here can be created more than
 * once for a single call (the service starts it AND the full-screen-intent
 * PendingIntent points at it, and a configuration change recreates it). When the
 * ring lived here, each creation started another looping player, which is what
 * produced two ringtones for one call. This screen is now only UI: show who is
 * calling, and accept or decline.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var sipManager: SipManager

    /** The pulse animations, so they can be stopped instead of leaking. */
    private val pulseAnimators = mutableListOf<ValueAnimator>()
    private val actionAnimators = mutableListOf<ObjectAnimator>()

    /**
     * What this call is, resolved once. Kept as a field so the intent that carries
     * it to the in-call screen is built from the SAME values that were rendered,
     * rather than re-read from the (possibly stale) launch intent.
     */
    private lateinit var display: CallerIdentity.Display
    /** Stops a second gesture while the first answer/decline is being handed to SIP. */
    private var actionInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sipManager = SipManager.getInstance(this)

        setupUI()

        // These two extras are the call's identity. The service resolves the raw SIP
        // string once and passes the resolved pair; re-resolving it here is
        // idempotent, and it is what makes the screen independent of WHICH launch
        // path got here first (see resolveAndRender).
        resolveAndRender(
            intent.getStringExtra(EXTRA_CALLER_NAME),
            intent.getStringExtra(EXTRA_CALLER_NUMBER)
        )

        observeCallState()
    }

    /**
     * A second delivery of the same call.
     *
     * Both launch paths can reach this activity for one call -- the service's
     * explicit start, and the notification's full-screen intent (which is the one
     * that runs when the phone is locked). Whichever arrives second is delivered
     * here rather than through [onCreate], because the activity is SINGLE_TOP.
     *
     * Without this override the second delivery was silently dropped: the screen
     * kept whatever the FIRST delivery had rendered. That is exactly how a locked
     * handset ended up showing "Unknown number" for a caller whose number the
     * service had resolved correctly a moment later.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Adopt the newest intent as THE intent, so anything else reading it (the
        // in-call hand-off below) sees the same values this screen is showing.
        setIntent(intent)
        resolveAndRender(
            intent.getStringExtra(EXTRA_CALLER_NAME),
            intent.getStringExtra(EXTRA_CALLER_NUMBER)
        )
    }

    /**
     * Resolve the caller and draw both lines.
     *
     * [rawName] and [rawNumber] arrive as the resolved pair, and are re-resolved so
     * a value that is already a number or an extension survives the second pass.
     *
     * The last resort matters: if BOTH are empty -- a vendor ROM that delivers the
     * full-screen intent stripped of its extras is the real case -- the raw identity
     * is read back from the service, which is the component that saw the INVITE. A
     * caller's number must never be lost because of how the screen was launched.
     */
    private fun resolveAndRender(rawName: String?, rawNumber: String?) {
        var name = rawName
        var number = rawNumber
        if (name.isNullOrBlank() && number.isNullOrBlank()) {
            val info = sipManager.incomingCallInfo.value
            name = info?.remoteName
            number = info?.remoteAddress
            Timber.d("Incoming-call extras were empty; identity taken from the live call")
        }

        display = CallerIdentity.withDirectoryName(
            CallerIdentity.fromInbound(name, number)
        ) { ext -> ExtensionDirectory.nameFor(this, ext) }

        binding.tvCallerName.text = CallerText.primary(this, display)
        binding.tvCallerInitials.text = initials(display)

        // The second line is drawn only when it adds something the first does not.
        // For a colleague on an extension it always does: the name does not tell you
        // which extension to dial back.
        val secondary = CallerText.secondary(this, display)
        if (secondary != null) {
            binding.tvCallerNumber.text = secondary
            binding.tvCallerNumber.visibility = View.VISIBLE
        } else {
            binding.tvCallerNumber.visibility = View.GONE
        }
    }

    /** Wire the two targets. Rendering is [resolveAndRender]'s job, not this one's. */
    private fun setupUI() {
        attachSwipeUp(binding.btnAnswer) { answer() }
        attachSwipeUp(binding.btnDecline) { decline() }
    }

    /**
     * A letter for the avatar: the caller's initial when the name is known, and a
     * neutral mark otherwise. Never the first digit of an extension -- a big "1" in
     * a circle reads as part of the number rather than as a person.
     */
    private fun initials(display: CallerIdentity.Display): String {
        val name = display.name?.trim()
        if (!name.isNullOrEmpty()) return name.first().uppercaseChar().toString()
        return if (display.number != null) "#" else "?"
    }

    private fun answer() {
        if (actionInProgress) return
        actionInProgress = true
        // Answering moves the call to CONNECTED, which is what stops the ring in
        // SipService. Nothing is stopped here -- this screen does not own the ring,
        // so it must not try to silence something it never started.
        sipManager.answerCall()
        // The extras carry the RESOLVED pieces, not the raw identity and not the
        // formatted label, so the in-call screen re-resolves to exactly this pair.
        val next = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALLER_NAME, display.name ?: "")
            putExtra(EXTRA_CALLER_NUMBER, display.number ?: "")
            putExtra(EXTRA_CALL_DIRECTION, "inbound")
        }
        startActivity(next)
        finish()
    }

    private fun decline() {
        if (actionInProgress) return
        actionInProgress = true
        sipManager.declineCall()
        finish()
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            sipManager.callState.collectLatest { state ->
                when (state) {
                    CallState.ENDED, CallState.ERROR -> finish()
                    else -> {}
                }
            }
        }
    }

    /**
     * Drag [target] towards the top of the screen to act.
     *
     * The touch listener owns the whole gesture rather than layering a click
     * listener beside it: consuming DOWN is what lets the drag exist, and once the
     * events are consumed a click listener never fires. So a gesture that ends
     * without travelling past the touch slop is treated as a tap and performs the
     * same action -- press and release is still enough to answer a call.
     */
    private fun attachSwipeUp(target: View, onTriggered: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val threshold = -resources.displayMetrics.density * SWIPE_DISTANCE_DP

        var startY = 0f
        var travelled = false
        var fired = false

        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    travelled = false
                    fired = false
                    view.animate().cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Upward travel only: dragging down must not move the target.
                    // Do not let the target disappear off-screen under the user's
                    // finger. The old raw translation could move it hundreds of dp
                    // upward, which looked as if the answer button had been cut off
                    // even though the call was accepted.
                    val up = (event.rawY - startY).coerceAtMost(0f).coerceAtLeast(threshold)
                    travelled = travelled || abs(event.rawY - startY) > slop
                    view.translationY = up
                    // Fade as it is committed, so the drag reads as "this is happening".
                    view.alpha = (1f + (up / threshold) * 0.35f).coerceIn(0.55f, 1f)
                    if (!fired && up <= threshold) {
                        fired = true
                        commitAction(view)
                        onTriggered()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!fired) {
                        springBack(view)
                        if (!travelled) {
                            // A tap. Same action, same path.
                            commitAction(view)
                            onTriggered()
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** Short confirmation animation without translating the target out of its row. */
    private fun commitAction(view: View) {
        view.performClick()
        view.animate()
            .translationY(-resources.displayMetrics.density * 18f)
            .scaleX(1.08f)
            .scaleY(1.08f)
            .alpha(0.85f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun springBack(view: View) {
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * The pulse behind the avatar: the only thing on this screen that moves, so the
     * eye lands on WHO is calling rather than on the buttons.
     *
     * [ValueAnimator] rather than [android.animation.AnimatorSet] because only a
     * value animator can repeat indefinitely; the set cannot loop.
     */
    private fun startPulse() {
        if (pulseAnimators.isNotEmpty()) return
        listOf(binding.pulseRing1 to 0L, binding.pulseRing2 to 700L).forEach { (ring, delay) ->
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PULSE_DURATION_MS
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    val scale = 0.90f + 0.40f * t
                    ring.scaleX = scale
                    ring.scaleY = scale
                    ring.alpha = 0.45f * (1f - t)
                }
            }
            animator.start()
            pulseAnimators += animator
        }
        // A gentle breathing motion tells the user that both answer controls are
        // alive before they touch the screen. It changes scale only, never position,
        // so it cannot fight the upward swipe gesture.
        listOf(binding.btnDecline to 0L, binding.btnAnswer to 250L).forEach { (button, delay) ->
            val animator = ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 1.06f, 1f).apply {
                duration = 1050L
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
            }
            animator.start()
            actionAnimators += animator
            val yAnimator = ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 1.06f, 1f).apply {
                duration = 1050L
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
            }
            yAnimator.start()
            actionAnimators += yAnimator
        }
    }

    private fun stopPulse() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators.clear()
        actionAnimators.forEach { it.cancel() }
        actionAnimators.clear()
        // Leave the rings in a defined state, not wherever the animation stopped.
        binding.pulseRing1.scaleX = 1f
        binding.pulseRing1.scaleY = 1f
        binding.pulseRing2.scaleX = 1f
        binding.pulseRing2.scaleY = 1f
        binding.btnAnswer.scaleX = 1f
        binding.btnAnswer.scaleY = 1f
        binding.btnDecline.scaleX = 1f
        binding.btnDecline.scaleY = 1f
    }

    override fun onResume() {
        super.onResume()
        try {
            startPulse()
        } catch (t: Throwable) {
            // An animation is decoration; it must never keep a call from being shown.
            Timber.w(t, "Incoming-call pulse could not start")
        }
    }

    override fun onPause() {
        stopPulse()
        super.onPause()
    }

    companion object {
        /**
         * The extras that carry a caller to this screen.
         *
         * One spelling, declared once, because MORE THAN ONE component launches this
         * activity: [com.callix.pbx.service.SipService] starts it directly, and the
         * incoming-call notification's full-screen intent starts it too (which is
         * what actually happens when the phone is locked). When those two disagreed
         * about what to put in which extra, the screen showed "Unknown number" --
         * so the names are owned here, by the screen that reads them.
         *
         * The pair is the RESOLVED identity (a name and a number/extension), not the
         * raw SIP string, and it is deliberately re-resolved on arrival so that
         * either launch path, in either order, lands on the same two lines.
         */
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_CALL_DIRECTION = "call_direction"

        /** How far up the target must travel before the action fires. */
        private const val SWIPE_DISTANCE_DP = 48f

        /** One full breath of the pulse. */
        private const val PULSE_DURATION_MS = 1800L
    }
}
