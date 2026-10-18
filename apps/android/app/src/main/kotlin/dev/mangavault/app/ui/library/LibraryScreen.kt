package dev.mangavault.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import dev.mangavault.app.ui.theme.ZenKaku
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

private val SORT_OPTIONS = listOf("Last read", "Last updated", "Most unread", "Title", "Date added")

/** Sort, category and filter applied to [all], as the Library shows them. */
fun filterLibrary(all: List<LibItem>, d: UserData, cat: String, query: String, unreadOnly: Boolean, sort: String): List<LibItem> {
    val q = query.trim().lowercase()
    var items = all.filter { cat == "All" || it.entry.category == cat }
    if (q.isNotEmpty()) items = items.filter { q in it.manga.title.lowercase() }
    if (unreadOnly) items = items.filter { it.unread > 0 }
    return when (sort) {
        "Title" -> items.sortedBy { it.manga.title.lowercase() }
        "Last updated" -> items.sortedByDescending { it.manga.updatedAt.orEmpty() }
        "Most unread" -> items.sortedWith(compareByDescending<LibItem> { it.unread }.thenBy { it.manga.title.lowercase() })
        "Date added" -> items.sortedByDescending { it.entry.addedAt }
        else -> items.sortedByDescending { d.history[it.manga.id]?.readAt ?: d.progress[it.manga.id]?.updatedAt ?: "" }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var moveSheet by remember { mutableStateOf(false) }
    var catActions by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val signedIn = session != null
    val selecting = selected.isNotEmpty()

    LaunchedEffect(signedIn) { if (signedIn) c.requestSync(0) }
    BackHandler(enabled = selecting || searching) {
        if (selecting) selected = emptySet() else { searching = false; query = "" }
    }

    val all = libraryItems(data, signedIn)
    val cats = listOf("All") + (data.categories + all.mapNotNull { it.entry.category }).distinct()
    val cat = if (selectedCat in cats) selectedCat else "All"
    val items = filterLibrary(all, data, cat, query, prefs.libUnread, prefs.libSort)
    // Titles removed elsewhere (another device, the details screen) drop out of the selection.
    LaunchedEffect(all) { selected = selected.filterTo(mutableSetOf()) { id -> all.any { it.manga.id == id } } }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }
    fun open(li: LibItem) {
        if (selecting) toggle(li.manga.id) else nav.details(li.manga)
    }
    fun moveSelected(category: String?) {
        val ids = selected
        c.user.update { UserOps.setCategory(it, ids, category) }
        c.requestSync()
        selected = emptySet()
        moveSheet = false
        c.toasts.show("Moved ${Format.plural(ids.size, "title")} to ${category ?: "no category"}")
    }
    fun removeSelected() {
        val gone = data.favorites.filter { it.mangaId in selected }
        c.user.update { UserOps.removeFavorites(it, gone.map { e -> e.mangaId }) }
        c.requestSync()
        selected = emptySet()
        c.toasts.show("Removed ${Format.plural(gone.size, "title")} from Library") {
            c.user.update { UserOps.restoreFavorites(it, gone) }
            c.requestSync()
        }
    }

    val offline = signedIn && (!online || (sync as? SyncStatus.Failed)?.offline == true)
    val syncError = signedIn && sync is SyncStatus.Failed && !offline
    val loading = signedIn && all.isEmpty() && (sync is SyncStatus.Syncing || sync is SyncStatus.Idle) && data.cursors.lastSyncAt == null
    val bottom = LocalBottomPadding.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (offline) Banner(Icons.Rounded.CloudOff, "Offline. Showing your saved library; changes sync when you're back.")
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconBtn(Icons.Rounded.Close, { selected = emptySet() }, contentDescription = "Cancel selection")
                Text("${selected.size} selected", fontSize = 17.sp, fontWeight = SemiBold, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.SelectAll, {
                    val shown = items.map { it.manga.id }.toSet()
                    selected = if (selected.containsAll(shown)) emptySet() else selected + shown
                }, contentDescription = "Select all")
                IconBtn(Icons.AutoMirrored.Rounded.DriveFileMove, { moveSheet = true }, contentDescription = "Move to category")
                IconBtn(Icons.Rounded.Delete, ::removeSelected, tint = Mv.colors.ac, contentDescription = "Remove from Library")
            }
        } else ScreenTitle("Library") {
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
                    Column(
                        Modifier.combinedClickable(
                            onClick = { selectedCat = name },
                            onLongClick = if (name == "All") null else ({ catActions = name }),
                            onLongClickLabel = "Edit category",
                        ),
                    ) {
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
        if (searching) {
            SearchField(query, { query = it }, Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp)) { searching = false; query = "" }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(prefs.libSort, { sortSheet = true }, icon = Icons.AutoMirrored.Rounded.Sort, height = 32.dp)
            if (prefs.libUnread) {
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Mv.colors.acs).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Unread", fontSize = 13.sp, color = Mv.colors.ac, fontWeight = FontWeight.Medium)
                }
            }
            Box(Modifier.weight(1f))
            if (!searching) {
                IconBtn(Icons.Rounded.Search, { searching = true }, size = 32.dp, iconSize = 20.dp, tint = Mv.colors.ts, contentDescription = "Search library")
            }
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

        PullToRefreshBox(
            refreshing,
            {
                scope.launch {
                    refreshing = true
                    c.syncNow()
                    refreshing = false
                }
            },
            Modifier.fillMaxSize(),
        ) {
            // Non-list states scroll too, so pull to refresh works on them.
            val still = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            when {
                loading -> Column(still) { LibrarySkeleton() }
                syncError && all.isEmpty() -> Column(still) {
                    StateMessage(
                        "Couldn't sync your library", "The Mangavault server didn't answer. Your favorites on this phone are untouched.",
                        icon = Icons.Rounded.SyncProblem, action = "Retry", onAction = { scope.launch { c.syncNow() } }, top = 90.dp,
                    )
                }
                items.isEmpty() && query.isNotBlank() -> Column(still) {
                    StateMessage("No matches", "Nothing in this list matches “${query.trim()}”.", icon = Icons.Rounded.Search, top = 80.dp)
                }
                items.isEmpty() -> Column(still) {
                    StateMessage(
                        if (all.isEmpty()) "Your library is empty" else "Nothing here yet",
                        "Tap the heart on any title and it lands here with new-chapter badges.",
                        icon = Icons.Rounded.Favorite, action = "Browse popular titles", onAction = { nav.browse("popular") }, top = 80.dp,
                    )
                }
                prefs.libView == "list" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                    items(items, key = { it.manga.id }) { li ->
                        ListRow(li, li.manga.id in selected, { open(li) }) { toggle(li.manga.id) }
                    }
                }
                else -> {
                    val titleSize = when (prefs.libCols) { 4 -> 11; 2 -> 15; else -> 13 }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(prefs.libCols.coerceIn(2, 4)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items, key = { it.manga.id }) { li ->
                            val on = li.manga.id in selected
                            Column(
                                Modifier.combinedClickable(onClick = { open(li) }, onLongClick = { toggle(li.manga.id) }, onLongClickLabel = "Select"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Cover(li.manga.coverUrl, li.manga.coverHeaders, Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                                    GridBadges(li)
                                    if (selecting) SelectionMark(on)
                                }
                                CoverTitle(li.manga.title, titleSize)
                            }
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
            if (moveSheet) {
                moveSelected(name)
            } else {
                c.user.update { UserOps.addCategory(it, name) }
                selectedCat = name
                c.toasts.show("Added “$name”")
            }
        }
    }
    if (moveSheet && !newCat) {
        val inAll = selected.mapNotNull { id -> data.library[id]?.category }.toSet()
        val current = inAll.singleOrNull()?.takeIf { selected.all { data.library[it]?.category != null } }
        ListSheet(
            "Move to category", { moveSheet = false },
            data.categories.map { name -> SheetItem(Icons.Rounded.Folder, name, { moveSelected(name) }, checked = name == current) } +
                SheetItem(Icons.Rounded.FolderOpen, "No category", { moveSelected(null) }) +
                SheetItem(Icons.Rounded.CreateNewFolder, "New category…", { newCat = true }),
            sub = Format.plural(selected.size, "title"),
        )
    }
    catActions?.let { name ->
        val n = all.count { it.entry.category == name }
        ListSheet(
            name, { catActions = null },
            listOf(
                SheetItem(Icons.Rounded.Edit, "Rename", { catActions = null; renaming = name }),
                SheetItem(Icons.Rounded.Delete, "Delete category", {
                    catActions = null
                    val before = data
                    c.user.update { UserOps.deleteCategory(it, name) }
                    c.requestSync()
                    if (selectedCat == name) selectedCat = "All"
                    c.toasts.show("Deleted “$name”") {
                        c.user.update { cur ->
                            val ids = before.favorites.filter { it.category == name }.map { it.mangaId }
                            UserOps.setCategory(cur.copy(categories = before.categories), ids, name)
                        }
                        c.requestSync()
                    }
                }, sub = if (n > 0) "${Format.plural(n, "title")} stay in your Library" else null, danger = true),
            ),
        )
    }
    renaming?.let { old ->
        NewCategorySheet(
            "Every title in it moves to the new name.", { renaming = null },
            title = "Rename category", initial = old, action = "Save",
        ) { name ->
            renaming = null
            if (name in data.categories && name != old) {
                c.toasts.show("“$name” already exists")
                return@NewCategorySheet
            }
            c.user.update { UserOps.renameCategory(it, old, name) }
            c.requestSync()
            if (selectedCat == old) selectedCat = name
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MvIcon(Icons.Rounded.Search, 18.dp, Mv.colors.ts)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("Search your library", fontSize = 15.sp, color = Mv.colors.ts)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = ZenKaku, fontSize = 15.sp, color = Mv.colors.tp),
                    cursorBrush = SolidColor(Mv.colors.ac),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        IconBtn(Icons.Rounded.Close, onClose, size = 40.dp, iconSize = 20.dp, tint = Mv.colors.ts, contentDescription = "Close search")
    }
}

@Composable
private fun BoxScope.SelectionMark(on: Boolean) {
    if (on) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
    Box(
        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp).clip(CircleShape)
            .background(if (on) Mv.colors.ac else Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) MvIcon(Icons.Rounded.Check, 16.dp, Color.White)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(li: LibItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Mv.colors.acs else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Cover(li.manga.coverUrl, li.manga.coverHeaders, Modifier.width(52.dp).height(78.dp), radius = 6.dp)
            if (li.hasNew) Dot(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp), ring = Mv.colors.bg)
            if (selected) {
                Box(Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Mv.colors.ac), contentAlignment = Alignment.Center) {
                    MvIcon(Icons.Rounded.Check, 18.dp, Color.White)
                }
            }
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
