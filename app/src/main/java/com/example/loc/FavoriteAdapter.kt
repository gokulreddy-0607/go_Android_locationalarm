package com.example.loc

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.FavoriteEntity
import com.example.loc.databinding.ItemFavoriteBinding

class FavoriteAdapter(
    private val onDeleteClick: (FavoriteEntity) -> Unit,
    private val onMapClick: (FavoriteEntity) -> Unit,
    private val onBookClick: (FavoriteEntity) -> Unit,
    private val onSetAlarmClick: (FavoriteEntity) -> Unit
) : ListAdapter<FavoriteEntity, FavoriteAdapter.FavoriteViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(private val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(favorite: FavoriteEntity) {
            binding.tvName.text = favorite.name
            binding.tvCoordinates.text = String.format("%.4f, %.4f", favorite.latitude, favorite.longitude)
            
            binding.btnDelete.setOnClickListener { onDeleteClick(favorite) }
            binding.btnMap.setOnClickListener { onMapClick(favorite) }
            binding.btnBook.setOnClickListener { onBookClick(favorite) }
            binding.btnSetAlarm.setOnClickListener { onSetAlarmClick(favorite) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<FavoriteEntity>() {
        override fun areItemsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
            return oldItem == newItem
        }
    }
}
