package dev.mangavault.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.mangavault.core.api.ApiException
import dev.mangavault.core.api.HttpMangavaultApi
import dev.mangavault.core.model.AuthResponse
import dev.mangavault.core.model.HomeFeed
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.Cursors
import dev.mangavault.core.store.JsonFileState
import dev.mangavault.core.store.Pending
import dev.mangavault.core.store.Prefs
import dev.mangavault.core.store.Session
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val offline: Boolean) : SyncStatus
}

data class Toast(val id: Long, val message: String, val undo: (() -> Unit)?)

/** App-wide toasts with an optional Undo, shown above the tab bar. */
class Toasts(private val scope: CoroutineScope) {
    private val state = MutableStateFlow<Toast?>(null)
    val current: StateFlow<Toast?> = state.asStateFlow()
    private var job: Job? = null
    private var seq = 0L

    fun show(message: String, undo: (() -> Unit)? = null) {
        job?.cancel()
        val t = Toast(++seq, message, undo)
        state.value = t
        job = scope.launch {
            delay(2600)
            if (state.value?.id == t.id) state.value = null
        }
    }

    fun dismiss() {
        state.value = null
    }
}

/** App-level sheets that any screen can open. */
class AccountSheets {
    /** null = closed; otherwise the context ("fav" or "default"). */
    val signIn = MutableStateFlow<String?>(null)
    val merge = MutableStateFlow(false)
}

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(true)
    private val _metered = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    init {
        runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            _metered.value = cm.isActiveNetworkMetered
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            })
        }
    }
}

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val session = JsonFileState(File(app.noBackupFilesDir, "session.json"), Session.serializer().nullable, null, scope)
    val user = JsonFileState(File(app.filesDir, "user.json"), UserData.serializer(), UserData(), scope)
    val prefs = JsonFileState(File(app.filesDir, "prefs.json"), Prefs.serializer(), Prefs(), scope)
    val homeCache = JsonFileState(File(app.cacheDir, "home.json"), HomeFeed.serializer().nullable, null, scope)

    val api = HttpMangavaultApi(BuildConfig.API_BASE_URL, http) { session.value?.token }
    private val engine = SyncEngine(api, user)
    val network = NetworkMonitor(app)
    val toasts = Toasts(scope)
    val sheets = AccountSheets()

    private val _sync = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _sync.asStateFlow()

    val signedIn: Boolean get() = session.value != null

    /** Summaries seen in lists, so pushed screens can draw a header before their request returns. */
    private val summaries = java.util.concurrent.ConcurrentHashMap<Long, MangaSummary>()

    fun cacheSummary(m: MangaSummary) {
        summaries[m.id] = m
    }

    fun summary(id: Long): MangaSummary? = summaries[id] ?: user.value.manga[id]

    private var syncJob: Job? = null

    /** Debounced background sync; a no-op when signed out. */
    fun requestSync(delayMs: Long = 1500) {
        if (!signedIn) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    suspend fun syncNow() {
        if (!signedIn) return
        _sync.value = SyncStatus.Syncing
        _sync.value = try {
            engine.sync()
            SyncStatus.Done(System.currentTimeMillis())
        } catch (e: ApiException) {
            if (e.isUnauthorized) signOutLocally()
            SyncStatus.Failed(offline = e.isNetwork)
        } catch (e: Exception) {
            SyncStatus.Failed(offline = false)
        }
    }

    fun onSignedIn(res: AuthResponse, provider: String) {
        session.set(Session(res.token, res.user, provider))
        if (user.value.hasLocalData) sheets.merge.value = true else requestSync(0)
    }

    fun mergeLocal() {
        user.update { UserOps.markAllPending(it) }
        sheets.merge.value = false
        toasts.show("Signed in · library merged and synced")
        requestSync(0)
    }

    fun useAccountOnly() {
        user.update { UserOps.wipePersonal(it) }
        sheets.merge.value = false
        toasts.show("Signed in")
        requestSync(0)
    }

    fun signOut() {
        val token = session.value?.token
        signOutLocally()
        toasts.show("Signed out · local data kept")
        if (token != null) scope.launch {
            runCatching {
                HttpMangavaultApi(BuildConfig.API_BASE_URL, http) { token }.logout()
            }
        }
    }

    private fun signOutLocally() {
        session.set(null)
        user.update { it.copy(pending = Pending(), cursors = Cursors()) }
        _sync.value = SyncStatus.Idle
    }
}
