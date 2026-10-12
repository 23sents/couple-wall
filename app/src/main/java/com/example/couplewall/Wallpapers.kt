package com.example.couplewall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Procedurally generated wallpapers: resolution independent, nothing to download, nothing stored. */
object Wallpapers {
    class Item(val id: String, val name: String)

    private class Palette(val id: String, val name: String, val top: Int, val bottom: Int, val b1: Int, val b2: Int, val b3: Int)

    private val palettes = listOf(
        Palette("sunset", "Закат", Color.rgb(255, 138, 101), Color.rgb(106, 27, 154), Color.rgb(255, 64, 129), Color.rgb(255, 193, 7), Color.rgb(124, 77, 255)),
        Palette("lavender", "Лаванда", Color.rgb(205, 180, 255), Color.rgb(112, 84, 196), Color.rgb(255, 170, 220), Color.rgb(130, 200, 255), Color.rgb(200, 160, 255)),
        Palette("mint", "Мята", Color.rgb(170, 240, 215), Color.rgb(24, 112, 116), Color.rgb(120, 255, 210), Color.rgb(100, 200, 255), Color.rgb(255, 255, 200)),
        Palette("peach", "Персик", Color.rgb(255, 214, 186), Color.rgb(226, 96, 118), Color.rgb(255, 170, 140), Color.rgb(255, 120, 160), Color.rgb(255, 230, 170)),
        Palette("ocean", "Океан", Color.rgb(60, 160, 230), Color.rgb(8, 30, 90), Color.rgb(0, 220, 255), Color.rgb(80, 120, 255), Color.rgb(0, 160, 200)),
        Palette("night", "Ночь", Color.rgb(34, 28, 78), Color.rgb(8, 8, 24), Color.rgb(90, 70, 200), Color.rgb(200, 60, 160), Color.rgb(40, 90, 200)),
        Palette("rose", "Роза", Color.rgb(255, 120, 160), Color.rgb(110, 10, 60), Color.rgb(255, 80, 140), Color.rgb(255, 170, 200), Color.rgb(180, 40, 120)),
        Palette("gold", "Золото", Color.rgb(255, 214, 140), Color.rgb(120, 60, 20), Color.rgb(255, 190, 70), Color.rgb(255, 120, 60), Color.rgb(255, 235, 170)),
    )

    /** Paired wallpapers (one photo split in two halves) live in assets/<id>.jpg. */
    fun isAsset(id: String): Boolean = id.startsWith("pair_")

    val items: List<Item> = listOf(Item("pair_him", "Парные · он"), Item("pair_her", "Парные · она")) +
        palettes.map { Item(it.id, it.name) } + listOf(
        Item("hearts", "Сердца"), Item("stars", "Звёзды"), Item("waves", "Волны"),
        Item("bokeh", "Боке"), Item("aurora", "Аврора"),
    )

    fun nameOf(id: String): String = items.firstOrNull { it.id == id }?.name ?: "Своё фото"

    fun render(id: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = palettes.firstOrNull { it.id == id }
        when {
            p != null -> mesh(c, w, h, p)
            id == "hearts" -> hearts(c, w, h)
            id == "stars" -> stars(c, w, h)
            id == "waves" -> waves(c, w, h)
            id == "bokeh" -> bokeh(c, w, h)
            id == "aurora" -> aurora(c, w, h)
            else -> mesh(c, w, h, palettes[6])
        }
        return bmp
    }

    // ------------------------------------------------------------------ building blocks
    private fun gradient(c: Canvas, w: Int, h: Int, top: Int, bottom: Int) {
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    /** Soft elliptical glow: unit radial gradient scaled by the canvas. */
    private fun glow(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Float, color: Int, alpha: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            0f, 0f, 1f, Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP
        )
        c.save()
        c.translate(cx, cy)
        c.rotate(rotDeg)
        c.scale(rx, ry)
        c.drawCircle(0f, 0f, 1f, p)
        c.restore()
    }

    private fun vignette(c: Canvas, w: Int, h: Int) {
        val p = Paint()
        p.shader = RadialGradient(
            w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) / 2f,
            intArrayOf(Color.TRANSPARENT, 0x66000000), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        val path = Path()
        path.moveTo(x, y - size)
        path.quadTo(x + size * 0.12f, y - size * 0.12f, x + size, y)
        path.quadTo(x + size * 0.12f, y + size * 0.12f, x, y + size)
        path.quadTo(x - size * 0.12f, y + size * 0.12f, x - size, y)
        path.quadTo(x - size * 0.12f, y - size * 0.12f, x, y - size)
        path.close()
        c.drawPath(path, paint)
    }

    // ------------------------------------------------------------------ styles
    private fun mesh(c: Canvas, w: Int, h: Int, p: Palette) {
        gradient(c, w, h, p.top, p.bottom)
        glow(c, w * 0.15f, h * 0.15f, w * 0.9f, h * 0.35f, -17f, p.b1, 170)
        glow(c, w * 0.90f, h * 0.50f, w * 0.8f, h * 0.30f, 23f, p.b2, 150)
        glow(c, w * 0.30f, h * 0.90f, w * 0.9f, h * 0.28f, 6f, p.b3, 150)
        vignette(c, w, h)
    }

