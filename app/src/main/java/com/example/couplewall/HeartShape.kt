package com.example.couplewall

import android.graphics.Path

/** Geometry of the heart. Unit space: x in [-1, 1], y in [-1, 1] (y grows downwards), tip at the bottom. */
object HeartShape {
    private const val START_Y = 0.92f

    /** Four cubic segments: c1x, c1y, c2x, c2y, x, y. */
    private val CUBICS = arrayOf(
        floatArrayOf(-0.25f, 0.72f, -1.02f, 0.22f, -1.02f, -0.34f),
        floatArrayOf(-1.02f, -0.86f, -0.38f, -1.06f, 0f, -0.52f),
        floatArrayOf(0.38f, -1.06f, 1.02f, -0.86f, 1.02f, -0.34f),
        floatArrayOf(1.02f, 0.22f, 0.25f, 0.72f, 0f, START_Y),
    )

    fun build(out: Path = Path()): Path {
        out.reset()
        out.moveTo(0f, START_Y)
        for (c in CUBICS) out.cubicTo(c[0], c[1], c[2], c[3], c[4], c[5])
        out.close()
        return out
    }

    /** Dense outline as an interleaved x,y polyline (4 * perSegment points), used by the gesture recogniser. */
    fun outline(perSegment: Int): FloatArray {
        val out = FloatArray(CUBICS.size * perSegment * 2)
        var px = 0f
        var py = START_Y
        var k = 0
        for (c in CUBICS) {
            for (i in 0 until perSegment) {
                val t = i.toFloat() / perSegment
                val u = 1f - t
                out[k++] = u * u * u * px + 3f * u * u * t * c[0] + 3f * u * t * t * c[2] + t * t * t * c[4]
                out[k++] = u * u * u * py + 3f * u * u * t * c[1] + 3f * u * t * t * c[3] + t * t * t * c[5]
            }
            px = c[4]
            py = c[5]
        }
        return out
    }
}
