package dev.mangavault.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mangavault.app.AppContainer
import dev.mangavault.app.ui.account.AccountSheetsHost
import dev.mangavault.app.ui.components.ToastHost
import dev.mangavault.app.ui.details.DetailsScreen
import dev.mangavault.app.ui.history.HistoryScreen
import dev.mangavault.app.ui.home.HomeScreen
import dev.mangavault.app.ui.library.LibraryScreen
import dev.mangavault.app.ui.onboarding.Onboarding
import dev.mangavault.app.ui.reader.ReaderScreen
import dev.mangavault.app.ui.search.SearchScreen
import dev.mangavault.app.ui.settings.LicensesScreen
import dev.mangavault.app.ui.settings.SettingsScreen
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.MvTheme
import dev.mangavault.core.model.MangaSummary

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

/** Space screens leave at the bottom so content clears the tab bar. */
val LocalBottomPadding = staticCompositionLocalOf { 120.dp }

/** Navigation actions shared by every screen. */
class Navigator(val controller: NavHostController, private val container: AppContainer) {
    fun tab(route: String) {
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun details(m: MangaSummary) {
        container.cacheSummary(m)
        controller.navigate("details/${m.id}")
    }

    fun details(id: Long) = controller.navigate("details/$id")

    fun reader(m: MangaSummary, chapterId: Long, page: Int = 1) {
        container.cacheSummary(m)
        controller.navigate("reader/${m.id}/$chapterId?page=$page")
    }

    fun browse(sort: String, genre: String? = null) {
        controller.navigate("search?sort=$sort" + (genre?.let { "&genre=" + android.net.Uri.encode(it) } ?: "")) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
    }

    fun back() {
        controller.popBackStack()
    }

    /** From the reader: return to this manga's details, reusing the screen below when it is the same title. */
    fun backToDetails(mangaId: Long) {
        val prev = controller.previousBackStackEntry
        val same = prev?.destination?.route?.startsWith("details/") == true &&
            prev.arguments?.getLong("id") == mangaId
        controller.popBackStack()
        if (!same) controller.navigate("details/$mangaId")
    }

    fun licenses() = controller.navigate("licenses")

    /** Tapping the current tab from a pushed screen returns to the tab's root. */
    fun popToTab(route: String) {
        while (controller.currentDestination?.route?.let { it == route || it.startsWith("$route?") } != true) {
            if (!controller.popBackStack()) break
        }
    }
}

private val TAB_ROUTES = listOf("home", "search", "library", "history", "settings")

@Composable
fun MangavaultRoot(container: AppContainer) {
    val prefs by container.prefs.flow.collectAsStateWithLifecycle()
    MvTheme(prefs.theme) {
        val colors = Mv.colors
        val view = LocalView.current
        val window = (view.context as? android.app.Activity)?.window
        if (window != null && !view.isInEditMode) {
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = colors.isLight
                controller.isAppearanceLightNavigationBars = colors.isLight
            }
        }
        CompositionLocalProvider(LocalContainer provides container, LocalContentColor provides colors.tp) {
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                if (!prefs.onboarded) {
                    Onboarding(container)
                    AccountSheetsHost(container)
                    ToastHost(container.toasts, bottom = 40.dp, modifier = Modifier.align(Alignment.BottomCenter))
                } else {
                    MainShell(container)
                }
            }
        }
    }
}

@Composable
private fun MainShell(container: AppContainer) {
    val controller = rememberNavController()
    val nav = remember(controller) { Navigator(controller, container) }
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val currentTab = TAB_ROUTES.firstOrNull { route == it || route.startsWith("$it?") }
    var lastTab by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(currentTab) { if (currentTab != null) lastTab = currentTab }
    val tab = currentTab ?: lastTab

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabHeight = 80.dp + navInset
    val showTabs = route.isEmpty() || TAB_ROUTES.any { route == it || route.startsWith("$it?") } || route.startsWith("details/")
    val inReader = route.startsWith("reader/")

    CompositionLocalProvider(LocalBottomPadding provides tabHeight + 24.dp) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = controller,
                startDestination = "home",
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(150)) },
            ) {
                composable("home") { HomeScreen(nav) }
                composable(
                    "search?sort={sort}&genre={genre}",
                    arguments = listOf(
                        navArgument("sort") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { e -> SearchScreen(nav, e.arguments?.getString("sort"), e.arguments?.getString("genre")) }
                composable("library") { LibraryScreen(nav) }
                composable("history") { HistoryScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable("licenses") { LicensesScreen(nav) }
                composable("details/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    DetailsScreen(nav, e.arguments?.getLong("id") ?: 0L)
                }
                composable(
                    "reader/{mangaId}/{chapterId}?page={page}",
                    arguments = listOf(
                        navArgument("mangaId") { type = NavType.LongType },
                        navArgument("chapterId") { type = NavType.LongType },
                        navArgument("page") { type = NavType.IntType; defaultValue = 1 },
                    ),
                ) { e ->
                    val a = e.arguments
                    ReaderScreen(nav, a?.getLong("mangaId") ?: 0L, a?.getLong("chapterId") ?: 0L, a?.getInt("page") ?: 1)
                }
            }
            if (showTabs) {
                TabBar(
                    selected = tab,
                    onSelect = { r ->
                        when {
                            r == currentTab -> Unit
                            r == tab -> nav.popToTab(r)
                            else -> nav.tab(r)
                        }
                    },
                    height = tabHeight,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            ToastHost(
                container.toasts,
                bottom = if (inReader) 150.dp else if (showTabs) tabHeight + 12.dp else 40.dp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            AccountSheetsHost(container)
        }
    }
}

private data class TabSpec(val route: String, val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    TabSpec("home", "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    TabSpec("search", "Search", Icons.Rounded.Search, Icons.Rounded.Search),
    TabSpec("library", "Library", Icons.Rounded.Bookmarks, Icons.Outlined.Bookmarks),
    TabSpec("history", "History", Icons.Rounded.History, Icons.Rounded.History),
    TabSpec("settings", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

/** Android tab bar: surface-raised background, 64×32 pill behind the active icon. */
@Composable
private fun TabBar(selected: String, onSelect: (String) -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val c = Mv.colors
    Row(
        modifier.fillMaxWidth().height(height).background(c.sr)
            // Swallow touches so content under the bar never receives them.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TABS.forEach { t ->
            val on = t.route == selected
            val pill by animateColorAsState(if (on) c.acs else Color.Transparent, tween(200), label = "pill")
            Column(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(t.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(pill), contentAlignment = Alignment.Center) {
                    Icon(if (on) t.on else t.off, contentDescription = t.label, tint = if (on) c.tp else c.ts, modifier = Modifier.width(24.dp).height(24.dp))
                }
                Text(
                    t.label, fontSize = 12.sp, lineHeight = 16.sp, color = if (on) c.tp else c.ts,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
