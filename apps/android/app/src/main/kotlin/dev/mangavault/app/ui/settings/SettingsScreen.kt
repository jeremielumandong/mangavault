package dev.mangavault.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import dev.mangavault.app.BuildConfig
import dev.mangavault.app.SyncStatus
import dev.mangavault.app.ui.LocalBottomPadding
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.components.Card
import dev.mangavault.app.ui.components.GroupLabel
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.ScreenTitle
import dev.mangavault.app.ui.components.Segmented
import dev.mangavault.app.ui.components.SettingRow
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.model.CatalogStats
import dev.mangavault.core.util.Format
import dev.mangavault.core.util.ReaderModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val READER_BACKGROUNDS = linkedMapOf(
    "Black" to Color(0xFF000000),
    "Dark gray" to Color(0xFF1C1C20),
    "White" to Color(0xFFFFFFFF),
    "Sepia" to Color(0xFFF1E6CF),
)
val READER_FITS = listOf("Width", "Height", "Screen", "Original")
private val MODES = listOf(ReaderModes.AUTO, ReaderModes.VERTICAL, ReaderModes.RTL, ReaderModes.LTR, ReaderModes.PAGED_VERTICAL)

private fun <T> List<T>.after(cur: T): T = this[(indexOf(cur) + 1).mod(size)]

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", n / (1L shl 30).toDouble())
    else -> "${n / (1L shl 20)} MB"
}

