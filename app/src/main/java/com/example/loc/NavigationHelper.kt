package com.example.loc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.loc.databinding.BottomSheetNavigationBinding

object NavigationHelper {

    /**
     * Directly opens Google Maps in navigation mode.
     */
    fun openGoogleMapsDirectly(context: Context, lat: Double, lng: Double) {
        val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lng")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        
        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            // If Google Maps app is not installed, open in browser
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    /**
     * Shows a custom Bottom Sheet with transport and navigation options.
     */
    fun showNavigationOptions(context: Context, name: String, lat: Double, lng: Double) {
        val dialog = BottomSheetDialog(context)
        val binding = BottomSheetNavigationBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvTitle.text = "Navigate to $name"

        binding.btnGoogleMaps.setOnClickListener {
            openGoogleMapsDirectly(context, lat, lng)
            dialog.dismiss()
        }

        binding.btnUber.setOnClickListener {
            // Intent to open Uber with destination pre-filled
            val uri = Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff_lat=$lat&dropoff_longitude=$lng&dropoff_nickname=$name")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to Uber mobile web
                val webUri = Uri.parse("https://m.uber.com/ul/?action=setPickup&dropoff[latitude]=$lat&dropoff[longitude]=$lng")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
            dialog.dismiss()
        }

        binding.btnOther.setOnClickListener {
            // General geo intent to show all compatible apps (Rapido, Ola, etc.)
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($name)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(Intent.createChooser(intent, "Open with:"))
            dialog.dismiss()
        }

        dialog.show()
    }
}
