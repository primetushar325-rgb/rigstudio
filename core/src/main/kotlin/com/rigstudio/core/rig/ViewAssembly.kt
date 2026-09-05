package com.rigstudio.core.rig

import com.rigstudio.core.geom.FloatRect
import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot

/**
 * Rotation limits of one bone, in degrees (positive = clockwise on screen).
 *
 * Every clip is validated against these limits and every sampled pose is clamped to them at
 * runtime, so no animation can ever dislocate the puppet — see [BoneConstraintValidator].
 */
data class BoneConstraint(val minRotationDeg: Float, val maxRotationDeg: Float) {

    init {
        require(minRotationDeg <= maxRotationDeg) {
            "min rotation must be <= max rotation (got $minRotationDeg..$maxRotationDeg)"
        }
    }

    fun clamp(rotationDeg: Float): Float = rotationDeg.coerceIn(minRotationDeg, maxRotationDeg)

    fun allows(rotationDeg: Float): Boolean =
        rotationDeg >= minRotationDeg && rotationDeg <= maxRotationDeg

    /** Limits of the same bone in a horizontally mirrored rig (rotation signs flip). */
    fun mirrored() = BoneConstraint(-maxRotationDeg, -minRotationDeg)

    companion object {
        val FIXED = BoneConstraint(0f, 0f)
    }
}

/**
 * Central table of biomechanical limits. Deliberately generous enough for the shipped
 * animation library, tight enough that a broken clip visibly fails validation in tests.
 */
object BoneConstraints {

    /** Whole-rig orientation/position (used by Sit, Sleep, Jump). Not a sprite bone. */
    val ROOT = BoneConstraint(-180f, 180f)

    private val TABLE: Map<String, BoneConstraint> = mapOf(
        BoneIds.TORSO to BoneConstraint(-25f, 25f),
        BoneIds.HEAD to BoneConstraint(-35f, 35f),
        BoneIds.UPPER_ARM_L to BoneConstraint(-165f, 165f),
        BoneIds.UPPER_ARM_R to BoneConstraint(-165f, 165f),
        // Elbows bend one way; the authored range covers both screen directions because
        // `_l` / `_r` limbs are mirrored copies of the same motion.
        BoneIds.FOREARM_L to BoneConstraint(-150f, 150f),
        BoneIds.FOREARM_R to BoneConstraint(-150f, 150f),
        BoneIds.HAND_L to BoneConstraint(-60f, 60f),
        BoneIds.HAND_R to BoneConstraint(-60f, 60f),
        BoneIds.THIGH_L to BoneConstraint(-125f, 60f),
        BoneIds.THIGH_R to BoneConstraint(-125f, 60f),
        BoneIds.SHIN_L to BoneConstraint(-45f, 140f),
        BoneIds.SHIN_R to BoneConstraint(-45f, 140f),
        BoneIds.FOOT_L to BoneConstraint(-45f, 45f),
        BoneIds.FOOT_R to BoneConstraint(-45f, 45f),
    )

    fun forBone(boneId: String): BoneConstraint = when (boneId) {
        BoneIds.ROOT -> ROOT
        else -> TABLE[boneId] ?: BoneConstraint(-45f, 45f)
    }

    fun all(): Map<String, BoneConstraint> = TABLE + (BoneIds.ROOT to ROOT)

    /** Mirrored copy of the whole table (used when a rig is flipped horizontally). */
    fun mirrored(): Map<String, BoneConstraint> =
        TABLE.entries.associate { (boneId, c) -> BoneIds.mirrorOf(boneId) to c.mirrored() } +
            (BoneIds.ROOT to ROOT)
}

/**
 * Where one bone's artwork sits in a view's **rest pose**.
 *
 * View space is normalised: the assembled character is exactly 1.0 unit tall, x = 0.5 is its
 * centre line, y = 0 is the top of the head and y = 1 is the floor. Everything the renderer
 * does is a single uniform scale from view space to pixels, which is why the same rig and the
 * same clip data work at preview size and at 1080p export size.
 *
 * Parts are scaled **uniformly** to [targetHeight]; their width follows the artwork's own
 * aspect ratio, so a fat or skinny character keeps its proportions instead of being stretched.
 */
