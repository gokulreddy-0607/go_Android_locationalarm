package com.example.loc

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.ReminderItem
import com.example.loc.databinding.ItemReminderSubItemBinding

class ReminderSubItemAdapter(
    private var items: List<ReminderItem>,
    private val onItemChanged: (Int, ReminderItem) -> Unit,
    private val onItemDeleted: (Int) -> Unit
) : RecyclerView.Adapter<ReminderSubItemAdapter.ViewHolder>() {

    fun updateItems(newItems: List<ReminderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReminderSubItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemReminderSubItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ReminderItem, position: Int) {
            binding.tvItemName.text = item.name
            
            // Avoid recursive calls by removing listeners temporarily
            binding.cbDone.setOnCheckedChangeListener(null)
            binding.cbFailed.setOnCheckedChangeListener(null)

            binding.cbDone.isChecked = item.isCompleted
            binding.cbFailed.isChecked = item.isFailed

            binding.cbDone.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    item.isCompleted = true
                    item.isFailed = false
                    binding.cbFailed.isChecked = false
                } else {
                    item.isCompleted = false
                }
                onItemChanged(position, item)
            }

            binding.cbFailed.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    item.isFailed = true
                    item.isCompleted = false
                    binding.cbDone.isChecked = false
                } else {
                    item.isFailed = false
                }
                onItemChanged(position, item)
            }

            binding.btnDeleteItem.setOnClickListener {
                onItemDeleted(position)
            }
        }
    }
}
