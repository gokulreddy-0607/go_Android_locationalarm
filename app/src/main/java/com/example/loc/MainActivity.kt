package com.example.loc

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import com.example.loc.data.GeofenceEntity
import com.example.loc.databinding.ActivityMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: GeofenceViewModel
    private lateinit var adapter: GeofenceAdapter
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var reminderAdapter: ReminderAdapter
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private var selectedGeofenceForAudio: GeofenceEntity? = null
    private var currentTab = R.id.nav_alarm
    private var isLogicStarted = false
    private var securityDialog: AlertDialog? = null

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    override fun attachBaseContext(newBase: Context) {
        val newConfig = Configuration(newBase.resources.configuration)
        newConfig.fontScale = 1.0f
        val context = newBase.createConfigurationContext(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = insets.top)
            binding.bottomNavigation.updatePadding(bottom = insets.bottom)
            
            // Adjust FAB margin to stay visible above the bottom navigation bar
            binding.fabAdd.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_bottom_margin) + insets.bottom
            }
            
            windowInsets
        }

        geofencingClient = LocationServices.getGeofencingClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        viewModel = ViewModelProvider(this)[GeofenceViewModel::class.java]

        setupRecyclerViews()
        
        binding.fabAdd.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                checkLocationSettings { navigateToCreateScreen() }
            } else {
                checkPermissionsChain()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkSecurityAndInitialize()
    }

    private fun checkSecurityAndInitialize() {
        val isDevOptionsOn = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        if (isDevOptionsOn) {
            showSecurityBlockedDialog()
        } else {
            securityDialog?.dismiss()
            securityDialog = null
            if (!isLogicStarted) {
                checkPermissionsChain()
            }
        }
    }

    private fun showSecurityBlockedDialog() {
        if (securityDialog?.isShowing == true) return
        securityDialog = AlertDialog.Builder(this)
            .setTitle("Security Blocked")
            .setMessage("Developer Options are enabled. Please disable them to use GeoSmaran.")
            .setCancelable(false)
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNeutralButton("Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun checkPermissionsChain() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        } else {
            checkBackgroundLocation()
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                showBackgroundRationale()
            } else {
                checkOverlayPermission()
            }
        } else {
            checkOverlayPermission()
        }
    }

    private fun showBackgroundRationale() {
        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage("GeoSmaran needs 'Allow all the time' location access to trigger alerts when the screen is off.")
            .setPositiveButton("Configure") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Dismiss") { _, _ -> checkOverlayPermission() }
            .show()
    }

    private val backgroundLocationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        registerAllGeofences() // Refresh geofences with background permission
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayRationale()
        } else {
            checkBatteryOptimization()
        }
    }

    private fun showOverlayRationale() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission")
            .setMessage("Allow 'Display over other apps' so alarms can pop up while using other apps.")
            .setPositiveButton("Enable") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Skip") { _, _ -> checkBatteryOptimization() }
            .show()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        } else {
            checkLocationSettings { startAppLogic() }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reliability Check")
            .setMessage("Please disable battery optimization to keep GeoSmaran tracking reliable in the background.")
            .setPositiveButton("Configure") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            .setNegativeButton("Later") { _, _ -> checkLocationSettings { startAppLogic() } }
            .show()
    }

    private fun startAppLogic() {
        if (isLogicStarted) return
        isLogicStarted = true

        startLocationUpdates()
        startLocationService()
        
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            binding.recyclerView.visibility = if (item.itemId == R.id.nav_alarm) View.VISIBLE else View.GONE
            binding.recyclerViewFavorites.visibility = if (item.itemId == R.id.nav_favorites) View.VISIBLE else View.GONE
            binding.recyclerViewReminders.visibility = if (item.itemId == R.id.nav_reminders) View.VISIBLE else View.GONE
            updateEmptyStateVisibility()
            true
        }

        viewModel.allGeofences.observe(this) { list ->
            adapter.submitList(list)
            registerAllGeofences()
            updateEmptyStateVisibility()
        }
        viewModel.allFavorites.observe(this) { list ->
            favoriteAdapter.submitList(list)
            updateEmptyStateVisibility()
        }
        viewModel.allReminders.observe(this) { list ->
            reminderAdapter.submitList(list)
            registerAllGeofences()
            updateEmptyStateVisibility()
        }
    }

    private fun registerAllGeofences() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val geofenceList = mutableListOf<Geofence>()

        // Regular Alarms
        viewModel.allGeofences.value?.filter { it.isActive }?.forEach {
            geofenceList.add(Geofence.Builder()
                .setRequestId("G_${it.id}")
                .setCircularRegion(it.latitude, it.longitude, it.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(10000)
                .build())
        }

        // Reminders (Handles Enter and Exit for unfinished tasks)
        viewModel.allReminders.value?.filter { it.isActive }?.forEach {
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

        // Remove old geofences before adding new ones to prevent stale triggers
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnCompleteListener {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    geofencingClient.addGeofences(request, geofencePendingIntent).run {
                        addOnSuccessListener { Log.d("MainActivity", "System geofences updated successfully") }
                        addOnFailureListener { Log.e("MainActivity", "Failed to update geofences", it) }
                    }
                }
            }
        }
    }

    private fun checkLocationSettings(onSuccess: () -> Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).setAlwaysShow(true)
        val client = LocationServices.getSettingsClient(this)
        
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(this@MainActivity, 1001)
                    } catch (e: Exception) { }
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            if (!isLogicStarted) startAppLogic()
        }
    }

    private fun navigateToCreateScreen() {
        val intent = when (currentTab) {
            R.id.nav_alarm -> Intent(this, AddGeofenceActivity::class.java).apply { putExtra("EXTRA_IS_REMINDER", false) }
            R.id.nav_favorites -> Intent(this, AddFavoriteActivity::class.java)
            R.id.nav_reminders -> Intent(this, AddGeofenceActivity::class.java).apply { putExtra("EXTRA_IS_REMINDER", true) }
            else -> null
        }
        intent?.let { 
            startActivity(it)
            overridePendingTransition(0, 0)
        }
    }

    private fun setupRecyclerViews() {
        adapter = GeofenceAdapter(
            onDeleteClick = { viewModel.delete(it) },
            onEditClick = { geofence ->
                val intent = Intent(this, AddGeofenceActivity::class.java).apply {
                    putExtra("EXTRA_GEOFENCE_ID", geofence.id)
                    putExtra("EXTRA_NAME", geofence.name)
                    putExtra("EXTRA_LATITUDE", geofence.latitude)
                    putExtra("EXTRA_LONGITUDE", geofence.longitude)
                    putExtra("EXTRA_RADIUS", geofence.radius)
                    putExtra("EXTRA_AUDIO_URI", geofence.audioUri)
                    putExtra("EXTRA_IS_ACTIVE", geofence.isActive)
                    putExtra("EXTRA_IS_REMINDER", false)
                }
                startActivity(intent)
            },
            onOpenMapClick = { NavigationHelper.openGoogleMapsDirectly(this, it.latitude, it.longitude) },
            onBookClick = { NavigationHelper.showNavigationOptions(this, it.name, it.latitude, it.longitude) },
            onToggleClick = { viewModel.update(it) },
            onAudioSelectClick = {
                selectedGeofenceForAudio = it
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                }
                startActivity(intent)
            }
        )
        binding.recyclerView.adapter = adapter

        favoriteAdapter = FavoriteAdapter(
            onDeleteClick = { viewModel.deleteFavorite(it) },
            onMapClick = { NavigationHelper.openGoogleMapsDirectly(this, it.latitude, it.longitude) },
            onBookClick = { NavigationHelper.showNavigationOptions(this, it.name, it.latitude, it.longitude) },
            onSetAlarmClick = {
                viewModel.insert(GeofenceEntity(name = it.name, latitude = it.latitude, longitude = it.longitude, radius = 100f))
                binding.bottomNavigation.selectedItemId = R.id.nav_alarm
                Toast.makeText(this, "Alarm created", Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerViewFavorites.adapter = favoriteAdapter

        reminderAdapter = ReminderAdapter(
            onDeleteClick = { viewModel.deleteReminder(it) },
            onUpdateClick = { viewModel.updateReminder(it) }
        )
        binding.recyclerViewReminders.adapter = reminderAdapter
    }

    private fun updateEmptyStateVisibility() {
        val isEmpty = when (currentTab) {
            R.id.nav_alarm -> viewModel.allGeofences.value.isNullOrEmpty()
            R.id.nav_favorites -> viewModel.allFavorites.value.isNullOrEmpty()
            R.id.nav_reminders -> viewModel.allReminders.value.isNullOrEmpty()
            else -> false
        }
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                currentLocation = res.lastLocation
                adapter.setCurrentLocation(currentLocation)
            }
        }, Looper.getMainLooper())
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
}
