package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.ImageButton
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

class SignupActivity : AppCompatActivity() {

    // Views
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPhone: TextInputLayout
    private lateinit var tilPass: TextInputLayout
    private lateinit var tilConfirm: TextInputLayout

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var cardGoogle: MaterialCardView

    // Optional loading UI (support both overlay and simple spinner)
    private var loadingOverlay: View? = null
    private var progress: CircularProgressIndicator? = null

    // Firebase
    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Google
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var googleLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // Back to Login
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Firebase
        auth = FirebaseAuth.getInstance()

        bindViews()
        initGoogleSignIn()
        setupInputConstraints()
        setupLiveValidation()

        btnSignUp.setOnClickListener { onEmailPasswordSignUpClick() }
        cardGoogle.setOnClickListener { startGoogleSignup() }
    }

    private fun bindViews() {
        tilEmail   = findViewById(R.id.tilSUEmail)
        tilPhone   = findViewById(R.id.tilSUPhone)
        tilPass    = findViewById(R.id.tilSUPass)
        tilConfirm = findViewById(R.id.tilSUConfirm)

        etEmail   = findViewById(R.id.etSUEmail)
        etPhone   = findViewById(R.id.etSUPhone)
        etPass    = findViewById(R.id.etSUPass)
        etConfirm = findViewById(R.id.etSUConfirm)

        btnSignUp   = findViewById(R.id.btnSignUp)
        cardGoogle  = findViewById(R.id.cardGoogleSU)

        // Try to bind both loader styles gracefully
        loadingOverlay = findViewById(R.id.loadingOverlay) // if you added the full overlay
        progress       = findViewById(R.id.progress)        // if you kept a simple spinner
    }

    // --- Google Sign-In setup ---
    private fun initGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            // default_web_client_id is auto-generated from google-services.json
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
                firebaseAuthWithGoogle(idToken)
            } catch (e: ApiException) {
                Toast.makeText(this, "Google sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
                showLoading(false)
            }
        }
    }

    private fun startGoogleSignup() {
        showLoading(true)
        googleLauncher.launch(googleClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val result = task.result
                    val isNew = result?.additionalUserInfo?.isNewUser == true
                    val user = auth.currentUser
                    val uid = user?.uid

                    if (uid != null) {
                        val payload = mutableMapOf<String, Any>(
                            "email" to (user?.email ?: ""),
                            "provider" to "google",
                            "createdAt" to System.currentTimeMillis(),
                            "stage" to 0
                        )
                        user?.displayName?.let { payload["name"] = it }
                        user?.photoUrl?.toString()?.let { payload["photoUrl"] = it }

                        // Save/merge profile (idempotent)
                        db.collection("users").document(uid)
                            .set(payload, com.google.firebase.firestore.SetOptions.merge())
                            .addOnSuccessListener { /* ok */ }
                            .addOnFailureListener { /* optional log */ }
                    }

                    Toast.makeText(this,
                        if (isNew) "Account created with Google." else "Signed in with Google.",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Navigate to Login screen (as per your flow)
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
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
    // --- End Google Sign-In ---

    // --- Email/Password sign-up logic (existing) ---
    private fun onEmailPasswordSignUpClick() {
        clearAllErrors()
        showLoading(true)

        val email    = etEmail.text?.toString()?.trim().orEmpty()
        val phoneRaw = etPhone.text?.toString()?.trim().orEmpty()
        val pass     = etPass.text?.toString().orEmpty()
        val confirm  = etConfirm.text?.toString().orEmpty()

        // Required fields
        if (email.isEmpty() || phoneRaw.isEmpty() || pass.isEmpty() || confirm.isEmpty()) {
            Toast.makeText(this, "Please fill all fields. All 4 fields are required.", Toast.LENGTH_SHORT).show()
            if (email.isEmpty())    tilEmail.error   = "Email is required"
            if (phoneRaw.isEmpty()) tilPhone.error   = "Phone number is required"
            if (pass.isEmpty())     tilPass.error    = "Password is required"
            if (confirm.isEmpty())  tilConfirm.error = "Confirm your password"
            showLoading(false)
            return
        }

        // Email validation
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Please enter a valid email address"
            showLoading(false)
            return
        }

        // Phone validation: 11 digits, optional hyphen after 4th digit (e.g., 0321-2345754)
        val phoneOk = phoneRaw.matches(Regex("^0\\d{3}-?\\d{7}\$"))
        if (!phoneOk) {
            tilPhone.error = "Phone must be 11 digits (e.g., 0321-2345754)"
            showLoading(false)
            return
        }

        // Confirm password check
        if (!validateConfirmPassword(showError = true)) {
            showLoading(false)
            return
        }

        // Normalize phone (digits only) and build payload
        val phoneDigits = phoneRaw.filter { it.isDigit() }
        val payload = mapOf(
            "email" to email,
            "phone" to phoneDigits,
            "provider" to "password",
            "createdAt" to System.currentTimeMillis(),
            "stage" to 0
        )

        // === Firebase Auth: create user ===
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid

                    // Optional: save phone in Firestore
                    if (uid != null) {
                        db.collection("users").document(uid)
                            .set(payload)
                            .addOnSuccessListener { /* profile saved */ }
                            .addOnFailureListener { /* you can log this if needed */ }
                    }

                    Toast.makeText(this, "Account created successfully.", Toast.LENGTH_SHORT).show()

                    // Navigate to Login and finish this screen
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
                } else {
                    val raw = task.exception?.localizedMessage ?: ""
                    val message =
                        if (raw.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true)) {
                            "Firebase config missing on this build. Add SHA-1 & SHA-256 in Firebase > Project settings > Android app, then download a new google-services.json and rebuild."
                        } else when (val e = task.exception) {
                            is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                                "This email is already registered."
                            is com.google.firebase.auth.FirebaseAuthWeakPasswordException ->
                                "Password is too weak. ${e.reason ?: ""}".trim()
                            else -> "Sign up failed. ${raw.ifBlank { "Try again." }}"
                        }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    showLoading(false)
                }
            }
    }
    // --- End Email/Password sign-up ---

    private fun setupInputConstraints() {
        // Up to 12 chars to allow optional hyphen: 0321-2345754
        etPhone.filters = arrayOf(InputFilter.LengthFilter(12))
    }

    private fun setupLiveValidation() {
        etEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { tilEmail.error = null }
        })

        etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { tilPhone.error = null }
        })

        etPass.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateConfirmPassword(showError = false)
                tilPass.error = null
            }
        })

        etConfirm.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateConfirmPassword(showError = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun validateConfirmPassword(showError: Boolean): Boolean {
        val pass = etPass.text?.toString().orEmpty()
        val confirm = etConfirm.text?.toString().orEmpty()
        val matches = pass == confirm
        if (showError) {
            tilConfirm.error = if (confirm.isNotEmpty() && !matches) "Passwords do not match" else null
        }
        return pass.isNotEmpty() && confirm.isNotEmpty() && matches
    }

    private fun clearAllErrors() {
        tilEmail.error = null
        tilPhone.error = null
        tilPass.error = null
        tilConfirm.error = null
    }

    private fun showLoading(loading: Boolean) {
        // Prefer overlay if present; else fall back to a small spinner if you kept it
        loadingOverlay?.visibility = if (loading) View.VISIBLE else View.GONE
        progress?.visibility = if (loading) View.VISIBLE else View.GONE

        btnSignUp.text = if (loading) "Creating..." else "Sign Up"

        // Disable inputs while loading
        btnSignUp.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPhone.isEnabled = !loading
        etPass.isEnabled = !loading
        etConfirm.isEnabled = !loading
        cardGoogle.isEnabled = !loading
    }
}
