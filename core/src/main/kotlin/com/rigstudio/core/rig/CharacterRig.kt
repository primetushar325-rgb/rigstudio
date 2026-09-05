package com.rigstudio.core.rig

import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.FloatRect
import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.util.MathUtils

/**
 * Facial sprites available to a rig, keyed by expression / mouth shape.
 *
 * Missing sprites degrade gracefully: [eyeSprite] and [mouthSprite] fall back to the closest
 * available sprite and then to `null`, so Talk simply plays without mouth movement instead of
 * failing.
 */
data class FaceSet(
    val eyes: Map<Expression, SpriteAsset> = emptyMap(),
    val mouths: Map<MouthShape, SpriteAsset> = emptyMap(),
) {
    val hasEyes: Boolean get() = eyes.isNotEmpty()
    val hasMouths: Boolean get() = mouths.isNotEmpty()

    fun eyeSprite(expression: Expression): SpriteAsset? =
        eyes[expression] ?: eyes[Expression.NEUTRAL] ?: eyes.values.firstOrNull()

    fun mouthSprite(shape: MouthShape): SpriteAsset? =
        mouths[shape] ?: mouths[MouthShape.CLOSED] ?: mouths[MouthShape.NORMAL]
        ?: mouths.values.firstOrNull()

    companion object {
        val EMPTY = FaceSet()
    }
}

/**
 * A finished, animation-ready rig for one camera view.
 *
 * A character project holds one rig per available view; all of them share the same universal
 * bone ids, which is why a single animation library drives every view.
 */
class CharacterRig(
    val view: ViewKind,
    val bones: List<RigBone>,
    val rootJoint: Vec2,
    val characterWidth: Float,
    val faceAnchors: FaceAnchors,
    val faceSet: FaceSet,
    /** Non-null when this view's artwork was mirrored from another view. */
    val mirroredFrom: ViewKind? = null,
    val sheetSize: Int = com.rigstudio.core.template.CharacterSheetTemplate.SHEET_WIDTH,
) {

    val byId: Map<String, RigBone> = bones.associateBy { it.id }

    /** Parents before children — the order forward kinematics must be solved in. */
    val topologicalOrder: List<RigBone> = run {
        val resolved = LinkedHashMap<String, RigBone>(bones.size)
        fun visit(bone: RigBone, stack: Set<String>) {
            if (resolved.containsKey(bone.id)) return
            require(bone.id !in stack) { "Bone hierarchy cycle at ${bone.id}" }
            bone.parentId?.let { parent -> byId[parent]?.let { visit(it, stack + bone.id) } }
            resolved[bone.id] = bone
        }
        bones.forEach { visit(it, emptySet()) }
        resolved.values.toList()
    }

    /** Static draw order (before per-pose z resolution). */
    val drawOrder: List<RigBone> = bones.sortedWith(compareBy({ it.z }, { it.id }))

    fun bone(boneId: String): RigBone? = byId[boneId]

    fun hasBone(boneId: String): Boolean = byId[boneId]?.hasArtwork == true

    val hasHead: Boolean get() = hasBone(BoneIds.HEAD)

    /** Bounds of the rest pose in view units, used for framing the character. */
    val restBounds: FloatRect
        get() = bones.map { it.restRect }
            .filter { !it.isEmpty() }
            .fold(FloatRect.EMPTY) { acc, r -> if (acc.isEmpty()) r else acc.expandBy(r) }

    /** Sanity check used by unit tests: hierarchy must be complete and acyclic. */
    fun selfCheck(): List<String> {
        val problems = mutableListOf<String>()
        if (bones.isEmpty()) problems += "$view rig has no bones"
        for (bone in bones) {
            val parent = bone.parentId ?: continue
            if (byId[parent] == null) problems += "${bone.id}: parent '$parent' is missing"
        }
        if (topologicalOrder.size != bones.size) problems += "$view rig hierarchy is not resolvable"
        for (bone in bones) {
            if (bone.id == BoneIds.ROOT) problems += "sprite bones must not be named 'root'"
            if (!BoneIds.isKnown(bone.id)) problems += "unknown bone id '${bone.id}'"
        }
        return problems
    }

    override fun toString(): String =
        "CharacterRig(view=$view, bones=${bones.size}, face=${faceSet.eyes.size}eyes/${faceSet.mouths.size}mouths)"
}

