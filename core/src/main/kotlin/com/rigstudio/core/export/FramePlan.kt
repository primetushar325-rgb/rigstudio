package com.rigstudio.core.export

import kotlin.math.roundToInt

/**
 * Frame sampling plan.
 *
 * One implementation, used by the preview clock, the MP4 encoder and the PNG sequence writer, so
 * "frame 125 of 900" always means the same instant of the animation everywhere.
 */
data class FramePlan(
    val frameCount: Int,
    val fps: Int,
    val durationSeconds: Float,
) {
    init {
        require(frameCount > 0) { "A frame plan needs at least one frame" }
        require(fps > 0) { "fps must be positive" }
    }

    val frameDurationSeconds: Float get() = 1f / fps

    /** Animation time (seconds) of frame [index]. */
    fun timeAt(index: Int): Float {
        val safe = index.coerceIn(0, frameCount - 1)
        return safe / fps.toFloat()
    }

    /** Normalised clip time (0..1) of frame [index] for a clip of [clipDurationSeconds]. */
    fun normalizedTimeAt(index: Int, clipDurationSeconds: Float, speed: Float = 1f): Float {
        if (clipDurationSeconds <= 0f) return 0f
        return (timeAt(index) * speed) / clipDurationSeconds
    }

    /** Presentation timestamp in microseconds, as `MediaMuxer` expects. */
    fun presentationTimeUs(index: Int): Long {
        val safe = index.coerceIn(0, frameCount - 1)
        return safe * 1_000_000L / fps
    }

    companion object {
        /**
         * Frame count is `round(duration * fps)`, clamped to at least one frame. This is the
         * formula asserted by the unit tests ("frame sampling produces expected frame count").
         */
        fun of(durationSeconds: Float, fps: Int): FramePlan {
            val safeFps = fps.coerceAtLeast(1)
            val safeDuration = durationSeconds.coerceAtLeast(0f)
            val count = (safeDuration * safeFps).roundToInt().coerceAtLeast(1)
            return FramePlan(count, safeFps, count.toFloat() / safeFps)
        }
    }
}
