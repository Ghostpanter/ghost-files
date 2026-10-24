package com.ghostpanter.files.ui.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okio.Path.Companion.toOkioPath

/** Shared Coil 3 loader: GIF/animated WebP (coil-gif), SVG, libavif on API 28–30. */
object GhostImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): ImageLoader {
        val cacheDir = context.cacheDir.resolve("coil_image_cache").apply { mkdirs() }
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .components {
                // GIF / animated WebP / APNG (API 28+ ImageDecoder path inside coil-gif)
                add(coil3.gif.AnimatedImageDecoder.Factory())
                add(SvgDecoder.Factory())
                add(AvifDecoder.Factory())
            }
            .allowHardware(true)
            .crossfade(false)
            .build()
    }
}
