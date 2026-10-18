package dev.mangavault.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mangavault.app.ui.components.Badge
import dev.mangavault.app.ui.components.Banner
import dev.mangavault.app.ui.components.Cover
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.Logo
import dev.mangavault.app.ui.components.MangaTile
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.ProgressLine
import dev.mangavault.app.ui.components.SectionHeader
import dev.mangavault.app.ui.components.SkeletonBar
import dev.mangavault.app.ui.components.StateMessage
import dev.mangavault.app.ui.components.shimmer
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.api.ApiException
import dev.mangavault.core.model.HomeFeed
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.ProgressEntry
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUi(
    val loading: Boolean = true,
    val error: Boolean = false,
    val offline: Boolean = false,
    val feed: HomeFeed? = null,
    val genre: String = "Romance",
    val genreRow: List<MangaSummary> = emptyList(),
    val updates: List<Pair<MangaSummary, Int>> = emptyList(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi(feed = c.homeCache.value, loading = c.homeCache.value == null))
    val ui = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        val genre = c.prefs.value.genres.firstOrNull() ?: "Romance"
        _ui.update { it.copy(genre = genre, error = false, loading = it.feed == null) }
        viewModelScope.launch {
            val genreRow = async {
                runCatching {
                    c.api.browse(dev.mangavault.core.model.BrowseQuery(sort = "popular", genres = listOf(genre), limit = 20)).items
                }.getOrNull()
            }
            val updates = async { loadUpdates() }
            try {
                val feed = c.api.home()
                c.homeCache.set(feed)
                (feed.popular + feed.latest + feed.newTitles).forEach { m -> refreshCached(m) }
                _ui.update { it.copy(loading = false, offline = false, feed = feed) }
            } catch (e: ApiException) {
                _ui.update { it.copy(loading = false, offline = it.feed != null, error = it.feed == null) }
            }
            genreRow.await()?.let { row -> _ui.update { it.copy(genreRow = row) } }
            _ui.update { it.copy(updates = updates.await()) }
        }
    }

    /** Lists carry the freshest summary; keep the copy we hold for favorites current. */
    private fun refreshCached(m: MangaSummary) {
        if (c.user.value.manga.containsKey(m.id) && c.user.value.manga[m.id] != m) c.user.update { UserOps.cache(it, m) }
    }

    private suspend fun loadUpdates(): List<Pair<MangaSummary, Int>> {
        if (c.signedIn) {
            runCatching { c.api.updates() }.getOrNull()?.let { list -> return list.map { it.manga to it.newChapters } }
        }
        return UserOps.localUpdates(c.user.value)
    }
}

data class ContinueItem(val manga: MangaSummary, val progress: ProgressEntry)

fun continueItems(d: UserData): List<ContinueItem> =
    d.progress.values.filter { !it.completed }
        .sortedByDescending { d.history[it.mangaId]?.readAt ?: it.updatedAt }
        .mapNotNull { p -> d.manga[p.mangaId]?.let { ContinueItem(it, p) } }
        .take(5)

