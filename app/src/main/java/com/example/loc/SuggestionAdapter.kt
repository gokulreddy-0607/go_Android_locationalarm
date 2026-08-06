package com.example.loc

import android.location.Address
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.databinding.ItemSuggestionBinding

class SuggestionAdapter(
    private val onSuggestionClick: (Address) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    private var suggestions: List<Address> = emptyList()

    fun setSuggestions(newSuggestions: List<Address>) {
        suggestions = newSuggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val address = suggestions[position]
        val addressText = address.getAddressLine(0)
        holder.binding.tvSuggestion.text = addressText
        holder.itemView.setOnClickListener { onSuggestionClick(address) }
    }

    override fun getItemCount(): Int = suggestions.size

    class ViewHolder(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)
}
