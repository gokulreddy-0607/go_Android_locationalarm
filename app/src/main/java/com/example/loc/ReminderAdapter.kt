package com.example.loc

import android.media.RingtoneManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.Converters
import com.example.loc.data.ReminderEntity
import com.example.loc.data.ReminderItem
import com.example.loc.databinding.ItemReminderBinding

class ReminderAdapter(
    private val onDeleteClick: (ReminderEntity) -> Unit,
    private val onUpdateClick: (ReminderEntity) -> Unit,
    private val onSelectAudioClick: (ReminderEntity) -> Unit,
    private val onUpdateLocationClick: (ReminderEntity) -> Unit
) : ListAdapter<ReminderEntity, ReminderAdapter.ReminderViewHolder>(ReminderDiffCallback()) {

    private var currentUserLocation: android.location.Location? = null

    fun setCurrentLocation(location: android.location.Location?) {
        currentUserLocation = location
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderViewHolder {
        val binding = ItemReminderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReminderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReminderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ReminderViewHolder(private val binding: ItemReminderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(reminder: ReminderEntity) {
            binding.tvName.text = reminder.name
            
            val distanceStr = if (reminder.isActive) {
                currentUserLocation?.let {
                    // REQUIREMENT: Only calculate/show distance when accuracy is "accurate" (blue radius is small)
                    val accuracy = if (it.hasAccuracy()) it.accuracy else 200f
                    if (accuracy <= 40f) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(it.latitude, it.longitude, reminder.latitude, reminder.longitude, results)
                        val distanceInMeters = results[0]
                        val formatted = if (distanceInMeters >= 1000) {
                            "${"%.2f".format(distanceInMeters / 1000)} km"
                        } else {
                            "${"%.0f".format(distanceInMeters)} m"
                        }
                        "| Distance: $formatted"
                    } else {
                        "| Distance: Acquiring GPS..."
                    }
                } ?: "| Distance: Calculating..."
            } else {
                ""
            }

            binding.tvRadius.text = "Radius: ${reminder.radius.toInt()}m $distanceStr"

            val audioUri = reminder.audioUri
            if (audioUri != null) {
                val ringtone = RingtoneManager.getRingtone(binding.root.context, Uri.parse(audioUri))
                val name = ringtone?.getTitle(binding.root.context) ?: "Custom"
                binding.tvAudioName.text = "Audio: $name"
            } else {
                binding.tvAudioName.text = "Audio: Default"
            }

            // Setup Toggle Switch
            binding.switchActive.setOnCheckedChangeListener(null) 
            binding.switchActive.isChecked = reminder.isActive
            binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
                if (reminder.isActive != isChecked) {
                    onUpdateClick(reminder.copy(isActive = isChecked))
                }
            }

            val converters = Converters()
            val items = converters.fromString(reminder.itemsJson).toMutableList()
            
            val subAdapter = ReminderSubItemAdapter(
                items,
                onItemChanged = { pos, updatedItem ->
                    items[pos] = updatedItem
                    val updatedReminder = reminder.copy(itemsJson = converters.fromList(items))
                    onUpdateClick(updatedReminder)
                },
                onItemDeleted = { pos ->
                    items.removeAt(pos)
                    val updatedReminder = reminder.copy(itemsJson = converters.fromList(items))
                    onUpdateClick(updatedReminder)
                },
                onItemEdit = { pos, item ->
                    showEditItemDialog(reminder, items, pos, item)
                }
            )
            binding.rvItems.adapter = subAdapter

            binding.btnAddItem.setOnClickListener {
                showAddItemDialog(reminder, items)
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(reminder) }
            binding.btnSelectAudio.setOnClickListener { onSelectAudioClick(reminder) }
            binding.btnEditReminder.setOnClickListener { onUpdateLocationClick(reminder) }
        }

        private fun showEditItemDialog(reminder: ReminderEntity, items: MutableList<ReminderItem>, pos: Int, item: ReminderItem) {
            val context = itemView.context
            val editText = EditText(context)
            editText.setText(item.name)
            editText.setSelection(item.name.length)
            editText.setTextColor(android.graphics.Color.BLACK)
            
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Loc_PurpleDialog)
                .setTitle("Edit Item")
                .setView(editText)
                .setPositiveButton("Save") { _, _ ->
                    val newName = editText.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        items[pos] = item.copy(name = newName)
                        val updatedReminder = reminder.copy(itemsJson = Converters().fromList(items))
                        onUpdateClick(updatedReminder)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showAddItemDialog(reminder: ReminderEntity, items: MutableList<ReminderItem>) {
            val context = itemView.context
            val editText = EditText(context)
            editText.hint = "Item Name"
            editText.setTextColor(android.graphics.Color.BLACK)
            
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Loc_PurpleDialog)
                .setTitle("Add Item")
                .setView(editText)
                .setPositiveButton("Add") { _, _ ->
                    val itemName = editText.text.toString().trim()
                    if (itemName.isNotEmpty()) {
                        items.add(ReminderItem(name = itemName))
                        val updatedReminder = reminder.copy(itemsJson = Converters().fromList(items))
                        onUpdateClick(updatedReminder)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    class ReminderDiffCallback : DiffUtil.ItemCallback<ReminderEntity>() {
        override fun areItemsTheSame(oldItem: ReminderEntity, newItem: ReminderEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ReminderEntity, newItem: ReminderEntity): Boolean = oldItem == newItem
    }
}
