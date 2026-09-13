package com.example.loc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.loc.data.GeofenceEntity
import com.example.loc.data.ReminderEntity
import com.example.loc.databinding.ActivityAddGeofenceBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.*
import java.io.IOException
import java.util.Locale

class AddGeofenceActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityAddGeofenceBinding
    private lateinit var viewModel: GeofenceViewModel
    private lateinit var mMap: GoogleMap
    private var selectedLatLng: LatLng? = null
    private var currentRadius = 100f
    private var editingGeofenceId: Int = 0
    private var isReminder: Boolean = false
    private var existingItemsJson: String = "[]"
    private var existingAudioUri: String? = null
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var searchJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        val newConfig = Configuration(newBase.resources.configuration)
        newConfig.fontScale = 1.0f
        val context = newBase.createConfigurationContext(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddGeofenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel = ViewModelProvider(this@AddGeofenceActivity)[GeofenceViewModel::class.java]

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this@AddGeofenceActivity)

        setupSuggestions()
        setupSearch()
        setupRadiusControls()

        isReminder = intent.getBooleanExtra("EXTRA_IS_REMINDER", false)
        editingGeofenceId = intent.getIntExtra("EXTRA_GEOFENCE_ID", 0)
        existingItemsJson = intent.getStringExtra("EXTRA_ITEMS_JSON") ?: "[]"
        existingAudioUri = intent.getStringExtra("EXTRA_AUDIO_URI")

        val title = if (editingGeofenceId != 0) {
            if (isReminder) "Edit Reminder" else "Edit Geofence"
        } else {
            if (isReminder) "Add Reminder" else "Add Geofence"
        }
        supportActionBar?.title = title
        
        if (isReminder) {
            binding.btnSave.text = "Save Reminder"
            if (editingGeofenceId == 0) {
                binding.etName.setHint("Reminder Name")
            }
        }

        if (editingGeofenceId != 0) {
            binding.etName.setText(intent.getStringExtra("EXTRA_NAME"))
            currentRadius = intent.getFloatExtra("EXTRA_RADIUS", 100f)
            
            // Set initial radius values
            if (currentRadius >= 1000) {
                binding.etRadiusValue.setText((currentRadius / 1000).toString())
                binding.spinnerUnit.setText("km", false)
            } else {
                binding.etRadiusValue.setText(currentRadius.toInt().toString())
                binding.spinnerUnit.setText("m", false)
            }

            val lat = intent.getDoubleExtra("EXTRA_LATITUDE", 0.0)
            val lng = intent.getDoubleExtra("EXTRA_LONGITUDE", 0.0)
            selectedLatLng = LatLng(lat, lng)
            binding.btnSave.text = if (isReminder) "Update Reminder" else "Update Geofence"
        } else {
            // Default for new
            binding.etRadiusValue.setText("100")
            binding.spinnerUnit.setText("m", false)
        }

        binding.btnSave.setOnClickListener {
            saveLocation()
        }
    }

    private fun setupRadiusControls() {
        val units = arrayOf("m", "km")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, units)
        binding.spinnerUnit.setAdapter(adapter)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateRadiusFromInput()
            }
        }

        binding.etRadiusValue.addTextChangedListener(watcher)
        binding.spinnerUnit.setOnItemClickListener { _, _, _, _ ->
            updateRadiusFromInput()
        }
    }

    private fun updateRadiusFromInput() {
        val valueStr = binding.etRadiusValue.text.toString()
        if (valueStr.isEmpty()) return

        val value = valueStr.toFloatOrNull() ?: return
        val unit = binding.spinnerUnit.text.toString()

        currentRadius = if (unit == "km") value * 1000f else value
        updateMapDisplay()
    }
    private fun setupSuggestions() {
        suggestionAdapter = SuggestionAdapter { address ->
            val latLng = LatLng(address.latitude, address.longitude)
            selectLocation(latLng)
            binding.rvSuggestions.visibility = View.GONE
            binding.searchView.setQuery(address.getAddressLine(0), false)
            binding.searchView.clearFocus()
        }
        binding.rvSuggestions.layoutManager = LinearLayoutManager(this@AddGeofenceActivity)
        binding.rvSuggestions.adapter = suggestionAdapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchLocation(it) }
                binding.rvSuggestions.visibility = View.GONE
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                if (newText.isNullOrBlank()) {
                    binding.rvSuggestions.visibility = View.GONE
                    return false
                }
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    getSuggestions(newText)
                }
                return true
            }
        })
    }

    private fun getSuggestions(query: String) {
        val geocoder = Geocoder(this@AddGeofenceActivity, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(query, 5) { addresses ->
                    runOnUiThread {
                        if (addresses.isNotEmpty()) {
                            suggestionAdapter.setSuggestions(addresses)
                            binding.rvSuggestions.visibility = View.VISIBLE
                        } else {
                            binding.rvSuggestions.visibility = View.GONE
                        }
                    }
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 5)
                    withContext(Dispatchers.Main) {
                        if (!addresses.isNullOrEmpty()) {
                            suggestionAdapter.setSuggestions(addresses)
                            binding.rvSuggestions.visibility = View.VISIBLE
                        } else {
                            binding.rvSuggestions.visibility = View.GONE
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun searchLocation(location: String) {
        val geocoder = Geocoder(this@AddGeofenceActivity, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(location, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val latLng = LatLng(address.latitude, address.longitude)
                        runOnUiThread { selectLocation(latLng) }
                    }
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(location, 1)
                    withContext(Dispatchers.Main) {
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val latLng = LatLng(address.latitude, address.longitude)
                            selectLocation(latLng)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Toast.makeText(this@AddGeofenceActivity, "Search error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectLocation(latLng: LatLng) {
        selectedLatLng = latLng
        updateMapDisplay()
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(this@AddGeofenceActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = true
            mMap.setPadding(0, 400, 0, 400)
        }

        mMap.setOnMapClickListener { latLng ->
            selectedLatLng = latLng
            updateMapDisplay()
        }

        selectedLatLng?.let {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
            updateMapDisplay()
        }
    }

    private fun updateMapDisplay() {
        val latLng = selectedLatLng ?: return
        mMap.clear()
        mMap.addMarker(MarkerOptions().position(latLng))
        mMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(currentRadius.toDouble())
                .strokeColor(Color.BLUE)
                .fillColor(0x220000FF)
                .strokeWidth(5.0f)
        )
    }

    private fun saveLocation() {
        var name = binding.etName.text.toString().trim()
        val latLng = selectedLatLng

        if (latLng == null) {
            Toast.makeText(this@AddGeofenceActivity, "Please select a location on the map", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            if (name.isEmpty()) {
                val geocoder = Geocoder(this@AddGeofenceActivity, Locale.getDefault())
                try {
                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    name = if (!addresses.isNullOrEmpty()) {
                        addresses[0].getAddressLine(0) ?: (if (isReminder) "Reminder" else "Saved Geofence")
                    } else {
                        if (isReminder) "Reminder" else "Saved Geofence"
                    }
                } catch (e: Exception) {
                    name = if (isReminder) "Reminder" else "Saved Geofence"
                }
            }

            if (isReminder) {
                val reminder = ReminderEntity(
                    id = editingGeofenceId,
                    name = name,
                    latitude = latLng.latitude,
                    longitude = latLng.longitude,
                    radius = currentRadius,
                    isActive = true,
                    itemsJson = existingItemsJson,
                    audioUri = existingAudioUri
                )
                viewModel.insertReminder(reminder)
            } else {
                val geofence = GeofenceEntity(
                    id = editingGeofenceId,
                    name = name,
                    latitude = latLng.latitude,
                    longitude = latLng.longitude,
                    radius = currentRadius,
                    isActive = true
                )
                viewModel.insert(geofence)
            }

            withContext(Dispatchers.Main) {
                val message = if (isReminder) {
                    if (editingGeofenceId == 0) "Reminder saved and tracking" else "Reminder updated"
                } else {
                    if (editingGeofenceId == 0) "Geofence saved and tracking" else "Geofence updated"
                }
                
                // REQUIREMENT: Trigger immediate sync in LocationService
                val syncIntent = Intent(this@AddGeofenceActivity, LocationService::class.java).apply {
                    action = LocationService.ACTION_SYNC_GEOFENCES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(syncIntent)
                } else {
                    startService(syncIntent)
                }
                Log.d("Reminder", "Triggered SYNC_GEOFENCES after save.")

                Toast.makeText(this@AddGeofenceActivity, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
