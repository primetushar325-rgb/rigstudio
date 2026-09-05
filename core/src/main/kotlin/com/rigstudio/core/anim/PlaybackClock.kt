package com.rigstudio.core.anim

import kotlin.math.max

/**
 * The playback clock: converts wall-clock time into normalised clip time.
 *
 * This lives in the core (not in the app module) because it is pure timing arithmetic with no
 * Android dependency, which means the editor, the exporter and the unit tests all agree about what
 * "0.4 seconds into a 1.6 second cycle at 2× speed" means. The preview view owns one instance and
 * calls [tick] once per display frame; the exporter never uses it, because export advances by exact
 * frame index instead of by elapsed time.
 *
 * All times are in **clip seconds** (unscaled). The speed multiplier changes how fast clip seconds
 * accumulate, never what they mean: [normalizedTime] is always `timeSeconds / durationSeconds`.
 */
class PlaybackClock(
    durationSeconds: Float = 1f,
    speed: Float = 1f,
    loop: Boolean = true,
    private val timeSource: () -> Long = { System.nanoTime() },
) {

    /** Length of one full cycle, in clip seconds. Always positive and never NaN. */
    var durationSeconds: Float = sanitizeDuration(durationSeconds)
        set(value) {
            field = sanitizeDuration(value)
        }

    /** Playback rate multiplier (0.25×–3× come from the UI; clamped here regardless). */
    var speed: Float = sanitizeSpeed(speed)
        set(value) {
            field = sanitizeSpeed(value)
        }

    /** Whether the clip repeats forever (locomotion) or plays once and holds (sit, sleep, jump). */
    var loop: Boolean = loop

    /** Clip seconds consumed so far, in `[0, durationSeconds]`. */
    var timeSeconds: Float = 0f
        private set

    /** True while frames are advancing. */
    var isPlaying: Boolean = false
        private set

    /** True once a non-looping clip reached its end and held. Cleared by [play] / [restart]. */
    var isFinished: Boolean = false
        private set

    /** Normalised clip time in `[0, 1]`. This is what [AnimationClip.sample] wants. */
    val normalizedTime: Float
        get() = (timeSeconds / durationSeconds).coerceIn(0f, 1f)

    /** Wall-clock length of one cycle at the current speed, for the timeline readout. */
    val cycleSeconds: Float get() = durationSeconds / speed

    /** Wall-clock seconds consumed at the current speed (clipped to the cycle when looping). */
    val elapsedSeconds: Float get() = timeSeconds / speed

    private var lastTickNanos: Long = timeSource()

    /** Starts (or resumes) playback from wherever the playhead is. */
    fun play() {
        if (isFinished) timeSeconds = 0f
        isFinished = false
        isPlaying = true
        lastTickNanos = timeSource()
    }

    /** Stops advancing but keeps the playhead where it is. */
    fun pause() {
        isPlaying = false
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    /** Playhead to zero and play. */
    fun restart() {
        timeSeconds = 0f
        isFinished = false
        play()
    }

    /** Jumps to a normalised position, pausing if it lands on the end of a one-shot clip. */
    fun seekNormalized(t: Float) {
        val clamped = t.coerceIn(0f, 1f)
        timeSeconds = clamped * durationSeconds
        isFinished = clamped >= 1f && !loop
        lastTickNanos = timeSource()
    }

    /** Jumps to a position expressed in clip seconds. */
    fun seekSeconds(seconds: Float) {
        seekNormalized(if (durationSeconds <= 0f) 0f else seconds / durationSeconds)
    }

    /**
     * Applies a new clip without losing the sense of position: the playhead keeps its normalised
     * place, so switching from a 1.0 s walk to a 2.6 s idle does not snap back to the start.
     */
    fun retarget(durationSeconds: Float, speed: Float = this.speed, loop: Boolean = this.loop) {
        val position = normalizedTime
        this.durationSeconds = durationSeconds
        this.speed = speed
        this.loop = loop
        timeSeconds = position * this.durationSeconds
        isFinished = position >= 1f && !this.loop
        lastTickNanos = timeSource()
    }

    /**
     * Advances the playhead by the wall time elapsed since the last call.
     *
     * @return true when the playhead actually moved, i.e. when the caller should redraw. False when
     *   paused or when the clip is holding its final frame — that is what keeps an idle editor from
     *   burning a frame every 16 ms for nothing.
     */
    fun tick(): Boolean {
        val now = timeSource()
        val elapsedNanos = max(0L, now - lastTickNanos)
        lastTickNanos = now
        if (!isPlaying || isFinished) return false

        // Cap the step: an app that was backgrounded for ten minutes must not teleport the
        // character ten minutes into the clip, and a single huge step would smear a one-shot.
        val deltaSeconds = minOf(elapsedNanos.toFloat() / NANOS_PER_SECOND, MAX_TICK_SECONDS) * speed
        if (deltaSeconds <= 0f) return false

        var next = timeSeconds + deltaSeconds
        if (next >= durationSeconds) {
            if (loop) {
                next %= durationSeconds
                if (next == 0f) next = durationSeconds - FLOAT_EPSILON
            } else {
                timeSeconds = durationSeconds
                isFinished = true
                isPlaying = false
                return true
            }
        }
        timeSeconds = next
        return true
    }

    companion object {
        /** Constructor defaults: one second, normal speed. */
        const val DEFAULT_DURATION = 1f

        /**
         * A zero or negative cycle length would make every normalised time 0/0 = NaN and poison the
         * sampler, so the clock floors it instead of throwing: a broken clip must never crash the
         * editor.
         */
        fun sanitizeDuration(value: Float): Float =
            if (value.isNaN() || value <= MIN_DURATION) MIN_DURATION else value

        fun sanitizeSpeed(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(MIN_SPEED, MAX_SPEED)

        const val MIN_DURATION = 0.01f
        const val MIN_SPEED = 0.05f
        const val MAX_SPEED = 8f

        /** Longest single step the clock will accept, in seconds. */
        const val MAX_TICK_SECONDS = 0.25f

        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val FLOAT_EPSILON = 1e-4f
    }
}
