package com.callix.pbx.ui.calls

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.callix.pbx.R
import com.callix.pbx.data.model.CallRecord
import com.callix.pbx.databinding.ItemCallHistoryBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One call-log row.
 *
 * Tapping a row hands its number to the dialer; long-pressing copies it. Both are
 * driven from the fragment so that this class stays free of navigation and
 * clipboard concerns.
 */
class CallHistoryAdapter(
    private val onRowClick: (CallRecord) -> Unit,
    private val onRowLongClick: (CallRecord) -> Unit,
    private val onPlayClick: (CallRecord) -> Unit
) : ListAdapter<CallRecord, CallHistoryAdapter.ViewHolder>(DiffCallback()) {

    /** The id of the row whose recording is currently playing, or null. */
    var playingId: String? = null
        private set

    /**
     * Move the play/stop marker to [id] (null stops everything).
     *
     * Only the rows whose icon actually changed are re-bound, so the list does not
     * flicker while playback starts or ends.
     */
    fun setPlaying(id: String?) {
        val previous = playingId
        if (previous == id) return
        playingId = id
        for (key in listOfNotNull(previous, id)) {
            val index = currentList.indexOfFirst { it.id == key }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    /** Called when playback ends on its own, so the icon returns to "play". */
    fun clearPlaying() = setPlaying(null)

    inner class ViewHolder(private val binding: ItemCallHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CallRecord) {
            val ctx = binding.root.context

            // The number is the identity of record; the name is optional. Every
            // field is read null-safely: Gson leaves an absent JSON key at the JVM
            // default, and this row previously went blank because of exactly that
            // (the model declared snake_case keys while the route emits camelCase).
            val number = item.otherNumber
            val rawName = item.otherName
            val hasNumber = number.isNotEmpty()
            val hasName = rawName.isNotEmpty()

            val status = item.status?.trim()?.lowercase().orEmpty()
            val isInbound = item.isInbound
            val isMissed = status == "missed"
            val isBusy = status == "busy"

            binding.tvContactName.text = when {
                hasName -> rawName
                hasNumber -> number
                else -> ctx.getString(R.string.calls_unknown_number)
            }

            // The number is printed exactly once. When there is no name it IS the
            // title above, so repeating it on the second line would show the same
            // digits twice in one row.
            val showNumberLine = hasName && hasNumber
            binding.tvNumber.text = number
            binding.tvNumber.visibility = if (showNumberLine) View.VISIBLE else View.GONE
            binding.tvNumberSeparator.visibility = if (showNumberLine) View.VISIBLE else View.GONE

            // Direction icon and colour
            val (iconRes, iconTint) = when {
                isMissed -> Pair(R.drawable.ic_call_missed, "#F44336")
                isBusy -> Pair(R.drawable.ic_call_busy, "#FF9800")
                isInbound -> Pair(R.drawable.ic_call_incoming, "#4CAF50")
                else -> Pair(R.drawable.ic_call_outgoing, "#2196F3")
            }
            binding.ivCallDirection.setImageResource(iconRes)
            binding.ivCallDirection.setColorFilter(Color.parseColor(iconTint))

            val statusText = when (status) {
                "answered" -> if (isInbound) "Incoming" else "Outgoing"
                "missed" -> "Missed Call"
                "busy" -> "Busy"
                "" -> ""
                else -> status.replaceFirstChar { it.uppercase() }
            }
            binding.tvCallStatus.text = statusText
            binding.tvCallStatus.setTextColor(Color.parseColor(iconTint))

            if (item.duration > 0) {
                val minutes = item.duration / 60
                val seconds = item.duration % 60
                binding.tvDuration.text = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
            } else {
                binding.tvDuration.text = ""
            }

            val startTime = item.startTime?.trim().orEmpty()
            if (startTime.isEmpty()) {
                binding.tvCallTime.text = ""
            } else {
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    val outputFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
                    val date = inputFormat.parse(startTime)
                    binding.tvCallTime.text = date?.let { outputFormat.format(it) } ?: startTime
                } catch (e: Exception) {
                    binding.tvCallTime.text = startTime
                }
            }

            // Playback control. canPlay is the SERVER's answer (hasRecording on a
            // row the caller owns), so the button is only offered when a recording
            // the user is allowed to hear really exists.
            if (item.canPlay) {
                val playing = item.id != null && item.id == playingId
                binding.ivPlayRecording.visibility = View.VISIBLE
                binding.ivPlayRecording.setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.ivPlayRecording.contentDescription = ctx.getString(
                    if (playing) R.string.calls_stop_recording else R.string.calls_play_recording
                )
                binding.ivPlayRecording.setOnClickListener { onPlayClick(item) }
            } else {
                binding.ivPlayRecording.visibility = View.GONE
                binding.ivPlayRecording.setOnClickListener(null)
            }

            // Tap = hand the number to the dialer. Long press = copy it.
            binding.root.setOnClickListener { onRowClick(item) }
            binding.root.setOnLongClickListener {
                onRowLongClick(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCallHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<CallRecord>() {
        override fun areItemsTheSame(oldItem: CallRecord, newItem: CallRecord) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CallRecord, newItem: CallRecord) =
            oldItem == newItem
    }
}
