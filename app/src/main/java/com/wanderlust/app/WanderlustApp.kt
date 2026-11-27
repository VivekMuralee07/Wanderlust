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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val db = WanderlustDatabase.create(app)
    val files = FileStore(app)
    val repo = TravelRepository(db.dao(), files)
    val settings = SettingsStore(app)
    val ai = ClaudeService(settings, repo)
    val updater = com.wanderlust.app.update.AppUpdater(app)
    val backups = com.wanderlust.app.backup.BackupManager(app)
    val fx = com.wanderlust.app.expenses.ExchangeRates(app)
    val reminders = com.wanderlust.app.reminders.ReminderScheduler(app)

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
        watchDataForRemindersAndWidget()
    }

    /** Whenever trips, bookings or reminder settings change: reschedule reminders and refresh the widget. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchDataForRemindersAndWidget() {
        val c = container
        c.appScope.launch {
            combine(c.repo.trips, c.repo.allTransports, c.repo.allLodgings, c.settings.state.map { it.remindersEnabled }) { _, _, _, _ -> Unit }
                .debounce(1500)
                .collect {
                    runCatching { c.reminders.reschedule() }
                    com.wanderlust.app.widget.CountdownWidget.refresh(this@WanderlustApp)
                }
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
