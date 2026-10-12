package com.example.couplewall.core

import kotlin.math.abs
import kotlin.math.hypot

/** Ramer-Douglas-Peucker polyline simplification (iterative). */
object Simplify {
    fun rdp(xs: FloatArray, ys: FloatArray, eps: Float): Pair<FloatArray, FloatArray> {
        val n = xs.size
        if (n < 3) return Pair(xs.copyOf(), ys.copyOf())
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeLast()
            val a = seg[0]
            val b = seg[1]
            var maxD = 0f
            var idx = -1
            for (i in a + 1 until b) {
                val d = distance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx != -1 && maxD > eps) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        val count = keep.count { it }
        val ox = FloatArray(count)
        val oy = FloatArray(count)
        var k = 0
        for (i in 0 until n) if (keep[i]) { ox[k] = xs[i]; oy[k] = ys[i]; k++ }
        return Pair(ox, oy)
    }

    /** Simplifies, doubling the tolerance until at most [maxPoints] remain. */
    fun limit(xs: FloatArray, ys: FloatArray, eps: Float, maxPoints: Int): Pair<FloatArray, FloatArray> {
        var e = eps
        var r = rdp(xs, ys, e)
        while (r.first.size > maxPoints) {
            e *= 2f
            r = rdp(xs, ys, e)
        }
        return r
    }

    private fun distance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-9f) return hypot(px - ax, py - ay)
        return abs(dy * px - dx * py + bx * ay - by * ax) / len
    }
}
