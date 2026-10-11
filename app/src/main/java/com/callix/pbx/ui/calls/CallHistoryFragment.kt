package com.callix.pbx.ui.calls

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.data.model.CallRecord
import com.callix.pbx.data.remote.ApiService
import com.callix.pbx.data.remote.DeviceContacts
import com.callix.pbx.data.remote.PhoneNumbers
import com.callix.pbx.data.remote.RetrofitClient
import com.callix.pbx.databinding.FragmentCallHistoryBinding
import com.callix.pbx.service.MissedCallNotifier
import com.callix.pbx.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class CallHistoryFragment : Fragment() {

    private var _binding: FragmentCallHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager
    private lateinit var adapter: CallHistoryAdapter
    private var player: MediaPlayer? = null

    /**
     * Canonical number -> the name the user has for it, taken from the phone's
     * address book and the PBX directory.
     *
     * A call log records NUMBERS, so a row for a number the user has saved as a
     * contact still reads as bare digits. This index is what turns those digits
     * back into the name the user actually knows. Rebuilt on every refresh, because
     * a contact can be added while the screen is open.
     */
    private var nameIndex: Map<String, String> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())

        // Opening the call log IS what "having seen the missed calls" means, so the
        // screen itself records it -- and cancels the alert.
        //
        // This deliberately lives here rather than only in the navigation that
        // usually leads here. The log is reachable by three routes (the bottom bar,
        // the missed-call notification, and the Dashboard's "quick history" button),
        // and marking it seen on only some of them is exactly how an alert outlives
        // the log the user is looking at. A screen that must be "seen" has to say so
        // itself, wherever it was opened from.
        MissedCallNotifier.markSeen(requireContext())

        setupRecyclerView()
        loadCallHistory()

        binding.swipeRefresh.setOnRefreshListener { loadCallHistory() }
    }

    private fun setupRecyclerView() {
        adapter = CallHistoryAdapter(
            onRowClick = { call -> openInDialer(call) },
            onRowLongClick = { call -> copyNumber(call) },
            onPlayClick = { call -> togglePlayback(call) }
        )
        binding.rvCallHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CallHistoryFragment.adapter
        }
    }

    /**
     * Tapping a row carries its number to the dialer instead of merely opening the
     * dialpad. The old handler discarded the row (`{ _ -> ... }`) and showed an
     * empty pad, so the number the user had just tapped was nowhere on screen.
     *
     * The number is typed in, not dialled on the spot: the user still sees it and
     * presses call, which is what tapping a number in a call log is expected to do.
     */
    private fun openInDialer(call: CallRecord) {
        val number = call.otherNumber
        if (number.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.calls_no_number_to_copy),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        (activity as? MainActivity)?.openDialpadWith(number)
    }

    /** Long press = copy the number, so it can be pasted anywhere. */
    private fun copyNumber(call: CallRecord) {
        val number = call.otherNumber
        if (number.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.calls_no_number_to_copy),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Timber.w("No clipboard service available on this device")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Callix number", number))
        // Since Android 13 the system draws its own "Copied" confirmation; a second
        // toast on top of it is just noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                requireContext(),
                getString(R.string.calls_number_copied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ────────────────────────── recording playback ──────────────────────────

    /**
     * Play the caller's OWN recording, or stop if this row is already playing.
     *
     * The URL comes from the server (recordingUrl) and is already the access-checked
     * media route, so this never builds a file path of its own -- a client-built
     * path is how one extension would end up requesting another one's recording.
     * The session token travels as an Authorization header rather than a query
     * string, so it stays out of the URL and out of any log that records URLs.
     */
    private fun togglePlayback(call: CallRecord) {
        val path = call.recordingUrl
        if (path.isNullOrBlank()) return

        if (player != null && adapter.playingId == call.id) {
            stopPlayback()
            return
        }
        stopPlayback()

        try {
            val request = MediaPlayer()
            request.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            request.setDataSource(
                requireContext(),
                Uri.parse(absoluteMediaUrl(path)),
                mapOf("Authorization" to "Bearer ${prefs.authToken}")
            )
            // `player` is the single source of truth for "this player is still ours".
            // Keep it assigned BEFORE prepareAsync(): a callback that arrives after
            // the screen went away (the prepare was still in flight) can then tell it
            // is stale and must not touch the released instance. Calling start() on a
            // released MediaPlayer throws on the main thread and takes the app down.
            player = request
            request.setOnPreparedListener { mp ->
                if (player !== request) return@setOnPreparedListener
                // Only now is it honest to say "playing": nothing has been heard yet
                // when the request is merely buffering.
                adapter.setPlaying(call.id)
                try {
                    mp.start()
                } catch (e: IllegalStateException) {
                    Timber.e(e, "Recording player was released before it could start")
                    stopPlayback()
                }
            }
            request.setOnCompletionListener {
                if (player !== request) return@setOnCompletionListener
                stopPlayback()
            }
            request.setOnErrorListener { _, _, _ ->
                if (player !== request) return@setOnErrorListener true
                stopPlayback()
                toast(R.string.calls_recording_failed)
                true
            }
            request.prepareAsync()
        } catch (e: Exception) {
            Timber.e(e, "Recording playback could not be started")
            stopPlayback()
            toast(R.string.calls_recording_failed)
        }
    }

    private fun stopPlayback() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: Exception) {
                // stop() throws when the player never reached the prepared state.
            }
            try {
                mp.reset()
                mp.release()
            } catch (_: Exception) {
                // Already released.
            }
        }
        player = null
        if (_binding != null) adapter.clearPlaying()
    }

    private fun absoluteMediaUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = prefs.apiUrl.trim().trimEnd('/')
        return if (path.startsWith("/")) base + path else "$base/$path"
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    // ────────────────────────────── loading ──────────────────────────────

    private fun loadCallHistory() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            try {
                val apiService = RetrofitClient.getApiService(prefs.apiUrl)
                val response = apiService.getCallHistory("Bearer ${prefs.authToken}")

                if (response.isSuccessful && response.body()?.success == true) {
                    val calls = response.body()?.data ?: emptyList()
                    // A call log records NUMBERS. Resolve them against the contacts
                    // the user has saved, so a saved number reads as its contact
                    // instead of as bare digits.
                    nameIndex = buildNameIndex(apiService)
                    val named = calls.map { withSavedName(it) }
                    adapter.submitList(named)
                    binding.tvEmpty.text = getString(R.string.calls_empty)
                    binding.tvEmpty.visibility = if (named.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvCallHistory.visibility = if (named.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    // A failed request is not an empty log. This screen used to
                    // fall back to four invented rows ("Sadia Islam", 5 min
                    // "answered"), which reads as a real record of real calls.
                    // Show the reason instead, and never a fabricated one.
                    Timber.e("Call history request failed: HTTP %d", response.code())
                    adapter.submitList(emptyList())
                    binding.tvEmpty.text = getString(R.string.calls_load_failed)
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.rvCallHistory.visibility = View.GONE
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load call history")
                adapter.submitList(emptyList())
                binding.tvEmpty.text = getString(R.string.calls_load_failed)
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvCallHistory.visibility = View.GONE
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    // ────────────────────────── saved-contact names ──────────────────────────

    /**
     * Build the canonical-number -> name index from both places a name can live:
     * the handset's own address book, and the tenant's PBX directory.
     *
     * The address book is read off the main thread (a large book is slow), and is
     * skipped entirely when the permission is absent -- a missing permission is not
     * an error here, it only means this source contributes nothing.
     *
     * Precedence: the PHONE's contact wins over the PBX directory. The request that
     * produced this feature was "if the number is saved in my contacts, show that",
     * and the name the user typed for a number is the one they will recognise.
     *
     * Neither source failing may stop the log from rendering: both are caught, so a
     * failure costs names, never rows.
     */
    private suspend fun buildNameIndex(apiService: ApiService): Map<String, String> {
        val index = HashMap<String, String>()

        // 1. Live PBX extension directory first, so the address book below can
        // override it. This keeps call-log names aligned with the same real
        // extension directory and presence source shown on Contacts.
        try {
            val response = apiService.getPbxExtensions("Bearer ${prefs.authToken}")
            if (response.isSuccessful && response.body()?.success == true) {
                for (contact in response.body()?.data.orEmpty()) {
                    val name = contact.displayName?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    // A PBX row is reached by its extension, which is exactly what an
                    // internal call records as the other party.
                    val key = PhoneNumbers.canonical(contact.extension)
                    // First PBX row wins for a given number. putIfAbsent() would say
                    // this in one word, but it is API 24 and this app now runs on
                    // Android 6, where it is a NoSuchMethodError -- a crash in the
                    // call log on exactly the older phones this release exists for.
                    if (key.isNotEmpty() && !index.containsKey(key)) index[key] = name
                }
            } else {
                Timber.w("Contact names unavailable: HTTP %d", response.code())
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read the PBX directory for call-log names")
        }

        // 2. The handset's own address book, which wins on a conflict.
        try {
            if (DeviceContacts.hasPermission(requireContext())) {
                val device = withContext(Dispatchers.IO) { DeviceContacts.load(requireContext()) }
                for (contact in device) {
                    val key = PhoneNumbers.canonical(contact.number)
                    if (key.isNotEmpty()) index[key] = contact.name
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read this phone's contacts for call-log names")
        }

        return index
    }

    /**
     * Give a row the name the user has saved for its number, when the server could
     * not supply a usable one.
     *
     * Only a MISSING name, or one that is just the number repeated, is replaced. A
     * real name from the server is left alone -- it may carry more than the contact
     * entry does, and overwriting it would be the app claiming to know better.
     */
    private fun withSavedName(call: CallRecord): CallRecord {
        val number = call.otherNumber
        if (number.isEmpty()) return call

        val existing = call.otherName
        if (existing.isNotEmpty() && !PhoneNumbers.isNameJustTheNumber(existing, number)) return call

        val saved = nameIndex[PhoneNumbers.canonical(number)] ?: return call
        return if (call.isInbound) call.copy(callerName = saved) else call.copy(calleeName = saved)
    }

    override fun onDestroyView() {
        // A MediaPlayer that outlives its screen keeps playing with nothing on
        // screen to stop it.
        stopPlayback()
        super.onDestroyView()
        _binding = null
    }
}
