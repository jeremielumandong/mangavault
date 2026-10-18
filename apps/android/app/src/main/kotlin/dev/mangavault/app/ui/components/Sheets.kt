package dev.mangavault.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangavault.app.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import dev.mangavault.app.Toasts
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold

/** Bottom sheet with the prototype's 16dp top corners and 36×5 handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MvSheet(
    onDismiss: () -> Unit,
    scrim: Color = Color.Black.copy(alpha = 0.45f),
    horizontal: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Mv.colors.sf,
        contentColor = Mv.colors.tp,
        scrimColor = scrim,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier.padding(top = 8.dp, bottom = 10.dp).width(36.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Mv.colors.ts.copy(alpha = 0.4f))
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = horizontal).padding(bottom = 12.dp)) { content() }
    }
}

data class SheetItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val sub: String? = null,
    val checked: Boolean = false,
    val highlight: Boolean = false,
    val danger: Boolean = false,
    val bold: Boolean = false,
    val iconTint: Color? = null,
)

/** The prototype's generic list sheet (sort, chapter actions, categories, chapter list). */
@Composable
fun ListSheet(
    title: String,
    onDismiss: () -> Unit,
    items: List<SheetItem>,
    sub: String? = null,
    initialIndex: Int = 0,
    scrim: Color = Color.Black.copy(alpha = 0.45f),
) {
    MvSheet(onDismiss, scrim = scrim, horizontal = 0.dp) {
        Text(title, fontSize = 17.sp, fontWeight = SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
        if (sub != null) Text(sub, style = Mv.small, color = Mv.colors.ts, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        // Lazy, so a 1,000-chapter list opens instantly.
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 2).coerceAtLeast(0))
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp), state = state) {
            items(items.size) { SheetRow(items[it]) }
        }
    }
}

@Composable
fun SheetRow(o: SheetItem) {
    val color = if (o.danger) Mv.colors.ac else Mv.colors.tp
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).background(if (o.highlight) Mv.colors.acs else Color.Transparent)
            .clickable(onClick = o.onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MvIcon(o.icon, 22.dp, o.iconTint ?: if (o.danger) Mv.colors.ac else Mv.colors.ts)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(o.label, fontSize = 15.sp, color = color, fontWeight = if (o.bold) FontWeight.Bold else FontWeight.Medium)
            if (!o.sub.isNullOrEmpty()) Text(o.sub, fontSize = 12.sp, color = Mv.colors.ts, maxLines = 1)
        }
        if (o.checked) MvIcon(Icons.Rounded.Check, 20.dp, Mv.colors.ac)
    }
}

/** The dark toast with an optional Undo. */
@Composable
fun ToastHost(toasts: Toasts, bottom: Dp, modifier: Modifier = Modifier) {
    val toast by toasts.current.collectAsStateWithLifecycle()
    // Keep the last toast so its text stays on screen while it fades out.
    var shown by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toast) { toast?.let { shown = it } }
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = bottom), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = fadeOut(),
        ) {
            val t = toast ?: shown ?: return@AnimatedVisibility
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).shadow(10.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A32)).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(t.message, color = Color(0xFFF2F2F5), fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                val undo = t.undo
                if (undo != null) {
                    Text(
                        "Undo", color = Color(0xFFFF8A8D), fontSize = 14.sp, fontWeight = SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            toasts.dismiss()
                            undo()
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Bottom padding that clears the system navigation bar. */
@Composable
fun Modifier.navBarPadding() = this.windowInsetsPadding(WindowInsets.navigationBars)

@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = SemiBold, modifier = modifier)
}

@Composable
fun SheetSpacer(h: Dp = 12.dp) = Box(Modifier.size(h))
