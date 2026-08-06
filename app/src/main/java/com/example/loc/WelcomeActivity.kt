package com.example.loc

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.loc.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Proceed directly to location check
        binding.buttonContainer.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.VISIBLE
        
        checkLocationEnabled()
    }

    private fun checkLocationEnabled() {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000
        ).build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = com.google.android.gms.location.LocationServices.getSettingsClient(this@WelcomeActivity)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            proceedToMain()
        }

        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@WelcomeActivity, 1001)
                } catch (sendEx: android.content.IntentSender.SendIntentException) { }
            } else {
                Toast.makeText(this@WelcomeActivity, "Location services are required", Toast.LENGTH_SHORT).show()
                proceedToMain() // Fallback
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            proceedToMain()
        }
    }

    private fun proceedToMain() {
        startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
        finish()
    }
}