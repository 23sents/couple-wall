package com.example.couplewall

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.example.couplewall.core.Stroke
import kotlin.math.max

/** Draws strokes as soft glowing lines (glow + colour core + white highlight) into a rectangle of the canvas. */
object StrokeRenderer {
    fun draw(c: Canvas, strokes: List<Stroke>, left: Float, top: Float, w: Float, h: Float) {
        val glow = linePaint()
        val core = linePaint()
        val hi = linePaint()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        for (s in strokes) {
            val n = s.size
            if (n == 0) continue
            val pw = max(2f, s.width * w)
            if (n == 1) {
                val x = left + s.xs[0] * w
                val y = top + s.ys[0] * h
                dot.color = withAlpha(s.color, 70)
                c.drawCircle(x, y, pw * 1.3f, dot)
                dot.color = s.color or (0xFF shl 24)
                c.drawCircle(x, y, pw / 2f, dot)
                dot.color = withAlpha(0xFFFFFF, 150)
                c.drawCircle(x, y, pw * 0.17f, dot)
                continue
            }
            path.rewind()
            path.moveTo(left + s.xs[0] * w, top + s.ys[0] * h)
            for (i in 1 until n - 1) {
                val px = left + s.xs[i] * w
                val py = top + s.ys[i] * h
                val mx = (px + left + s.xs[i + 1] * w) / 2f
                val my = (py + top + s.ys[i + 1] * h) / 2f
                path.quadTo(px, py, mx, my)
            }
            path.lineTo(left + s.xs[n - 1] * w, top + s.ys[n - 1] * h)
            glow.strokeWidth = pw * 2.6f
            glow.color = withAlpha(s.color, 70)
            c.drawPath(path, glow)
            core.strokeWidth = pw
            core.color = s.color or (0xFF shl 24)
            c.drawPath(path, core)
            hi.strokeWidth = pw * 0.3f
            hi.color = withAlpha(0xFFFFFF, 150)
            c.drawPath(path, hi)
        }
    }

    private fun linePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
