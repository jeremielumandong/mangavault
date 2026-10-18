package dev.mangavault.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mangavault.app.AppContainer
import dev.mangavault.app.ui.LocalBottomPadding
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.components.Chip
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.MangaTile
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.MvSheet
import dev.mangavault.app.ui.components.PrimaryButton
import dev.mangavault.app.ui.components.ScreenTitle
import dev.mangavault.app.ui.components.SkeletonBar
import dev.mangavault.app.ui.components.StateMessage
import dev.mangavault.app.ui.components.TextAction
import dev.mangavault.app.ui.components.shimmer
import dev.mangavault.app.ui.onboarding.FALLBACK_GENRES
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.app.ui.theme.ZenKaku
import dev.mangavault.core.api.ApiException
import dev.mangavault.core.model.BrowseQuery
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Filters(
    val inc: List<String> = emptyList(),
    val exc: List<String> = emptyList(),
    val status: String = "Any",
    val type: String = "Any",
    val sort: String = "Relevance",
) {
    val count get() = (if (status != "Any") 1 else 0) + (if (type != "Any") 1 else 0) + inc.size + exc.size +
        (if (sort != "Relevance") 1 else 0)

    fun query(page: Int, limit: Int = 30, forSearch: Boolean) = BrowseQuery(
        sort = when (sort) {
            "Popular" -> "popular"
            "Latest update" -> "latest"
            "New" -> "new"
            "Title" -> "title"
            else -> if (forSearch) null else "popular"
        },
        genres = inc, excludeGenres = exc,
        status = status.takeIf { it != "Any" }?.lowercase(),
        type = type.takeIf { it != "Any" }?.lowercase(),
        page = page, limit = limit,
    )
}

val SORTS = listOf("Relevance", "Popular", "Latest update", "New", "Title")
val STATUSES = listOf("Any", "Ongoing", "Completed", "Hiatus", "Cancelled")
val TYPES = listOf("Any", "Manga", "Manhwa", "Manhua", "Comic")

enum class Phase { Idle, Loading, Results, Error }

data class SearchUi(
    val q: String = "",
    val phase: Phase = Phase.Idle,
    val browse: Boolean = false,
    val results: List<MangaSummary> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val filters: Filters = Filters(),
    val draft: Filters? = null,
    val draftCount: Pair<Int, Boolean>? = null,
    val genres: List<String> = FALLBACK_GENRES,
)

class SearchViewModel(private val c: AppContainer, sort: String?, genre: String?) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private var countJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { c.api.genres() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { g -> _ui.update { it.copy(genres = g) } }
        }
        if (sort != null) {
            val label = when (sort) { "latest" -> "Latest update"; "new" -> "New"; "title" -> "Title"; else -> "Popular" }
            _ui.update { it.copy(browse = true, filters = Filters(sort = label, inc = listOfNotNull(genre))) }
            run(0)
        }
    }

    fun onQuery(q: String) {
        _ui.update { it.copy(q = q) }
        if (q.isBlank()) {
            job?.cancel()
            if (_ui.value.browse) run(0) else _ui.update { it.copy(phase = Phase.Idle, results = emptyList()) }
            return
        }
        run(450)
    }

    fun submit() {
        val q = _ui.value.q
        if (q.isBlank()) return
        c.prefs.update { it.withRecent(q) }
        run(0)
    }

    fun useRecent(q: String) {
        _ui.update { it.copy(q = q, browse = false) }
        c.prefs.update { it.withRecent(q) }
        run(0)
    }

    fun removeRecent(q: String) = c.prefs.update { p -> p.copy(recents = p.recents - q) }
    fun clearRecents() = c.prefs.update { it.copy(recents = emptyList()) }

    fun clear() {
        job?.cancel()
        _ui.update { it.copy(q = "", phase = Phase.Idle, browse = false, results = emptyList()) }
    }

    fun retry() = run(0)

    private fun run(debounceMs: Long) {
        job?.cancel()
        _ui.update { it.copy(phase = Phase.Loading) }
        job = viewModelScope.launch {
            delay(debounceMs)
            val s = _ui.value
            try {
                val page = fetch(s.q, s.filters, 1)
                _ui.update { it.copy(phase = Phase.Results, results = page.items, page = 1, hasMore = page.hasMore) }
            } catch (e: ApiException) {
                _ui.update { it.copy(phase = Phase.Error) }
            }
        }
    }

    private suspend fun fetch(q: String, f: Filters, page: Int) =
        if (q.isBlank()) c.api.browse(f.query(page, forSearch = false)) else c.api.search(q.trim(), f.query(page, forSearch = true))

    fun loadMore() {
        val s = _ui.value
        if (!s.hasMore || s.loadingMore || s.phase != Phase.Results) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = fetch(s.q, s.filters, s.page + 1)
                _ui.update { cur ->
                    cur.copy(results = (cur.results + page.items).distinctBy { it.id }, page = s.page + 1, hasMore = page.hasMore, loadingMore = false)
                }
            } catch (e: ApiException) {
                _ui.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun openFilters() {
        _ui.update { it.copy(draft = it.filters) }
        countDraft()
    }

    fun closeFilters() = _ui.update { it.copy(draft = null) }

    fun editDraft(fn: (Filters) -> Filters) {
        _ui.update { it.copy(draft = fn(it.draft ?: it.filters)) }
        countDraft()
    }

    fun resetFilters() {
        if (_ui.value.draft != null) editDraft { Filters() } else {
            _ui.update { it.copy(filters = Filters()) }
            run(0)
        }
    }

    fun applyFilters() {
        _ui.update { it.copy(filters = it.draft ?: it.filters, draft = null, browse = if (it.q.isBlank()) true else it.browse) }
        run(0)
    }

    /** "Show N titles": count what the draft filters match, up to one page of 100. */
    private fun countDraft() {
        countJob?.cancel()
        _ui.update { it.copy(draftCount = null) }
        countJob = viewModelScope.launch {
            delay(300)
            val d = _ui.value.draft ?: return@launch
            runCatching { c.api.browse(d.query(1, 100, forSearch = false)) }.getOrNull()?.let { page ->
                _ui.update { it.copy(draftCount = page.items.size to page.hasMore) }
            }
        }
    }
}