@Composable
fun SettingsScreen(nav: Navigator) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val prefs by c.prefs.flow.collectAsStateWithLifecycle()
    val session by c.session.flow.collectAsStateWithLifecycle()
    val sync by c.syncStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { SingletonImageLoader.get(ctx).diskCache?.size ?: 0L }
    }
    var stats by remember { mutableStateOf<CatalogStats?>(null) }
    var statsFailed by remember { mutableStateOf(false) }
    fun loadStats() = scope.launch {
        statsFailed = false
        runCatching { c.api.stats() }.onSuccess { stats = it }.onFailure { statsFailed = stats == null }
    }
    LaunchedEffect(Unit) { loadStats() }
    val r = prefs.reader
    fun reader(f: (dev.mangavault.core.store.ReaderPrefs) -> dev.mangavault.core.store.ReaderPrefs) =
        c.prefs.update { it.copy(reader = f(it.reader)) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomPadding.current + 24.dp),
    ) {
        ScreenTitle("Settings", Modifier.padding(bottom = 8.dp))
        // Account
        val s = session
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = s?.user?.name ?: s?.user?.email
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (s != null) Color(0xFFB3463A) else Mv.colors.sr),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (s != null) (name?.firstOrNull()?.uppercase() ?: "M") else "?",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (s != null) name ?: "Signed in" else "Not signed in", fontSize = 15.sp, fontWeight = SemiBold)
                val sub = if (s == null) "Favorites and progress stay on this phone" else {
                    val provider = when (s.provider) { "google" -> "Google"; "apple" -> "Apple ID"; "open" -> "Personal"; else -> "Dev account" }
                    val when_ = when (val st = sync) {
                        is SyncStatus.Done -> {
                            val min = ((System.currentTimeMillis() - st.at) / 60_000).toInt()
                            if (min < 1) "synced just now" else "synced $min min ago"
                        }
                        is SyncStatus.Syncing -> "syncing…"
                        is SyncStatus.Failed -> if (st.offline) "waiting to sync" else "sync failed"
                        SyncStatus.Idle -> "sync pending"
                    }
                    "$provider · $when_"
                }
                Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = Mv.colors.ts)
            }
            if (c.openAccount) {
                // No account to switch in open mode.
            } else if (s != null) {
                PillButton("Sign out", {
                    c.signOut()
                    c.toasts.show("Signed out · local data kept")
                }, background = Mv.colors.sr, weight = SemiBold, horizontal = 14.dp)
            } else {
                PillButton(
                    "Sign in", { c.sheets.signIn.value = "default" },
                    background = Mv.colors.ac, textColor = Color.White, weight = SemiBold, horizontal = 14.dp,
                )
            }
        }

        GroupLabel("Appearance")
        val themes = listOf("dark" to "Dark", "light" to "Light", "oled" to "OLED Black")
        Segmented(
            themes.map { it.second }, (themes.firstOrNull { it.first == prefs.theme } ?: themes[0]).second,
            { label -> c.prefs.update { p -> p.copy(theme = themes.first { it.second == label }.first) } },
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(), radius = 12.dp,
        )

        GroupLabel("Library")
        Card {
            SettingRow(Icons.Rounded.GridView, "Grid columns", { c.prefs.update { it.copy(libCols = listOf(2, 3, 4).after(it.libCols)) } }, true, value = "${prefs.libCols}")
            SettingRow(
                Icons.AutoMirrored.Rounded.ViewList, "Default view",
                { c.prefs.update { it.copy(libView = if (it.libView == "grid") "list" else "grid") } }, false,
                value = if (prefs.libView == "grid") "Grid" else "List",
            )
        }

        GroupLabel("Reader defaults")
        Card {
            SettingRow(Icons.Rounded.AutoStories, "Reading mode", { reader { it.copy(defaultMode = MODES.after(it.defaultMode)) } }, true, value = ReaderModes.name(r.defaultMode))
            SettingRow(Icons.Rounded.Contrast, "Background", { reader { it.copy(background = READER_BACKGROUNDS.keys.toList().after(it.background)) } }, false, value = r.background)
            SettingRow(Icons.Rounded.FitScreen, "Fit", { reader { it.copy(fit = READER_FITS.after(it.fit)) } }, false, value = r.fit)
            SettingRow(Icons.Rounded.LightMode, "Keep screen on", { reader { it.copy(keepOn = !it.keepOn) } }, false, toggle = r.keepOn)
            SettingRow(Icons.Rounded.Pin, "Show page number", { reader { it.copy(showNum = !it.showNum) } }, false, toggle = r.showNum)
            SettingRow(Icons.Rounded.SwapHoriz, "Invert tap zones", { reader { it.copy(invert = !it.invert) } }, false, toggle = r.invert)
        }

        GroupLabel("Data")
        Card {
            SettingRow(Icons.Rounded.PhotoLibrary, "Image cache", {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val loader = SingletonImageLoader.get(ctx)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }
                    cacheBytes = 0
                    c.toasts.show("Cache cleared")
                }
            }, true, value = if (cacheBytes < 0) "…" else bytes(cacheBytes))
            SettingRow(Icons.Rounded.Download, "Prefetch pages", { c.prefs.update { it.copy(prefetch = listOf(3, 5, 8, 10).after(it.prefetch)) } }, false, value = "${prefs.prefetch}")
            SettingRow(Icons.Rounded.SignalCellularAlt, "Data saver on cellular", { c.prefs.update { it.copy(dataSaver = !it.dataSaver) } }, false, toggle = prefs.dataSaver)
        }

        GroupLabel("Server catalog")
        Card {
            val st = stats
            val pending = if (statsFailed) "Unavailable" else "…"
            SettingRow(Icons.Rounded.CollectionsBookmark, "Series", { loadStats() }, true, value = st?.let { Format.count(it.series) } ?: pending)
            SettingRow(Icons.AutoMirrored.Rounded.MenuBook, "Chapters", { loadStats() }, false, value = st?.let { Format.count(it.chapters) } ?: pending)
            SettingRow(
                Icons.Rounded.Update, "Last chapter update", { loadStats() }, false,
                value = st?.latestUpdateAt?.let { Format.date(it) } ?: if (st != null) "None yet" else pending,
            )
        }

        GroupLabel("About")
        Card {
            SettingRow(Icons.AutoMirrored.Rounded.Chat, "Send feedback", {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "Mangavault ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) feedback")
                }
                runCatching { ctx.startActivity(intent) }.onFailure { c.toasts.show("No email app found") }
            }, true, chevron = true)
            SettingRow(Icons.Rounded.Description, "Open-source licenses", nav::licenses, false, chevron = true)
            SettingRow(Icons.Rounded.Info, "Version", {}, false, value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        Text(
            "Mangavault ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 12.sp, color = Mv.colors.ts,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}

private val LIBRARIES = listOf(
    "AndroidX (Compose, Activity, Lifecycle, Navigation, Credentials)" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "OkHttp, Okio" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Google Identity Services (googleid)" to "Apache License 2.0",
    "Material Symbols" to "Apache License 2.0",
    "Dela Gothic One, Zen Kaku Gothic New" to "SIL Open Font License 1.1",
)

@Composable
fun LicensesScreen(nav: Navigator) {
    val ctx = LocalContext.current
    var ofl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        ofl = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("licenses/OFL.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, nav::back, contentDescription = "Back")
            Text("Open-source licenses", fontSize = 17.sp, fontWeight = SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = LocalBottomPadding.current + 24.dp),
        ) {
            LIBRARIES.forEach { (name, license) ->
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, fontSize = 15.sp, fontWeight = SemiBold)
                    Text(license, fontSize = 13.sp, color = Mv.colors.ts)
                }
            }
            if (ofl.isNotEmpty()) {
                Text("SIL Open Font License", fontSize = 15.sp, fontWeight = SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Text(ofl, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = Mv.colors.ts)
            }
        }
    }
}
