package dev.mangavault.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold

fun headersOf(map: Map<String, String>): NetworkHeaders {
    val b = NetworkHeaders.Builder()
    map.forEach { (k, v) -> b.set(k, v) }
    return b.build()
}

/** The 2:3 shimmer-free cover tile; [url] null shows the skeleton color. */
@Composable
fun Cover(
    url: String?,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(Mv.colors.sk)) {
        if (url != null) {
            val req = remember(url, headers) {
                ImageRequest.Builder(ctx).data(url).httpHeaders(headersOf(headers)).build()
            }
            AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))
                )
            )
        }
        overlay()
    }
}

/** The prototype's mvShim: a moving highlight across the skeleton color. */
fun Modifier.shimmer(base: Color? = null, highlight: Color? = null): Modifier = composed {
    val b = base ?: Mv.colors.sk
    val h = highlight ?: Mv.colors.sk2
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = 1f, targetValue = -1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    this.background(
        Brush.linearGradient(
            0.3f to b, 0.5f to h, 0.7f to b,
            start = androidx.compose.ui.geometry.Offset(x * 1000f, 0f),
            end = androidx.compose.ui.geometry.Offset(x * 1000f + 1000f, 300f),
        )
    )
}

@Composable
fun SkeletonBar(width: Dp, height: Dp, modifier: Modifier = Modifier, radius: Dp = 4.dp) {
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(radius)).background(Mv.colors.sk))
}

@Composable
fun MvIcon(icon: ImageVector, size: Dp = 22.dp, tint: Color = Mv.colors.tp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** A 44dp round or rounded icon button with no ripple chrome beyond the shape. */
@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    tint: Color = Mv.colors.tp,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    contentDescription: String? = null,
) {
    Box(
        modifier.size(size).clip(shape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize)) }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    fontSize: Int = 17,
    color: Color = Mv.colors.ac,
    textColor: Color = Color.White,
    radius: Dp = 12.dp,
    sub: String? = null,
) {
    Column(
        modifier.height(height).clip(RoundedCornerShape(radius)).background(color).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = textColor, fontSize = fontSize.sp, lineHeight = 22.sp, fontWeight = SemiBold, maxLines = 1)
        if (sub != null) Text(sub, color = textColor.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 15.sp)
    }
}

/** Compact pill button, e.g. "Retry" (44dp) or "Jump" (36dp). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 36.dp,
    background: Color = Mv.colors.sf,
    textColor: Color = Mv.colors.tp,
    fontSize: Int = 13,
    weight: FontWeight = FontWeight.Medium,
    horizontal: Dp = 12.dp,
    radius: Dp = height / 2,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(radius)).background(background).clickable(onClick = onClick)
            .padding(horizontal = horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) MvIcon(icon, 18.dp, textColor)
        Text(text, color = textColor, fontSize = fontSize.sp, fontWeight = weight, maxLines = 1)
        if (trailingIcon != null) MvIcon(trailingIcon, 18.dp, textColor)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mv.colors.ac, size: Int = 15) {
    Text(
        text, color = color, fontSize = size.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Mv.section, color = Mv.colors.tp, modifier = Modifier.weight(1f))
        if (action != null) TextAction(action, onAction)
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Mv.h1, color = Mv.colors.tp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Logo(size: Int = 24, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text("manga", style = Mv.logo.copy(fontSize = size.sp, lineHeight = (size + 8).sp), color = Mv.colors.tp)
        Text("vault", style = Mv.logo.copy(fontSize = size.sp, lineHeight = (size + 8).sp), color = Mv.colors.ac)
    }
}

/** Full-screen empty/error state with an optional action. */
@Composable
fun StateMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    art: (@Composable () -> Unit)? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    secondary: Boolean = false,
    top: Dp = 110.dp,
) {
    Column(
        modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = top),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) MvIcon(icon, 44.dp, Mv.colors.ts)
        art?.invoke()
        Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = SemiBold, color = Mv.colors.tp, textAlign = TextAlign.Center)
        Text(body, color = Mv.colors.ts, textAlign = TextAlign.Center, style = Mv.body)
        if (action != null) {
            Spacer(Modifier.height(0.dp))
            PillButton(
                action, onAction, height = 44.dp, horizontal = 24.dp, radius = 12.dp,
                background = if (secondary) Mv.colors.sr else Mv.colors.ac,
                textColor = if (secondary) Mv.colors.tp else Color.White,
                fontSize = 15, weight = SemiBold,
            )
        }
    }
}

