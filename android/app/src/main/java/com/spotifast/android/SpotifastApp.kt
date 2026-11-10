package com.spotifast.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class SpotifastApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Spotifast.init(this)
    }

    /** Covers come back often: keep plenty in memory and on disk. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(128L * 1024 * 1024).build() }
        .crossfade(120)
        .build()
}
