package com.rigstudio.app.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.rigstudio.core.anim.PlaybackClock
import com.rigstudio.core.rig.Pose

/**
 * The editor's live viewport: a plain [View] that redraws the puppet on every display frame.
 *
 * Playback is driven by [Choreographer] inside the view rather than by recomposing a Compose tree
 * at 60 Hz. That keeps the hot path (sample pose → compose draw list → paint bitmaps) free of
 * composition overhead, and it means the playhead can never drift from the frame actually on
 * screen. Compose owns the *controls*; this view owns the *clock and the pixels*, and reports the
 * playhead back at ~15 Hz so the timeline readout can follow along without thrashing.
 *
 * The stage is prepared at the view's own pixel size, so what the user sees is framed exactly as a
 * 1280×720 or 1920×1080 export of the same clip will be framed (see [StageRenderer]).
 */
class StageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    /**
     * What to draw. Assigning a new source re-solves the camera for the current size; assigning
     * null clears the stage (the view then paints only its background).
     */
    var stageSource: StageSource? = null
        set(value) {
            if (field == value) return
            field = value
            prepare()
            invalidate()
        }

    /** Show a subtle checkerboard behind transparent artwork. Editor only — never export. */
    var drawChecker: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Playhead position as normalised clip time (0..1). Writing seeks. */
    var normalizedTime: Float
        get() = clock.normalizedTime
        set(value) {
            clock.seekNormalized(value)
            publishFrame(force = true)
            invalidate()
        }

    /** Playback speed multiplier (0.25×–3×). */
    var speed: Float
        get() = clock.speed
        set(value) {
            clock.speed = value
        }

    /** Whether the clip repeats. Set from the clip's own `loop` flag. */
    var loop: Boolean
        get() = clock.loop
        set(value) {
            clock.loop = value
            invalidate()
        }

    val isPlaying: Boolean get() = clock.isPlaying

    /** Length of one cycle in wall-clock seconds at the current speed. */
    val cycleSeconds: Float get() = clock.cycleSeconds

    /** The pose drawn on the most recent frame, for the live expression/mouth readout. */
    var lastPose: Pose? = null
        private set

    /** Called at ~15 Hz (and on every seek/finish) with the current playhead. */
    var onFrame: ((normalizedTime: Float, isPlaying: Boolean) -> Unit)? = null

    /** Called once when a non-looping clip reaches its final frame. */
    var onFinished: (() -> Unit)? = null

    private val clock = PlaybackClock()
    private var prepared: PreparedStage? = null
    private var callbackPosted = false
    private var lastPublishMillis = 0L

    init {
        // Nothing to draw without this, and it keeps the puppet sharp on every density.
        isClickable = false
        setWillNotDraw(false)
    }

    fun play() {
        clock.play()
        scheduleFrames()
        publishFrame(force = true)
    }

    fun pause() {
        clock.pause()
        publishFrame(force = true)
    }

    fun toggle() {
        if (clock.isPlaying) pause() else play()
    }

    fun restart() {
        clock.restart()
        scheduleFrames()
        publishFrame(force = true)
        invalidate()
    }

    /**
     * Points the clock at a different clip while keeping the proportional playhead position, which
     * is what makes flipping between Idle and Walk feel continuous instead of jarring.
     */
    fun retarget(durationSeconds: Float, speed: Float = clock.speed, loop: Boolean = clock.loop) {
        val wasPlaying = clock.isPlaying
        clock.retarget(durationSeconds, speed, loop)
        if (wasPlaying) scheduleFrames()
        publishFrame(force = true)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepare()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val stage = prepared
        if (stage == null) {
            canvas.drawColor(FALLBACK_BACKGROUND)
            return
        }
        lastPose = stage.paintNormalized(canvas, clock.normalizedTime, drawChecker)
    }

    override fun doFrame(frameTimeNanos: Long) {
        callbackPosted = false
        val moved = clock.tick()
        if (moved) {
            invalidate()
            publishFrame()
        }
        if (!clock.isPlaying) {
            if (clock.isFinished) {
                invalidate()
                publishFrame(force = true)
                onFinished?.invoke()
            }
            return
        }
        scheduleFrames()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (clock.isPlaying) scheduleFrames()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        callbackPosted = false
        super.onDetachedFromWindow()
    }

    private fun scheduleFrames() {
        if (callbackPosted) return
        callbackPosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun publishFrame(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now
        onFrame?.invoke(clock.normalizedTime, clock.isPlaying)
    }

    private fun prepare() {
        val source = stageSource
        if (source == null || width <= 0 || height <= 0) {
            prepared = null
            return
        }
        prepared = StageRenderer.DEFAULT.prepare(source, width, height)
        clock.durationSeconds = source.clip.durationSeconds
    }

    companion object {
        /** Throttle for playhead callbacks into Compose (~15 Hz reads as continuous). */
        const val PUBLISH_INTERVAL_MILLIS = 66L

        /** Painted when there is nothing to show yet. */
        const val FALLBACK_BACKGROUND = 0xFF12161C.toInt()
    }
}