/** One drawable item produced by the solver: a bone plus its world transform. */
data class BoneDraw(
    val bone: RigBone,
    /** Rest-space -> pixel-space transform (already includes the camera/view transform). */
    val world: Affine,
    /** Where the sprite sits in rest space, in view units. */
    val restRect: FloatRect,
    val z: Int,
    val depthShade: Float,
)

/** Result of solving a pose: per-bone world transforms plus the ordered draw list. */
class FkSolution(
    val transforms: Map<String, Affine>,
    val rootTransform: Affine,
    val draws: List<BoneDraw>,
    /** Axis-aligned bounds of everything drawn, in the same space as [rootTransform]'s output. */
    val bounds: FloatRect,
) {
    fun transformOf(boneId: String): Affine = transforms[boneId] ?: Affine.IDENTITY
}

/**
 * Pure 2D forward kinematics for a cut-out puppet.
 *
 * ```
 * world(bone) = world(parent) · T(joint + offset) · R(θ) · S(scale) · T(-joint) · [mirror]
 * ```
 *
 * Every bone is defined by its joint in **rest space**; a bone's local transform rotates the
 * whole rest space about that joint and children inherit it automatically. That is what makes a
 * thigh rotation carry the shin and the foot along without any skinning.
 *
 * The solver is shared by the on-screen preview and the MP4 exporter: given the same rig, pose
 * and view transform it always produces the same geometry, so export can never look different
 * from preview.
 */
object ForwardKinematics {

    fun solve(
        rig: CharacterRig,
        pose: Pose,
        viewTransform: Affine = Affine.IDENTITY,
        dynamicZ: Boolean = true,
    ): FkSolution {
        val rootOffset = pose.root.offset
        val rootRotation = MathUtils.degToRad(pose.root.rotationDeg)
        val rootLocal = Affine.translation(rig.rootJoint.x + rootOffset.x, rig.rootJoint.y + rootOffset.y)
            .multiply(Affine.rotation(rootRotation))
            .multiply(Affine.translation(-rig.rootJoint.x, -rig.rootJoint.y))
        val rootWorld = viewTransform.multiply(rootLocal)

        val transforms = LinkedHashMap<String, Affine>(rig.bones.size + 1)
        val drawTransforms = LinkedHashMap<String, Affine>(rig.bones.size + 1)
        transforms[BoneIds.ROOT] = rootWorld
        drawTransforms[BoneIds.ROOT] = rootWorld

        val zOverrides = if (dynamicZ) ZOrderResolver.resolve(rig, pose) else emptyMap()

        for (bone in rig.topologicalOrder) {
            val bonePose = pose.poseOf(bone.id)
            val parentWorld = bone.parentId?.let { transforms[it] } ?: rootWorld

            val rotation = MathUtils.degToRad(bonePose.rotationDeg * rotationSign(bone))
            var local = Affine.translation(bone.joint.x + bonePose.offset.x, bone.joint.y + bonePose.offset.y)
                .multiply(Affine.rotation(rotation))
            if (bonePose.scale != 1f) {
                local = local.multiply(Affine.scaling(bonePose.scale))
            }
            local = local.multiply(Affine.translation(-bone.joint.x, -bone.joint.y))

            // The transform handed to child bones never contains the artwork flip: a mirrored
            // profile places its joints as a mirror image already (see ViewAssemblies), so
            // mirroring again here would swing every child limb to the wrong side of the body.
            val world = parentWorld.multiply(local)
            transforms[bone.id] = world

            val restRect = bone.restRect
            drawTransforms[bone.id] = if (bone.flipX && !restRect.isEmpty()) {
                // Flipping about the artwork's own centre mirrors the pixels in place.
                world.multiply(Affine.mirrorAbout(restRect.centerX))
            } else {
                world
            }
        }

        val draws = ArrayList<BoneDraw>(rig.bones.size)
        var bounds = FloatRect.EMPTY
        for (bone in rig.bones) {
            val rect = bone.restRect
            if (rect.isEmpty()) continue
            val world = drawTransforms[bone.id] ?: continue
            draws += BoneDraw(
                bone = bone,
                world = world,
                restRect = rect,
                z = zOverrides[bone.id] ?: bone.z,
                depthShade = bone.depthShade,
            )
            bounds = expandWithTransformedRect(bounds, rect, world)
        }
        draws.sortWith(compareBy({ it.z }, { it.bone.id }))

        return FkSolution(transforms, rootWorld, draws, bounds)
    }

