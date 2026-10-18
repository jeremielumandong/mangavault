package dev.mangavault.app.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangavault.app.ui.LocalBottomPadding
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.components.Cover
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.ListSheet
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.ScreenTitle
import dev.mangavault.app.ui.components.SheetItem
import dev.mangavault.app.ui.components.StateMessage
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.HistoryEntry
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.util.Format
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class HistRow(val entry: HistoryEntry, val manga: MangaSummary, val sub: String, val time: String)

@Composable
fun HistoryScreen(nav: Navigator) {
    val c = LocalContainer.current
    val data by c.user.flow.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val bottom = LocalBottomPadding.current

    val rows = data.history.values.sortedByDescending { Format.parse(it.readAt) }.mapNotNull { h ->
        val m = data.manga[h.mangaId] ?: return@mapNotNull null
        val page = if (h.pageCount != null) "page ${h.page} of ${h.pageCount}" else "page ${h.page}"
        HistRow(h, m, "${Format.chapter(h.chapterNumber)} · $page", Format.historyTime(h.readAt))
    }
    val groups = rows.groupBy { Format.historyGroup(it.entry.readAt) }

    fun remove(r: HistRow) {
        c.user.update { UserOps.removeHistory(it, r.entry.mangaId) }
        c.requestSync()
        c.toasts.show("Removed from history") {
            c.user.update { UserOps.restoreHistory(it, r.entry) }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("History") {
            IconBtn(Icons.Rounded.MoreVert, { menu = true }, contentDescription = "More")
        }
        if (rows.isEmpty()) {
            StateMessage(
                "No reading history", "Chapters you open will appear here.",
                icon = Icons.Rounded.History, action = "Browse popular titles", onAction = { nav.browse("popular") },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 8.dp)) {
                groups.forEach { (label, list) ->
                    item(key = "g:$label") {
                        Text(
                            label, fontSize = 13.sp, fontWeight = SemiBold, color = Mv.colors.ts,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                        )
                    }
                    items(list, key = { "h:${it.entry.mangaId}" }) { r ->
                        SwipeRow(onRemove = { remove(r) }) {
                            Row(
                                Modifier.fillMaxWidth().background(Mv.colors.bg).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier.weight(1f).clickable { nav.details(r.manga) },
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Cover(r.manga.coverUrl, r.manga.coverHeaders, Modifier.width(44.dp).height(66.dp), radius = 6.dp)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(r.manga.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(r.sub, fontSize = 13.sp, lineHeight = 18.sp, color = Mv.colors.ts)
                                        Text(r.time, fontSize = 12.sp, lineHeight = 16.sp, color = Mv.colors.ts)
                                    }
                                }
                                PillButton(
                                    "Resume", { nav.reader(r.manga, r.entry.chapterId, r.entry.page) },
                                    icon = Icons.Rounded.PlayArrow, background = Mv.colors.sr, weight = SemiBold, horizontal = 14.dp,
                                )
                            }
                        }
                    }
                }
                item(key = "footer") {
                    Text(
                        "Swipe a row left to remove it.", fontSize = 12.sp, color = Mv.colors.ts,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }

    if (menu) {
        ListSheet(
            "History", { menu = false },
            listOf(
                SheetItem(Icons.Rounded.DeleteSweep, "Clear all history", {
                    menu = false
                    val prev = c.user.value.history
                    c.user.update { UserOps.clearHistory(it) }
                    c.requestSync()
                    c.toasts.show("History cleared") { c.user.update { UserOps.restoreAllHistory(it, prev) } }
                }, danger = true),
                SheetItem(Icons.Rounded.Close, "Cancel", { menu = false }),
            ),
        )
    }
}

/** A row that slides left over a red "Remove" backdrop; past the threshold it is removed. */
@Composable
private fun SwipeRow(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 90.dp.toPx() }
    var width by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().background(Mv.colors.ac).onSizeChanged { width = it.width }) {
        Row(
            Modifier.matchParentSize().padding(end = 20.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MvIcon(Icons.Rounded.Delete, 20.dp, Color.White)
            Text(" Remove", color = Color.White, fontSize = 13.sp, fontWeight = SemiBold)
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    interactionSource = remember { MutableInteractionSource() },
                    state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceAtMost(0f)) } },
                    onDragStopped = { velocity ->
                        if (offset.value < -threshold || velocity < -2500f) {
                            offset.animateTo(-width.toFloat().coerceAtLeast(threshold * 2), tween(160))
                            onRemove()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f, tween(180))
                        }
                    },
                ),
        ) { content() }
    }
}
