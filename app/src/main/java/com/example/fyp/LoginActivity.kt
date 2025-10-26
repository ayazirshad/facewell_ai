package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class LoginActivity : AppCompatActivity() {

    // Views
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var googleCard: MaterialCardView
    private lateinit var llSignUp: LinearLayout

    // Optional loaders (if present in XML)
    private var loadingOverlay: View? = null
    private var progress: CircularProgressIndicator? = null

    // Firebase
    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Google (legacy GSI; works but deprecated)
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var googleLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        bindViews()
        initGoogleSignIn()
        setupLiveValidation()

        // Email/password login
        btnLogin.setOnClickListener { onEmailPasswordLoginClick() }

        // Google login
        googleCard.setOnClickListener { startGoogleLogin() }

        // Go to Sign Up
        llSignUp.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        // If already logged in, route by stage
        auth.currentUser?.let {
            showLoading(true)
            goNextByStage() // <- stage-based routing
        }
    }

    private fun bindViews() {
        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        googleCard = findViewById(R.id.cardGoogle)
        llSignUp = findViewById(R.id.llSignUp)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        progress = findViewById(R.id.progress)
    }

    // --- Email/Password login ---
    private fun onEmailPasswordLoginClick() {
        clearErrors()
        showLoading(true)

        val email = etEmail.text?.toString()?.trim().orEmpty()
        val pass = etPassword.text?.toString().orEmpty()

        if (email.isEmpty() || pass.isEmpty()) {
            if (email.isEmpty()) tilEmail.error = "Email is required"
            if (pass.isEmpty()) tilPassword.error = "Password is required"
            Toast.makeText(this, "Please fill both fields.", Toast.LENGTH_SHORT).show()
            showLoading(false)
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Please enter a valid email"
            showLoading(false)
            return
        }

        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Stage-based routing
                    goNextByStage()
                } else {
                    val msg = task.exception?.localizedMessage ?: "Login failed. Try again."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    showLoading(false)
                }
            }
    }

    // --- Google login flow (legacy GSI) ---
    private fun initGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            // provided by google-services.json
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleClient = GoogleSignIn.getClient(this, gso)

        googleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            try {
                val accountTask = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = accountTask.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken.isNullOrEmpty()) {
                    Toast.makeText(this, "Google sign-in failed: missing token.", Toast.LENGTH_LONG).show()
                    showLoading(false)
                    return@registerForActivityResult
                }
                firebaseLoginWithGoogle(idToken)
            } catch (e: ApiException) {
                Toast.makeText(this, "Google sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
                showLoading(false)
            }
        }
    }

    private fun startGoogleLogin() {
        showLoading(true)
        googleLauncher.launch(googleClient.signInIntent)
    }

    private fun firebaseLoginWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Ensure user doc exists; if new, create with stage 0
                    val uid = auth.currentUser?.uid
                    if (uid != null) {
                        val base = mapOf(
                            "email" to (auth.currentUser?.email ?: ""),
                            "provider" to "google",
                            "createdAt" to System.currentTimeMillis()
                        )
                        db.collection("users").document(uid)
                            .set(base, SetOptions.merge())
                    }
                    // Route by stage
                    goNextByStage()
                } else {
                    val raw = task.exception?.localizedMessage ?: ""
                    val message =
                        if (raw.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true)) {
                            "Firebase config missing on this build. Add SHA-1 & SHA-256 in Firebase > Project settings > Android app, then download a new google-services.json and rebuild."
                        } else "Google sign-in failed. ${raw.ifBlank { "Try again." }}"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    showLoading(false)
                }
            }
    }

    // --- Stage-based routing ---
    private fun goNextByStage() {
        val uid = auth.currentUser?.uid
        if (uid == null) { showLoading(false); return }

        db.collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val stage = if (doc.exists()) (doc.getLong("stage") ?: 0L).toInt() else 0

                when {
                    stage <= 0 -> {
                        startActivity(Intent(this, CreateProfileActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    stage == 1 -> {
                        val firstName = doc.getString("firstName") ?: ""
                        startActivity(Intent(this, SelectGenderActivity::class.java).apply {
                            putExtra("firstName", firstName)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    else -> {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                finish()
            }
            .addOnFailureListener {
                // If read fails, be safe and send to CreateProfile
                startActivity(Intent(this, CreateProfileActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
    }


    // --- Helpers ---
    private fun setupLiveValidation() {
        etEmail.afterTextChanged { tilEmail.error = null }
        etPassword.afterTextChanged { tilPassword.error = null }
    }

    private fun clearErrors() {
        tilEmail.error = null
        tilPassword.error = null
    }

    private fun showLoading(loading: Boolean) {
        loadingOverlay?.visibility = if (loading) View.VISIBLE else View.GONE
        progress?.visibility = if (loading) View.VISIBLE else View.GONE

        btnLogin.text = if (loading) "Signing in..." else "Login"
        btnLogin.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
        googleCard.isEnabled = !loading
        llSignUp.isEnabled = !loading
    }

    // Small extension to reduce boilerplate
    private fun TextInputEditText.afterTextChanged(after: (Editable?) -> Unit) {
        this.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { after(s) }
        })
    }
}
