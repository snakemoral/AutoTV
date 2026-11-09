package com.autotv.player.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import okhttp3.Request

/** Cache de miniaturas em memória. load() é bloqueante: chame em Dispatchers.IO. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val iconCache = HashMap<String, CarIcon>()

    fun cached(url: String): Bitmap? = cache.get(url)

    fun load(url: String, targetWidth: Int = 400): Bitmap? {
        cache.get(url)?.let { return it }
        synchronized(failed) { if (url in failed) return null }
        return try {
            val req = Request.Builder().url(url).header("User-Agent", Http.UA_WEB).build()
            val bytes = Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()
            } ?: throw IllegalStateException("sem corpo")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IllegalStateException("decode")
            cache.put(url, bmp)
            bmp
        } catch (e: Exception) {
            synchronized(failed) { failed += url }
            null
        }
    }

    fun carIcon(url: String, bmp: Bitmap): CarIcon = synchronized(iconCache) {
        iconCache.getOrPut(url) { CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build() }
    }

    private val placeholder: CarIcon by lazy {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#2A2A2E"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55FFFFFF") }
        val path = Path().apply { moveTo(135f, 60f); lineTo(135f, 120f); lineTo(190f, 90f); close() }
        c.drawPath(path, p)
        CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    fun placeholderIcon(): CarIcon = placeholder
}
