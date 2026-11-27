package com.wanderlust.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import com.wanderlust.app.ui.WanderlustNavHost
import com.wanderlust.app.ui.theme.WanderlustTheme

/** Files shared into the app from another app (e.g. photos from Google Photos, a ticket PDF from email). */
data class SharedPayload(val uris: List<Uri>, val mimeType: String) {
    val isImages get() = mimeType.startsWith("image/")
}

class MainActivity : ComponentActivity() {

    private val shared = mutableStateOf<SharedPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        val container = (application as WanderlustApp).container
        // quietly look for a newer release (at most every 12 h; never in debug builds)
        container.appScope.launch { container.updater.checkInBackground() }
        setContent {
            WanderlustTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    WanderlustNavHost(shared = shared.value, onSharedConsumed = { shared.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
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
}
