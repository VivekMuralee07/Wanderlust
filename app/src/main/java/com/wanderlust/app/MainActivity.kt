package com.wanderlust.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wanderlust.app.reminders.ReminderScheduler
import com.wanderlust.app.ui.LockScreen
import com.wanderlust.app.ui.WanderlustNavHost
import com.wanderlust.app.ui.theme.WanderlustTheme
import kotlinx.coroutines.launch

/** Files shared into the app from another app (e.g. photos from Google Photos, a ticket PDF from email). */
data class SharedPayload(val uris: List<Uri>, val mimeType: String) {
    val isImages get() = mimeType.startsWith("image/")
}

class MainActivity : FragmentActivity() {

    private val shared = mutableStateOf<SharedPayload?>(null)
    private val openTrip = mutableStateOf<Long?>(null)
    private val locked = mutableStateOf(false)
    private var stoppedAt = 0L

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WanderlustApp).container
        locked.value = container.settings.state.value.appLockEnabled && canLock(this)
        handleIntent(intent)
        // quietly look for a newer release (at most every 12 h; never in debug builds)
        container.appScope.launch { container.updater.checkInBackground() }
        askForNotificationsOnce()
        setContent {
            WanderlustTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    WanderlustNavHost(
                        shared = shared.value, onSharedConsumed = { shared.value = null },
                        openTrip = openTrip.value, onTripOpened = { openTrip.value = null },
                    )
                    if (locked.value) LockScreen(onUnlock = ::authenticate)
                }
            }
        }
        if (locked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val settings = (application as WanderlustApp).container.settings.state.value
        // re-lock after a minute in the background
        if (settings.appLockEnabled && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 60_000 && canLock(this)) {
            locked.value = true
            authenticate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        })
        prompt.authenticate(promptInfo())
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("wanderlust_reminders", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(ReminderScheduler.EXTRA_TRIP, 0L).takeIf { it > 0 }?.let { openTrip.value = it }
        val type = intent.type ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) shared.value = SharedPayload(uris, type)
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelable(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableList(key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key)) ?: emptyList()

    companion object {
        private val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /** Biometrics or a screen lock (PIN/pattern/password) must be set up to use app lock. */
        fun canLock(activity: FragmentActivity): Boolean =
            if (Build.VERSION.SDK_INT >= 30) BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
            else (activity.getSystemService(android.app.KeyguardManager::class.java)).isDeviceSecure

        @Suppress("DEPRECATION")
        fun promptInfo(): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Wanderlust")
            .setSubtitle("Your tickets and travel documents are protected")
            .apply { if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(AUTHENTICATORS) else setDeviceCredentialAllowed(true) }
            .build()
    }
}
