package dev.mangavault.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangavault.app.SyncStatus
import dev.mangavault.app.ui.LocalBottomPadding
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.components.Banner
import dev.mangavault.app.ui.components.Cover
import dev.mangavault.app.ui.components.CoverTitle
import dev.mangavault.app.ui.components.Dot
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.ListSheet
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.ProgressLine
import dev.mangavault.app.ui.components.ScreenTitle
import dev.mangavault.app.ui.components.Segmented
import dev.mangavault.app.ui.components.SheetItem
import dev.mangavault.app.ui.components.SkeletonBar
import dev.mangavault.app.ui.components.StateMessage
import dev.mangavault.app.ui.components.shimmer
import dev.mangavault.app.ui.details.NewCategorySheet
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.LibraryEntry
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.store.isoAfter
import dev.mangavault.core.util.Format
import kotlinx.coroutines.launch

data class LibItem(
    val entry: LibraryEntry,
    val manga: MangaSummary,
    val unread: Int,
    val hasNew: Boolean,
    val progress: Float,
    val listSub: String,
)

fun libraryItems(d: UserData, signedIn: Boolean): List<LibItem> = d.favorites.mapNotNull { e ->
    val m = d.manga[e.mangaId] ?: return@mapNotNull null
    val p = d.progress[e.mangaId]
    val unread = UserOps.unread(d, e.mangaId, signedIn)
    val latest = m.latestChapter
    val done = p != null && p.completed && latest != null && (p.chapterNumber ?: 0.0) >= latest
    val pn = p?.chapterNumber
    val frac = when {
        done -> 1f
        pn != null && latest != null && latest > 0 -> (pn / latest).toFloat()
        else -> 0f
    }
    val total = latest?.let { Format.plural(kotlin.math.floor(it).toInt(), "chapter") } ?: ""
    val sub = when {
        done -> "Completed · $total"
        p != null -> "${Format.chapter(p.chapterNumber)}" + (latest?.let { " of ${Format.number(it)}" } ?: "")
        else -> "Not started" + if (total.isNotEmpty()) " · $total" else ""
    }
    val since = p?.updatedAt ?: e.addedAt
    LibItem(e, m, unread, unread > 0 && isoAfter(m.updatedAt, since), frac, sub)
}

private val SORT_OPTIONS = listOf("Last read", "Last updated", "Title", "Date added")