    /**
     * Profile rigs built by mirroring another view's artwork negate rotations, because a flipped
     * sprite swings the opposite way for the same signed angle. Clip data stays view-agnostic.
     */
    private fun rotationSign(bone: RigBone): Float = if (bone.flipX) -1f else 1f

    /** Posed bounds in view space (identity camera) — used by tests and by framing logic. */
    fun posedBounds(rig: CharacterRig, pose: Pose): FloatRect = solve(rig, pose).bounds

    private fun expandWithTransformedRect(acc: FloatRect, rect: FloatRect, world: Affine): FloatRect {
        val p1 = world.transform(rect.left, rect.top)
        val p2 = world.transform(rect.right, rect.top)
        val p3 = world.transform(rect.left, rect.bottom)
        val p4 = world.transform(rect.right, rect.bottom)
        val transformed = FloatRect(
            left = minOf(p1.x, p2.x, p3.x, p4.x),
            top = minOf(p1.y, p2.y, p3.y, p4.y),
            right = maxOf(p1.x, p2.x, p3.x, p4.x),
            bottom = maxOf(p1.y, p2.y, p3.y, p4.y),
        )
        return if (acc.isEmpty()) transformed else acc.expandBy(transformed)
    }
}

/**
 * Per-pose draw order.
 *
 * Front and back views swap which leg / arm is drawn on top so the limb that is swinging forward
 * passes in front of the trailing one — the cheapest possible depth cue and the reason a walk
 * cycle reads as a walk instead of a scissor. Profile views keep their authored near/far order,
 * because there the depth read comes from the near limb being in front of the torso.
 */
object ZOrderResolver {

    private const val LEG_FRONT_THIGH = 9
    private const val ARM_LEAD_BONUS = 12

    fun resolve(rig: CharacterRig, pose: Pose): Map<String, Int> {
        if (rig.view == ViewKind.SIDE_LEFT || rig.view == ViewKind.SIDE_RIGHT) return emptyMap()

        val result = HashMap<String, Int>(rig.bones.size)
        // A more negative thigh rotation means the leg is swinging forward (screen-left travel).
        val leftLegForward = pose.rotationOf(BoneIds.THIGH_L) <= pose.rotationOf(BoneIds.THIGH_R)
        val legBones = mapOf(
            BoneIds.THIGH_L to leftLegForward,
            BoneIds.SHIN_L to leftLegForward,
            BoneIds.FOOT_L to leftLegForward,
            BoneIds.THIGH_R to !leftLegForward,
            BoneIds.SHIN_R to !leftLegForward,
            BoneIds.FOOT_R to !leftLegForward,
        )
        for (bone in rig.bones) {
            val isLeg = legBones[bone.id]
            if (isLeg != null) {
                result[bone.id] = if (isLeg) forwardLegZ(bone.id) else trailingLegZ(bone.id)
                continue
            }
            val boneZ = bone.z
            val isArm = bone.id.startsWith("upper_arm") || bone.id.startsWith("forearm") ||
                bone.id.startsWith("hand")
            if (isArm) {
                val leftArmForward =
                    pose.rotationOf(BoneIds.UPPER_ARM_L) <= pose.rotationOf(BoneIds.UPPER_ARM_R)
                val leads = (bone.id.endsWith("_l") && leftArmForward) ||
                    (bone.id.endsWith("_r") && !leftArmForward)
                result[bone.id] = if (leads) boneZ + ARM_LEAD_BONUS else boneZ
                continue
            }
            result[bone.id] = boneZ
        }
        return result
    }

    private fun forwardLegZ(boneId: String): Int = when {
        boneId.startsWith("thigh") -> LEG_FRONT_THIGH
        boneId.startsWith("shin") -> LEG_FRONT_THIGH - 1
        else -> LEG_FRONT_THIGH - 2
    }

    private fun trailingLegZ(boneId: String): Int = when {
        boneId.startsWith("thigh") -> 5
        boneId.startsWith("shin") -> 4
        else -> 3
    }
}
