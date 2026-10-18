package dev.mangavault.app.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangavault.app.ui.components.ListSheet
import dev.mangavault.app.ui.components.MvSheet
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.SheetItem
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.app.ui.theme.ZenKaku
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.UserData
import dev.mangavault.core.util.Format
import dev.mangavault.core.util.jumpBuckets

/** A filled text field + action button, as in the Jump and New category sheets. */
@Composable
fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    action: String,
    onAction: () -> Unit,
    numeric: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sr).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = Mv.colors.ts)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = ZenKaku, fontSize = 17.sp, color = Mv.colors.tp),
                cursorBrush = SolidColor(Mv.colors.ac),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAction() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        PillButton(
            action, onAction, height = 48.dp, horizontal = 22.dp, radius = 12.dp,
            background = Mv.colors.ac, textColor = androidx.compose.ui.graphics.Color.White, fontSize = 15, weight = SemiBold,
        )
    }
}

/** Jump to a chapter by number, or by range bucket. [ordered] is in reading order. */
@Composable
fun JumpSheet(ordered: List<Chapter>, newestFirst: Boolean, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val first = ordered.firstOrNull()?.number
    val last = ordered.lastOrNull()?.number
    fun go() {
        val n = text.toDoubleOrNull()
        if (n == null) {
            onJump(1)
            return
        }
        // The chapter with that number, else the last one before it.
        val pos = ordered.indexOfLast { (it.number ?: Double.MAX_VALUE) <= n }.let { if (it < 0) 0 else it }
        onJump(pos + 1)
    }
    MvSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jump to chapter", fontSize = 17.sp, fontWeight = SemiBold)
            InputRow(
                text, { v -> text = v.filter { it.isDigit() || it == '.' } },
                placeholder = if (first != null && last != null) "${Format.number(first)} – ${Format.number(last)}" else "Chapter number",
                action = "Go", onAction = ::go, numeric = true,
            )
            jumpBuckets(ordered.size).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { r ->
                        val a = ordered[r.first - 1].number
                        val b = ordered[r.last - 1].number
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Mv.colors.sr)
                                .clickable { onJump(if (newestFirst) r.last else r.first) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${Format.number(a)}–${Format.number(b)}", fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun CategorySheet(
    m: MangaSummary,
    data: UserData,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val current = data.library[m.id]?.category
    val items = data.categories.map { cat -> SheetItem(Icons.Rounded.Folder, cat, { onPick(cat) }, checked = cat == current) } +
        SheetItem(Icons.Rounded.CreateNewFolder, "New category…", onNew) +
        SheetItem(Icons.Rounded.HeartBroken, "Remove from Library", onRemove, danger = true)
    ListSheet("Library category", onDismiss, items, sub = m.title)
}

@Composable
fun NewCategorySheet(hint: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    MvSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New category", fontSize = 17.sp, fontWeight = SemiBold)
            InputRow(text, { text = it }, "e.g. Weekly reads", "Add", { text.trim().takeIf { it.isNotEmpty() }?.let(onAdd) })
            Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = Mv.colors.ts)
        }
    }
}

@Composable
fun ChapterActionsSheet(
    ch: Chapter,
    read: Boolean,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
    onMarkPrevious: () -> Unit,
) {
    ListSheet(
        Format.chapterWithTitle(ch), onDismiss,
        listOf(
            SheetItem(Icons.Rounded.PlayArrow, "Read from start", onRead),
            SheetItem(if (read) Icons.Rounded.RemoveDone else Icons.Rounded.Done, if (read) "Mark as unread" else "Mark as read", { onToggleRead(!read) }),
            SheetItem(Icons.Rounded.DoneAll, "Mark all previous as read", onMarkPrevious),
        ),
    )
}