@Composable
fun HomeScreen(nav: Navigator) {
    val c = LocalContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by c.user.flow.collectAsStateWithLifecycle()
    val online by c.network.online.collectAsStateWithLifecycle()
    LaunchedEffect(online) { if (online && (ui.error || ui.offline)) vm.load() }
    val cont = continueItems(data)
    val updates = ui.updates.filter { data.isFavorite(it.first.id) }
    val feed = ui.feed
    val rows = buildList {
        if (feed != null) {
            add(Triple("Popular", feed.popular, { nav.browse("popular") }))
            add(Triple("Latest updates", feed.latest, { nav.browse("latest") }))
            add(Triple("New titles", feed.newTitles, { nav.browse("new") }))
        }
        add(Triple("More ${ui.genre}", ui.genreRow, { nav.browse("popular", ui.genre) }))
    }.filter { it.second.isNotEmpty() }
    val empty = !ui.loading && !ui.error && cont.isEmpty() && rows.isEmpty() && updates.isEmpty()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomPadding.current),
    ) {
        if (ui.offline || !online && feed != null) {
            item { Banner(Icons.Rounded.CloudOff, "You're offline. Downloaded and cached chapters still open.") }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(24, Modifier.weight(1f))
                IconBtn(Icons.Rounded.Person, { nav.tab("settings") }, background = Mv.colors.sf, contentDescription = "Account")
            }
        }
        when {
            ui.loading -> item { HomeSkeleton() }
            ui.error -> item {
                StateMessage(
                    "Couldn't load Home", "The Mangavault server didn't respond. Check your connection and try again.",
                    icon = Icons.Rounded.WifiOff, action = "Retry", onAction = vm::load, top = 120.dp,
                )
            }
            empty -> item {
                StateMessage(
                    "Nothing to continue yet", "Start a series and it'll wait for you here, one tap from where you left off.",
                    art = {
                        Box(
                            Modifier.width(72.dp).height(108.dp).clip(RoundedCornerShape(8.dp))
                                .background(Mv.colors.ts.copy(alpha = 0.15f))
                        )
                    },
                    action = "Browse popular titles", onAction = { nav.browse("popular") },
                )
            }
            else -> {
                if (cont.isNotEmpty()) {
                    item { SectionHeader("Continue reading", Modifier.padding(top = 0.dp)) }
                    item {
                        val state = rememberLazyListState()
                        LazyRow(
                            state = state,
                            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(cont, key = { it.manga.id }) { ci ->
                                ContinueCard(ci) { nav.reader(ci.manga, ci.progress.chapterId, ci.progress.page.coerceAtLeast(1)) }
                            }
                        }
                    }
                }
                if (updates.isNotEmpty()) {
                    item { SectionHeader("Updated favorites", action = "Library", onAction = { nav.tab("library") }) }
                    item {
                        TileRow(updates.map { it.first }, nav) { m ->
                            val n = updates.firstOrNull { it.first.id == m.id }?.second ?: 0
                            Badge("$n new", Modifier.padding(6.dp))
                        }
                    }
                }
                rows.forEach { (title, items, seeAll) ->
                    item(key = title) { SectionHeader(title, action = "See all", onAction = seeAll) }
                    item(key = "$title-row") { TileRow(items, nav) }
                }
            }
        }
    }
}

@Composable
private fun TileRow(items: List<MangaSummary>, nav: Navigator, badge: (@Composable (MangaSummary) -> Unit)? = null) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { m ->
            MangaTile(m.title, m.coverUrl, m.coverHeaders, { nav.details(m) }, Modifier.width(112.dp)) {
                badge?.invoke(m)
            }
        }
    }
}

@Composable
private fun ContinueCard(ci: ContinueItem, onResume: () -> Unit) {
    val p = ci.progress
    val sub = "${Format.chapter(p.chapterNumber)}" + (p.pageCount?.let { " · page ${p.page} of $it" } ?: " · page ${p.page}")
    val pct = p.pageCount?.takeIf { it > 0 }?.let { p.page.toFloat() / it } ?: 0f
    Row(
        Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf).clickable(onClick = onResume).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ci.manga.coverUrl, ci.manga.coverHeaders, Modifier.width(60.dp).height(90.dp), radius = 6.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ci.manga.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = Mv.colors.ts)
            ProgressLine(pct, Modifier.padding(top = 4.dp))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(Mv.colors.ac), contentAlignment = Alignment.Center) {
            MvIcon(Icons.Rounded.PlayArrow, 26.dp, Color.White)
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column {
        Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)) {
            Box(Modifier.width(140.dp).height(16.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { Box(Modifier.width(300.dp).height(110.dp).clip(RoundedCornerShape(12.dp)).shimmer()) }
        }
        repeat(3) {
            Box(Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp)) { SkeletonBar(120.dp, 16.dp, radius = 6.dp) }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) {
                    Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).shimmer())
                        SkeletonBar(100.dp, 10.dp)
                        SkeletonBar(66.dp, 10.dp)
                    }
                }
            }
        }
    }
}