data class AssembledBone(
    val boneId: String,
    /** Character-sheet slot that supplies this bone's artwork in this view. */
    val slotId: String,
    /** Joint position in view units. The slot's pivot is placed exactly here at rest. */
    val joint: Vec2,
    /** Assembled height of the part in view units (character height = 1.0). */
    val targetHeight: Float,
    /** Draw order; higher draws later (on top). */
    val z: Int,
    /**
     * Depth shading multiplier. 1.0 = normal. Far limbs in a profile view get a slightly
     * darker tint so the near/far read is unmistakable without extra artwork.
     */
    val depthShade: Float = 1f,
    /** Draw the sprite mirrored inside its own rect (used by the mirrored side-right rig). */
    val flipX: Boolean = false,
) {
    val slot: SheetSlot
        get() = CharacterSheetTemplate.requireSlot(slotId)

    /** Width/height of the source slot — the nominal aspect used for rest bounds. */
    val nominalAspect: Float get() = slot.rect.width.toFloat() / slot.rect.height.toFloat()

    /** Rest-pose rectangle of the artwork in view units. */
    val restRect: FloatRect
        get() {
            val h = targetHeight
            val w = h * nominalAspect
            return FloatRect(
                left = joint.x - slot.pivot.x * w,
                top = joint.y - slot.pivot.y * h,
                right = joint.x - slot.pivot.x * w + w,
                bottom = joint.y - slot.pivot.y * h + h,
            )
        }

    val constraint: BoneConstraint get() = BoneConstraints.forBone(boneId)
}

/** Where the facial sprites are anchored, in *head sprite* space (0..1 of the head rect). */
data class FaceAnchors(
    val eyeCenter: Vec2,
    val eyeWidth: Float,
    val mouthCenter: Vec2,
    val mouthWidth: Float,
) {
    companion object {
        /** Front/back faces: eyes just above the middle, mouth in the lower third. */
        val STANDARD = FaceAnchors(
            eyeCenter = Vec2(0.50f, 0.44f),
            eyeWidth = 0.62f,
            mouthCenter = Vec2(0.50f, 0.70f),
            mouthWidth = 0.34f,
        )

        /**
         * Profile faces sit towards the facing edge of the head. [towardsFacingEdge] is
         * negative when the character faces screen-left, positive when it faces screen-right.
         */
        fun profile(towardsFacingEdge: Float) = FaceAnchors(
            eyeCenter = Vec2(0.50f + towardsFacingEdge * 0.10f, 0.44f),
            eyeWidth = 0.40f,
            mouthCenter = Vec2(0.50f + towardsFacingEdge * 0.16f, 0.70f),
            mouthWidth = 0.24f,
        )
    }
}

/**
 * The complete rest-pose definition of one camera view: which slot feeds which bone, where the
 * joints are, and how the parts are layered.
 *
 * This is the *only* place character geometry lives. The animation engine, the renderer and the
 * exporter all read from it, so adding or tuning a view never touches animation code.
 */
data class ViewAssembly(
    val view: ViewKind,
    /** Pivot used by whole-body (root) motion — the pelvis. */
    val rootJoint: Vec2,
    val bones: List<AssembledBone>,
    /** Assembled character width in view units, used for framing. */
    val characterWidth: Float,
    val faceAnchors: FaceAnchors,
    /** True when this view was synthesised by mirroring another view's artwork. */
    val mirroredFrom: ViewKind? = null,
) {
    fun bone(boneId: String): AssembledBone? = bones.firstOrNull { it.boneId == boneId }

    /** Draw order: parents and far limbs first, head/face last. */
    fun drawOrder(): List<AssembledBone> = bones.sortedWith(compareBy({ it.z }, { it.boneId }))

    val restBounds: FloatRect
        get() = bones.map { it.restRect }.reduce(FloatRect::expandBy)
}

/**
 * Rest-pose tables for the four views.
 *
 * Limb naming is **screen space**: `*_l` ends up on the left of the assembled view. In a
 * profile the user draws one arm and one leg; the near bone (`_r` when facing left, `_l` when
 * facing right) and the far bone share that single sprite, offset a little in x and shaded,
 * which gives a profile walk genuine opposite-phase limbs from one drawing.
 */
