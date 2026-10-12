package com.example.couplewall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * Pre-renders a "liquid glass" heart into a bitmap: soft glow, translucent body, inner refraction rim,
 * bottom caustic, bright outer rim and specular highlights. Rendering once keeps per-frame cost tiny.
 */
object GlassSprites {
    val TINTS = intArrayOf(
        Color.rgb(255, 82, 140),   // rose
        Color.rgb(255, 140, 190),  // blush
        Color.rgb(176, 118, 255),  // violet
        Color.rgb(255, 112, 112),  // coral
        Color.rgb(255, 214, 236),  // pearl
        Color.rgb(132, 178, 255),  // sky
    )

    fun create(size: Int, tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = size / 2f
        val s = half * 0.80f
        val cx = half
        val cy = half + size * 0.03f
        val r = Color.red(tint)
        val g = Color.green(tint)
        val b = Color.blue(tint)

        val path = HeartShape.build()
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(cx, cy)
        path.transform(m)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. ambient glow
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, s * 1.25f, Color.argb(70, r, g, b), Color.argb(0, r, g, b), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 1.25f, p)

        // 2. glass body
        p.shader = RadialGradient(
            cx - s * 0.35f, cy - s * 0.45f, s * 1.7f,
            intArrayOf(Color.argb(205, 255, 255, 255), Color.argb(120, r, g, b), Color.argb(82, r, g, b)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, p)

        // 3. inner refraction rim + bottom caustic (clipped to the heart)
        c.save()
        c.clipPath(path)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.20f
        p.color = Color.argb(95, r, g, b)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx + s * 0.30f, cy + s * 0.55f, s * 0.75f,
            Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx + s * 0.30f, cy + s * 0.55f, s * 0.75f, p)
        c.restore()

        // 4. bright outer rim
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(2f, s * 0.035f)
        p.shader = LinearGradient(
            cx - s, cy - s, cx + s, cy + s,
            intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(190, r, g, b)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, p)

        // 5. specular highlights
        p.style = Paint.Style.FILL
        highlight(c, p, cx - s * 0.55f, cy - s * 0.62f, s * 0.62f, s * 0.20f, -32f, 225)
        highlight(c, p, cx + s * 0.55f, cy - s * 0.62f, s * 0.40f, s * 0.12f, 32f, 120)
        p.shader = RadialGradient(
            cx - s * 0.28f, cy - s * 0.78f, s * 0.07f,
            Color.argb(255, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx - s * 0.28f, cy - s * 0.78f, s * 0.07f, p)
        return bmp
    }

    private fun highlight(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, deg: Float, alpha: Int) {
        p.shader = RadialGradient(x, y, w / 2f, Color.argb(alpha, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.save()
        c.rotate(deg, x, y)
        c.drawOval(RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), p)
        c.restore()
    }
}
