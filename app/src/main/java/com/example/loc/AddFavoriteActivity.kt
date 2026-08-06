package com.example.loc

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.loc.data.FavoriteEntity
import com.example.loc.databinding.ActivityAddFavoriteBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.*
import java.io.IOException
import java.util.Locale

class AddFavoriteActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityAddFavoriteBinding
    private lateinit var viewModel: GeofenceViewModel
    private lateinit var mMap: GoogleMap
    private var selectedLatLng: LatLng? = null
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFavoriteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[GeofenceViewModel::class.java]

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupSuggestions()
        setupSearch()

        binding.btnSave.setOnClickListener {
            saveFavorite()
        }
    }

    private fun setupSuggestions() {
        suggestionAdapter = SuggestionAdapter { address ->
            val latLng = LatLng(address.latitude, address.longitude)
            selectLocation(latLng)
            binding.rvSuggestions.visibility = View.GONE
            binding.searchView.setQuery(address.getAddressLine(0), false)
            binding.searchView.clearFocus()
        }
        binding.rvSuggestions.layoutManager = LinearLayoutManager(this)
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
        val geocoder = Geocoder(this, Locale.getDefault())
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
        val geocoder = Geocoder(this, Locale.getDefault())
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
            Toast.makeText(this, "Search error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectLocation(latLng: LatLng) {
        selectedLatLng = latLng
        mMap.clear()
        mMap.addMarker(MarkerOptions().position(latLng))
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        binding.saveContainer.visibility = View.VISIBLE
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = true
            // Increase padding to ensure button is visible below the search bar and above the save container
            mMap.setPadding(0, 200, 0, 400)
        }

        mMap.setOnMapClickListener { latLng ->
            selectLocation(latLng)
        }
    }

    private fun saveFavorite() {
        var name = binding.etName.text.toString().trim()
        val latLng = selectedLatLng

        if (latLng == null) {
            Toast.makeText(this@AddFavoriteActivity, "Please select a location on the map", Toast.LENGTH_SHORT).show()
            return
        }

        // Disable save button to prevent multiple clicks
        binding.btnSave.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            if (name.isEmpty()) {
                val geocoder = Geocoder(this@AddFavoriteActivity, Locale.getDefault())
                try {
                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    name = if (!addresses.isNullOrEmpty()) {
                        addresses[0].getAddressLine(0) ?: "Saved Favorite"
                    } else {
                        "Saved Favorite"
                    }
                } catch (e: Exception) {
                    name = "Saved Favorite"
                }
            }

            val favorite = FavoriteEntity(
                name = name,
                latitude = latLng.latitude,
                longitude = latLng.longitude
            )

            viewModel.insertFavorite(favorite)
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddFavoriteActivity, "Favorite saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
