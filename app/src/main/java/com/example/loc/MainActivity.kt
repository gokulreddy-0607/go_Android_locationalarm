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
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.loc.data.GeofenceEntity
import com.example.loc.data.ReminderEntity
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
    private var selectedReminderForAudio: ReminderEntity? = null
    private var currentTab = R.id.nav_alarm
    private var isLogicStarted = false
    private var activeRationaleDialog: AlertDialog? = null
    private var locationCallback: LocationCallback? = null

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
        supportActionBar?.title = "Alarms"

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = insets.top)
            binding.bottomNavigation.updatePadding(bottom = insets.bottom)
            
            windowInsets
        }

        geofencingClient = LocationServices.getGeofencingClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        viewModel = ViewModelProvider(this)[GeofenceViewModel::class.java]

        setupRecyclerViews()
    }

    override fun onResume() {
        super.onResume()
        // Hide the main UI until permissions are verified
        binding.mainRoot.visibility = View.INVISIBLE
        if (activeRationaleDialog == null) {
            checkPermissionsChain()
        }
        if (isLogicStarted) {
            registerAllGeofences()
        }
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
    }

    private fun checkPermissionsChain() {
        val permissions = mutableListOf<String>()
        
        // REQUIREMENT: Always request Fine and Coarse together for modern Android behavior
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            showBasicRationale(permissions)
        } else {
            // Already have basic location, check if it's PRECISE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                checkBackgroundLocation()
            } else {
                showPreciseRequiredRationale()
            }
        }
    }

    private fun showBasicRationale(permissions: List<String>) {
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Permissions Required")
            .setMessage("GeoSmaran needs Location access to detect destinations.")
            .setPositiveButton("Grant") { _, _ ->
                activeRationaleDialog = null
                requestPermissionsLauncher.launch(permissions.toTypedArray())
            }
            .setNegativeButton("Exit") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
            .show()
    }

    private fun showPreciseRequiredRationale() {
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Precise Location Required")
            .setMessage("GeoSmaran requires 'Precise Location' to trigger reminders accurately when you arrive at small destinations.\n\nPlease enable it in the next screen.")
            .setPositiveButton("Configure") { _, _ ->
                activeRationaleDialog = null
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            .setNegativeButton("Exit") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
            .show()
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)

        if (fineGranted) {
            checkBackgroundLocation()
        } else if (coarseGranted) {
            showPreciseRequiredRationale()
        } else {
            Toast.makeText(this, "Location permission is mandatory", Toast.LENGTH_LONG).show()
            finishAffinity()
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
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Background Location")
            .setMessage("Location access 'Allow all the time' is mandatory to trigger alerts when the screen is off.\n\nOn the next system screen, select 'Allow all the time'.")
            .setPositiveButton("Configure") { _, _ ->
                activeRationaleDialog = null
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Exit App") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
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
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Overlay Permission")
            .setMessage("The 'Display over other apps' permission is mandatory so alarms can pop up while using other apps.\n\n" +
                        "TIP: On some devices (Xiaomi/Samsung), you may also need to manually enable 'Show on lock screen' in the device app settings for 100% reliability.")
            .setPositiveButton("Enable") { _, _ ->
                activeRationaleDialog = null
                try {
                    // Intent that takes the user directly to the toggle for THIS app
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to the general list if the direct package link is not supported on this device
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("Exit App") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
            .show()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        } else {
            checkExactAlarmPermission()
        }
    }

    private fun showBatteryOptimizationDialog() {
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Reliability Check")
            .setMessage("Disabling battery optimization is mandatory to keep location tracking reliable in the background.")
            .setPositiveButton("Configure") { _, _ ->
                activeRationaleDialog = null
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            .setNegativeButton("Exit App") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
            .show()
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                showExactAlarmRationale()
            } else {
                checkLocationSettings { startAppLogic() }
            }
        } else {
            checkLocationSettings { startAppLogic() }
        }
    }

    private fun showExactAlarmRationale() {
        activeRationaleDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
            .setTitle("Exact Alarms")
            .setMessage("The 'Alarms & reminders' permission is mandatory so the app can trigger alerts at the exact moment you arrive.")
            .setPositiveButton("Enable") { _, _ ->
                activeRationaleDialog = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Exit App") { _, _ -> 
                activeRationaleDialog = null
                finishAffinity() 
            }
            .setCancelable(false)
            .show()
    }

    private fun startAppLogic() {
        // Show the UI once all checks have passed
        binding.mainRoot.visibility = View.VISIBLE
        
        if (isLogicStarted) return
        isLogicStarted = true

        startLocationUpdates()
        startLocationService()

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            binding.recyclerView.visibility = if (item.itemId == R.id.nav_alarm) View.VISIBLE else View.GONE
            binding.recyclerViewFavorites.visibility = if (item.itemId == R.id.nav_favorites) View.VISIBLE else View.GONE
            binding.recyclerViewReminders.visibility = if (item.itemId == R.id.nav_reminders) View.VISIBLE else View.GONE
            
            // Update title based on selected tab
            supportActionBar?.title = when(item.itemId) {
                R.id.nav_alarm -> "Alarms"
                R.id.nav_favorites -> "Favorites"
                R.id.nav_reminders -> "Reminders"
                else -> "Home"
            }
            
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
        // Handled by LocationService observing the database
        // We call startLocationService to ensure it's alive when an alarm is added/active
        startLocationService()
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
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                startAppLogic()
            } else {
                Toast.makeText(this, "GPS must be enabled to use this app", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
        } else if (requestCode == 1002 && resultCode == RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            selectedGeofenceForAudio?.let { geofence ->
                val updatedGeofence = geofence.copy(audioUri = uri?.toString())
                viewModel.update(updatedGeofence)
                Toast.makeText(this, "Alarm sound updated", Toast.LENGTH_SHORT).show()
            }
            selectedGeofenceForAudio = null
            
            selectedReminderForAudio?.let { reminder ->
                val updatedReminder = reminder.copy(audioUri = uri?.toString())
                viewModel.updateReminder(updatedReminder)
                Toast.makeText(this, "Reminder sound updated", Toast.LENGTH_SHORT).show()
            }
            selectedReminderForAudio = null
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
        val standardFooter = FooterAdapter(onCreateClick = {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                checkLocationSettings { navigateToCreateScreen() }
            } else {
                checkPermissionsChain()
            }
        })

        val favoritesFooter = FooterAdapter(
            onCreateClick = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    checkLocationSettings { navigateToCreateScreen() }
                } else {
                    checkPermissionsChain()
                }
            },
            showCurrentLocation = true,
            onCurrentLocClick = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    saveCurrentLocationAsFavorite()
                } else {
                    checkPermissionsChain()
                }
            }
        )

        adapter = GeofenceAdapter(
            onDeleteClick = { geofence ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
                    .setTitle("Delete Alarm?")
                    .setMessage("Are you sure you want to delete '${geofence.name}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.delete(geofence) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
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
            onToggleClick = { viewModel.update(it) },
            onAudioSelectClick = {
                selectedGeofenceForAudio = it
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it.audioUri?.let { uriStr -> Uri.parse(uriStr) })
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1002)
            }
        )
        binding.recyclerView.adapter = ConcatAdapter(adapter, standardFooter)

        favoriteAdapter = FavoriteAdapter(
            onDeleteClick = { favorite ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
                    .setTitle("Remove Favorite?")
                    .setMessage("Are you sure you want to remove '${favorite.name}' from your favorites?")
                    .setPositiveButton("Remove") { _, _ -> viewModel.deleteFavorite(favorite) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onMapClick = { NavigationHelper.openGoogleMapsDirectly(this, it.latitude, it.longitude) },
            onBookClick = { NavigationHelper.openOtherMapsDirectly(this, it.name, it.latitude, it.longitude) },
            onSetAlarmClick = {
                viewModel.insert(GeofenceEntity(name = it.name, latitude = it.latitude, longitude = it.longitude, radius = 100f, isActive = true))
                binding.bottomNavigation.selectedItemId = R.id.nav_alarm
                Toast.makeText(this, "Alarm set for ${it.name}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerViewFavorites.adapter = ConcatAdapter(favoriteAdapter, favoritesFooter)

        reminderAdapter = ReminderAdapter(
            onDeleteClick = { reminder ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Loc_PurpleDialog)
                    .setTitle("Delete Reminder?")
                    .setMessage("Are you sure you want to delete '${reminder.name}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteReminder(reminder) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onUpdateClick = { viewModel.updateReminder(it) },
            onSelectAudioClick = { reminder ->
                selectedReminderForAudio = reminder
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Reminder Sound")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, reminder.audioUri?.let { Uri.parse(it) })
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1002)
            },
            onUpdateLocationClick = { reminder ->
                val intent = Intent(this, AddGeofenceActivity::class.java).apply {
                    putExtra("EXTRA_GEOFENCE_ID", reminder.id)
                    putExtra("EXTRA_NAME", reminder.name)
                    putExtra("EXTRA_LATITUDE", reminder.latitude)
                    putExtra("EXTRA_LONGITUDE", reminder.longitude)
                    putExtra("EXTRA_RADIUS", reminder.radius)
                    putExtra("EXTRA_IS_ACTIVE", reminder.isActive)
                    putExtra("EXTRA_IS_REMINDER", true)
                    putExtra("EXTRA_ITEMS_JSON", reminder.itemsJson)
                    putExtra("EXTRA_AUDIO_URI", reminder.audioUri)
                }
                startActivity(intent)
            }
        )
        binding.recyclerViewReminders.adapter = ConcatAdapter(reminderAdapter, standardFooter)
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
        if (locationCallback != null) return // Already running
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        // Try to get last known location immediately
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && currentLocation == null) {
                currentLocation = location
                adapter.setCurrentLocation(currentLocation)
                reminderAdapter.setCurrentLocation(currentLocation)
            }
        }
        
        // REQUIREMENT: Increase frequency of distance updates to 2s (from 10s) for better responsiveness
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                currentLocation = res.lastLocation
                adapter.setCurrentLocation(currentLocation)
                reminderAdapter.setCurrentLocation(currentLocation)
            }
        }
        
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            currentLocation = null
            adapter.setCurrentLocation(null)
            reminderAdapter.setCurrentLocation(null)
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun saveCurrentLocationAsFavorite() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            checkPermissionsChain()
            return
        }

        val loc = currentLocation
        if (loc != null) {
            launchAddFavorite(loc)
            return
        }

        // Proactively try to get a fresh location if the background update hasn't fired yet
        Toast.makeText(this, "Locating your position...", Toast.LENGTH_SHORT).show()
        
        val priority = Priority.PRIORITY_HIGH_ACCURACY
        fusedLocationClient.getCurrentLocation(priority, null).addOnSuccessListener { location ->
            if (location != null) {
                currentLocation = location
                launchAddFavorite(location)
            } else {
                // Last ditch effort: Try lastLocation
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        currentLocation = lastLoc
                        launchAddFavorite(lastLoc)
                    } else {
                        Toast.makeText(this, "Could not get location. Check GPS settings.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Location service error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchAddFavorite(location: Location) {
        val intent = Intent(this, AddFavoriteActivity::class.java).apply {
            putExtra("EXTRA_LATITUDE", location.latitude)
            putExtra("EXTRA_LONGITUDE", location.longitude)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }
}
