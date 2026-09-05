package com.rigstudio.core.rig

import com.rigstudio.core.geom.FloatRect
import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.geom.Vec2

/**
 * Geometry-only description of one extracted sprite.
 *
 * The core module never touches `android.graphics.Bitmap`; the app module keeps a
 * `Map<String, Bitmap>` keyed by [slotId] next to the rig. Splitting it this way keeps rigging,
 * animation and bounds maths runnable (and unit-testable) on a plain JVM.
 */
data class SpriteAsset(
    val slotId: String,
    val width: Int,
    val height: Int,
    /** Joint inside the sprite, normalised 0..1. */
    val pivot: Vec2,
    /** Fraction of the crop that contains artwork (0..1). */
    val coverage: Float,
    /** Crop rectangle in sheet pixels. */
    val sourceRect: IntRect,
    /** Tight artwork bounds in sheet pixels. */
    val contentRect: IntRect,
) {
    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()

    /** Pivot of the horizontally flipped sprite. */
    val flippedPivot: Vec2 get() = Vec2(1f - pivot.x, pivot.y)
}

/**
 * One bone of a built rig: its sprite, where the joint sits in view space, how big the part is
 * drawn, its layer and its rotation limits.
 */
data class RigBone(
    val id: String,
    val parentId: String?,
    val sprite: SpriteAsset?,
    /** Joint position in view units (character height = 1.0). */
    val joint: Vec2,
    /** Assembled part height in view units. */
    val targetHeight: Float,
    /** Static draw order; [ZOrderResolver] may adjust it per pose. */
    val z: Int,
    val constraint: BoneConstraint,
    val depthShade: Float = 1f,
    val flipX: Boolean = false,
) {
    val hasArtwork: Boolean get() = sprite != null

    val effectivePivot: Vec2
        get() {
            val sprite = sprite ?: return Vec2(0.5f, 0.5f)
            return if (flipX) sprite.flippedPivot else sprite.pivot
        }

    /** Rest-pose rectangle of the artwork in view units (uniformly scaled to [targetHeight]). */
    val restRect: FloatRect
        get() {
            val sprite = sprite ?: return FloatRect.EMPTY
            val h = targetHeight
            val w = h * sprite.aspect
            val pivot = effectivePivot
            val left = joint.x - pivot.x * w
            val top = joint.y - pivot.y * h
            return FloatRect(left, top, left + w, top + h)
        }
}
