package com.rigstudio.core.anim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Interpolation curves between two keyframes.
 *
 * Linear rotation alone is what makes cut-out animation look robotic, so every shipped clip uses
 * [SMOOTH] or a directional ease. Curves are pure functions of normalised time (0..1 → 0..1),
 * which keeps them trivially testable and identical in preview and export.
 */
enum class Easing {
    /** Constant speed. Used for holds. */
    LINEAR,

    /** Hermite smoothstep — the default; eases in and out of every key. */
    SMOOTH,

    /** Gentle sinusoidal ease, good for breathing and idles. */
    SINUSOIDAL,

    /** Slow start, fast finish — anticipations. */
    EASE_IN,

    /** Fast start, soft finish — falls, landings, follow-through. */
    EASE_OUT,

    /** Strong cubic ease in/out for deliberate, weighty moves. */
    EASE_IN_OUT_CUBIC,

    /**
     * Overshoots the target slightly and settles back: the "follow-through" curve that gives
     * gestures a whip-like finish.
     */
    EASE_OUT_BACK,
    ;

    fun apply(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            SMOOTH -> t * t * (3f - 2f * t)
            SINUSOIDAL -> (1f - cos(t * PI.toFloat())) * 0.5f
            EASE_IN -> t * t
            EASE_OUT -> 1f - (1f - t) * (1f - t)
            EASE_IN_OUT_CUBIC ->
                if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3f) / 2f
            EASE_OUT_BACK -> {
                // Standard "back" overshoot constant (c1 ≈ 1.70158).
                val c1 = 1.70158f
                val c3 = c1 + 1f
                val p = t - 1f
                1f + c3 * p * p * p + c1 * p * p
            }
        }
    }

    companion object {
        /** Bounce-free pulse 0 → 1 → 0 across u = 0..1 (secondary motion helper). */
        fun pulse(u: Float): Float = sin(u.coerceIn(0f, 1f) * PI.toFloat())

        /** Damped oscillation for settle / follow-through on secondary parts. */
        fun wobble(u: Float, frequency: Float = 2f, damping: Float = 2.5f): Float {
            val t = u.coerceIn(0f, 1f)
            return sin(t * frequency * 2f * PI.toFloat()) * exp(-damping * t)
        }

        /**
         * Normalised cubic bezier easing (the CSS `cubic-bezier(x1,y1,x2,y2)` shape), solved by
         * bisection so there is no dependency on an animation library.
         */
        fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): (Float) -> Float = { u ->
            val target = u.coerceIn(0f, 1f)
            var low = 0f
            var high = 1f
            var t = target
            var iterations = 0
            while (iterations < 20) {
                val x = bezierComponent(t, x1, x2)
                if (abs(x - target) < 1e-4f) break
                if (x < target) low = t else high = t
                t = (low + high) * 0.5f
                iterations++
            }
            bezierComponent(t, y1, y2)
        }

        private fun bezierComponent(t: Float, c1: Float, c2: Float): Float {
            val inv = 1f - t
            return 3f * inv * inv * t * c1 + 3f * inv * t * t * c2 + t * t * t
        }
    }
}
