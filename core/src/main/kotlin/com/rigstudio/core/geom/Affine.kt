package com.rigstudio.core.geom

import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal 2D affine transform stored as six floats:
 *
 * ```
 * | a c tx |
 * | b d ty |
 * | 0 0  1 |
 * ```
 *
 * The engine deliberately does **not** use a 3D/4x4 matrix: RigStudio is a cut-out
 * 2D puppet, so a 2x3 affine is exactly enough, allocation-free to compose, and maps
 * 1:1 onto `android.graphics.Matrix` (see `Affine.toAndroidMatrix` in the app module).
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {

    /** this * other — applies [other] first, then this. */
    fun multiply(o: Affine) = Affine(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty,
    )

    fun transform(p: Vec2) = Vec2(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)

    fun transform(x: Float, y: Float) = Vec2(a * x + c * y + tx, b * x + d * y + ty)

    /** Uniform scale factor implied by the transform (used for line widths / LOD). */
    fun scaleMagnitude(): Float {
        val sx = kotlin.math.hypot(a, b)
        val sy = kotlin.math.hypot(c, d)
        return (sx + sy) * 0.5f
    }

    fun isMirrored(): Boolean = (a * d - b * c) < 0f

    companion object {
        val IDENTITY = Affine()

        fun translation(x: Float, y: Float) = Affine(tx = x, ty = y)

        fun scaling(sx: Float, sy: Float = sx) = Affine(a = sx, d = sy)

        /** Rotation by [radians]; positive = clockwise because y points down. */
        fun rotation(radians: Float) = Affine(
            a = cos(radians),
            b = sin(radians),
            c = -sin(radians),
            d = cos(radians),
        )

        /** Rotation about an arbitrary point. */
        fun rotationAbout(radians: Float, pivot: Vec2) =
            translation(pivot.x, pivot.y) * rotation(radians) * translation(-pivot.x, -pivot.y)

        /** Horizontal mirror about the vertical line x = [axis]. */
        fun mirrorAbout(axis: Float) = Affine(a = -1f, d = 1f, tx = 2f * axis)
    }
}

/** Infix helpers so transform chains read like the maths they express. */
operator fun Affine.times(o: Affine): Affine = multiply(o)
