package com.example.loc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.loc.data.AppDatabase
import com.example.loc.data.FavoriteEntity
import com.example.loc.data.GeofenceEntity
import com.example.loc.data.ReminderEntity
import kotlinx.coroutines.launch

class GeofenceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val geofenceDao = db.geofenceDao()
    private val favoriteDao = db.favoriteDao()
    private val reminderDao = AppDatabase.getDatabase(application).reminderDao()

    val allGeofences: LiveData<List<GeofenceEntity>> = geofenceDao.getAllGeofences()
    val allFavorites: LiveData<List<FavoriteEntity>> = favoriteDao.getAllFavorites()
    val allReminders: LiveData<List<ReminderEntity>> = reminderDao.getAllReminders()

    fun insert(geofence: GeofenceEntity) = viewModelScope.launch {
        geofenceDao.insert(geofence)
    }

    fun update(geofence: GeofenceEntity) = viewModelScope.launch {
        geofenceDao.update(geofence)
    }

    fun delete(geofence: GeofenceEntity) = viewModelScope.launch {
        geofenceDao.delete(geofence)
    }

    fun insertFavorite(favorite: FavoriteEntity) = viewModelScope.launch {
        favoriteDao.insert(favorite)
    }

    fun deleteFavorite(favorite: FavoriteEntity) = viewModelScope.launch {
        favoriteDao.delete(favorite)
    }

    fun insertReminder(reminder: ReminderEntity) = viewModelScope.launch {
        reminderDao.insert(reminder)
    }

    fun updateReminder(reminder: ReminderEntity) = viewModelScope.launch {
        reminderDao.update(reminder)
    }

    fun deleteReminder(reminder: ReminderEntity) = viewModelScope.launch {
        reminderDao.delete(reminder)
    }

    fun clearAllLocalData() = viewModelScope.launch {
        geofenceDao.deleteAll()
        favoriteDao.deleteAll()
        reminderDao.deleteAll()
    }
}
