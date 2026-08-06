package com.example.loc

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.Converters
import com.example.loc.data.ReminderEntity
import com.example.loc.data.ReminderItem
import com.example.loc.databinding.ItemReminderBinding

class ReminderAdapter(
    private val onDeleteClick: (ReminderEntity) -> Unit,
    private val onUpdateClick: (ReminderEntity) -> Unit
) : ListAdapter<ReminderEntity, ReminderAdapter.ReminderViewHolder>(ReminderDiffCallback()) {

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
            binding.tvRadius.text = "Radius: ${reminder.radius.toInt()}m"

            // Setup Toggle Switch
            binding.switchActive.setOnCheckedChangeListener(null) // Prevent recursive calls
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
                }
            )
            binding.rvItems.adapter = subAdapter

            binding.btnAddItem.setOnClickListener {
                showAddItemDialog(reminder, items)
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(reminder) }
        }

        private fun showAddItemDialog(reminder: ReminderEntity, items: MutableList<ReminderItem>) {
            val context = itemView.context
            val editText = EditText(context)
            editText.hint = "Item Name"
            
            AlertDialog.Builder(context)
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