object ViewAssemblies {

    private const val FRONT_WIDTH = 0.410f

    private val frontBones: List<AssembledBone> = listOf(
        // Vertical layout: head top = 0.000, feet bottom = 1.000 (the floor line).
        AssembledBone(BoneIds.TORSO, "front_torso", Vec2(0.500f, 0.215f), 0.400f, 10),
        AssembledBone(BoneIds.HEAD, "front_head", Vec2(0.500f, 0.207f), 0.230f, 40),
        AssembledBone(BoneIds.UPPER_ARM_R, "front_upper_arm_r", Vec2(0.626f, 0.222f), 0.216f, 20),
        AssembledBone(BoneIds.FOREARM_R, "front_forearm_r", Vec2(0.634f, 0.421f), 0.196f, 21),
        AssembledBone(BoneIds.HAND_R, "front_hand_r", Vec2(0.640f, 0.601f), 0.090f, 22),
        AssembledBone(BoneIds.UPPER_ARM_L, "front_upper_arm_l", Vec2(0.374f, 0.222f), 0.216f, 30),
        AssembledBone(BoneIds.FOREARM_L, "front_forearm_l", Vec2(0.366f, 0.421f), 0.196f, 31),
        AssembledBone(BoneIds.HAND_L, "front_hand_l", Vec2(0.360f, 0.601f), 0.090f, 32),
        AssembledBone(BoneIds.THIGH_R, "front_thigh_r", Vec2(0.558f, 0.555f), 0.244f, 5),
        AssembledBone(BoneIds.SHIN_R, "front_shin_r", Vec2(0.562f, 0.779f), 0.235f, 4),
        AssembledBone(BoneIds.FOOT_R, "front_foot_r", Vec2(0.570f, 0.944f), 0.112f, 3),
        AssembledBone(BoneIds.THIGH_L, "front_thigh_l", Vec2(0.442f, 0.555f), 0.244f, 8),
        AssembledBone(BoneIds.SHIN_L, "front_shin_l", Vec2(0.438f, 0.779f), 0.235f, 7),
        AssembledBone(BoneIds.FOOT_L, "front_foot_l", Vec2(0.430f, 0.944f), 0.112f, 6),
    )

    val FRONT = ViewAssembly(
        view = ViewKind.FRONT,
        rootJoint = Vec2(0.500f, 0.545f),
        bones = frontBones,
        characterWidth = FRONT_WIDTH,
        faceAnchors = FaceAnchors.STANDARD,
    )

    /** Back view shares the front geometry exactly — only the artwork differs. */
    val BACK = ViewAssembly(
        view = ViewKind.BACK,
        rootJoint = Vec2(0.500f, 0.545f),
        bones = frontBones.map {
            it.copy(
                slotId = it.slotId.replaceFirst("front_", "back_"),
                z = if (it.boneId == BoneIds.HEAD) 40 else it.z - 1,
            )
        },
        characterWidth = FRONT_WIDTH,
        faceAnchors = FaceAnchors.STANDARD,
    )

