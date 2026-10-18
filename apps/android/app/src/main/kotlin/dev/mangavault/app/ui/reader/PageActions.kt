package dev.mangavault.app.ui.reader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import dev.mangavault.core.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import java.io.File
import java.io.IOException

/** Saving and sharing a page's original bytes, taken from Coil's disk cache when possible. */
object PageActions {
    private fun mimeOf(bytes: ByteArray): Pair<String, String> = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png" to "png"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
        bytes.size > 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif" to "gif"
        else -> "image/jpeg" to "jpg"
    }

    suspend fun bytes(ctx: Context, http: OkHttpClient, page: Page, headers: Map<String, String>): ByteArray =
        withContext(Dispatchers.IO) {
            val cache = SingletonImageLoader.get(ctx).diskCache
            cache?.openSnapshot(page.url)?.use { snap ->
                return@withContext FileSystem.SYSTEM.read(snap.data) { readByteArray() }
            }
            val req = Request.Builder().url(page.url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                res.body?.bytes() ?: throw IOException("Empty body")
            }
        }

    /** Writes to Pictures/Mangavault. On API 26–28 the caller must hold WRITE_EXTERNAL_STORAGE. */
    suspend fun save(ctx: Context, bytes: ByteArray, name: String) = withContext(Dispatchers.IO) {
        val (mime, ext) = mimeOf(bytes)
        val file = "$name.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mangavault")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No media store")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Can't write")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Mangavault")
            dir.mkdirs()
            val out = File(dir, file)
            out.writeBytes(bytes)
            MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mime), null)
        }
    }

    /** Shares through the app's FileProvider (cache/shared). */
    suspend fun share(ctx: Context, bytes: ByteArray, name: String, text: String) {
        val uri = withContext(Dispatchers.IO) {
            val (_, ext) = mimeOf(bytes)
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "$name.$ext").apply { writeBytes(bytes) }
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(bytes).first
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Forgets a page so the next load goes back to the network. */
    fun evict(ctx: Context, page: Page) {
        SingletonImageLoader.get(ctx).diskCache?.remove(page.url)
    }
}
