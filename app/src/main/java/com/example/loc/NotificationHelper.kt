package com.example.loc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object NotificationHelper {

    private const val ALARM_CHANNEL_ID = "alarm_channel"
    private const val ALARM_CHANNEL_NAME = "Location Alarms"

    fun triggerAlarm(
        context: Context,
        id: Int,
        name: String,
        audioUri: String?,
        lat: Double,
        lng: Double,
        itemsJson: String?
    ) {
        val isReminder = itemsJson != null
        Log.d("NotificationHelper", "Triggering alarm for ${if (isReminder) "Reminder" else "Alarm"}: $name (ID: $id)")

        val uniqueId = if (isReminder) id + 100000 else id

        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("EXTRA_GEOFENCE_ID", id)
            putExtra("EXTRA_LOCATION_NAME", name)
            putExtra("EXTRA_AUDIO_URI", audioUri)
            putExtra("EXTRA_LATITUDE", lat)
            putExtra("EXTRA_LONGITUDE", lng)
            putExtra("EXTRA_ITEMS_JSON", itemsJson)
            putExtra("EXTRA_IS_REMINDER", isReminder)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            uniqueId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALARM_CHANNEL_ID,
                ALARM_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for location arrival"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val alarmUri = if (!audioUri.isNullOrEmpty()) {
            android.net.Uri.parse(audioUri)
        } else {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
        }

        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (isReminder) "Location Reminder" else "Location Alarm")
            .setContentText("You arrived at $name")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSound(alarmUri)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // REQUIREMENT: Check for full-screen intent access on modern Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (notificationManager.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(pendingIntent, true)
            } else {
                Log.w("NotificationHelper", "Cannot use fullScreenIntent, permission not granted.")
                builder.setContentIntent(pendingIntent)
            }
        } else {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        notificationManager.notify(uniqueId, builder.build())
        Log.d("NotificationHelper", "Notification posted for ID: $uniqueId")

        // Also try direct start for immediate display if possible
        try {
            val options = android.app.ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            context.startActivity(intent, options.toBundle())
            Log.d("NotificationHelper", "Activity started directly.")
        } catch (e: Exception) {
            Log.d("NotificationHelper", "Direct start blocked, relying on notification.")
        }
    }
}
