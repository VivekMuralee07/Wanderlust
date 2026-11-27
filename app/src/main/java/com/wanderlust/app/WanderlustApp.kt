package com.wanderlust.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.wanderlust.app.ai.ClaudeService
import com.wanderlust.app.data.FileStore
import com.wanderlust.app.data.SettingsStore
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.WanderlustDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val db = WanderlustDatabase.create(app)
    val files = FileStore(app)
    val repo = TravelRepository(db.dao(), files)
    val settings = SettingsStore(app)
    val ai = ClaudeService(settings, repo)

    /** Long-lived scope for writes that must finish even if the user leaves the screen. */
    val appScope = CoroutineScope(SupervisorJob())
}

class WanderlustApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // osmdroid (OpenStreetMap) tile policy requires an identifying user agent
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
