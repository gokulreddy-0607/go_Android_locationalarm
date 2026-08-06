package com.example.loc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.loc.data.AppDatabase
import com.example.loc.data.GeofenceEntity
import com.example.loc.data.ReminderEntity
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private val channelId = "location_service_channel"
    private val alarmChannelId = "alarm_channel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var activeAlarms = listOf<GeofenceEntity>()
    private var activeReminders = listOf<ReminderEntity>()
    
    private var wakeLock: PowerManager.WakeLock? = null

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        createNotificationChannels()
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loc:ServiceWakeLock")
        wakeLock?.acquire()

        observeData()
    }

    private fun observeData() {
        val db = AppDatabase.getDatabase(applicationContext)
        
        db.geofenceDao().getAllGeofences().observeForever { list ->
            activeAlarms = list.filter { it.isActive }
            syncGeofences()
        }
        
        db.reminderDao().getAllReminders().observeForever { list ->
            activeReminders = list.filter { it.isActive }
            syncGeofences()
        }
    }

    private fun syncGeofences() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val geofenceList = mutableListOf<Geofence>()

        activeAlarms.forEach {
            geofenceList.add(Geofence.Builder()
                .setRequestId("G_${it.id}")
                .setCircularRegion(it.latitude, it.longitude, it.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(10000)
                .build())
        }

        activeReminders.forEach {
            geofenceList.add(Geofence.Builder()
                .setRequestId("R_${it.id}")
                .setCircularRegion(it.latitude, it.longitude, it.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build())
        }

        if (geofenceList.isEmpty()) {
            geofencingClient.removeGeofences(geofencePendingIntent)
            return
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofenceList)
            .build()

        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnCompleteListener {
                if (ActivityCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    geofencingClient.addGeofences(request, geofencePendingIntent)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    private fun createServiceNotification(): Notification {
        val stopIntent = Intent(this, LocationService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GeoSmaran Tracking Active")
            .setContentText("Monitoring for your destinations...")
            .setSmallIcon(R.drawable.ic_map)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop Tracking", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(channelId, "Tracking", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(alarmChannelId, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP_SERVICE = "STOP_LOCATION_SERVICE"
    }
}
