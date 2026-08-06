package com.example.loc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed. Checking permissions to start LocationService.")

            // Perform necessary permission checks before starting the foreground service
            val hasFineLocation = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                                          ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasForegroundServiceLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                                                 ActivityCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED

            val canStartLocationService = (hasFineLocation || hasCoarseLocation) && hasBackgroundLocation && hasForegroundServiceLocation

            if (canStartLocationService) {
                val serviceIntent = Intent(context, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("BootReceiver", "LocationService started successfully.")
            } else {
                Log.e("BootReceiver", "Required permissions not granted to start LocationService. Fine: $hasFineLocation, Coarse: $hasCoarseLocation, Background: $hasBackgroundLocation, FGS_Location: $hasForegroundServiceLocation")
                // Optionally, you might want to show a persistent notification informing the user
                // that the service cannot start due to missing permissions.
            }
        }
    }
}
