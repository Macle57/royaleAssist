package com.macle.royaleassist.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.macle.royaleassist.lookup.HttpClients
import io.ktor.client.HttpClient
import okio.Path

/**
 * Card images (Coil 3). Every `AsyncImage` uses the singleton loader set up here: images come
 * through the app's own Ktor [HttpClient] (Android: the OkHttp engine with the WebView cookie jar;
 * iOS: the Darwin engine), the memory cache takes Coil's default share of the app's memory and the
 * disk cache 2% of the free space (10 MB..250 MB), as with Coil 2 before.
 */
object CardImages {
    /** Call once at start-up, before the first image loads; the client is created on first use. */
    fun install(http: HttpClients, diskCacheDir: Path) = install({ http.client }, diskCacheDir)

    fun install(client: () -> HttpClient, diskCacheDir: Path) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory(httpClient = client)) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context).build() }
                .diskCache { DiskCache.Builder().directory(diskCacheDir).maxSizePercent(0.02).build() }
                .build()
        }
    }
}
