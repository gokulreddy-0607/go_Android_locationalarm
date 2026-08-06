package com.example.loc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.loc.data.AppDatabase
import com.example.loc.data.Converters
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Use goAsync to keep the receiver alive for background tasks
        val pendingResult = goAsync()
        
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: run {
            pendingResult.finish()
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Error: ${geofencingEvent.errorCode}")
            pendingResult.finish()
            return
        }

        // Acquire a temporary WakeLock to ensure CPU doesn't sleep while starting the alarm
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loc:GeofenceWakeLock")
        wakeLock.acquire(10000) // 10 seconds max

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: run {
            wakeLock.release()
            pendingResult.finish()
            return
        }

        val db = AppDatabase.getDatabase(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                triggeringGeofences.forEach { geofence ->
                    val requestId = geofence.requestId
                    
                    if (AlarmActivity.isShowing) return@forEach

                    if (requestId.startsWith("G_")) { // Regular Alarm
                        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
                            val id = requestId.substring(2).toIntOrNull() ?: return@forEach
                            val entity = db.geofenceDao().getGeofenceById(id) ?: return@forEach
                            if (entity.isActive) {
                                openAlarmPage(context, id, entity.name, entity.audioUri, entity.latitude, entity.longitude, null)
                            }
                        }
                    } else if (requestId.startsWith("R_")) { // Reminder
                        val id = requestId.substring(2).toIntOrNull() ?: return@forEach
                        val reminder = db.reminderDao().getReminderById(id) ?: return@forEach
                        
                        if (!reminder.isActive) return@forEach

                        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                            openAlarmPage(context, id, reminder.name, null, reminder.latitude, reminder.longitude, reminder.itemsJson)
                        } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                            val items = Converters().fromString(reminder.itemsJson)
                            val incomplete = items.filter { !it.isCompleted && !it.isFailed }
                            if (incomplete.isNotEmpty()) {
                                val summary = incomplete.joinToString(", ") { it.name }
                                openAlarmPage(context, id, "Forgotten: $summary", null, reminder.latitude, reminder.longitude, reminder.itemsJson)
                            }
                        }
                    }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    private fun openAlarmPage(context: Context, id: Int, name: String, audio: String?, lat: Double, lng: Double, items: String?) {
        val channelId = "alarm_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // High priority alarm intent
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("EXTRA_GEOFENCE_ID", id)
            putExtra("EXTRA_LOCATION_NAME", name)
            putExtra("EXTRA_AUDIO_URI", audio)
            putExtra("EXTRA_LATITUDE", lat)
            putExtra("EXTRA_LONGITUDE", lng)
            putExtra("EXTRA_ITEMS_JSON", items)
            putExtra("EXTRA_IS_REMINDER", items != null)
        }

        val pendingIntent = PendingIntent.getActivity(context, id, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (items != null) "Reminder Alert" else "Location Alert")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // Crucial for screen-off popups
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        notificationManager.notify(id, builder.build())

        // Try to start the activity immediately
        try { 
            context.startActivity(alarmIntent) 
        } catch (e: Exception) {
            Log.d("GeofenceReceiver", "Activity start blocked, relying on FullScreenIntent")
        }
    }
}
