package app.mygachalist.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mygachalist.MainViewModel
import app.mygachalist.ui.screens.DetailScreen
import app.mygachalist.ui.screens.EntryEditScreen
import app.mygachalist.ui.screens.HomeScreen
import app.mygachalist.ui.screens.MyListScreen
import app.mygachalist.ui.screens.ProfileScreen
import app.mygachalist.ui.screens.SearchScreen
import app.mygachalist.ui.screens.SeasonalScreen
import app.mygachalist.ui.screens.SettingsScreen

private data class TabDef(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabDef("home", "Home", Icons.Filled.Home),
    TabDef("seasonal", "Seasonal", Icons.Filled.DateRange),
    TabDef("search", "Search", Icons.Filled.Search),
    TabDef("mylist", "My List", Icons.AutoMirrored.Filled.List),
    TabDef("profile", "Profile", Icons.Filled.Person),
)

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppRoot(vm: MainViewModel, openGameId: String?, onOpenGameConsumed: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val showTabs = route == null || TABS.any { it.route == route }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Opened from a notification.
    LaunchedEffect(openGameId) {
        if (openGameId != null) {
            nav.navigate("game/$openGameId") { launchSingleTop = true }
            onOpenGameConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Screens without the bottom bar must keep clear of the system navigation buttons themselves.
        val systemBarPadding = if (showTabs) Modifier else Modifier.navigationBarsPadding()
        Box(Modifier.fillMaxSize().padding(padding).then(systemBarPadding)) {
            val openGame = { id: String -> nav.navigate("game/$id") { launchSingleTop = true } }
            val editEntry = { id: String -> nav.navigate("entry/$id") { launchSingleTop = true } }
            val openSettings = { nav.navigate("settings") { launchSingleTop = true } }
            // No transitions: cheaper on a low-end phone and snappier.
            NavHost(
                nav, startDestination = "home",
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
            ) {
                composable("home") {
                    HomeScreen(vm, openGame, { nav.switchTab("seasonal") }, { nav.switchTab("search") }, openSettings)
                }
                composable("seasonal") { SeasonalScreen(vm, openGame) }
                composable("search") { SearchScreen(vm, openGame) }
                composable("mylist") { MyListScreen(vm, openGame, editEntry) { nav.switchTab("search") } }
                composable("profile") { ProfileScreen(vm, openSettings) { nav.switchTab("search") } }
                composable("settings") { SettingsScreen(vm) { nav.popBackStack() } }
                composable("game/{id}") { entry ->
                    DetailScreen(vm, entry.arguments?.getString("id").orEmpty(), { nav.popBackStack() }, editEntry, { id -> nav.navigate("game/$id") })
                }
                composable("entry/{id}") { entry ->
                    EntryEditScreen(vm, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
                }
            }
        }
    }
}
