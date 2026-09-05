package com.rigstudio.core.model

/**
 * Universal bone identifiers.
 *
 * These fourteen ids are the *only* rig vocabulary in RigStudio. Animation clips,
 * constraints, z-ordering and mirroring all speak in terms of these ids, which is why
 * a clip authored once plays on every character and in every supplied view.
 *
 * `_l` / `_r` are **screen** left / right of the assembled view (not the character's
 * anatomical left/right). The character sheet is drawn the way you look at it, so the
 * part in a `*_l` slot ends up on the left of the screen. Mirroring a rig swaps the two
 * and negates rotations (see [com.rigstudio.core.rig.RigMirror]).
 */
object BoneIds {
    const val ROOT = "root"
    const val TORSO = "torso"
    const val HEAD = "head"
    const val UPPER_ARM_L = "upper_arm_l"
    const val UPPER_ARM_R = "upper_arm_r"
    const val FOREARM_L = "forearm_l"
    const val FOREARM_R = "forearm_r"
    const val HAND_L = "hand_l"
    const val HAND_R = "hand_r"
    const val THIGH_L = "thigh_l"
    const val THIGH_R = "thigh_r"
    const val SHIN_L = "shin_l"
    const val SHIN_R = "shin_r"
    const val FOOT_L = "foot_l"
    const val FOOT_R = "foot_r"

    /** Every sprite-carrying bone, in hierarchy order (parents before children). */
    val ALL: List<String> = listOf(
        TORSO,
        HEAD,
        UPPER_ARM_L, FOREARM_L, HAND_L,
        UPPER_ARM_R, FOREARM_R, HAND_R,
        THIGH_L, SHIN_L, FOOT_L,
        THIGH_R, SHIN_R, FOOT_R,
    )

    /** Canonical parent of each bone. `null` parent means "child of the implicit root". */
    val PARENTS: Map<String, String?> = mapOf(
        TORSO to null,
        HEAD to TORSO,
        UPPER_ARM_L to TORSO,
        FOREARM_L to UPPER_ARM_L,
        HAND_L to FOREARM_L,
        UPPER_ARM_R to TORSO,
        FOREARM_R to UPPER_ARM_R,
        HAND_R to FOREARM_R,
        THIGH_L to TORSO,
        SHIN_L to THIGH_L,
        FOOT_L to SHIN_L,
        THIGH_R to TORSO,
        SHIN_R to THIGH_R,
        FOOT_R to SHIN_R,
    )

    /** Screen-left bone <-> screen-right bone. Used by rig mirroring. */
    val MIRROR_PAIRS: Map<String, String> = mapOf(
        UPPER_ARM_L to UPPER_ARM_R, UPPER_ARM_R to UPPER_ARM_L,
        FOREARM_L to FOREARM_R, FOREARM_R to FOREARM_L,
        HAND_L to HAND_R, HAND_R to HAND_L,
        THIGH_L to THIGH_R, THIGH_R to THIGH_L,
        SHIN_L to SHIN_R, SHIN_R to SHIN_L,
        FOOT_L to FOOT_R, FOOT_R to FOOT_L,
        TORSO to TORSO, HEAD to HEAD,
    )

    fun mirrorOf(boneId: String): String = MIRROR_PAIRS[boneId] ?: boneId

    fun isKnown(boneId: String): Boolean = boneId == ROOT || boneId in PARENTS
}
