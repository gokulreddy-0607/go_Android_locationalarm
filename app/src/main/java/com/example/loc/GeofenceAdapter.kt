package com.example.loc

import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.GeofenceEntity
import com.example.loc.databinding.ItemGeofenceBinding

class GeofenceAdapter(
    private val onDeleteClick: (GeofenceEntity) -> Unit,
    private val onEditClick: (GeofenceEntity) -> Unit,
    private val onOpenMapClick: (GeofenceEntity) -> Unit,
    private val onToggleClick: (GeofenceEntity) -> Unit,
    private val onAudioSelectClick: (GeofenceEntity) -> Unit
) : ListAdapter<GeofenceEntity, GeofenceAdapter.GeofenceViewHolder>(DiffCallback()) {

    private var currentUserLocation: Location? = null

    fun setCurrentLocation(location: Location?) {
        currentUserLocation = location
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GeofenceViewHolder {
        val binding = ItemGeofenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GeofenceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GeofenceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GeofenceViewHolder(private val binding: ItemGeofenceBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(geofence: GeofenceEntity) {
            binding.tvName.text = geofence.name
            
            val distanceStr = if (geofence.isActive) {
                currentUserLocation?.let {
                    // REQUIREMENT: Only calculate/show distance when accuracy is "accurate" (blue radius is small)
                    val accuracy = if (it.hasAccuracy()) it.accuracy else 200f
                    if (accuracy <= 40f) {
                        val results = FloatArray(1)
                        Location.distanceBetween(it.latitude, it.longitude, geofence.latitude, geofence.longitude, results)
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
            
            val radiusStr = if (geofence.radius >= 1000) {
                "${"%.1f".format(geofence.radius / 1000)}km"
            } else {
                "${geofence.radius.toInt()}m"
            }
            
            binding.tvRadius.text = "Radius: $radiusStr $distanceStr"
            
            val audioUri = geofence.audioUri
            if (audioUri != null) {
                val ringtone = RingtoneManager.getRingtone(binding.root.context, Uri.parse(audioUri))
                val name = ringtone?.getTitle(binding.root.context) ?: "Custom"
                binding.tvAudioName.text = "Audio: $name"
            } else {
                binding.tvAudioName.text = "Audio: Default"
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(geofence) }
            binding.btnEdit.setOnClickListener { onEditClick(geofence) }
            binding.btnOpenMap.setOnClickListener { onOpenMapClick(geofence) }
            binding.btnSelectAudio.setOnClickListener { onAudioSelectClick(geofence) }
            
            binding.switchActive.setOnCheckedChangeListener(null)
            binding.switchActive.isChecked = geofence.isActive
            binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
                onToggleClick(geofence.copy(isActive = isChecked))
            }

            val alpha = if (geofence.isActive) 1.0f else 0.5f
            binding.tvName.alpha = alpha
            binding.tvRadius.alpha = alpha
            binding.tvAudioName.alpha = alpha
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GeofenceEntity>() {
        override fun areItemsTheSame(oldItem: GeofenceEntity, newItem: GeofenceEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: GeofenceEntity, newItem: GeofenceEntity) = oldItem == newItem
    }
}
