package com.example.loc

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.loc.data.AppDatabase
import com.example.loc.data.Converters
import com.example.loc.data.ReminderItem
import com.example.loc.databinding.ActivityAlarmBinding
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var targetLatitude: Double = 0.0
    private var targetLongitude: Double = 0.0
    private var currentGeofenceId: Int = -1

    companion object {
        var isShowing = false
    }

    override fun attachBaseContext(newBase: Context) {
        val newConfig = Configuration(newBase.resources.configuration)
        newConfig.fontScale = 1.0f
        val context = newBase.createConfigurationContext(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        
        // Comprehensive flags to wake up and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        currentGeofenceId = intent.getIntExtra("EXTRA_GEOFENCE_ID", -1)
        val locationName = intent.getStringExtra("EXTRA_LOCATION_NAME") ?: "Destination"
        val audioUriStr = intent.getStringExtra("EXTRA_AUDIO_URI")
        val itemsJson = intent.getStringExtra("EXTRA_ITEMS_JSON")
        val isReminder = intent.getBooleanExtra("EXTRA_IS_REMINDER", false)
        
        targetLatitude = intent.getDoubleExtra("EXTRA_LATITUDE", 0.0)
        targetLongitude = intent.getDoubleExtra("EXTRA_LONGITUDE", 0.0)

        binding.tvLocationName.text = locationName
        
        if (isReminder) {
            binding.tvAlertSubtitle.text = "Location Reminder"
            setupReminderItems(itemsJson)
        }

        startAlarmSound(audioUriStr)
        startDistanceUpdates()

        binding.btnStopAlarm.setOnClickListener {
            stopAlarm(currentGeofenceId, isReminder)
        }
    }

    private fun setupReminderItems(json: String?) {
        if (json.isNullOrEmpty()) return
        
        try {
            val converters = Converters()
            val items = converters.fromString(json).toMutableList()
            if (items.isNotEmpty()) {
                binding.itemsContainer.visibility = View.VISIBLE
                binding.rvReminderItems.layoutManager = LinearLayoutManager(this)
                binding.rvReminderItems.adapter = object : RecyclerView.Adapter<ReminderItemViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderItemViewHolder {
                        val view = layoutInflater.inflate(R.layout.item_alarm_reminder, parent, false)
                        return ReminderItemViewHolder(view)
                    }

                    override fun onBindViewHolder(holder: ReminderItemViewHolder, position: Int) {
                        val item = items[position]
                        holder.bind(item) { isChecked ->
                            // Update local list
                            items[position].isCompleted = isChecked
                            // Sync back to database immediately
                            updateItemsInDatabase(items)
                        }
                    }

                    override fun getItemCount() = items.size
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmActivity", "Error parsing items", e)
        }
    }

    private fun updateItemsInDatabase(items: List<ReminderItem>) {
        if (currentGeofenceId == -1) return
        val json = Converters().fromList(items)
        val db = AppDatabase.getDatabase(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = db.reminderDao()
            val reminder = dao.getReminderById(currentGeofenceId)
            if (reminder != null) {
                dao.update(reminder.copy(itemsJson = json))
            }
        }
    }

    class ReminderItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvItemName)
        private val checkBox: CheckBox = view.findViewById(R.id.cbReminderItem)
        
        fun bind(item: ReminderItem, onCheckChanged: (Boolean) -> Unit) {
            tvName.text = item.name
            checkBox.setOnCheckedChangeListener(null) // Prevent recursive trigger
            checkBox.isChecked = item.isCompleted
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                onCheckChanged(isChecked)
            }
        }
    }

    private fun startDistanceUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, targetLatitude, targetLongitude, results)
                val distance = results[0]
                
                val distanceStr = if (distance >= 1000) {
                    "%.2f km".format(distance / 1000)
                } else {
                    "%.0f m".format(distance)
                }
                
                binding.tvDistanceDisplay.text = "Distance: $distanceStr"
            }
        }, Looper.getMainLooper())
    }

    private fun startAlarmSound(audioUriStr: String?) {
        if (mediaPlayer == null) {
            val alarmUri = if (!audioUriStr.isNullOrEmpty()) {
                Uri.parse(audioUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmActivity, alarmUri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("AlarmActivity", "Error playing audio, falling back to default", e)
                try {
                    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(this@AlarmActivity, defaultUri!!)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (e2: Exception) {
                    Log.e("AlarmActivity", "Critical error playing default alarm", e2)
                }
            }
        }
    }

    private fun stopAlarm(geofenceId: Int, isReminder: Boolean) {
        isShowing = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(geofenceId) 

        if (geofenceId != -1) {
            val db = AppDatabase.getDatabase(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                if (isReminder) {
                    // Reminders stay active to monitor EXIT, but we stop the sound
                    // We only turn them OFF manually in the main list.
                    withContext(Dispatchers.Main) {
                        finishAndRemoveTask()
                    }
                } else {
                    val dao = db.geofenceDao()
                    val geofence = dao.getGeofenceById(geofenceId)
                    if (geofence != null) {
                        dao.update(geofence.copy(isActive = false))
                    }
                    withContext(Dispatchers.Main) {
                        finishAndRemoveTask()
                    }
                }
            }
        } else {
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
