package com.rigstudio.core.rig

import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.model.BoneIds

/**
 * Central bone-limit enforcement (spec: "Animations MUST be validated against those
 * constraints").
 *
 * Two modes, on purpose:
 *  - **development / tests**: [validate] reports every violation so a badly authored clip fails
 *    the build instead of shipping a dislocated puppet;
 *  - **runtime**: [clampPose] silently keeps every sampled pose inside the limits, so even a
 *    hand-edited project file can never break the character.
 */
object BoneConstraintValidator {

    enum class ViolationKind {
        UNKNOWN_BONE,
        ROTATION_OUT_OF_RANGE,
        KEYFRAME_TIME_OUT_OF_RANGE,
        KEYFRAME_ORDER,
    }

    data class Violation(
        val clipId: String,
        val boneId: String,
        val kind: ViolationKind,
        val detail: String,
    )

    /**
     * Validates a clip against the constraint table.
     *
     * @param samples how many interpolated poses to check in addition to the raw keyframes.
     */
    fun validate(
        clip: AnimationClip,
        constraints: Map<String, BoneConstraint> = BoneConstraints.all(),
        samples: Int = 24,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()

        fun constraintFor(boneId: String): BoneConstraint? = when {
            boneId == BoneIds.ROOT -> constraints[BoneIds.ROOT] ?: BoneConstraints.ROOT
            BoneIds.isKnown(boneId) -> constraints[boneId] ?: BoneConstraints.forBone(boneId)
            else -> null
        }

        for ((boneId, track) in clip.tracks) {
            val constraint = constraintFor(boneId)
            if (constraint == null) {
                violations += Violation(
                    clip.id, boneId, ViolationKind.UNKNOWN_BONE,
                    "Track targets unknown bone '$boneId'",
                )
                continue
            }
            var previousTime = -1f
            for (key in track.keys) {
                if (key.time < 0f || key.time > 1f) {
                    violations += Violation(
                        clip.id, boneId, ViolationKind.KEYFRAME_TIME_OUT_OF_RANGE,
                        "Keyframe time ${key.time} outside 0..1",
                    )
                }
                if (key.time < previousTime) {
                    violations += Violation(
                        clip.id, boneId, ViolationKind.KEYFRAME_ORDER,
                        "Keyframe times must not go backwards (${key.time} after $previousTime)",
                    )
                }
                previousTime = key.time
                if (!constraint.allows(key.rotationDeg)) {
                    violations += Violation(
                        clip.id, boneId, ViolationKind.ROTATION_OUT_OF_RANGE,
                        "Keyframe rotation ${key.rotationDeg}° at t=${key.time} is outside " +
                            "${constraint.minRotationDeg}°..${constraint.maxRotationDeg}°",
                    )
                }
            }
        }

        // Interpolated check: catches tracks whose *blend* leaves the allowed range.
        if (samples > 0) {
            for (i in 0..samples) {
                val pose = clip.sample(i.toFloat() / samples)
                for ((boneId, bonePose) in pose.bones) {
                    val constraint = constraintFor(boneId) ?: continue
                    if (!constraint.allows(bonePose.rotationDeg)) {
                        violations += Violation(
                            clip.id, boneId, ViolationKind.ROTATION_OUT_OF_RANGE,
                            "Interpolated rotation ${bonePose.rotationDeg}° at t=${i.toFloat() / samples} " +
                                "is outside ${constraint.minRotationDeg}°..${constraint.maxRotationDeg}°",
                        )
                    }
                }
                val rootConstraint = constraintFor(BoneIds.ROOT)!!
                if (!rootConstraint.allows(pose.root.rotationDeg)) {
                    violations += Violation(
                        clip.id, BoneIds.ROOT, ViolationKind.ROTATION_OUT_OF_RANGE,
                        "Root rotation ${pose.root.rotationDeg}° outside limits",
                    )
                }
            }
        }

        return violations.distinct()
    }

    /** True when the whole animation library respects the limits. Used by unit tests / CI. */
    fun validateAll(
        clips: List<AnimationClip>,
        constraints: Map<String, BoneConstraint> = BoneConstraints.all(),
    ): List<Violation> = clips.flatMap { validate(it, constraints) }

    /**
     * Runtime safety net: keeps every bone (and the root) inside its allowed range.
     *
     * Poses are clamped in **clip space** (before the forward-kinematics rotation sign is
     * applied), so the standard — not the mirrored — limit table is the correct one here.
     */
    fun clampPose(pose: Pose, rig: CharacterRig): Pose {
        val clampedBones = HashMap<String, BonePose>(pose.bones.size)
        for ((boneId, bonePose) in pose.bones) {
            val constraint = BoneConstraints.forBone(boneId)
            clampedBones[boneId] = if (constraint.allows(bonePose.rotationDeg)) {
                bonePose
            } else {
                bonePose.copy(rotationDeg = constraint.clamp(bonePose.rotationDeg))
            }
        }
        val root = pose.root.let {
            if (BoneConstraints.ROOT.allows(it.rotationDeg)) it
            else it.copy(rotationDeg = BoneConstraints.ROOT.clamp(it.rotationDeg))
        }
        return if (root == pose.root && clampedBones == pose.bones) pose
        else pose.copy(root = root, bones = clampedBones)
    }
}
