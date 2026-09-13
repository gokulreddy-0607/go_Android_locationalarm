package com.example.loc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.databinding.ItemListFooterBinding

class FooterAdapter(
    private val onCreateClick: () -> Unit,
    private val showCurrentLocation: Boolean = false,
    private val onCurrentLocClick: (() -> Unit)? = null
) : RecyclerView.Adapter<FooterAdapter.FooterViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val binding = ItemListFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FooterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) {
        holder.bind()
    }

    override fun getItemCount() = 1

    inner class FooterViewHolder(private val binding: ItemListFooterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.btnCreate.setOnClickListener { onCreateClick() }
            
            if (showCurrentLocation) {
                binding.btnCurrentLocation.visibility = View.VISIBLE
                binding.btnCurrentLocation.setOnClickListener { onCurrentLocClick?.invoke() }
            } else {
                binding.btnCurrentLocation.visibility = View.GONE
            }
        }
    }
}
