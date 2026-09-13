package com.example.loc

import android.Manifest
import android.app.AlarmManager
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.core.content.ContextCompat
import com.example.loc.data.AppDatabase
import com.example.loc.data.Converters
import com.example.loc.data.GeofenceEntity
import com.example.loc.data.ReminderEntity
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.Calendar

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var alarmManager: AlarmManager
    private val channelId = "location_tracking_silent_channel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var activeAlarms = listOf<GeofenceEntity>()
    private var activeReminders = listOf<ReminderEntity>()
    
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var geofenceObserver: Observer<List<GeofenceEntity>>? = null
    private var reminderObserver: Observer<List<ReminderEntity>>? = null
    private var geofenceLiveData: LiveData<List<GeofenceEntity>>? = null
    private var reminderLiveData: LiveData<List<ReminderEntity>>? = null
    private var locationCallback: LocationCallback? = null
    private var currentHeartbeatDelay: Long = 60000L // Default 1 min
    private var isFirstLoad = true

    companion object {
        const val ACTION_SYNC_GEOFENCES = "com.example.loc.ACTION_SYNC_GEOFENCES"
    }
    
    // Heartbeat for Screen-Off reliability
    private val heartbeatIntent: PendingIntent by lazy {
        // EXPERT FIX: Use a Broadcast instead of a Service intent for more reliable wakeup in Doze mode
        val intent = Intent("com.example.loc.ACTION_HEARTBEAT")
        intent.setPackage(packageName)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    
    private val heartbeatReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Heartbeat received: Force a high-accuracy location check
            if (ActivityCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Use a temporary WakeLock to ensure we finish the calculation
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loc:HeartbeatWakeLock")
                wl.acquire(10000)

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            checkDistancesManually(location)
                        }
                        if (wl.isHeld) wl.release()
                    }
                    .addOnFailureListener {
                        if (wl.isHeld) wl.release()
                    }
            }
            scheduleHeartbeat() // Re-schedule next heartbeat
        }
    }

    // THE NEW CLEAN STATE MACHINE
    // Tracks reminders that the user is currently "INSIDE" of.
    private val insideReminderIds = mutableSetOf<Int>()
    
    // Tracks reminders where the alarm has already been triggered for the CURRENT entry event.
    private val entryAlarmTriggeredIds = mutableSetOf<Int>()

    // Tracks reminders that have had their initial state determined after being turned ON.
    // This prevents "Immediate alarms" when enabling a card while already near the boundary.
    private val primedReminderIds = mutableSetOf<Int>()
    
    // Tracks geofences (regular alarms) that have been handled.
    private val handledGeofenceIds = mutableSetOf<String>()

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(this, 0, intent, flags)
    }
    
    private val dummyAlarmPendingIntent: PendingIntent by lazy {
        val intent = Intent(this, MainActivity::class.java)
        PendingIntent.getActivity(this, 9999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        createNotificationChannels()
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loc:ServiceWakeLock")
        wakeLock?.acquire()

        // Register heartbeat receiver using ContextCompat to satisfy modern Android requirements
        val filter = android.content.IntentFilter("com.example.loc.ACTION_HEARTBEAT")
        ContextCompat.registerReceiver(this, heartbeatReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        observeData()
    }

    private fun observeData() {
        val db = AppDatabase.getDatabase(applicationContext)

        geofenceLiveData = db.geofenceDao().getAllGeofences()
        geofenceObserver = Observer { list ->
            Log.d("LocationService", "Alarms loaded from DB: ${list.size} total")
            activeAlarms = list.filter { it.isActive }
            Log.d("LocationService", "Active Alarms: ${activeAlarms.size} (${activeAlarms.map { it.id }})")
            
            // Cleanup handled IDs for deactivated geofences
            val activeIds = activeAlarms.map { "G_${it.id}" }.toSet()
            handledGeofenceIds.retainAll(activeIds)
            
            syncGeofences()
        }
        geofenceLiveData?.observeForever(geofenceObserver!!)
        
        reminderLiveData = db.reminderDao().getAllReminders()
        reminderObserver = Observer { list ->
            Log.d("LocationService", "Reminders loaded from DB: ${list.size} total")
            val activeList = list.filter { it.isActive }
            Log.d("LocationService", "Active Reminders: ${activeList.size} (${activeList.map { it.id }})")
            
            val converters = Converters()
            
            // REQUIREMENT: AUTOMATICALLY TURN OFF CARD IF ALL ITEMS ARE CHECKED
            activeList.forEach { reminder ->
                val items = converters.fromString(reminder.itemsJson)
                val allVerified = items.isNotEmpty() && items.all { it.isCompleted || it.isFailed }
                
                if (allVerified) {
                    Log.d("LocationService", "Auto-deactivating reminder ${reminder.id} (all items completed)")
                    serviceScope.launch(Dispatchers.IO) {
                        db.reminderDao().update(reminder.copy(isActive = false))
                    }
                }
            }

            activeReminders = activeList
            
            // Cleanup session states for reminders that were turned OFF manually
            val activeIds = activeReminders.map { it.id }.toSet()
            insideReminderIds.retainAll(activeIds)
            entryAlarmTriggeredIds.retainAll(activeIds)
            primedReminderIds.retainAll(activeIds)
            
            syncGeofences()
        }
        reminderLiveData?.observeForever(reminderObserver!!)

        // Give Room a moment to load before allowing the service to stop itself
        serviceScope.launch {
            delay(3000)
            isFirstLoad = false
            syncGeofences() // Re-check if we should stop
        }
    }

    private fun syncGeofences() {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        Log.d("LocationService", "Syncing geofences. Fine Loc: $hasFine, Background Loc: $hasBackground")

        if (!hasFine) {
            Log.e("LocationService", "Missing Precise Location permission. Cannot register geofences.")
            return
        }

        val geofenceList = mutableListOf<Geofence>()

        // Regular Alarms
        activeAlarms.forEach {
            Log.d("LocationService", "Adding Geofence G_${it.id} (Radius: ${it.radius}m)")
            geofenceList.add(Geofence.Builder()
                .setRequestId("G_${it.id}")
                .setCircularRegion(it.latitude, it.longitude, it.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build())
        }

        // Reminders
        activeReminders.forEach {
            Log.d("LocationService", "Adding Reminder R_${it.id} (Radius: ${it.radius}m)")
            geofenceList.add(Geofence.Builder()
                .setRequestId("R_${it.id}")
                .setCircularRegion(it.latitude, it.longitude, it.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build())
        }

        if (geofenceList.isEmpty()) {
            if (isFirstLoad) {
                Log.d("LocationService", "No active items yet, but waiting for first load...")
                return
            }
            Log.d("LocationService", "No active items. Removing all geofences.")
            geofencingClient.removeGeofences(geofencePendingIntent)
            updateSystemAlarmIcon(false)
            stopLocationUpdates()
            Log.d("LocationService", "Stopping service (no active tasks).")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        updateSystemAlarmIcon(true)
        startLocationUpdates()
        scheduleHeartbeat()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // As per user requirement: setInitialTrigger(0)
            .addGeofences(geofenceList)
            .build()

        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnCompleteListener {
                if (ActivityCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    geofencingClient.addGeofences(request, geofencePendingIntent).run {
                        addOnSuccessListener {
                            Log.d("LocationService", "SUCCESS: ${geofenceList.size} GEOFENCES REGISTERED")
                        }
                        addOnFailureListener {
                            Log.e("LocationService", "FAILED: GEOFENCE REGISTRATION ERROR", it)
                        }
                    }
                }
            }
        }
    }

    private fun updateSystemAlarmIcon(show: Boolean) {
        if (show) {
            val calendar = Calendar.getInstance().apply {
                add(Calendar.YEAR, 10)
            }
            val alarmInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, dummyAlarmPendingIntent)
            alarmManager.setAlarmClock(alarmInfo, dummyAlarmPendingIntent)
        } else {
            alarmManager.cancel(dummyAlarmPendingIntent)
        }
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        // REQUIREMENT: Increase frequency to 2s for faster manual calculation when close
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                checkDistancesManually(location)
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        cancelHeartbeat()
    }

    private fun scheduleHeartbeat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Wake up at the adaptive interval to check location, bypassing Doze mode
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + currentHeartbeatDelay,
                heartbeatIntent
            )
            Log.d("LocationService", "Next adaptive check in ${currentHeartbeatDelay/1000/60} minutes")
        }
    }

    private fun cancelHeartbeat() {
        alarmManager.cancel(heartbeatIntent)
    }

    private fun checkDistancesManually(currentLocation: Location) {
        val currentAccuracy = if (currentLocation.hasAccuracy()) currentLocation.accuracy else 200f
        
        // Only calculate when accuracy is sufficient (blue radius is small)
        val ACCURACY_THRESHOLD = 40f
        if (currentAccuracy > ACCURACY_THRESHOLD) return

        var minDistance = Float.MAX_VALUE

        // 1. Regular Alarms (Geofences)
        activeAlarms.forEach { alarm ->
            // REQUIREMENT: Check if card is on (already filtered by activeAlarms)
            // THEN check if distance <= radius
            val results = FloatArray(1)
            Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, alarm.latitude, alarm.longitude, results)
            val distance = results[0]
            if (distance < minDistance) minDistance = distance
            
            val uniqueId = "G_${alarm.id}"
            if (!handledGeofenceIds.contains(uniqueId) && distance <= alarm.radius) {
                handledGeofenceIds.add(uniqueId)
                NotificationHelper.triggerAlarm(this, alarm.id, alarm.name, alarm.audioUri, alarm.latitude, alarm.longitude, null)
            }
        }
        
        // 2. Reminders Workflow
        activeReminders.forEach { reminder ->
            // REQUIREMENT: Check if card is on (already filtered by activeReminders)
            // THEN check if distance <= radius
            val results = FloatArray(1)
            Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, reminder.latitude, reminder.longitude, results)
            val distance = results[0]
            if (distance < minDistance) minDistance = distance
            
            val isInside = distance <= reminder.radius
            if (isInside) {
                if (!entryAlarmTriggeredIds.contains(reminder.id)) {
                    val converters = Converters()
                    val items = converters.fromString(reminder.itemsJson)
                    val hasUnverified = items.isEmpty() || items.any { !(it.isCompleted || it.isFailed) }
                    
                    if (hasUnverified) {
                        entryAlarmTriggeredIds.add(reminder.id)
                        NotificationHelper.triggerAlarm(this, reminder.id, reminder.name, reminder.audioUri, reminder.latitude, reminder.longitude, reminder.itemsJson)
                    }
                }
                insideReminderIds.add(reminder.id)
            } else if (distance > (reminder.radius + 10)) { // Strict exit
                insideReminderIds.remove(reminder.id)
                entryAlarmTriggeredIds.remove(reminder.id)
            }
        }

        // 3. ADAPTIVE POLLING LOGIC
        // Based on minDistance, calculate the next heartbeat delay and toggle high-frequency updates
        val nextDelay = when {
            minDistance >= 1000000f -> 10800000L // 3 Hours
            minDistance >= 500000f  -> 7200000L  // 2 Hours
            minDistance >= 200000f  -> 3600000L  // 1 Hour
            minDistance >= 100000f  -> 1800000L  // 30 Mins
            minDistance >= 50000f   -> 900000L   // 15 Mins
            minDistance >= 20000f   -> 300000L   // 5 Mins
            minDistance >= 10000f   -> 120000L   // 2 Mins
            else -> 60000L // 1 Min base heartbeat
        }

        currentHeartbeatDelay = nextDelay

        if (minDistance < 10000f) {
            startLocationUpdates() // Keep high-frequency 3s polling active
        } else {
            // Stop 3s polling to save battery, rely on adaptive heartbeat
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                locationCallback = null
                Log.d("LocationService", "Min distance > 10km: High-frequency GPS paused")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service started with action: ${intent?.action}")
        
        val notification = createServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        if (intent?.action == ACTION_SYNC_GEOFENCES) {
            syncGeofences()
        }

        return START_STICKY
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_alarm) 
            .setContentTitle("GeoSmaran is active")
            .setContentText("Monitoring your destinations in background")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Changed from MIN to LOW as per requirement
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 1. Silent Tracking Channel
            val channel = NotificationChannel(channelId, "System Sync", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                description = "Keeps the app active for reliable location tracking"
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateSystemAlarmIcon(false)
        geofenceObserver?.let { geofenceLiveData?.removeObserver(it) }
        reminderObserver?.let { reminderLiveData?.removeObserver(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        try {
            unregisterReceiver(heartbeatReceiver)
        } catch (e: Exception) {}
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
