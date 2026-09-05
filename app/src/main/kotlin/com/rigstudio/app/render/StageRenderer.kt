package com.rigstudio.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.render.Camera
import com.rigstudio.core.render.Framing
import com.rigstudio.core.render.PuppetComposer
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.Pose

/**
 * Everything needed to draw one character in one view for one clip.
 *
 * Deliberately a plain value object: the preview view, the timeline scrubber, the thumbnail
 * generator and the MP4 exporter all build one of these and hand it to [StageRenderer]. There is
 * no second rendering implementation in RigStudio, which is the whole reason an export looks
 * exactly like the editor did.
 *
 * @param bitmaps resolves a slot id to its artwork (null when the user never drew that part).
 * @param expressionOverride pins the eyes to one expression instead of following the clip.
 * @param mouthOverride pins the mouth shape instead of following the clip's lip-sync track.
 */
data class StageSource(
    val rig: CharacterRig,
    val clip: AnimationClip,
    val bitmaps: (String) -> Bitmap?,
    val background: StageBackground = StageBackground.DEFAULT,
    val expressionOverride: Expression? = null,
    val mouthOverride: MouthShape? = null,
)

/**
 * A stage whose camera has already been solved for a fixed pixel size.
 *
 * Framing is the expensive part of drawing a frame — [Framing.forClip] samples 48 poses through
 * forward kinematics to find the clip's motion envelope — so it is solved **once** here and then
 * reused for every frame of a 1800-frame export and every 16 ms preview tick.
 */
class PreparedStage internal constructor(
    val width: Int,
    val height: Int,
    val source: StageSource,
    val camera: Camera,
    private val painter: PuppetPainter,
) {

    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height

    val clip: AnimationClip get() = source.clip

    /** Seconds of one full cycle at the current speed multiplier. */
    val cycleSeconds: Float get() = clip.durationSeconds

    /**
     * Draws one frame at normalised clip time [time01] (0..1; wrapping is the clip's business).
     * Returns the pose that was drawn so callers can surface the live expression and mouth shape.
     */
    fun paintNormalized(canvas: Canvas, time01: Float, drawChecker: Boolean = false): Pose {
        val pose = resolvePose(time01)
        val draws = PuppetComposer.compose(source.rig, pose, camera.transform)
        painter.paint(canvas, width, height, draws, source.bitmaps, source.background, drawChecker)
        return pose
    }

    /** Draws one frame from a wall-clock time in seconds, at [speed]. */
    fun paintSeconds(canvas: Canvas, timeSeconds: Float, speed: Float, drawChecker: Boolean = false): Pose =
        paintNormalized(canvas, normalized(timeSeconds, speed), drawChecker)

    /** Convenience for offscreen targets (export frames, thumbnails). */
    fun paintInto(bitmap: Bitmap, time01: Float): Pose {
        val canvas = Canvas(bitmap)
        return paintNormalized(canvas, time01)
    }

    /** Wall-clock seconds → normalised clip time, honouring the speed multiplier. */
    fun normalized(timeSeconds: Float, speed: Float): Float {
        if (clip.durationSeconds <= 0f) return 0f
        val effectiveSpeed = if (speed <= 0f) 0f else speed
        return timeSeconds * effectiveSpeed / clip.durationSeconds
    }

    /** Normalised clip time → wall-clock seconds at [speed] (for the timeline readout). */
    fun secondsAt(time01: Float, speed: Float): Float {
        val effectiveSpeed = if (speed <= 0f) 1f else speed
        return time01 * clip.durationSeconds / effectiveSpeed
    }

    private fun resolvePose(time01: Float): Pose {
        val pose = clip.sample(time01)
        val expression = source.expressionOverride
        val mouth = source.mouthOverride
        return if (expression == null && mouth == null) {
            pose
        } else {
            pose.copy(
                expression = expression ?: pose.expression,
                mouth = mouth ?: pose.mouth,
            )
        }
    }
}

/**
 * Solves a camera and produces a [PreparedStage].
 *
 * This is the single entry point from geometry to pixels on Android: the app never calls
 * [PuppetComposer] or [Framing] directly outside this class, so composition rules (z-order, face
 * attachment, depth shading) cannot drift between preview and export.
 */
class StageRenderer(private val painter: PuppetPainter = PuppetPainter()) {

    /**
     * @param width target width in pixels; must be positive.
     * @param height target height in pixels; must be positive.
     */
    fun prepare(source: StageSource, width: Int, height: Int): PreparedStage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val camera = Framing.forPixels(source.rig, source.clip, w, h)
        return PreparedStage(w, h, source, camera, painter)
    }

    /** A stage prepared for a clip at 16:9 — the editor's default viewport. */
    fun prepareForAspect(source: StageSource, aspect: Float = Framing.DEFAULT_ASPECT): PreparedStage {
        val safeAspect = if (aspect <= 0.01f) Framing.DEFAULT_ASPECT else aspect
        val height = 720
        val width = Math.round(height * safeAspect).coerceAtLeast(1)
        return prepare(source, width, height)
    }

    companion object {
        /** Shared instance: the renderer is stateless apart from its reusable paints. */
        val DEFAULT: StageRenderer = StageRenderer()
    }
}
