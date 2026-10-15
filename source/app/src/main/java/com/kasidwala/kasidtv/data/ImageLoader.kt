package com.kasidwala.kasidtv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.kasidwala.kasidtv.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tiny poster loader: memory LRU + disk cache, no dependencies.
 * The view's tag guards against recycled-view mixups.
 */
object ImageLoader {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)

    fun load(iv: ImageView, url: String?) {
        iv.tag = url
        if (url.isNullOrBlank()) {
            iv.setImageResource(R.drawable.poster_placeholder)
            return
        }
        mem.get(url)?.let {
            if (iv.tag == url) iv.setImageBitmap(it)
            return
        }
        iv.setImageResource(R.drawable.poster_placeholder)
        pool.execute {
            try {
                val bmp = fetch(iv, url) ?: return@execute
                mem.put(url, bmp)
                iv.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
                // leave the placeholder
            }
        }
    }

    private fun fetch(iv: ImageView, url: String): Bitmap? {
        val dir = File(iv.context.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, url.hashCode().toString() + ".img")
        if (f.exists()) {
            BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
            f.delete()
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; TV) KasidTV/1.0"
                )
                connectTimeout = 12000
                readTimeout = 12000
            }
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.readBytes()
            // Downsample to poster-ish size to keep memory sane.
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 300) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )?.also {
                try {
                    f.outputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun clearDiskCache(iv: ImageView) {
        File(iv.context.cacheDir, "posters").deleteRecursively()
    }
}
