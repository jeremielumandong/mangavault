package dev.mangavault.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangavault.app.AppContainer
import dev.mangavault.app.ui.components.Chip
import dev.mangavault.app.ui.components.Cover
import dev.mangavault.app.ui.components.Logo
import dev.mangavault.app.ui.components.PrimaryButton
import dev.mangavault.app.ui.theme.Dela
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.core.model.MangaSummary

/** Genres offered before the catalog has answered. */
val FALLBACK_GENRES = listOf(
    "Action", "Romance", "Fantasy", "Drama", "Comedy", "Slice of life", "Horror", "Mystery", "Sci-fi", "Martial arts",
    "Historical", "School",
)

@Composable
fun Onboarding(container: AppContainer) {
    var step by rememberSaveable { mutableStateOf(1) }
    AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { s ->
        if (s == 1) Welcome(container, onNext = { step = 2 }) else GenrePicker(container)
    }
}

@Composable
private fun Welcome(container: AppContainer, onNext: () -> Unit) {
    val home by container.homeCache.flow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (container.homeCache.value == null) runCatching { container.homeCache.set(container.api.home()) }
    }
    val covers: List<MangaSummary?> = (home?.popular.orEmpty() + home?.latest.orEmpty()).distinctBy { it.id }.take(9)
        .let { it + List(9 - it.size) { null } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 20.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            covers.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { i, m ->
                        val mod = Modifier.weight(1f).aspectRatio(2f / 3f).offset(y = if (i == 1) (-18).dp else 0.dp)
                        if (m != null) Cover(m.coverUrl, m.coverHeaders, mod)
                        else Box(mod.clip(RoundedCornerShape(8.dp)).background(placeholder(i)))
                    }
                }
            }
        }
        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo(16)
            Text("Tap a chapter. Start reading.", fontFamily = Dela, fontSize = 30.sp, lineHeight = 40.sp, color = Mv.colors.tp)
            Text("Manga, manhwa and webtoons in one library. No account needed.", fontSize = 15.sp, lineHeight = 21.sp, color = Mv.colors.ts)
        }
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Get started", onNext, Modifier.fillMaxWidth())
            if (!container.openAccount) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { container.sheets.signIn.value = "default" },
                    contentAlignment = Alignment.Center,
                ) { Text("I already have an account", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mv.colors.tp) }
            }
        }
    }
}

private fun placeholder(i: Int): Brush {
    val hues = listOf(0xFF5B3A6E, 0xFF2E4A6B, 0xFF6B3A3A, 0xFF2F5B4A, 0xFF6B5A2E)
    val c = Color(hues[i % hues.size])
    return Brush.linearGradient(listOf(c, c.copy(alpha = 0.5f)))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePicker(container: AppContainer) {
    var genres by remember { mutableStateOf(FALLBACK_GENRES) }
    var picked by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        runCatching { container.api.genres() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { genres = it }
    }
    fun done(save: Boolean) {
        container.prefs.update { it.copy(onboarded = true, genres = if (save) picked else it.genres) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(20.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mv.colors.sr))
                Box(Modifier.width(20.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mv.colors.ac))
            }
            Text(
                "Skip", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mv.colors.ts,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { done(false) }.padding(8.dp),
            )
        }
        Text(
            "What do you like to read?", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp, color = Mv.colors.tp, modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "We'll use this to fill Home. Change it any time.", fontSize = 15.sp, lineHeight = 21.sp, color = Mv.colors.ts,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(
            Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            genres.forEach { g ->
                val on = g in picked
                Chip(
                    g, on, { picked = if (on) picked - g else picked + g }, height = 40.dp, fontSize = 15,
                    background = if (on) Mv.colors.ac else Mv.colors.sf, foreground = if (on) Color.White else Mv.colors.tp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (picked.isEmpty()) "Continue" else "Continue with ${picked.size} genre${if (picked.size > 1) "s" else ""}",
            { done(true) }, Modifier.fillMaxWidth(),
        )
    }
}
