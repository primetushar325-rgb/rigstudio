package com.rigstudio.core.render

import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.FloatRect
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.ForwardKinematics
import kotlin.math.max
import kotlin.math.min

/**
 * A fixed camera for one (rig, clip) pair.
 *
 * Output space is a unit frame: y spans 0..1 (top → bottom) and x spans 0..[aspect]. The
 * renderer only has to multiply by the output height in pixels, so the exact same [transform]
 * drives the on-screen preview and the 1080p encoder — preview and export cannot drift apart.
 *
 * The camera is computed **once per clip** from the union of every posed frame, never per frame.
 * That keeps the framing stable (no zoom pumping during playback) while guaranteeing that even
 * the most extreme pose — a jump apex or a character lying down to sleep — stays inside shot.
 */
data class Camera(
    val transform: Affine,
    /** Bounds of everything the clip ever draws, in view units. */
    val contentBounds: FloatRect,
    /** Uniform scale from view units to the unit frame. */
    val scale: Float,
    val aspect: Float,
) {
    /** Maps a view-space point into the unit frame. */
    fun project(x: Float, y: Float) = transform.transform(x, y)

    /** The floor line (y in the unit frame) the character stands on. */
    val floorY: Float get() = transform.transform(0f, contentBounds.bottom).y
}

/** Computes cameras. All numbers here are the "one place" for framing policy. */
object Framing {

    /** Empty space kept around the character, as a fraction of its largest extent. */
    const val MARGIN_FRACTION = 0.06f

    /** Gap kept between the feet and the bottom edge of the frame. */
    const val FLOOR_MARGIN_FRACTION = 0.045f

    /** How many poses are sampled to find the clip's extremes. */
    const val BOUND_SAMPLES = 48

    const val DEFAULT_ASPECT = 16f / 9f

    fun forClip(
        rig: CharacterRig,
        clip: AnimationClip,
        aspect: Float = DEFAULT_ASPECT,
        samples: Int = BOUND_SAMPLES,
    ): Camera {
        var bounds = rig.restBounds
        val count = max(1, samples)
        for (i in 0..count) {
            val pose = clip.sample(i.toFloat() / count)
            val posed = ForwardKinematics.posedBounds(rig, pose)
            if (!posed.isEmpty()) bounds = if (bounds.isEmpty()) posed else bounds.expandBy(posed)
        }
        return fromBounds(bounds, aspect)
    }

    /** Framing for a still character (thumbnails, the template preview, reset poses). */
    fun forRestPose(rig: CharacterRig, aspect: Float = DEFAULT_ASPECT): Camera =
        fromBounds(rig.restBounds, aspect)

    fun fromBounds(bounds: FloatRect, aspect: Float = DEFAULT_ASPECT): Camera {
        val safeAspect = if (aspect <= 0.01f) DEFAULT_ASPECT else aspect
        if (bounds.isEmpty()) {
            return Camera(Affine.IDENTITY, bounds, 1f, safeAspect)
        }
        val extent = max(bounds.width, bounds.height)
        val margin = MARGIN_FRACTION * extent
        val padded = FloatRect(
            left = bounds.left - margin,
            top = bounds.top - margin,
            right = bounds.right + margin,
            bottom = bounds.bottom + margin,
        )

        // Uniform fit: the padded content must satisfy width <= aspect and height <= 1.
        val scale = min(safeAspect / padded.width, 1f / padded.height)
        val tx = (safeAspect - padded.width * scale) * 0.5f - padded.left * scale
        val ty = (1f - FLOOR_MARGIN_FRACTION) - padded.bottom * scale

        return Camera(
            transform = Affine.translation(tx, ty).multiply(Affine.scaling(scale)),
            contentBounds = bounds,
            scale = scale,
            aspect = safeAspect,
        )
    }

    /**
     * Camera for an explicit output size, in pixels. Used by the exporter so a 1280x720 and a
     * 1920x1080 render differ only by resolution, never by composition.
     */
    fun forPixels(
        rig: CharacterRig,
        clip: AnimationClip,
        outputWidth: Int,
        outputHeight: Int,
    ): Camera {
        val aspect = if (outputHeight <= 0) DEFAULT_ASPECT else outputWidth.toFloat() / outputHeight
        val camera = forClip(rig, clip, aspect)
        val pixelsPerUnit = outputHeight.toFloat()
        return camera.copy(
            transform = Affine.scaling(pixelsPerUnit).multiply(camera.transform),
        )
    }
}
