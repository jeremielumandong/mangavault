package dev.mangavault.app.ui.details

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.mangavault.app.AppContainer
import dev.mangavault.app.ui.LocalBottomPadding
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.components.Cover
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.PrimaryButton
import dev.mangavault.app.ui.components.ProgressLine
import dev.mangavault.app.ui.components.SkeletonBar
import dev.mangavault.app.ui.components.StateMessage
import dev.mangavault.app.ui.components.TextAction
import dev.mangavault.app.ui.components.headersOf
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.api.ApiException
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.MangaDetail
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

data class DetailsUi(
    val summary: MangaSummary? = null,
    val detail: MangaDetail? = null,
    val chapters: List<Chapter> = emptyList(),
    val refreshing: Boolean = true,
    val failed: Boolean = false,
) {
    val title get() = detail?.title ?: summary?.title.orEmpty()
    val coverUrl get() = detail?.coverUrl ?: summary?.coverUrl
    val coverHeaders get() = detail?.coverHeaders ?: summary?.coverHeaders.orEmpty()
    fun bestSummary(): MangaSummary? = detail?.summary() ?: summary
}

class DetailsViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val _ui = MutableStateFlow(DetailsUi(summary = c.summary(id)))
    val ui = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(refreshing = true, failed = false) }
        viewModelScope.launch {
            try {
                val detail = async { c.api.manga(id) }
                val chapters = async { c.api.chapters(id) }
                val d = detail.await()
                val ch = chapters.await()
                _ui.update { it.copy(detail = d, chapters = ch, refreshing = false) }
                c.cacheSummary(d.summary())
                if (c.user.value.manga.containsKey(id) || c.user.value.manga.containsKey(d.id)) {
                    c.user.update { UserOps.cache(it, d.summary()) }
                }
            } catch (e: ApiException) {
                _ui.update { it.copy(refreshing = false, failed = true) }
            }
        }
    }
}

