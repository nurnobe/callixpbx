package com.callix.pbx.ui.contacts

import android.Manifest
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.ContactRow
import com.callix.pbx.data.model.ServerContact
import com.callix.pbx.data.remote.DeviceContacts
import com.callix.pbx.data.remote.RetrofitClient
import com.callix.pbx.databinding.FragmentContactsBinding
import com.callix.pbx.service.SipManager
import com.callix.pbx.data.model.SipRegistrationState
import com.callix.pbx.ui.call.CallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One list holding two sources: the tenant's PBX directory and the contacts saved
 * on this handset. Both rows are dialable, which is the point -- the phone's own
 * contacts previously could not be reached from this app at all.
 *
 * The two sources fail independently and are reported independently:
 *   - the PBX request failing is NOT an empty directory (this screen once showed
 *     six invented people when the API threw, and the user would dial a stranger);
 *   - the address book being unreadable is NOT a denial, so it gets its own line
 *     and its own way back.
 */
class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager
    private lateinit var sipManager: SipManager
    private lateinit var adapter: ContactsAdapter

    private var deviceRows: List<ContactRow> = emptyList()
    private var pbxRows: List<ContactRow> = emptyList()
    private var pbxFailed = false
    private var query = ""
    private var deviceRowsLoaded = false
    private var presenceRefreshJob: Job? = null

    /**
     * The address book could not be READ (as opposed to being empty, or denied).
     * Tracked apart from [pbxFailed] so the empty state can name the source that
     * actually failed instead of blaming both.
     */
    private var deviceFailed = false

    /** Distinguishes "not asked yet" from "asked and refused" in the banner text. */
    private var permissionRefused = false

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefused = !granted
        if (_binding == null) return@registerForActivityResult
        refreshPermissionBanner()
        if (granted) loadContacts()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())
        sipManager = SipManager.getInstance(requireContext())

        setupRecyclerView()
        setupSearch()
        setupPermissionBanner()
        loadContacts()

        binding.swipeRefresh.setOnRefreshListener { loadContacts() }

        // Ask once, on first entry, so the phone's contacts are visible without the
        // user hunting for a setting. A refusal is remembered and only re-asked when
        // the banner is tapped.
        if (!DeviceContacts.hasPermission(requireContext()) && savedInstanceState == null) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the permission in system Settings while we were
        // in the background: re-read the banner, and pick up the rows if we now can.
        if (_binding != null) {
            refreshPermissionBanner()
            if (DeviceContacts.hasPermission(requireContext()) && !deviceRowsLoaded) loadContacts()
            startPresenceRefresh()
        }
    }

    override fun onPause() {
        presenceRefreshJob?.cancel()
        presenceRefreshJob = null
        super.onPause()
    }

    /** Poll the server while this tab is visible so registration changes appear promptly. */
    private fun startPresenceRefresh() {
        presenceRefreshJob?.cancel()
        presenceRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                // The server is the presence authority. Five seconds keeps the
                // directory visibly in step with registration without holding a
                // permanent socket or polling while this screen is hidden.
                delay(5_000L)
                if (_binding != null) loadContacts(refreshDeviceContacts = false)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { row -> placeCall(row) }
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ContactsFragment.adapter
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                applyFilter(newText.orEmpty())
                return true
            }
        })
    }

    private fun setupPermissionBanner() {
        refreshPermissionBanner()
        binding.tvPermissionBanner.setOnClickListener {
            if (DeviceContacts.hasPermission(requireContext())) {
                loadContacts()
            } else {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun refreshPermissionBanner() {
        if (_binding == null) return
        val granted = DeviceContacts.hasPermission(requireContext())
        binding.tvPermissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.tvPermissionBanner.text = getString(
            if (permissionRefused) R.string.contacts_phone_denied
            else R.string.contacts_phone_permission_hint
        )
    }

    /** Dial through the registered extension, exactly like the dialer does. */
    private fun placeCall(row: ContactRow) {
        val number = row.number.trim()
        if (number.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.calls_no_number_to_copy),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!sipManager.isRegistered()) {
            // Offering a call the PBX cannot place is how a user learns to distrust
            // the button. Say why instead, with the same wording the dialer uses.
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
            putExtra("caller_name", row.name)
            putExtra("caller_number", number)
            putExtra("call_direction", "outbound")
        }
        startActivity(intent)
    }

    private fun applyFilter(q: String) {
        if (_binding == null) return
        query = q
        val merged = pbxRows + deviceRows
        val filtered = if (q.isBlank()) merged else merged.filter { matches(it, q) }
        adapter.submitList(filtered)

        if (filtered.isNotEmpty()) {
            binding.tvEmpty.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
            return
        }
        binding.rvContacts.visibility = View.GONE
        binding.tvEmpty.text = when {
            // Rows exist but the search excluded them all.
            merged.isNotEmpty() -> getString(R.string.contacts_empty)
            // Nothing at all, and at least one source refused to answer. Name the
            // fact that actually happened -- a vague "no contacts" would be a lie
            // when the truth is that we could not look.
            deviceFailed && !pbxFailed -> getString(R.string.contacts_load_failed_permission)
            pbxFailed -> getString(R.string.contacts_load_failed)
            deviceFailed -> getString(R.string.contacts_load_failed_permission)
            else -> getString(R.string.contacts_empty)
        }
        binding.tvEmpty.visibility = View.VISIBLE
    }

    private fun matches(row: ContactRow, q: String): Boolean =
        row.name.contains(q, ignoreCase = true) ||
            row.number.contains(q, ignoreCase = true) ||
            row.extension.contains(q, ignoreCase = true) ||
            row.department.contains(q, ignoreCase = true)

    private fun loadContacts(refreshDeviceContacts: Boolean = true) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true

            // 1. The handset's own address book. Reading it can be slow on a large
            //    book, so it never runs on the main thread. It is isolated in its
            //    own try: an unreadable address book must not be reported as a
            //    failed directory, and must not stop the directory from loading.
            if (refreshDeviceContacts) {
                try {
                    val device = withContext(Dispatchers.IO) { DeviceContacts.load(requireContext()) }
                    deviceRowsLoaded = DeviceContacts.hasPermission(requireContext())
                    deviceFailed = false
                    deviceRows = device.map { c ->
                        ContactRow(
                            key = "dev:" + c.number + "|" + c.name.lowercase(),
                            name = c.name,
                            number = c.number,
                            isDevice = true
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read this phone's contacts")
                    deviceFailed = true
                    deviceRowsLoaded = false
                    deviceRows = emptyList()
                }
            }

            // 2. The tenant's live presence directory. Unlike /api/pbx/extensions
            // (which is a provisioning list), /api/contacts carries the PBX's
            // current registration status for every extension.
            try {
                val apiService = RetrofitClient.getApiService(prefs.apiUrl)
                val response = apiService.getContacts("Bearer ${prefs.authToken}")
                if (response.isSuccessful && response.body()?.success == true) {
                    pbxFailed = false
                    val ownExtension = prefs.extension.trim()
                    pbxRows = response.body()?.data.orEmpty()
                        .map { it.toRow() }
                        // A directory is for reaching colleagues, not for showing
                        // the logged-in user's own extension as a contact.
                        .filterNot { it.extension == ownExtension }
                } else {
                    Timber.e("Contacts request failed: HTTP %d", response.code())
                    pbxFailed = true
                    pbxRows = emptyList()
                }
            } catch (e: Exception) {
                // The true directory is unknown, so display nothing but the reason.
                // Never fabricate a contact.
                Timber.e(e, "Failed to load contacts")
                pbxFailed = true
                pbxRows = emptyList()
            }

            if (_binding != null) {
                binding.swipeRefresh.isRefreshing = false
                applyFilter(query)
            }
        }
    }

    private fun ServerContact.toRow(): ContactRow {
        val ext = extension.orEmpty().trim()
        return ContactRow(
            key = "pbx:" + (id?.trim().takeUnless { it.isNullOrBlank() } ?: ext),
            name = name.orEmpty().trim().ifBlank { "Extension $ext" },
            number = ext,
            extension = ext,
            department = department.orEmpty().trim(),
            status = status.orEmpty().trim().ifBlank { "offline" },
            isDevice = false
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
