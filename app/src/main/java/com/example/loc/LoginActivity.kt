package com.example.loc

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.loc.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>

    override fun attachBaseContext(newBase: Context) {
        val newConfig = Configuration(newBase.resources.configuration)
        newConfig.fontScale = 1.0f
        val context = newBase.createConfigurationContext(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        // Configure Google Sign In
        val webClientId = getString(R.string.default_web_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this@LoginActivity, gso)

        googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("Login", "Google Sign In result: ${result.resultCode}")
            if (result.resultCode == RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)!!
                    firebaseAuthWithGoogle(account.idToken!!)
                } catch (e: ApiException) {
                    Log.e("Login", "Google Sign In failed: status code ${e.statusCode}", e)
                    val msg = when(e.statusCode) {
                        10 -> "DEVELOPER_ERROR: Please check your Web Client ID in strings.xml and SHA-1 in Firebase Console."
                        7 -> "NETWORK_ERROR: Please check your internet connection."
                        else -> "Google sign in failed (Code ${e.statusCode}): ${e.message}"
                    }
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                // Try to get status code even if resultCode is not OK
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    task.getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    Log.e("Login", "Google Sign In result error: code ${e.statusCode}", e)
                }

                val msg = if (result.resultCode == RESULT_CANCELED) {
                    "Sign in cancelled. If this keeps happening, ensure you have registered your SHA-1 fingerprint in Firebase."
                } else {
                    "Sign in failed (Result Code: ${result.resultCode})"
                }
                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        if (auth.currentUser != null) {
            startSyncAndCheckLocation()
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this@LoginActivity, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressBar.visibility = View.VISIBLE
            binding.btnLogin.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this@LoginActivity) { task ->
                    if (task.isSuccessful) {
                        startSyncAndCheckLocation()
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        binding.tvSignup.setOnClickListener {
            startActivity(Intent(this@LoginActivity, SignupActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.btnGoogleSignIn.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun showForgotPasswordDialog() {
        val dialogBinding = com.example.loc.databinding.DialogForgotPasswordBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@LoginActivity)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnSend.setOnClickListener {
            val email = dialogBinding.etResetEmail.text.toString().trim()
            if (email.isEmpty()) {
                dialogBinding.tilResetEmail.error = "Please enter your email"
                return@setOnClickListener
            }

            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this@LoginActivity, "Reset link sent to $email", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@LoginActivity, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        binding.progressBar.visibility = View.VISIBLE
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this@LoginActivity) { task ->
                if (task.isSuccessful) {
                    startSyncAndCheckLocation()
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@LoginActivity, "Google auth failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun startSyncAndCheckLocation() {
        val user = auth.currentUser
        if (user != null) {
            SyncManager.syncAll(
                applicationContext,
                user.uid,
                user.displayName ?: "User",
                user.email ?: ""
            )
            checkLocationEnabled()
        } else {
            checkLocationEnabled()
        }
    }

    private fun checkLocationEnabled() {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000
        ).build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = com.google.android.gms.location.LocationServices.getSettingsClient(this@LoginActivity)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            launchMainActivity()
        }

        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@LoginActivity, 1001)
                } catch (sendEx: android.content.IntentSender.SendIntentException) { }
            } else {
                Toast.makeText(this@LoginActivity, "Location services are required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                launchMainActivity()
            } else {
                Toast.makeText(this@LoginActivity, "Please enable location to use the app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchMainActivity() {
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        finish()
    }
}