private enum class Sheet { None, Jump, Category, NewCategory, ChapterActions }

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(nav: Navigator, id: Long) {
    val c = LocalContainer.current
    val vm: DetailsViewModel = viewModel(key = "details-$id") { DetailsViewModel(c, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by c.user.flow.collectAsStateWithLifecycle()
    val prefs by c.prefs.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    var sheet by remember { mutableStateOf(Sheet.None) }
    var sheetChapter by remember { mutableStateOf<Chapter?>(null) }
    var flash by remember { mutableStateOf<Long?>(null) }
    var altOpen by remember { mutableStateOf(false) }
    var synMore by remember { mutableStateOf(false) }
    var headerColor by remember { mutableStateOf<Color?>(null) }

    val summary = ui.bestSummary()
    val mangaId = ui.detail?.id ?: id
    val newestFirst = prefs.chaptersNewestFirst
    val shown = if (newestFirst) ui.chapters.sortedByDescending { it.index } else ui.chapters.sortedBy { it.index }
    val fav = data.isFavorite(mangaId)
    val progress = data.progress[mangaId]
    val headerItems = 2

    fun openChapter(ch: Chapter, page: Int = 1) {
        val m = summary ?: return
        nav.reader(m, ch.id, page)
    }

    fun toggleFavorite() {
        val m = summary ?: return
        if (fav) {
            sheet = Sheet.Category
            return
        }
        c.user.update { UserOps.setFavorite(it, m, data.library[mangaId]?.category ?: "Reading") }
        c.requestSync()
        if (!c.signedIn && !prefs.favPromptShown) {
            c.prefs.update { it.copy(favPromptShown = true) }
            c.sheets.signIn.value = "fav"
        } else {
            c.toasts.show("Added to Library")
        }
    }

    fun jumpTo(position: Int) {
        // position is 1-based in reading order
        val target = ui.chapters.sortedBy { it.index }.getOrNull(position - 1) ?: return
        val idx = shown.indexOfFirst { it.id == target.id }.takeIf { it >= 0 } ?: return
        sheet = Sheet.None
        flash = target.id
        scope.launch {
            listState.animateScrollToItem(headerItems + idx, -with(density) { 140.dp.roundToPx() })
            delay(1400)
            flash = null
        }
    }

    if (ui.failed && ui.detail == null && summary == null) {
        Box(Modifier.fillMaxSize()) {
            StateMessage(
                "Couldn't load this title", "The Mangavault server didn't respond. Check your connection and try again.",
                icon = Icons.Rounded.WifiOff, action = "Retry", onAction = vm::load, top = 160.dp,
            )
            BackButton(nav)
        }
        return
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hue = headerColor
    val headerBg = if (hue == null) Mv.colors.sf else hue
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomPadding.current)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerBg, Mv.colors.bg)))
                        .padding(start = 16.dp, end = 16.dp, top = statusTop + 52.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                        HeaderCover(ui.coverUrl, ui.coverHeaders, Mv.colors.isLight) { headerColor = it }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(ui.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                            val d = ui.detail
                            if (d != null) {
                                val people = (d.authors + d.artists.filter { it !in d.authors }).joinToString(" · ")
                                if (people.isNotEmpty()) Text(people, style = Mv.small, color = Mv.colors.ts)
                            }
                            val meta = listOfNotNull(
                                Format.label(summary?.type), Format.label(summary?.status),
                                if (ui.chapters.isNotEmpty()) Format.plural(ui.chapters.size, "chapter") else null,
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, style = Mv.small, color = Mv.colors.ts)
                        }
                    }
                    val alt = ui.detail?.altTitles.orEmpty()
                    if (alt.isNotEmpty()) {
                        Row(
                            Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(6.dp)).clickable { altOpen = !altOpen },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Also known as", style = Mv.small, color = Mv.colors.ts)
                            MvIcon(if (altOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, 18.dp, Mv.colors.ts)
                        }
                        if (altOpen) Text(alt.joinToString(" · "), style = Mv.small, color = Mv.colors.tp, modifier = Modifier.padding(top = 0.dp))
                    }
                    val genres = ui.detail?.genres.orEmpty()
                    if (genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            genres.forEach { g ->
                                Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Mv.colors.sr).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                    Text(g, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val ordered = ui.chapters.sortedBy { it.index }
                    val current = progress?.let { p -> ordered.firstOrNull { it.id == p.chapterId } }
                    val next = current?.let { cur -> ordered.firstOrNull { it.index > cur.index } }
                    val primary: Triple<String, String, () -> Unit> = when {
                        progress == null || current == null && ordered.isEmpty() -> Triple(
                            if (ordered.isEmpty()) "Read" else "Read ${Format.chapterLabel(ordered.first())}",
                            if (ordered.isEmpty()) "Loading chapters…" else "${Format.plural(ordered.size, "chapter")} available",
                            { ordered.firstOrNull()?.let { openChapter(it) } },
                        )
                        !progress.completed -> Triple(
                            "Continue ${Format.chapter(progress.chapterNumber)}",
                            progress.pageCount?.let { "Page ${progress.page} of $it" } ?: "Page ${progress.page}",
                            { current?.let { openChapter(it, progress.page.coerceAtLeast(1)) } ?: ordered.firstOrNull()?.let { openChapter(it) } },
                        )
                        next != null -> Triple(
                            "Continue ${Format.chapterLabel(next)}",
                            "Finished ${Format.chapter(progress.chapterNumber)}",
                            { openChapter(next) },
                        )
                        else -> Triple(
                            "Read again from ${ordered.firstOrNull()?.let { Format.chapterLabel(it) } ?: "Ch. 1"}",
                            "Finished · ${Format.plural(ordered.size, "chapter")}",
                            { ordered.firstOrNull()?.let { openChapter(it) } },
                        )
                    }
                    PrimaryButton(primary.first, primary.third, Modifier.fillMaxWidth(), sub = primary.second)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(
                            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (fav) data.library[mangaId]?.category ?: "In Library" else "Favorite",
                            if (fav) Mv.colors.ac else Mv.colors.tp,
                            Modifier.weight(1f),
                            ::toggleFavorite,
                        )
                        ActionTile(Icons.Rounded.Share, "Share", Mv.colors.tp, Modifier.weight(1f)) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${ui.title} on Mangavault")
                            }
                            ctx.startActivity(Intent.createChooser(send, null))
                        }
                    }
                    val syn = ui.detail?.description
                    if (!syn.isNullOrBlank()) {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(syn, fontSize = 15.sp, lineHeight = 21.sp, maxLines = if (synMore) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                            if (syn.length > 180) TextAction(if (synMore) "Less" else "More", { synMore = !synMore })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (ui.chapters.isEmpty() && ui.refreshing) "Chapters" else Format.plural(ui.chapters.size, "chapter"), style = Mv.section, modifier = Modifier.weight(1f))
                        PillButton("Jump", { if (ui.chapters.isNotEmpty()) sheet = Sheet.Jump }, icon = Icons.Rounded.Pin)
                        PillButton(if (newestFirst) "Newest" else "Oldest", { c.prefs.update { it.copy(chaptersNewestFirst = !it.chaptersNewestFirst) } }, icon = Icons.Rounded.SwapVert)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        when {
                            ui.refreshing -> {
                                MvIcon(Icons.Rounded.Sync, 15.dp, Mv.colors.ts)
                                Text("Checking every site for new chapters…", style = Mv.caption, color = Mv.colors.ts)
                            }
                            ui.failed -> {
                                MvIcon(Icons.Rounded.CloudOff, 15.dp, Mv.colors.wn)
                                Text("Couldn't check for new chapters · tap to retry", style = Mv.caption, color = Mv.colors.ts, modifier = Modifier.clickable { vm.load() })
                            }
                            else -> {
                                MvIcon(Icons.Rounded.Check, 15.dp, Mv.colors.ok)
                                Text("Up to date · long-press a chapter to mark it read", style = Mv.caption, color = Mv.colors.ts)
                            }
                        }
                    }
                }
            }
            if (ui.chapters.isEmpty() && ui.refreshing) {
                items(8) { ChapterSkeleton() }
            }
            itemsIndexed(shown, key = { _, ch -> ch.id }) { _, ch ->
                ChapterRow(
                    ch, data, fav, flash == ch.id,
                    onClick = {
                        val cur = progress?.takeIf { it.chapterId == ch.id && !it.completed }
                        openChapter(ch, cur?.page?.coerceAtLeast(1) ?: 1)
                    },
                    onLongClick = {
                        sheetChapter = ch
                        sheet = Sheet.ChapterActions
                    },
                )
            }
        }
        BackButton(nav)
    }

    val m = summary
    when (sheet) {
        Sheet.Jump -> JumpSheet(ui.chapters.sortedBy { it.index }, newestFirst, onDismiss = { sheet = Sheet.None }, onJump = ::jumpTo)
        Sheet.Category -> if (m != null) CategorySheet(
            m, data,
            onDismiss = { sheet = Sheet.None },
            onNew = { sheet = Sheet.NewCategory },
            onPick = { cat ->
                sheet = Sheet.None
                c.user.update { UserOps.setFavorite(it, m, cat) }
                c.requestSync()
                c.toasts.show("Moved to $cat")
            },
            onRemove = {
                sheet = Sheet.None
                val prevCat = data.library[mangaId]?.category
                c.user.update { UserOps.removeFavorite(it, mangaId) }
                c.requestSync(4000)
                c.toasts.show("Removed from Library") {
                    c.user.update { UserOps.setFavorite(it, m, prevCat) }
                    c.requestSync()
                }
            },
        )
        Sheet.NewCategory -> NewCategorySheet(
            hint = if (fav) "${ui.title} moves into it. Categories sync with your account." else "Categories sync with your account.",
            onDismiss = { sheet = Sheet.None },
            onAdd = { name ->
                sheet = Sheet.None
                c.user.update { d -> if (fav && m != null) UserOps.setFavorite(d, m, name) else UserOps.addCategory(d, name) }
                c.requestSync()
                c.toasts.show("Added “$name”")
            },
        )
        Sheet.ChapterActions -> sheetChapter?.let { ch ->
            ChapterActionsSheet(
                ch, UserOps.isRead(data, ch),
                onDismiss = { sheet = Sheet.None },
                onRead = { sheet = Sheet.None; openChapter(ch, 1) },
                onToggleRead = { read ->
                    sheet = Sheet.None
                    c.user.update { UserOps.setRead(it, mangaId, listOf(ch.id), read) }
                    c.requestSync()
                },
                onMarkPrevious = {
                    sheet = Sheet.None
                    val prev = ui.chapters.filter { it.index <= ch.index }
                    val wasUnread = prev.filter { !UserOps.isRead(data, it) }.map { it.id }
                    c.user.update { UserOps.setRead(it, mangaId, prev.map { p -> p.id }, true) }
                    c.requestSync(4000)
                    val first = ui.chapters.minByOrNull { it.index }
                    c.toasts.show("Chapters ${first?.number?.let(Format::number) ?: "1"}–${Format.number(ch.number)} marked read") {
                        c.user.update { UserOps.setRead(it, mangaId, wasUnread, false) }
                        c.requestSync()
                    }
                },
            )
        }
        Sheet.None -> Unit
    }
}

