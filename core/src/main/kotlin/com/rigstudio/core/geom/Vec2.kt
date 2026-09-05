package com.rigstudio.core.geom

import kotlin.math.hypot

/**
 * Immutable 2D vector / point.
 *
 * RigStudio's coordinate convention everywhere in the engine: **y grows downwards**
 * (image space) and a positive rotation is **clockwise on screen**.
 */
data class Vec2(val x: Float, val y: Float) {

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun times(o: Vec2) = Vec2(x * o.x, y * o.y)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)

    fun length(): Float = hypot(x, y)

    fun lerp(o: Vec2, t: Float) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object {
        val ZERO = Vec2f(0f, 0f)
    }
}

/** Small helper so `Vec2(…)` reads naturally at call sites. */
fun Vec2f(x: Float, y: Float) = Vec2(x, y)

/** Axis-aligned rectangle in float space (image / view coordinates). */
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun isEmpty(): Boolean = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun expandBy(other: FloatRect): FloatRect = FloatRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        val EMPTY = FloatRect(0f, 0f, 0f, 0f)

        fun fromLTWH(left: Float, top: Float, width: Float, height: Float) =
            FloatRect(left, top, left + width, top + height)
    }
}

/** Axis-aligned rectangle in integer pixel space (character-sheet slots, crops). */
data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val left: Int get() = x
    val top: Int get() = y
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun isEmpty(): Boolean = width <= 0 || height <= 0

    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom

    fun intersects(o: IntRect): Boolean =
        x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun intersection(o: IntRect): IntRect {
        val l = maxOf(x, o.x)
        val t = maxOf(y, o.y)
        val r = minOf(right, o.right)
        val b = minOf(bottom, o.bottom)
        return if (r <= l || b <= t) IntRect(0, 0, 0, 0) else IntRect(l, t, r - l, b - t)
    }

    fun union(o: IntRect): IntRect {
        if (isEmpty()) return o
        if (o.isEmpty()) return this
        val l = minOf(x, o.x)
        val t = minOf(y, o.y)
        val r = maxOf(right, o.right)
        val b = maxOf(bottom, o.bottom)
        return IntRect(l, t, r - l, b - t)
    }

    /** Shrinks/grows the rect on all sides, never collapsing below 1x1 inside [bounds]. */
    fun padBy(padding: Int, bounds: IntRect): IntRect {
        val grown = IntRect(x - padding, y - padding, width + padding * 2, height + padding * 2)
        return grown.clampTo(bounds)
    }

    fun clampTo(bounds: IntRect): IntRect {
        val l = x.coerceIn(bounds.x, bounds.right)
        val t = y.coerceIn(bounds.y, bounds.bottom)
        val r = right.coerceIn(bounds.x, bounds.right)
        val b = bottom.coerceIn(bounds.y, bounds.bottom)
        return IntRect(l, t, maxOf(0, r - l), maxOf(0, b - t))
    }

    fun toFloatRect() = FloatRect(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        val ZERO = IntRect(0, 0, 0, 0)
    }
}