    private fun hearts(c: Canvas, w: Int, h: Int) {
        mesh(c, w, h, palettes.first { it.id == "rose" })
        val sprites = Array(4) { GlassSprites.create(320, GlassSprites.TINTS[it]) }
        val r = Random(42)
        val sizes = List(16) { w * r.nextFloat() * 0.32f + w * 0.14f }.sortedDescending()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for ((i, s) in sizes.withIndex()) {
            val x = w * (0.05f + r.nextFloat() * 0.9f)
            val y = h * (0.05f + r.nextFloat() * 0.9f)
            paint.alpha = 150 + r.nextInt(105)
            c.save()
            c.translate(x, y)
            c.rotate(r.nextFloat() * 56f - 28f)
            val sc = s / 320f
            c.scale(sc, sc)
            c.drawBitmap(sprites[i % sprites.size], -160f, -160f, paint)
            c.restore()
        }
        sprites.forEach { it.recycle() }
    }

    private fun stars(c: Canvas, w: Int, h: Int) {
        val u = w / 1080f
        val r = Random(5)
        gradient(c, w, h, Color.rgb(20, 18, 56), Color.rgb(5, 6, 20))
        glow(c, w * 0.78f, h * 0.17f, w * 0.5f, w * 0.5f, 0f, Color.rgb(200, 210, 255), 140)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(245, 245, 255)
        c.drawCircle(w * 0.78f, h * 0.17f, w * 0.075f, p)
        repeat(150) {
            p.color = Color.argb(90 + r.nextInt(165), 255, 255, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, u * (0.8f + r.nextFloat() * 1.8f), p)
        }
        repeat(14) {
            p.color = Color.argb(160 + r.nextInt(95), 255, 240, 250)
            sparkle(c, r.nextFloat() * w, r.nextFloat() * h, u * (8f + r.nextFloat() * 14f), p)
        }
    }

    private fun waves(c: Canvas, w: Int, h: Int) {
        gradient(c, w, h, Color.rgb(120, 205, 236), Color.rgb(10, 40, 110))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        for (k in 0 until 5) {
            val base = h * (0.50f + 0.095f * k)
            val amp = h * 0.018f * (1f + 0.25f * k)
            val len = w * (0.9f - 0.08f * k)
            path.rewind()
            path.moveTo(0f, h.toFloat())
            var x = 0f
            while (x <= w + 12f) {
                path.lineTo(x, base + amp * sin(x / len * 6.2832f + k * 1.3f))
                x += 12f
            }
            path.lineTo(w.toFloat(), h.toFloat())
            path.close()
            val f = k / 4f
            p.color = Color.argb(120 + k * 28, (170 - 150 * f).toInt(), (225 - 155 * f).toInt(), (245 - 95 * f).toInt())
            c.drawPath(path, p)
        }
    }

    private fun bokeh(c: Canvas, w: Int, h: Int) {
        mesh(c, w, h, palettes.first { it.id == "night" })
        val u = w / 1080f
        val r = Random(9)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u * 2f }
        val tints = intArrayOf(Color.rgb(255, 150, 200), Color.rgb(160, 180, 255), Color.rgb(255, 230, 255), Color.rgb(255, 180, 130))
        repeat(46) {
            val t = tints[r.nextInt(tints.size)]
            val rad = u * (20f + r.nextFloat() * 90f)
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            fill.color = Color.argb(40 + r.nextInt(40), Color.red(t), Color.green(t), Color.blue(t))
            ring.color = Color.argb(70 + r.nextInt(60), Color.red(t), Color.green(t), Color.blue(t))
            c.drawCircle(x, y, rad, fill)
            c.drawCircle(x, y, rad, ring)
        }
    }

    private fun aurora(c: Canvas, w: Int, h: Int) {
        gradient(c, w, h, Color.rgb(4, 10, 30), Color.rgb(8, 48, 72))
        glow(c, w * 0.40f, h * 0.32f, w * 0.9f, h * 0.10f, -24f, Color.rgb(60, 255, 170), 190)
        glow(c, w * 0.65f, h * 0.45f, w * 0.8f, h * 0.09f, -30f, Color.rgb(60, 200, 255), 170)
        glow(c, w * 0.30f, h * 0.58f, w * 0.8f, h * 0.08f, -20f, Color.rgb(150, 100, 255), 150)
        glow(c, w * 0.55f, h * 0.25f, w * 0.7f, h * 0.06f, -35f, Color.rgb(120, 255, 210), 120)
        val r = Random(3)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = w / 1080f
        repeat(70) {
            p.color = Color.argb(80 + r.nextInt(160), 255, 255, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.7f, u * (0.7f + r.nextFloat() * 1.4f), p)
        }
    }
}
