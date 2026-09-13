package com.example.loc

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.loc.data.AppDatabase
import com.example.loc.data.Converters
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: run {
            Log.e("GeofenceReceiver", "Received intent is not a geofencing event.")
            pendingResult.finish()
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofence event error: ${geofencingEvent.errorCode}")
            pendingResult.finish()
            return
        }

        val transition = geofencingEvent.geofenceTransition
        Log.d("GeofenceReceiver", "GEOFENCE EVENT RECEIVED: Transition $transition")

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: run {
            Log.d("GeofenceReceiver", "No triggering geofences in event.")
            pendingResult.finish()
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoSmaran:GeofenceWakeLock")
        wakeLock.acquire(15000)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                
                triggeringGeofences.forEach { geofence ->
                    val requestId = geofence.requestId
                    Log.d("GeofenceReceiver", "Triggering ID: $requestId")

                    if (requestId.startsWith("G_")) { // Regular Alarm
                        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                            val id = requestId.substring(2).toIntOrNull() ?: return@forEach
                            val entity = db.geofenceDao().getGeofenceById(id)
                            if (entity != null && entity.isActive) {
                                Log.d("GeofenceReceiver", "Activating Alarm: ${entity.name}")
                                NotificationHelper.triggerAlarm(context, id, entity.name, entity.audioUri, entity.latitude, entity.longitude, null)
                            } else {
                                Log.d("GeofenceReceiver", "Alarm $id not found or inactive.")
                            }
                        }
                    } else if (requestId.startsWith("R_")) { // Reminder
                        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                            val id = requestId.substring(2).toIntOrNull() ?: return@forEach
                            val reminder = db.reminderDao().getReminderById(id)
                            
                            if (reminder != null && reminder.isActive) {
                                val items = Converters().fromString(reminder.itemsJson)
                                val hasUnverified = items.isEmpty() || items.any { !(it.isCompleted || it.isFailed) }

                                if (hasUnverified) {
                                    Log.d("GeofenceReceiver", "Activating Reminder: ${reminder.name}")
                                    NotificationHelper.triggerAlarm(context, id, reminder.name, reminder.audioUri, reminder.latitude, reminder.longitude, reminder.itemsJson)
                                } else {
                                    Log.d("GeofenceReceiver", "Reminder $id has no unverified items.")
                                }
                            } else {
                                Log.d("GeofenceReceiver", "Reminder $id not found or inactive.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GeofenceReceiver", "Error processing geofence event", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }
}
