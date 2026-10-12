package com.example.couplewall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.couplewall.core.Simplify
import com.example.couplewall.core.Stroke
import com.example.couplewall.core.Protocol
import kotlin.math.hypot

/**
 * The shared canvas. Its rectangle has the aspect of the phone screen, so what is drawn here lands on the
 * lock screen 1:1. Coordinates are stored normalised (0..1) so two phones with different screens agree.
 */
class DrawView(context: Context) : View(context) {
    private val dens = resources.displayMetrics.density
    private val rect = RectF()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = dens * 1.2f
    }
    private val backdrop = Paint().apply { color = Color.rgb(16, 14, 34) }

    private var background: Bitmap? = null
    private var strokes: List<Stroke> = emptyList()
    private val current = ArrayList<Float>()          // interleaved normalised x, y
    private var drawing = false

    var color: Int = Color.WHITE
    var brushIndex: Int = 1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val (sw, sh) = LockRenderer.screenSize(context)
        val aspect = sh.toFloat() / sw
        val pad = dens * 12f
        var cw = w - 2 * pad
        var ch = cw * aspect
        if (ch > h - 2 * pad) {
            ch = h - 2 * pad
            cw = ch / aspect
        }
        val l = (w - cw) / 2f
        val t = (h - ch) / 2f
        rect.set(l, t, l + cw, t + ch)
        reloadBackground()
        refresh()
    }

    /** Re-reads the wallpaper the lock screen is composed on (after the user changed it). */
    fun reloadBackground() {
        val w = rect.width().toInt()
        val h = rect.height().toInt()
        if (w <= 0 || h <= 0) return
        val id = SyncHub.prefs.lockBackground
        Work.io.execute {
            val bmp = LockRenderer.background(context, id, w, h)
            post {
                background = bmp
                invalidate()
            }
        }
    }

    /** Pulls the latest strokes from the store (called on every canvas change). */
    fun refresh() {
        strokes = SyncHub.store.snapshot()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.isEmpty) return
        val r = dens * 26f
        canvas.save()
        clip.rewind()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        val bg = background
        if (bg != null) canvas.drawBitmap(bg, null, rect, paint) else canvas.drawRect(rect, backdrop)
        StrokeRenderer.draw(canvas, strokes, rect.left, rect.top, rect.width(), rect.height())
        if (drawing && current.size >= 2) {
            StrokeRenderer.draw(canvas, listOf(buildStroke(0L, 0L, current)), rect.left, rect.top, rect.width(), rect.height())
        }
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!rect.contains(e.x, e.y)) return false
                drawing = true
                current.clear()
                addPoint(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (drawing) {
                for (i in 0 until e.historySize) addPoint(e.getHistoricalX(i), e.getHistoricalY(i))
                addPoint(e.x, e.y)
                if (current.size / 2 >= MAX_RAW_POINTS) commit(continueFromLast = true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drawing) {
                addPoint(e.x, e.y)
                commit(continueFromLast = false)
                drawing = false
                invalidate()
            }
        }
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        val n = current.size
        if (n >= 2) {
            val dx = (nx - current[n - 2]) * rect.width()
            val dy = (ny - current[n - 1]) * rect.height()
            if (hypot(dx, dy) < dens * 1.2f) return
        }
        current.add(nx)
        current.add(ny)
    }

    private fun commit(continueFromLast: Boolean) {
        if (current.size < 2) return
        val lastX = current[current.size - 2]
        val lastY = current[current.size - 1]
        val prefs = SyncHub.prefs
        val seq = prefs.nextSeq().toLong() and 0xFFFFFFFFL
        val id = (prefs.deviceId.toLong() shl 32) or seq
        SyncHub.addLocal(buildStroke(id, System.currentTimeMillis(), current))
        current.clear()
        if (continueFromLast) {
            current.add(lastX)
            current.add(lastY)
        }
    }

    private fun buildStroke(id: Long, time: Long, pts: List<Float>): Stroke {
        val n = pts.size / 2
        val xs = FloatArray(n) { pts[2 * it] }
        val ys = FloatArray(n) { pts[2 * it + 1] }
        val (sx, sy) = Simplify.limit(xs, ys, SIMPLIFY_EPS, Protocol.MAX_POINTS)
        return Stroke(id, time, SyncHub.prefs.deviceId, color, BRUSHES[brushIndex.coerceIn(0, BRUSHES.size - 1)], sx, sy)
    }

    companion object {
        val BRUSHES = floatArrayOf(0.006f, 0.012f, 0.024f)
        private const val SIMPLIFY_EPS = 0.0006f
        private const val MAX_RAW_POINTS = 400
    }
}
