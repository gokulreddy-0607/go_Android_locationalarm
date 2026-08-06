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
import com.example.loc.databinding.ActivitySignupBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
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
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this@SignupActivity, gso)

        googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("Signup", "Google Sign In result: ${result.resultCode}")
            if (result.resultCode == RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)!!
                    firebaseAuthWithGoogle(account.idToken!!)
                } catch (e: ApiException) {
                    Log.e("Signup", "Google Sign In failed: status code ${e.statusCode}", e)
                    val msg = when(e.statusCode) {
                        10 -> "DEVELOPER_ERROR: Please check your Web Client ID in strings.xml and SHA-1 in Firebase Console."
                        7 -> "NETWORK_ERROR: Please check your internet connection."
                        12500 -> "SIGN_IN_FAILED: Google Play Services issue or incorrect configuration."
                        else -> "Google sign in failed (Code ${e.statusCode}): ${e.message}"
                    }
                    Toast.makeText(this@SignupActivity, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                // Try to get status code even if resultCode is not OK
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    task.getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    Log.e("Signup", "Google Sign In result error: code ${e.statusCode}", e)
                }
                
                val msg = if (result.resultCode == RESULT_CANCELED) {
                    "Sign in cancelled. If this keeps happening, ensure you have registered your SHA-1 fingerprint in Firebase."
                } else {
                    "Sign in failed (Result Code: ${result.resultCode})"
                }
                Toast.makeText(this@SignupActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSignup.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this@SignupActivity, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 6) {
                Toast.makeText(this@SignupActivity, "Password should be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressBar.visibility = View.VISIBLE
            binding.btnSignup.isEnabled = false

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this@SignupActivity) { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        val profileUpdates = userProfileChangeRequest {
                            displayName = username
                        }
                        user?.updateProfile(profileUpdates)?.addOnCompleteListener {
                            saveUserToFirestore(user.uid, username, email)
                        }
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSignup.isEnabled = true
                        Toast.makeText(this@SignupActivity, "Signup failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        binding.btnGoogleSignIn.setOnClickListener {
            signInWithGoogle()
        }

        binding.tvLogin.setOnClickListener {
            finish() // Go back to LoginActivity
        }
    }

    private fun saveUserToFirestore(uid: String, username: String, email: String) {
        SyncManager.syncAll(applicationContext, uid, username, email)

        // Proceed to main screen
        binding.progressBar.visibility = View.GONE
        Toast.makeText(this@SignupActivity, "Welcome, $username!", Toast.LENGTH_SHORT).show()
        startMainActivity()
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        binding.progressBar.visibility = View.VISIBLE
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this@SignupActivity) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        saveUserToFirestore(user.uid, user.displayName ?: "Google User", user.email ?: "")
                    }
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@SignupActivity, "Google auth failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun startMainActivity() {
        checkLocationEnabled()
    }

    private fun checkLocationEnabled() {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000
        ).build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = com.google.android.gms.location.LocationServices.getSettingsClient(this@SignupActivity)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            launchMainActivity()
        }

        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@SignupActivity, 1001)
                } catch (sendEx: android.content.IntentSender.SendIntentException) { }
            } else {
                Toast.makeText(this@SignupActivity, "Location services are required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                launchMainActivity()
            } else {
                Toast.makeText(this@SignupActivity, "Please enable location to use the app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchMainActivity() {
        startActivity(Intent(this@SignupActivity, MainActivity::class.java))
        finishAffinity() // Clear activity stack
    }
}