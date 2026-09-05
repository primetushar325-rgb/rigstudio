package com.rigstudio.core.util

import kotlin.math.PI

/**
 * Small maths helpers shared by the rig, animation and export code.
 *
 * Angle conversions are written out explicitly rather than using `kotlin.math.deg2rad`, so the
 * core module compiles identically on every Kotlin/JVM version a build machine may have.
 */
object MathUtils {

    val DEG_TO_RAD: Float = (PI / 180.0).toFloat()
    val RAD_TO_DEG: Float = (180.0 / PI).toFloat()

    fun degToRad(degrees: Float): Float = degrees * DEG_TO_RAD
    fun radToDeg(radians: Float): Float = radians * RAD_TO_DEG

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    /** Frames -> seconds helper that never divides by zero. */
    fun frameTime(frameIndex: Int, fps: Int): Float =
        if (fps <= 0) 0f else frameIndex / fps.toFloat()

    /** True when [value] is a usable, finite number. */
    fun isFinite(value: Float): Boolean = !value.isNaN() && !value.isInfinite()
}
