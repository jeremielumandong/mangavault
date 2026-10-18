package dev.mangavault.core.store

import dev.mangavault.core.api.ApiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * A value kept in memory as a [StateFlow] and persisted to one JSON file.
 * Reads happen once at construction; writes are coalesced and atomic
 * (temp file + rename), so a crash never leaves a half-written file.
 */
class JsonFileState<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
    private val scope: CoroutineScope,
    private val writeDelayMs: Long = 300,
) {
    private val state = MutableStateFlow(load() ?: default)
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in dirty) {
                delay(writeDelayMs)
                write()
            }
        }
    }

    fun update(fn: (T) -> T) {
        state.update(fn)
        dirty.trySend(Unit)
    }

    fun set(value: T) = update { value }

    suspend fun flush() = withContext(Dispatchers.IO) { write() }

    private suspend fun write() = writeLock.withLock {
        val text = ApiJson.encodeToString(serializer, state.value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun load(): T? = runCatching {
        if (file.isFile) ApiJson.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()
}
