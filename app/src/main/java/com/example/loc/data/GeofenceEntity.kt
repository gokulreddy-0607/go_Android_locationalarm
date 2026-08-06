package com.example.loc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val isActive: Boolean = true,
    val audioUri: String? = null
)
