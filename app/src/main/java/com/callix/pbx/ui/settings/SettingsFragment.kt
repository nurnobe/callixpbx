package com.callix.pbx.ui.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.databinding.FragmentSettingsBinding
import com.callix.pbx.ui.auth.LoginActivity
import com.callix.pbx.util.KeepAlive
import timber.log.Timber

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        prefs.ringtoneUri = uri?.toString().orEmpty()
        updateRingtoneLabel()
        Toast.makeText(requireContext(), "Incoming ringtone saved", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())
        setupUI()
    }

    private fun setupUI() {
        // Current user info
        binding.tvUsername.text = prefs.username.ifEmpty { prefs.userEmail }.ifEmpty { "—" }
        binding.tvExtension.text = prefs.extension.ifEmpty { "—" }
        binding.tvServer.text = prefs.apiUrl.ifEmpty { "https://app.callix.com.bd" }

        // SIP Settings
        binding.etSipServer.setText(prefs.sipServer)
        binding.etSipPort.setText(prefs.sipPort.toString())

        // Save SIP settings
        binding.btnSaveSip.setOnClickListener {
            val server = binding.etSipServer.text.toString().trim()
            val port = binding.etSipPort.text.toString().trim().toIntOrNull() ?: 5060
            if (server.isNotEmpty()) {
                prefs.sipServer = server
                prefs.sipPort = port
                Toast.makeText(requireContext(), "SIP settings saved", Toast.LENGTH_SHORT).show()
                Timber.d("SIP settings saved: $server:$port")
            } else {
                Toast.makeText(requireContext(), "Please enter SIP server address", Toast.LENGTH_SHORT).show()
            }
        }

        // Call alerts + background access. Both grants can be revoked by the user
        // or by an OS upgrade, and both decide whether a call actually reaches the
        // screen, so the state is shown and each row opens the page that fixes it.
        binding.rowFullScreen.setOnClickListener {
            try {
                startActivity(KeepAlive.fullScreenIntentSettingsIntent(requireContext()))
            } catch (t: Throwable) {
                Timber.w(t, "No full-screen-intent settings page")
                try {
                    startActivity(KeepAlive.appDetailsIntent(requireContext()))
                } catch (t2: Throwable) {
                    Timber.w(t2, "No app details page")
                }
            }
        }
        binding.rowBattery.setOnClickListener {
            try {
                startActivity(KeepAlive.batteryOptimizationSettingsIntent(requireContext()))
            } catch (t: Throwable) {
                Timber.w(t, "No battery-optimisation dialog")
                try {
                    startActivity(KeepAlive.appDetailsIntent(requireContext()))
                } catch (t2: Throwable) {
                    Timber.w(t2, "No app details page")
                }
            }
        }

        binding.rowRingtone.setOnClickListener {
            val current = prefs.ringtoneUri.takeIf { it.isNotBlank() }
                ?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Incoming ringtone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            })
        }
        updateRingtoneLabel()

        // Logout
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Log Out?")
                .setMessage("Are you sure you want to log out? You will need to sign in again.")
                .setPositiveButton("Log Out") { _, _ -> logout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refreshed here, not only in onViewCreated: the user leaves for a system
        // settings page and comes straight back, and the row must now tell the truth.
        refreshAccessStatus()
    }

    /**
     * Report the two grants in plain words.
     *
     * "Not allowed -- tap to fix" is deliberately an instruction rather than a bare
     * "denied": the row is the fix, and a status the user cannot act on is noise.
     */
    private fun refreshAccessStatus() {
        val context = context ?: return

        binding.tvFullScreenStatus.text = if (KeepAlive.canUseFullScreenIntent(context)) {
            getString(R.string.settings_status_on)
        } else {
            getString(R.string.settings_status_off)
        }

        binding.tvBatteryStatus.text = if (KeepAlive.isIgnoringBatteryOptimizations(context)) {
            getString(R.string.settings_status_on)
        } else {
            getString(R.string.settings_status_off)
        }
    }

    private fun updateRingtoneLabel() {
        val uri = prefs.ringtoneUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        binding.tvRingtoneStatus.text = if (uri == null) {
            "System default"
        } else {
            RingtoneManager.getRingtone(requireContext(), uri)?.getTitle(requireContext())
                ?: "Selected ringtone"
        }
    }

    private fun logout() {
        // Tell the PBX first, while the session is still valid.
        //
        // Tearing the service down no longer de-registers the account -- that is what
        // used to take the extension offline on an ordinary restart -- so signing out
        // has to say so itself. Without this the phone would stay registered on the
        // PBX for someone who has signed out, and would keep ringing for them.
        try {
            com.callix.pbx.service.SipManager.getInstance(requireContext()).unregister()
        } catch (e: Exception) {
            Timber.w(e, "SIP unregister on logout failed")
        }
        try {
            val intent = android.content.Intent(requireContext(),
                com.callix.pbx.service.SipService::class.java)
            requireContext().stopService(intent)
        } catch (e: Exception) {
            Timber.w(e, "SipService stop on logout failed")
        }
        prefs.clearUserData()
        startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
