package com.example.couplewall

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.ExifInterface
import android.net.Uri
import android.view.Display
import com.example.couplewall.core.Stroke
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Builds wallpaper bitmaps (background + shared drawing) and hands them to the system. */
object LockRenderer {
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 4
    }

    /** Real screen size in portrait orientation. */
    fun screenSize(ctx: Context): Pair<Int, Int> {
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        return Pair(min(p.x, p.y), max(p.x, p.y))
    }

    fun customFile(ctx: Context) = File(ctx.filesDir, "custom.jpg")

    /** Background bitmap for a wallpaper id ("custom" = the user's own photo). Do not modify the result. */
    @Synchronized fun background(ctx: Context, id: String, w: Int, h: Int): Bitmap {
        val stamp = if (id == "custom") customFile(ctx).lastModified() else 0L
        val key = "$id:$w:$h:$stamp"
        cache[key]?.let { if (!it.isRecycled) return it }
        val bmp = if (id == "custom" && customFile(ctx).exists()) {
            BitmapFactory.decodeFile(customFile(ctx).path)?.let { fit(it, w, h) } ?: Wallpapers.render("rose", w, h)
        } else {
            Wallpapers.render(id, w, h)
        }
        cache[key] = bmp
        return bmp
    }

    /** Background with the shared drawing on top, at full screen size. */
    fun render(ctx: Context, id: String, strokes: List<Stroke>, w: Int, h: Int): Bitmap {
        val out = background(ctx, id, w, h).copy(Bitmap.Config.ARGB_8888, true)
        StrokeRenderer.draw(Canvas(out), strokes, 0f, 0f, w.toFloat(), h.toFloat())
        return out
    }

    fun applyLock(ctx: Context, bgId: String, strokes: List<Stroke>) {
        val (w, h) = screenSize(ctx)
        val bmp = render(ctx, bgId, strokes, w, h)
        setWallpaper(ctx, bmp, WallpaperManager.FLAG_LOCK)
        bmp.recycle()
    }

    fun setWallpaper(ctx: Context, bmp: Bitmap, which: Int) {
        WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, which)
    }

    /** Stores a photo picked by the user, cropped to the screen aspect. */
    @Synchronized fun saveCustom(ctx: Context, uri: Uri): Boolean {
        val (w, h) = screenSize(ctx)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val rotation = ctx.contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val upright = if (rotation != 0) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else {
            src
        }
        val fitted = fit(upright, w, h)
        FileOutputStream(customFile(ctx)).use { fitted.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        cache.clear()
        return true
    }

    private fun exifRotation(stream: InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** Scale to cover w x h and crop the centre. */
    private fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
    }
}
