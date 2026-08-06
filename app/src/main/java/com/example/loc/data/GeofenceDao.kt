package com.example.loc.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GeofenceDao {
    @Query("SELECT * FROM geofences ORDER BY id DESC")
    fun getAllGeofences(): LiveData<List<GeofenceEntity>>

    @Query("SELECT * FROM geofences ORDER BY id DESC")
    suspend fun getAllGeofencesList(): List<GeofenceEntity>

    @Query("SELECT * FROM geofences WHERE id = :id LIMIT 1")
    suspend fun getGeofenceById(id: Int): GeofenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(geofence: GeofenceEntity): Long

    @Update
    suspend fun update(geofence: GeofenceEntity)

    @Delete
    suspend fun delete(geofence: GeofenceEntity)

    @Query("DELETE FROM geofences WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM geofences")
    suspend fun deleteAll()
}