    /**
     * Profile facing screen-left. The near side of the body (drawn in front of the torso) is
     * the `_r` bone set; the far side is `_l`, pushed back in x and shaded.
     */
    private fun sideBones(
        prefix: String,
        facingLeft: Boolean,
        mirrorArt: Boolean,
    ): List<AssembledBone> {
        // +1 keeps the far limbs behind the torso, -1 puts the near limbs in front of it.
        val farSign = if (facingLeft) 1f else -1f
        val nearSign = -farSign
        val farX = 0.014f * farSign
        val nearX = 0.014f * nearSign
        val farShade = 0.76f

        // Which of the character's own limbs is nearest the camera:
        //  - facing screen-left  -> the character's right side faces us;
        //  - facing screen-right -> the character's left side faces us;
        //  - mirrored artwork    -> a horizontal flip does not change which side faces us, so a
        //    mirrored left-facing drawing keeps its right limbs in front.
        val nearIsRight = facingLeft || mirrorArt

        // A derived (mirrored) profile is the exact mirror image of the authored one, so joints
        // that are not on the centre line — the ankle sits in front of the shin, the wrist in
        // front of the forearm — must be reflected as well as the artwork. Without this the
        // mirrored character's feet point backwards.
        fun mx(x: Float): Float = if (mirrorArt) 1f - x else x

        fun limb(
            slotSuffix: String,
            jointX: Float,
            jointY: Float,
            height: Float,
            zNear: Int,
            zFar: Int,
        ): List<AssembledBone> {
            val rightId = BoneIds.mirrorOf("${slotSuffix}_l")
            val leftId = "${slotSuffix}_l"
            val nearId = if (nearIsRight) rightId else leftId
            val farId = if (nearIsRight) leftId else rightId
            return listOf(
                AssembledBone(
                    boneId = nearId, slotId = "${prefix}_$slotSuffix",
                    joint = Vec2(mx(jointX) + nearX, jointY), targetHeight = height, z = zNear,
                    flipX = mirrorArt,
                ),
                AssembledBone(
                    boneId = farId, slotId = "${prefix}_$slotSuffix",
                    joint = Vec2(mx(jointX) + farX, jointY), targetHeight = height, z = zFar,
                    depthShade = farShade, flipX = mirrorArt,
                ),
            )
        }

        return listOf(
            AssembledBone(
                BoneIds.TORSO, "${prefix}_torso", Vec2(mx(0.500f), 0.215f), 0.400f, 10,
                flipX = mirrorArt,
            ),
            AssembledBone(
                BoneIds.HEAD, "${prefix}_head", Vec2(mx(0.500f), 0.207f), 0.215f, 40,
                flipX = mirrorArt,
            ),
        ) +
            limb("thigh", 0.500f, 0.555f, 0.240f, 13, 5) +
            limb("shin", 0.500f, 0.779f, 0.232f, 12, 4) +
            limb("foot", 0.470f, 0.944f, 0.105f, 11, 3) +
            limb("upper_arm", 0.500f, 0.222f, 0.205f, 22, 6) +
            limb("forearm", 0.502f, 0.421f, 0.190f, 23, 7) +
            limb("hand", 0.504f, 0.601f, 0.078f, 24, 8)
    }

    val SIDE_LEFT = ViewAssembly(
        view = ViewKind.SIDE_LEFT,
        rootJoint = Vec2(0.500f, 0.545f),
        bones = sideBones("side_left", facingLeft = true, mirrorArt = false),
        characterWidth = 0.330f,
        faceAnchors = FaceAnchors.profile(towardsFacingEdge = -1f),
    )

    val SIDE_RIGHT = ViewAssembly(
        view = ViewKind.SIDE_RIGHT,
        rootJoint = Vec2(0.500f, 0.545f),
        bones = sideBones("side_right", facingLeft = false, mirrorArt = false),
        characterWidth = 0.330f,
        faceAnchors = FaceAnchors.profile(towardsFacingEdge = 1f),
    )

    /**
     * Synthesises a side-right rig by mirroring the user's side-left artwork.
     *
     * This is an explicit, user-enabled convenience ("Mirror Side View"): the *same drawing*
     * is flipped and the near/far limb roles swap, so animation semantics stay correct. It is
     * never used to pretend a front drawing is a profile.
     */
    val SIDE_RIGHT_MIRRORED = ViewAssembly(
        view = ViewKind.SIDE_RIGHT,
        rootJoint = Vec2(0.500f, 0.545f),
        bones = sideBones("side_left", facingLeft = false, mirrorArt = true),
        characterWidth = 0.330f,
        // Anchored as if facing left: the artwork flip mirrors the head (and everything parented
        // to it) back to the right-hand edge, so the face ends up on the facing side.
        faceAnchors = FaceAnchors.profile(towardsFacingEdge = -1f),
        mirroredFrom = ViewKind.SIDE_LEFT,
    )

    fun forView(view: ViewKind): ViewAssembly = when (view) {
        ViewKind.FRONT -> FRONT
        ViewKind.SIDE_LEFT -> SIDE_LEFT
        ViewKind.SIDE_RIGHT -> SIDE_RIGHT
        ViewKind.BACK -> BACK
    }

    val ALL: List<ViewAssembly> = listOf(FRONT, SIDE_LEFT, SIDE_RIGHT, BACK)
}
