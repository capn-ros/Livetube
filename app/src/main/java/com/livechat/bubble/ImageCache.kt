package com.livechat.bubble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Descarga y cachea imágenes (avatares, emojis) sin depender de otra librería. */
object ImageCache {
    private val client = OkHttpClient()
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(url, it) }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { ImageCache.get(it)?.asImageBitmap() }
    }
    bmp?.let { Image(bitmap = it, contentDescription = null, modifier = modifier) }
}
