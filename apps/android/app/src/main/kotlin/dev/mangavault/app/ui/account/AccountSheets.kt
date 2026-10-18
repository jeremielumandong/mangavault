package dev.mangavault.app.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.mangavault.app.AppContainer
import dev.mangavault.app.BuildConfig
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.MvSheet
import dev.mangavault.app.ui.components.PrimaryButton
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.api.ApiException
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun AccountSheetsHost(container: AppContainer) {
    val signIn by container.sheets.signIn.collectAsStateWithLifecycle()
    val merge by container.sheets.merge.collectAsStateWithLifecycle()
    signIn?.let { ctx -> SignInSheet(container, ctx) { container.sheets.signIn.value = null } }
    if (merge) MergeSheet(container)
}

@Composable
private fun SignInSheet(container: AppContainer, context: String, onDismiss: () -> Unit) {
    val activityContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun finish(provider: String, body: Map<String, String>) {
        scope.launch {
            try {
                val res = container.api.signIn(provider, body)
                onDismiss()
                container.onSignedIn(res, provider)
            } catch (e: ApiException) {
                container.toasts.show(if (e.isNetwork) "Couldn't reach Mangavault. Try again." else "Sign-in failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun google() {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            container.toasts.show("Google sign-in isn't set up in this build")
            return
        }
        val activity = activityContext.findActivity() ?: return
        busy = true
        scope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val cred = result.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    finish("google", mapOf("idToken" to token))
                } else {
                    busy = false
                    container.toasts.show("Sign-in failed")
                }
            } catch (e: GetCredentialCancellationException) {
                busy = false
            } catch (e: GetCredentialException) {
                busy = false
                container.toasts.show("Google sign-in isn't available on this phone")
            }
        }
    }

    MvSheet(onDismiss, horizontal = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (context == "fav") "Saved to this phone. Sync it everywhere?" else "Sign in to Mangavault",
                fontSize = 22.sp, lineHeight = 28.sp, fontWeight = SemiBold,
            )
            Text(
                "Sync favorites, reading progress and history across your devices. Reading never needs an account.",
                fontSize = 15.sp, lineHeight = 21.sp, color = Mv.colors.ts, modifier = Modifier.padding(bottom = 8.dp),
            )
            // Sign in with Apple is iOS-only; Android signs in with Google.
            Row(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(Mv.colors.sr)
                    .border(0.5.dp, Mv.colors.ln, RoundedCornerShape(12.dp)).clickable(enabled = !busy) { google() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Mv.colors.tp, strokeWidth = 2.dp)
                } else {
                    GoogleMark()
                    Text("Sign in with Google", fontSize = 16.sp, fontWeight = SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (BuildConfig.DEV_SIGN_IN) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) {
                        busy = true
                        finish("dev", mapOf("name" to "Developer"))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("Developer sign-in (debug build)", fontSize = 15.sp, color = Mv.colors.ac, fontWeight = FontWeight.Medium) }
            }
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text("Not now", fontSize = 15.sp, color = Mv.colors.ts) }
        }
    }
}

/** Google's four-color mark, drawn rather than shipped as an asset. */
@Composable
private fun GoogleMark() {
    Canvas(Modifier.size(18.dp)) {
        val colors = listOf(Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853), Color(0xFF4285F4))
        colors.forEachIndexed { i, c ->
            drawArc(c, startAngle = -90f + i * 90f, sweepAngle = 90f, useCenter = true, size = Size(size.width, size.height))
        }
    }
}

@Composable
private fun MergeSheet(container: AppContainer) {
    val data by container.user.flow.collectAsStateWithLifecycle()
    MvSheet(onDismiss = { container.mergeLocal() }, horizontal = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MvIcon(Icons.Rounded.CloudSync, 36.dp, Mv.colors.ok)
            Text("Add this phone's reading to your account?", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = SemiBold)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mv.colors.sr).padding(horizontal = 14.dp, vertical = 4.dp)) {
                listOf(
                    "Favorites" to "${data.favorites.size} titles",
                    "Reading progress" to "${data.progress.size} titles",
                    "History" to "${data.history.size} entries",
                ).forEachIndexed { i, (label, value) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mv.colors.ln))
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(value, fontSize = 15.sp, color = Mv.colors.ts)
                    }
                }
            }
            Text("If something changed in both places, the most recent change wins.", fontSize = 13.sp, lineHeight = 18.sp, color = Mv.colors.ts)
            PrimaryButton("Merge and sync", { container.mergeLocal() }, Modifier.fillMaxWidth(), height = 50.dp, fontSize = 16)
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable { container.useAccountOnly() },
                contentAlignment = Alignment.Center,
            ) { Text("Use account data only", fontSize = 15.sp, color = Mv.colors.ts) }
        }
    }
}
