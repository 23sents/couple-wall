package com.example.couplewall.core

/**
 * One finger stroke on the shared canvas. Coordinates are normalised to 0..1 of the canvas (= the lock screen),
 * [width] is a fraction of the canvas width. [id] is unique per device and stroke.
 */
class Stroke(
    val id: Long,
    val time: Long,
    val author: Int,
    val color: Int,
    val width: Float,
    val xs: FloatArray,
    val ys: FloatArray,
) {
    var sent = false
    val size: Int get() = xs.size
}