@Composable
private fun BackButton(nav: Navigator) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    IconBtn(
        Icons.AutoMirrored.Rounded.ArrowBack, nav::back,
        modifier = Modifier.padding(start = 8.dp, top = top + 4.dp),
        background = Color.Black.copy(alpha = 0.35f), tint = Color.White, iconSize = 26.dp, contentDescription = "Back",
    )
}

/** The 120×180 cover; reports a tint for the header gradient once loaded. */
@Composable
private fun HeaderCover(url: String?, headers: Map<String, String>, light: Boolean, onTint: (Color) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.width(120.dp).height(180.dp).shadow(12.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Mv.colors.sk)) {
        if (url != null) {
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).httpHeaders(headersOf(headers)).allowHardware(false).build() }
            AsyncImage(
                model = req, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { s ->
                    if (s is coil3.compose.AsyncImagePainter.State.Success) {
                        runCatching {
                            val bmp = s.result.image.toBitmap()
                            val px = android.graphics.Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(px, hsv)
                            onTint(Color.hsv(hsv[0], if (light) 0.16f else (hsv[1] * 0.8f).coerceIn(0.2f, 0.55f), if (light) 0.94f else 0.36f))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MvIcon(icon, 22.dp, tint)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(ch: Chapter, data: UserData, fav: Boolean, flash: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = data.progress[ch.mangaId]
    val current = p != null && p.chapterId == ch.id && !p.completed
    val read = UserOps.isRead(data, ch)
    val isNew = fav && !read && ch.publishedAt?.let { Format.parse(it) }?.isAfter(Instant.now().minus(7, ChronoUnit.DAYS)) == true
    val bg by animateColorAsState(
        when {
            flash -> Mv.colors.acs
            current -> Mv.colors.sf
            else -> Color.Transparent
        },
        label = "rowBg",
    )
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mv.colors.ln))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).alpha(if (read && !current) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Format.chapterLabel(ch), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold)
                    if (isNew) {
                        Box(Modifier.height(16.dp).clip(RoundedCornerShape(4.dp)).background(Mv.colors.ac).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                            Text("NEW", color = Color.White, fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                        }
                    }
                }
                val sub = Format.chapterSub(ch)
                if (sub.isNotEmpty()) Text(sub, style = Mv.small, color = Mv.colors.ts, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current && p != null) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val frac = p.pageCount?.takeIf { it > 0 }?.let { p.page.toFloat() / it } ?: 0f
                        ProgressLine(frac, Modifier.weight(1f))
                        Text(p.pageCount?.let { "p. ${p.page}/$it" } ?: "p. ${p.page}", fontSize = 12.sp, color = Mv.colors.ac, fontWeight = SemiBold)
                    }
                }
            }
            when {
                current -> MvIcon(Icons.Rounded.Bookmark, 20.dp, Mv.colors.ac)
                read -> MvIcon(Icons.Rounded.CheckCircle, 20.dp, Mv.colors.ts)
                else -> Box(Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ChapterSkeleton() {
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonBar(70.dp, 12.dp)
        SkeletonBar(180.dp, 10.dp)
    }
}
