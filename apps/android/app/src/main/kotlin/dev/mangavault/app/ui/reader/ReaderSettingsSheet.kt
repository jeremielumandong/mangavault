package dev.mangavault.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.MvSheet
import dev.mangavault.app.ui.components.Segmented
import dev.mangavault.app.ui.components.Toggle
import dev.mangavault.app.ui.settings.READER_BACKGROUNDS
import dev.mangavault.app.ui.settings.READER_FITS
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.store.ReaderPrefs
import dev.mangavault.core.util.ReaderModes

private val MODE_OPTIONS = listOf(
    Triple(ReaderModes.VERTICAL, "Webtoon scroll", Icons.Rounded.ViewDay),
    Triple(ReaderModes.RTL, "Right to left", Icons.Rounded.AutoStories),
    Triple(ReaderModes.LTR, "Left to right", Icons.AutoMirrored.Rounded.MenuBook),
    Triple(ReaderModes.PAGED_VERTICAL, "Vertical pages", Icons.Rounded.SwapVert),
)

/** Mode is saved for this title; the rest are the reader defaults, shared by every title. */
@Composable
fun ReaderSettingsSheet(
    mode: String,
    prefs: ReaderPrefs,
    onMode: (String) -> Unit,
    onPrefs: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    MvSheet(onDismiss, scrim = Color.Transparent) {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.54f).dp
        Column(Modifier.fillMaxWidth().height(maxH).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reader settings", fontSize = 17.sp, fontWeight = SemiBold, modifier = Modifier.weight(1f))
                Text("Mode saved for this title", fontSize = 12.sp, color = Mv.colors.ts)
            }
            Label("Mode", top = 14)
            MODE_OPTIONS.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (key, label, icon) ->
                        val on = key == mode
                        Row(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Mv.colors.acs else Mv.colors.sr)
                                .border(1.5.dp, if (on) Mv.colors.ac else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { onMode(key) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MvIcon(icon, 20.dp)
                            Text(label, fontSize = 13.sp, fontWeight = SemiBold)
                        }
                    }
                }
            }
            Label("Background", top = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                READER_BACKGROUNDS.forEach { (name, color) ->
                    val on = prefs.background == name
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onPrefs { it.copy(background = name) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(48.dp).then(
                                if (on) Modifier.border(2.dp, Mv.colors.ac, CircleShape).padding(4.dp)
                                else Modifier.padding(2.dp).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape),
                            ).clip(CircleShape).background(color),
                        )
                        Text(name, fontSize = 12.sp)
                    }
                }
            }
            Label("Fit", top = 16)
            Segmented(READER_FITS, prefs.fit, { f -> onPrefs { it.copy(fit = f) } }, Modifier.fillMaxWidth(), height = 44.dp, container = Mv.colors.sr, active = Mv.colors.sf)
            Label("Brightness", top = 16)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MvIcon(Icons.Rounded.BrightnessLow, 20.dp, Mv.colors.ts)
                Slider(
                    value = prefs.brightness.toFloat(),
                    onValueChange = { v -> onPrefs { it.copy(brightness = v.toInt()) } },
                    valueRange = 20f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Mv.colors.ac, activeTrackColor = Mv.colors.ac, inactiveTrackColor = Mv.colors.sk2),
                )
                MvIcon(Icons.Rounded.BrightnessHigh, 20.dp, Mv.colors.ts)
            }
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mv.colors.sr)) {
                val rows = listOf(
                    Triple<String, Boolean, (ReaderPrefs) -> ReaderPrefs>("Page gap (webtoon)", prefs.gap, { p: ReaderPrefs -> p.copy(gap = !p.gap) }),
                    Triple<String, Boolean, (ReaderPrefs) -> ReaderPrefs>("Dim overlay", prefs.dim, { p: ReaderPrefs -> p.copy(dim = !p.dim) }),
                    Triple<String, Boolean, (ReaderPrefs) -> ReaderPrefs>("Keep screen on", prefs.keepOn, { p: ReaderPrefs -> p.copy(keepOn = !p.keepOn) }),
                    Triple<String, Boolean, (ReaderPrefs) -> ReaderPrefs>("Show page number", prefs.showNum, { p: ReaderPrefs -> p.copy(showNum = !p.showNum) }),
                    Triple<String, Boolean, (ReaderPrefs) -> ReaderPrefs>("Invert tap zones", prefs.invert, { p: ReaderPrefs -> p.copy(invert = !p.invert) }),
                )
                rows.forEachIndexed { i, (label, on, flip) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mv.colors.ln))
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clickable { onPrefs(flip) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Toggle(on)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, top: Int) {
    Text(text, fontSize = 13.sp, color = Mv.colors.ts, modifier = Modifier.padding(top = top.dp, bottom = 8.dp))
}
