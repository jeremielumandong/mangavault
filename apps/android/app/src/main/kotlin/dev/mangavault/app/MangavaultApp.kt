package dev.mangavault.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import org.conscrypt.Conscrypt
import java.security.Security

class MangavaultApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android 9 and older have no TLS 1.3, which some image hosts (MangaDex) now require.
        // OkHttp picks Conscrypt up when it is the first provider.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(1024L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
