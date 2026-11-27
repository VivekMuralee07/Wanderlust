package com.wanderlust.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wanderlust.app.SharedPayload
import com.wanderlust.app.ui.assistant.AssistantScreen
import com.wanderlust.app.ui.editors.ActivityEditor
import com.wanderlust.app.ui.editors.JournalEditor
import com.wanderlust.app.ui.editors.LodgingEditor
import com.wanderlust.app.ui.editors.PlaceEditor
import com.wanderlust.app.ui.editors.TransportEditor
import com.wanderlust.app.ui.editors.TripEditor
import com.wanderlust.app.ui.home.HomeScreen
import com.wanderlust.app.ui.importing.ScanTicketScreen
import com.wanderlust.app.ui.importing.ShareImportScreen
import com.wanderlust.app.ui.itinerary.VisualItineraryScreen
import com.wanderlust.app.ui.journal.JournalSummaryScreen
import com.wanderlust.app.ui.settings.SettingsScreen
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.trips.TripDetailScreen
import com.wanderlust.app.ui.trips.TripsScreen
import com.wanderlust.app.ui.world.WorldScreen

object Routes {
    const val HOME = "home"
    const val TRIPS = "trips"
    const val ASSISTANT = "assistant"
    const val WORLD = "world"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val SHARE = "share"
    fun trip(id: Long) = "trip/$id"
    fun tripEdit(id: Long) = "tripEdit/$id"
    fun transport(tripId: Long, id: Long = 0) = "transport/$tripId/$id"
    fun lodging(tripId: Long, id: Long = 0) = "lodging/$tripId/$id"
    fun place(tripId: Long, id: Long = 0) = "place/$tripId/$id"
    fun activity(tripId: Long, id: Long = 0, date: String = "") = "activity/$tripId/$id?date=$date"
    fun journal(tripId: Long, id: Long = 0) = "journal/$tripId/$id"
    fun itinerary(tripId: Long) = "itinerary/$tripId"
    fun summary(tripId: Long) = "summary/$tripId"
    fun scan(tripId: Long = 0) = "$SCAN?tripId=$tripId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.TRIPS, "Trips", Icons.AutoMirrored.Filled.List),
    Tab(Routes.ASSISTANT, "Ask AI", Icons.Default.AutoAwesome),
    Tab(Routes.WORLD, "World", Icons.Default.Public),
)

@Composable
fun WanderlustNavHost(shared: SharedPayload?, onSharedConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = tabs.any { it.route == current }

    LaunchedEffect(shared) {
        if (shared != null) nav.navigate(Routes.SHARE)
    }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Color(0xFF0F1533)) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = if (showBar) pad.calculateBottomPadding() else 0.dp)) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.TRIPS) { TripsScreen(nav) }
                composable(Routes.ASSISTANT) { AssistantScreen() }
                composable(Routes.WORLD) { WorldScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(
                    "${Routes.SCAN}?tripId={tripId}",
                    arguments = listOf(navArgument("tripId") { type = NavType.LongType; defaultValue = 0L }),
                ) { ScanTicketScreen(nav, it.arguments!!.getLong("tripId")) }
                composable(Routes.SHARE) { ShareImportScreen(nav, shared, onSharedConsumed) }

                composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    TripDetailScreen(nav, it.arguments!!.getLong("id"))
                }
                composable("tripEdit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    TripEditor(nav, it.arguments!!.getLong("id"))
                }
                editor("transport", nav) { n, t, i -> TransportEditor(n, t, i) }
                editor("lodging", nav) { n, t, i -> LodgingEditor(n, t, i) }
                editor("place", nav) { n, t, i -> PlaceEditor(n, t, i) }
                editor("journal", nav) { n, t, i -> JournalEditor(n, t, i) }
                composable(
                    "activity/{tripId}/{id}?date={date}",
                    arguments = listOf(
                        navArgument("tripId") { type = NavType.LongType },
                        navArgument("id") { type = NavType.LongType },
                        navArgument("date") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    ActivityEditor(nav, it.arguments!!.getLong("tripId"), it.arguments!!.getLong("id"), it.arguments!!.getString("date") ?: "")
                }
                composable("itinerary/{tripId}", arguments = listOf(navArgument("tripId") { type = NavType.LongType })) {
                    VisualItineraryScreen(nav, it.arguments!!.getLong("tripId"))
                }
                composable("summary/{tripId}", arguments = listOf(navArgument("tripId") { type = NavType.LongType })) {
                    JournalSummaryScreen(nav, it.arguments!!.getLong("tripId"))
                }
            }
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.editor(
    name: String,
    nav: NavHostController,
    content: @Composable (NavHostController, Long, Long) -> Unit,
) {
    composable(
        "$name/{tripId}/{id}",
        arguments = listOf(navArgument("tripId") { type = NavType.LongType }, navArgument("id") { type = NavType.LongType }),
    ) { entry ->
        content(nav, entry.arguments!!.getLong("tripId"), entry.arguments!!.getLong("id"))
    }
}

fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