@Composable
fun Banner(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Mv.colors.wn.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MvIcon(icon, 20.dp, Mv.colors.wn)
        Text(text, style = Mv.small, color = Mv.colors.tp, modifier = Modifier.weight(1f))
    }
}

/** Filter/genre chip: selected chips invert to text color. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    background: Color? = null,
    foreground: Color? = null,
    icon: ImageVector? = null,
    strike: Boolean = false,
    fontSize: Int = 13,
) {
    val bg = background ?: if (selected) Mv.colors.tp else Mv.colors.sr
    val fg = foreground ?: if (selected) Mv.colors.bg else Mv.colors.tp
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) MvIcon(icon, 16.dp, fg)
        Text(
            label, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
    }
}

/** A segmented control (theme picker, fit, grid columns). */
@Composable
fun Segmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    container: Color = Mv.colors.sf,
    active: Color = Mv.colors.sr,
    radius: Dp = 12.dp,
    padding: Dp = 4.dp,
) {
    Row(
        modifier.clip(RoundedCornerShape(radius)).background(container).padding(padding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(radius - padding + 1.dp))
                    .background(if (o == selected) active else Color.Transparent).clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = SemiBold, color = Mv.colors.tp, maxLines = 1) }
        }
    }
}

/** The 46×28 switch from the prototype. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val knob by animateDpAsState(if (on) 21.dp else 3.dp, label = "knob")
    Box(modifier.width(46.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Mv.colors.ac else Mv.colors.sk2)) {
        Box(Modifier.offset(x = knob, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

/** A row in a grouped settings card. */
@Composable
fun SettingRow(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    first: Boolean,
    value: String? = null,
    toggle: Boolean? = null,
    chevron: Boolean = false,
    labelColor: Color = Mv.colors.tp,
) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mv.colors.ln))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) MvIcon(icon, 20.dp, Mv.colors.ts)
            Text(label, style = Mv.body, color = labelColor, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = Mv.body, color = Mv.colors.ts)
            if (toggle != null) Toggle(toggle)
            if (chevron) MvIcon(Icons.Rounded.ChevronRight, 20.dp, Mv.colors.ts)
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mv.colors.sf)) { content() }
}

@Composable
fun GroupLabel(text: String) {
    Text(text, style = Mv.small, color = Mv.colors.ts, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

/** A thin progress bar. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = Mv.colors.sr, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(Mv.colors.ac))
    }
}

/** Title under a cover, clamped to two lines. */
@Composable
fun CoverTitle(text: String, size: Int = 13, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = SemiBold, color = Mv.colors.tp,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/** Grid/row cover card used by Home, Search and Library. */
@Composable
fun MangaTile(
    title: String,
    coverUrl: String?,
    coverHeaders: Map<String, String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleSize: Int = 13,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Column(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Cover(coverUrl, coverHeaders, Modifier.fillMaxWidth().aspectRatio(2f / 3f), overlay = overlay)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CoverTitle(title, titleSize)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, lineHeight = 13.sp, color = Mv.colors.ts, maxLines = 1)
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, background: Color = Mv.colors.ac, color: Color = Color.White) {
    Box(
        modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(background).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 11.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun Dot(modifier: Modifier = Modifier, color: Color = Mv.colors.ac, ring: Color = Color.Black.copy(alpha = 0.45f)) {
    Box(modifier.size(14.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}
