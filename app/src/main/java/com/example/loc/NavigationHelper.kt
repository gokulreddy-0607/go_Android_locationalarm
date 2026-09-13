package com.example.loc

import android.content.Context
import android.content.Intent
import android.net.Uri

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
     * Shows a chooser to open the location in any compatible map/transport app (Rapido, Ola, etc.)
     */
    fun openOtherMapsDirectly(context: Context, name: String, lat: Double, lng: Double) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($name)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        context.startActivity(Intent.createChooser(intent, "Open with:"))
    }
}
