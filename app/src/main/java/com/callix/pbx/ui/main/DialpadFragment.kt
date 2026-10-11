package com.callix.pbx.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.databinding.FragmentDialpadBinding
import com.callix.pbx.service.SipManager
import com.callix.pbx.data.model.SipRegistrationState
import com.callix.pbx.ui.call.CallActivity

class DialpadFragment : Fragment() {

    private var _binding: FragmentDialpadBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager
    private lateinit var sipManager: SipManager
    private val dialedNumber = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialpadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())
        sipManager = SipManager.getInstance(requireContext())
        showExtensionName()
        setupDialpad()
        applyPrefilledNumber()
    }

    /**
     * Type in a number handed over by another screen (the call log).
     *
     * It is applied AFTER setupDialpad() on purpose: the TextWatcher registered
     * there is the only thing that copies the field into [dialedNumber], and the
     * call button reads [dialedNumber] first -- so setting the text before that
     * listener existed left the visible number out of step with the dialling one.
     * The number is typed, not dialled: the user still reviews it and presses
     * call, which is what "tap a number in the log" is expected to do.
     */
    private fun applyPrefilledNumber() {
        val carried = arguments?.getString(ARG_PREFILL_NUMBER)?.trim().orEmpty()
        if (carried.isEmpty()) return
        binding.etDialedNumber.setText(carried)
        binding.etDialedNumber.setSelection(carried.length)
    }

    private fun setupDialpad() {
        val buttonClickAnim = AnimationUtils.loadAnimation(context, R.anim.button_press)

        val dialButtons = mapOf(
            binding.btn0 to "0",
            binding.btn1 to "1",
            binding.btn2 to "2",
            binding.btn3 to "3",
            binding.btn4 to "4",
            binding.btn5 to "5",
            binding.btn6 to "6",
            binding.btn7 to "7",
            binding.btn8 to "8",
            binding.btn9 to "9",
            binding.btnStar to "*",
            binding.btnHash to "#"
        )

        dialButtons.forEach { (button, digit) ->
            button.setOnClickListener {
                it.startAnimation(buttonClickAnim)
                appendDigit(digit)
                // Send DTMF if call in progress
                if (sipManager.hasActiveCall()) {
                    sipManager.sendDtmf(digit[0])
                }
            }
        }

        // Delete button
        binding.btnDelete.setOnClickListener {
            if (dialedNumber.isNotEmpty()) {
                dialedNumber.deleteCharAt(dialedNumber.length - 1)
                binding.etDialedNumber.setText(dialedNumber.toString())
                binding.etDialedNumber.setSelection(dialedNumber.length)
            }
        }

        binding.btnDelete.setOnLongClickListener {
            dialedNumber.clear()
            binding.etDialedNumber.setText("")
            true
        }

        // Call button
        binding.btnCall.setOnClickListener {
            val number = dialedNumber.toString().ifEmpty {
                binding.etDialedNumber.text?.toString() ?: ""
            }
            if (number.isNotEmpty()) {
                makeCall(number)
            }
        }

        // Manual input
        binding.etDialedNumber.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                dialedNumber.clear()
                dialedNumber.append(s.toString())
            }
        })
    }

    /**
     * The dialer must state WHICH extension is signed in. The server returns the
     * person's name at login and it is stored in PreferenceManager.displayName;
     * when the server sends no name that value falls back to whatever the user
     * typed, which may be the full SIP identity ("101@09699800222") -- so a value
     * containing "@" is never treated as a name.
     */
    private fun showExtensionName() {
        val name = prefs.displayName.trim()
        val extension = prefs.effectiveExtension.trim()
        val usableName = name.takeIf { it.isNotEmpty() && !it.contains("@") }

        binding.tvExtensionName.text = when {
            usableName != null && extension.isNotEmpty() && usableName != extension ->
                "$usableName · $extension"
            usableName != null -> usableName
            extension.isNotEmpty() -> extension
            name.isNotEmpty() -> name
            else -> getString(R.string.dialpad_no_extension)
        }
    }

    private fun appendDigit(digit: String) {
        dialedNumber.append(digit)
        binding.etDialedNumber.setText(dialedNumber.toString())
        binding.etDialedNumber.setSelection(dialedNumber.length)
    }

    private fun makeCall(number: String) {
        if (!sipManager.isRegistered()) {
            val messageRes = if (sipManager.registrationState.value == SipRegistrationState.FAILED) {
                R.string.call_needs_registration_failed
            } else {
                R.string.call_needs_registration_pending
            }
            Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_LONG).show()
            return
        }
        sipManager.makeCall(number)
        val intent = android.content.Intent(requireContext(), CallActivity::class.java).apply {
            putExtra("call_number", number)
            putExtra("call_direction", "outbound")
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PREFILL_NUMBER = "dialpad_prefill_number"

        /**
         * A dialer that already has [number] typed in. Passing null (or blank)
         * produces the ordinary empty dialer, so callers do not need two paths.
         */
        fun newInstance(number: String?): DialpadFragment = DialpadFragment().apply {
            if (!number.isNullOrBlank()) {
                arguments = Bundle().apply { putString(ARG_PREFILL_NUMBER, number) }
            }
        }
    }
}