@Composable
fun SearchScreen(nav: Navigator, sort: String?, genre: String?) {
    val c = LocalContainer.current
    val vm: SearchViewModel = viewModel(key = "search-$sort-$genre") { SearchViewModel(c, sort, genre) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by c.prefs.flow.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (sort == null && ui.q.isEmpty() && ui.phase == Phase.Idle) runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Search", Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MvIcon(Icons.Rounded.Search, 22.dp, Mv.colors.ts)
                Box(Modifier.weight(1f)) {
                    if (ui.q.isEmpty()) Text("Titles, authors, genres", fontSize = 16.sp, color = Mv.colors.ts)
                    BasicTextField(
                        value = ui.q,
                        onValueChange = vm::onQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = ZenKaku, fontSize = 16.sp, color = Mv.colors.tp),
                        cursorBrush = SolidColor(Mv.colors.ac),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.submit()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (ui.q.isNotEmpty()) {
                    IconBtn(Icons.Rounded.Cancel, { vm.clear(); runCatching { focus.requestFocus() } }, size = 32.dp, iconSize = 20.dp, tint = Mv.colors.ts, contentDescription = "Clear")
                }
            }
            Box {
                IconBtn(Icons.Rounded.Tune, vm::openFilters, background = Mv.colors.sf, shape = RoundedCornerShape(12.dp), contentDescription = "Filters")
                if (ui.filters.count > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(16.dp).clip(CircleShape).background(Mv.colors.ac),
                        contentAlignment = Alignment.Center,
                    ) { Text("${ui.filters.count}", color = Color.White, fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        val bottom = LocalBottomPadding.current
        when {
            ui.phase == Phase.Idle && !ui.browse -> Recents(prefs.recents, vm, bottom)
            ui.phase == Phase.Loading -> ResultsSkeleton()
            ui.phase == Phase.Error -> StateMessage(
                "Couldn't search", "The Mangavault server didn't respond. Check your connection and try again.",
                icon = Icons.Rounded.WifiOff, action = "Retry", onAction = vm::retry, top = 60.dp,
            )
            ui.results.isEmpty() && ui.q.isNotBlank() -> StateMessage(
                "No matches for “${ui.q}”", "Try a shorter title, or loosen your filters.",
                action = "Clear filters", onAction = vm::resetFilters, secondary = true, top = 60.dp,
            )
            ui.results.isEmpty() -> StateMessage(
                "Nothing matches these filters", "Try a shorter title, or loosen your filters.",
                action = "Clear filters", onAction = vm::resetFilters, secondary = true, top = 60.dp,
            )
            else -> Results(ui, nav, vm, bottom)
        }
    }

    ui.draft?.let { d -> FilterSheet(ui, d, vm) }
}

@Composable
private fun Recents(recents: List<String>, vm: SearchViewModel, bottom: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", style = Mv.section, modifier = Modifier.weight(1f))
            if (recents.isNotEmpty()) TextAction("Clear", vm::clearRecents)
        }
        if (recents.isEmpty()) {
            Text("Your searches will show up here.", color = Mv.colors.ts, style = Mv.body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        recents.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.useRecent(r) }.padding(start = 16.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MvIcon(Icons.Rounded.History, 20.dp, Mv.colors.ts)
                Text(r, style = Mv.body, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.Close, { vm.removeRecent(r) }, iconSize = 18.dp, tint = Mv.colors.ts, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun Results(ui: SearchUi, nav: Navigator, vm: SearchViewModel, bottom: androidx.compose.ui.unit.Dp) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 7
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() }
    }
    val countLabel = if (ui.hasMore) "${ui.results.size}+ results" else Format.plural(ui.results.size, "result")
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("$countLabel · ${ui.filters.sort}", style = Mv.small, color = Mv.colors.ts, modifier = Modifier.padding(top = 8.dp))
        }
        items(ui.results, key = { it.id }) { m ->
            MangaTile(
                m.title, m.coverUrl, m.coverHeaders,
                onClick = {
                    if (ui.q.isNotBlank()) vm.submit()
                    nav.details(m)
                },
                subtitle = listOfNotNull(Format.label(m.type), Format.label(m.status)).joinToString(" · ").ifEmpty { null },
            )
        }
        if (ui.q.isNotBlank() && ui.results.size < 6 && !ui.hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MvIcon(Icons.Rounded.DoneAll, 18.dp, Mv.colors.ts)
                    Text(
                        "  Checked every site · that's everything for “${ui.q}”",
                        style = Mv.small, color = Mv.colors.ts, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsSkeleton() {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Searching…", style = Mv.small, color = Mv.colors.ts, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        repeat(3) {
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).shimmer())
                        SkeletonBar(90.dp, 10.dp)
                        SkeletonBar(50.dp, 8.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: SearchUi, d: Filters, vm: SearchViewModel) {
    MvSheet(vm::closeFilters) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", fontSize = 17.sp, fontWeight = SemiBold, modifier = Modifier.weight(1f))
                TextAction("Reset", vm::resetFilters, modifier = Modifier.padding(vertical = 10.dp))
            }
            FilterGroup("Genre", note = "Tap once to include, twice to exclude.") {
                ui.genres.forEach { g ->
                    val inc = g in d.inc
                    val exc = g in d.exc
                    Chip(
                        g, inc,
                        onClick = {
                            vm.editDraft { f ->
                                when {
                                    inc -> f.copy(inc = f.inc - g, exc = f.exc + g)
                                    exc -> f.copy(exc = f.exc - g)
                                    else -> f.copy(inc = f.inc + g)
                                }
                            }
                        },
                        background = if (inc) Mv.colors.tp else if (exc) Mv.colors.acs else Mv.colors.sr,
                        foreground = if (inc) Mv.colors.bg else if (exc) Mv.colors.ac else Mv.colors.tp,
                        icon = if (inc) Icons.Rounded.Check else if (exc) Icons.Rounded.Block else null,
                        strike = exc,
                    )
                }
            }
            FilterGroup("Status") { STATUSES.forEach { s -> Chip(s, d.status == s, { vm.editDraft { it.copy(status = s) } }) } }
            FilterGroup("Type") { TYPES.forEach { t -> Chip(t, d.type == t, { vm.editDraft { it.copy(type = t) } }) } }
            FilterGroup("Sort by") { SORTS.forEach { s -> Chip(s, d.sort == s, { vm.editDraft { it.copy(sort = s) } }) } }
            val label = ui.draftCount?.let { (n, more) -> if (more) "Show $n+ titles" else "Show $n title${if (n == 1) "" else "s"}" } ?: "Show titles"
            PrimaryButton(label, vm::applyFilters, Modifier.fillMaxWidth().padding(top = 20.dp), height = 50.dp, fontSize = 16)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, note: String? = null, chips: @Composable () -> Unit) {
    Text(title, style = Mv.small, color = Mv.colors.ts, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    if (note != null) Text(note, style = Mv.caption, color = Mv.colors.ts, modifier = Modifier.padding(top = 6.dp))
}
