package com.callix.pbx.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.SipRegistrationState
import com.callix.pbx.databinding.FragmentDashboardBinding
import com.callix.pbx.service.SipManager
import com.callix.pbx.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager
    private lateinit var sipManager: SipManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())
        sipManager = SipManager.getInstance(requireContext())

        setupUI()
        observeRegistration()
    }

    private fun setupUI() {
        val name = prefs.displayName.ifEmpty { prefs.username }.ifEmpty { "User" }
        binding.tvDisplayName.text = name
        binding.tvUserInitial.text = name.take(1).uppercase()
        binding.tvExtension.text = "Ext: ${prefs.extension.ifEmpty { prefs.username }}"
        binding.tvSipServer.text = prefs.sipServer.ifEmpty { "sip.callix.com.bd" }

        // Logout
        binding.btnLogout.setOnClickListener {
            (activity as? MainActivity)?.logout()
        }

        binding.cardSipStatus.setOnClickListener {
            sipManager.retryRegistration()
            Toast.makeText(requireContext(), "Retrying PBX registration…", Toast.LENGTH_SHORT).show()
        }

        // Quick call button
        binding.btnQuickCall.setOnClickListener {
            (activity as? MainActivity)?.let {
                it.supportFragmentManager.beginTransaction()
                    .replace(com.callix.pbx.R.id.fragmentContainer, com.callix.pbx.ui.main.DialpadFragment())
                    .commit()
            }
        }

        binding.btnQuickHistory.setOnClickListener {
            (activity as? MainActivity)?.let {
                it.supportFragmentManager.beginTransaction()
                    .replace(com.callix.pbx.R.id.fragmentContainer, com.callix.pbx.ui.calls.CallHistoryFragment())
                    .commit()
            }
        }
    }

    private fun observeRegistration() {
        lifecycleScope.launch {
            sipManager.registrationState.collectLatest { state ->
                val (statusText, statusColor, dotColor) = when (state) {
                    SipRegistrationState.REGISTERED -> Triple(
                        "Online · Registered", 
                        "#4CAF50", "#4CAF50"
                    )
                    SipRegistrationState.REGISTERING -> Triple(
                        "Connecting...", 
                        "#FF9800", "#FF9800"
                    )
                    SipRegistrationState.FAILED -> Triple(
                        "Registration Failed · Tap to retry",
                        "#F44336", "#F44336"
                    )
                    SipRegistrationState.CLEARED -> Triple(
                        "Offline", 
                        "#9E9E9E", "#9E9E9E"
                    )
                    else -> Triple(
                        "Initializing...", 
                        "#9E9E9E", "#9E9E9E"
                    )
                }
                binding.tvSipStatus.text = statusText
                binding.tvSipStatus.setTextColor(android.graphics.Color.parseColor(statusColor))
                binding.ivStatusDot.setColorFilter(android.graphics.Color.parseColor(dotColor))
            }
        }

        lifecycleScope.launch {
            sipManager.registrationMessage.collectLatest { message ->
                if (sipManager.registrationState.value == SipRegistrationState.FAILED && !message.isNullOrBlank()) {
                    binding.tvSipServer.text = "sip.callix.com.bd · $message"
                } else if (sipManager.registrationState.value != SipRegistrationState.FAILED) {
                    binding.tvSipServer.text = prefs.sipServer
                }
            }
        }

        lifecycleScope.launch {
            sipManager.callState.collectLatest { state ->
                val callText = when (state) {
                    com.callix.pbx.data.model.CallState.INCOMING -> "📞 Incoming Call..."
                    com.callix.pbx.data.model.CallState.OUTGOING -> "📲 Calling..."
                    com.callix.pbx.data.model.CallState.CONNECTED -> "🔗 In Call"
                    com.callix.pbx.data.model.CallState.PAUSED -> "⏸ Call on Hold"
                    else -> ""
                }
                binding.tvActiveCall.text = callText
                binding.tvActiveCall.visibility = if (callText.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
