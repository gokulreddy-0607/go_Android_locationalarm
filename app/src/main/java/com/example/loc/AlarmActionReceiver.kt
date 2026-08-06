package com.example.loc

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.loc.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "STOP_ALARM") {
            val geofenceId = intent.getIntExtra("EXTRA_GEOFENCE_ID", -1)
            val isReminder = intent.getBooleanExtra("EXTRA_IS_REMINDER", false)
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(geofenceId)

            if (geofenceId != -1) {
                val db = AppDatabase.getDatabase(context.applicationContext)
                CoroutineScope(Dispatchers.IO).launch {
                    if (isReminder) {
                        val dao = db.reminderDao()
                        val reminder = dao.getReminderById(geofenceId)
                        if (reminder != null) {
                            dao.update(reminder.copy(isActive = false))
                        }
                    } else {
                        val dao = db.geofenceDao()
                        val geofence = dao.getGeofenceById(geofenceId)
                        if (geofence != null) {
                            dao.update(geofence.copy(isActive = false))
                        }
                    }
                }
            }
        }
    }
}
