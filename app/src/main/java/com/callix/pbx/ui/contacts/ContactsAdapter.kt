package com.callix.pbx.ui.contacts

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.callix.pbx.R
import com.callix.pbx.data.model.ContactRow
import com.callix.pbx.databinding.ItemContactBinding

/**
 * One contact row, whether it came from the PBX directory or from the phone's
 * own address book.
 *
 * The two kinds differ in what they can say about a person, so they differ in
 * what the row shows: a PBX row knows a registration state, a phone row only
 * knows a number. Rather than print a meaningless "Unknown" state for a phone
 * contact, the status line states where the row came from.
 */
class ContactsAdapter(
    private val onCallClick: (ContactRow) -> Unit
) : ListAdapter<ContactRow, ContactsAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ContactRow) {
            val ctx = binding.root.context

            binding.tvName.text = row.name

            if (row.isDevice) {
                // The number IS the point of this row: it is what the user will dial.
                binding.tvExtension.text = row.number
                binding.tvDepartment.visibility = View.GONE
                binding.ivStatusDot.visibility = View.GONE
                binding.tvStatus.text = ctx.getString(R.string.contacts_source_phone)
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_secondary)
                )
            } else {
                binding.tvExtension.text = "Ext: ${row.extension}"
                binding.tvDepartment.text = row.department
                binding.tvDepartment.visibility = if (row.department.isBlank()) View.GONE else View.VISIBLE
                binding.ivStatusDot.visibility = View.VISIBLE

                val (statusText, statusColor) = when (row.status.lowercase()) {
                    "online", "registered", "available", "active", "reachable", "ringing" -> Pair("Online", "#4CAF50")
                    "busy", "in_call", "on_call", "on a call" -> Pair("Busy", "#FF5722")
                    "offline", "unregistered", "unavailable", "inactive", "unreachable" -> Pair("Offline", "#9E9E9E")
                    else -> Pair("Unknown", "#9E9E9E")
                }
                binding.tvStatus.text = statusText
                binding.tvStatus.setTextColor(Color.parseColor(statusColor))
                binding.ivStatusDot.setColorFilter(Color.parseColor(statusColor))
            }

            // A row with no name at all still has to be tappable and readable.
            if (row.name.isBlank()) {
                binding.tvName.text = row.number.ifBlank {
                    ctx.getString(R.string.calls_unknown_number)
                }
            }

            val initials = row.name.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.firstOrNull()?.toString() ?: "" }
                .uppercase()
            binding.tvInitials.text = initials.ifEmpty { "?" }

            val colors = listOf(
                "#6C63FF", "#FF6584", "#43A047", "#FB8C00",
                "#00ACC1", "#8E24AA", "#E53935", "#3949AB"
            )
            val colorIndex = Math.abs(row.name.hashCode()) % colors.size
            binding.layoutAvatar.setBackgroundColor(Color.parseColor(colors[colorIndex]))

            binding.btnCall.setOnClickListener { onCallClick(row) }
            binding.root.setOnClickListener { onCallClick(row) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ContactRow>() {
        override fun areItemsTheSame(oldItem: ContactRow, newItem: ContactRow) =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: ContactRow, newItem: ContactRow) =
            oldItem == newItem
    }
}