@Composable
fun LibraryScreen(nav: Navigator) {
    val c = LocalContainer.current
    val data by c.user.flow.collectAsStateWithLifecycle()
    val prefs by c.prefs.flow.collectAsStateWithLifecycle()
    val session by c.session.flow.collectAsStateWithLifecycle()
    val sync by c.syncStatus.collectAsStateWithLifecycle()
    val online by c.network.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedCat by rememberSaveable { mutableStateOf("All") }
    var sortSheet by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    val signedIn = session != null

    LaunchedEffect(signedIn) { if (signedIn) c.requestSync(0) }

    val all = libraryItems(data, signedIn)
    val cats = listOf("All") + (data.categories + all.mapNotNull { it.entry.category }).distinct()
    val cat = if (selectedCat in cats) selectedCat else "All"
    var items = all.filter { cat == "All" || it.entry.category == cat }
    if (prefs.libUnread) items = items.filter { it.unread > 0 }
    items = when (prefs.libSort) {
        "Title" -> items.sortedBy { it.manga.title.lowercase() }
        "Last updated" -> items.sortedByDescending { it.manga.updatedAt.orEmpty() }
        "Date added" -> items.sortedByDescending { it.entry.addedAt }
        else -> items.sortedByDescending { data.history[it.manga.id]?.readAt ?: data.progress[it.manga.id]?.updatedAt ?: "" }
    }

    val offline = signedIn && (!online || (sync as? SyncStatus.Failed)?.offline == true)
    val syncError = signedIn && sync is SyncStatus.Failed && !offline
    val loading = signedIn && all.isEmpty() && (sync is SyncStatus.Syncing || sync is SyncStatus.Idle) && data.cursors.lastSyncAt == null
    val bottom = LocalBottomPadding.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (offline) Banner(Icons.Rounded.CloudOff, "Offline. Showing your saved library; changes sync when you're back.")
        ScreenTitle("Library") {
            val (icon, label) = when {
                offline -> Icons.Rounded.CloudOff to "Waiting to sync"
                !signedIn -> Icons.Rounded.PhoneAndroid to "On this device"
                sync is SyncStatus.Syncing -> Icons.Rounded.Sync to "Syncing…"
                sync is SyncStatus.Failed -> Icons.Rounded.SyncProblem to "Sync failed · retry"
                else -> Icons.Rounded.CloudDone to syncedLabel((sync as? SyncStatus.Done)?.at)
            }
            Row(
                Modifier.padding(bottom = 6.dp, end = 8.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    if (!signedIn) c.sheets.signIn.value = "default" else scope.launch { c.syncNow() }
                }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MvIcon(icon, 16.dp, Mv.colors.ts)
                Text(label, fontSize = 12.sp, color = Mv.colors.ts)
            }
        }
        Column {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cats.forEach { name ->
                    val on = cat == name
                    val count = if (name == "All") all.size else all.count { it.entry.category == name }
                    Column(Modifier.clickable { selectedCat = name }) {
                        Row(Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name, fontSize = 15.sp, fontWeight = SemiBold, color = if (on) Mv.colors.tp else Mv.colors.ts)
                            Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Mv.colors.ts)
                        }
                        Box(Modifier.height(2.dp).fillMaxWidthOf(on))
                    }
                }
                IconBtn(Icons.Rounded.Add, { newCat = true }, size = 32.dp, iconSize = 20.dp, tint = Mv.colors.ts, contentDescription = "New category")
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mv.colors.ln))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(prefs.libSort, { sortSheet = true }, icon = Icons.AutoMirrored.Rounded.Sort, height = 32.dp)
            if (prefs.libUnread) {
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Mv.colors.acs).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Unread", fontSize = 13.sp, color = Mv.colors.ac, fontWeight = FontWeight.Medium)
                }
            }
            Box(Modifier.weight(1f))
            if (prefs.libView == "grid") {
                Segmented(
                    listOf("2", "3", "4"), prefs.libCols.toString(), { v -> c.prefs.update { it.copy(libCols = v.toInt()) } },
                    Modifier.width(102.dp), height = 28.dp, radius = 16.dp, padding = 2.dp,
                )
            }
            IconBtn(
                if (prefs.libView == "grid") Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                { c.prefs.update { it.copy(libView = if (it.libView == "grid") "list" else "grid") } },
                size = 32.dp, iconSize = 20.dp, background = Mv.colors.sf, shape = RoundedCornerShape(16.dp), contentDescription = "Toggle view",
                modifier = Modifier.width(44.dp),
            )
        }

        when {
            loading -> LibrarySkeleton()
            syncError && all.isEmpty() -> StateMessage(
                "Couldn't sync your library", "The Mangavault server didn't answer. Your favorites on this phone are untouched.",
                icon = Icons.Rounded.SyncProblem, action = "Retry", onAction = { scope.launch { c.syncNow() } }, top = 90.dp,
            )
            items.isEmpty() -> StateMessage(
                if (all.isEmpty()) "Your library is empty" else "Nothing here yet",
                "Tap the heart on any title and it lands here with new-chapter badges.",
                icon = Icons.Rounded.Favorite, action = "Browse popular titles", onAction = { nav.browse("popular") }, top = 80.dp,
            )
            prefs.libView == "list" -> LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                items(items, key = { it.manga.id }) { li -> ListRow(li) { nav.details(li.manga) } }
            }
            else -> {
                val titleSize = when (prefs.libCols) { 4 -> 11; 2 -> 15; else -> 13 }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(prefs.libCols.coerceIn(2, 4)),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = { it.manga.id }) { li ->
                        Column(Modifier.clickable { nav.details(li.manga) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Cover(li.manga.coverUrl, li.manga.coverHeaders, Modifier.fillMaxWidth().aspectRatio(2f / 3f)) { GridBadges(li) }
                            CoverTitle(li.manga.title, titleSize)
                        }
                    }
                }
            }
        }
    }

    if (sortSheet) {
        ListSheet(
            "Sort and filter", { sortSheet = false },
            SORT_OPTIONS.map { o ->
                SheetItem(Icons.AutoMirrored.Rounded.Sort, o, { c.prefs.update { it.copy(libSort = o) }; sortSheet = false }, checked = prefs.libSort == o)
            } + SheetItem(Icons.Rounded.MarkEmailUnread, "Unread only", { c.prefs.update { it.copy(libUnread = !it.libUnread) } }, checked = prefs.libUnread),
        )
    }
    if (newCat) {
        NewCategorySheet("Categories sync with your account.", { newCat = false }) { name ->
            newCat = false
            c.user.update { UserOps.addCategory(it, name) }
            selectedCat = name
            c.toasts.show("Added “$name”")
        }
    }
}

@Composable
private fun Modifier.fillMaxWidthOf(on: Boolean): Modifier =
    this.fillMaxWidth().background(if (on) Mv.colors.ac else Color.Transparent)

private fun syncedLabel(at: Long?): String {
    if (at == null) return "Synced"
    val min = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return if (min < 1) "Synced just now" else "Synced $min min ago"
}

@Composable
private fun BoxScope.GridBadges(li: LibItem) {
    if (li.unread > 0) {
        Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text("${li.unread}", color = Color.White, fontSize = 11.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold) }
    }
    if (li.hasNew) Dot(Modifier.align(Alignment.TopEnd).padding(5.dp))
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(li.progress.coerceIn(0f, 1f)).height(3.dp).background(Mv.colors.ac))
    }
}

@Composable
private fun ListRow(li: LibItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Cover(li.manga.coverUrl, li.manga.coverHeaders, Modifier.width(52.dp).height(78.dp), radius = 6.dp)
            if (li.hasNew) Dot(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp), ring = Mv.colors.bg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(li.manga.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(li.listSub, fontSize = 12.sp, lineHeight = 16.sp, color = Mv.colors.ts)
            ProgressLine(li.progress, Modifier.padding(top = 3.dp))
        }
        if (li.unread > 0) {
            Box(
                Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.acs).padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${li.unread}", color = Mv.colors.ac, fontSize = 11.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun LibrarySkeleton() {
    Column(Modifier.padding(horizontal = 16.dp)) {
        repeat(3) {
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).shimmer())
                        SkeletonBar(80.dp, 10.dp)
                    }
                }
            }
        }
    }
}
