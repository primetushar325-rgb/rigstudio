package com.rigstudio.core.rig

import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape

/**
 * Animated local state of one bone.
 *
 * Rotation is in degrees (positive = clockwise on screen). [offset] is a translation expressed
 * in **character-height fractions**, i.e. view units: because the assembled character is exactly
 * 1.0 units tall, the same clip data works at any output resolution with no rescaling.
 */
data class BonePose(
    val rotationDeg: Float = 0f,
    val offset: Vec2 = Vec2.ZERO,
    val scale: Float = 1f,
) {
    fun mirrored() = BonePose(-rotationDeg, Vec2(-offset.x, offset.y), scale)

    companion object {
        val REST = BonePose()
    }
}

/**
 * A fully resolved instant of an animation: every bone's local transform, the whole-body root
 * motion, and which facial sprites are showing.
 *
 * This is the single contract between the animation engine and every renderer (preview, MP4
 * export, thumbnail generation), which is why preview and export can never drift apart.
 */
data class Pose(
    val timeSeconds: Float = 0f,
    val root: BonePose = BonePose.REST,
    val bones: Map<String, BonePose> = emptyMap(),
    val expression: Expression = Expression.NEUTRAL,
    val mouth: MouthShape = MouthShape.CLOSED,
) {

    fun rotationOf(boneId: String): Float = bones[boneId]?.rotationDeg ?: 0f

    fun poseOf(boneId: String): BonePose = bones[boneId] ?: BonePose.REST

    /**
     * Horizontally mirrored pose: `_l` and `_r` swap and rotations/offsets flip sign, so bone
     * semantics survive the mirror (the left arm stays the left arm, it just appears on the
     * other side).
     */
    fun mirrored(): Pose {
        val swapped = HashMap<String, BonePose>(bones.size)
        for ((boneId, bonePose) in bones) {
            swapped[com.rigstudio.core.model.BoneIds.mirrorOf(boneId)] = bonePose.mirrored()
        }
        return Pose(
            timeSeconds = timeSeconds,
            root = root.mirrored(),
            bones = swapped,
            expression = expression,
            mouth = mouth,
        )
    }

    companion object {
        val REST = Pose()
    }
}
