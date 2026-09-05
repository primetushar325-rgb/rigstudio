package com.rigstudio.core.render

import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.FloatRect
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.FaceSet
import com.rigstudio.core.rig.FkSolution
import com.rigstudio.core.rig.ForwardKinematics
import com.rigstudio.core.rig.Pose
import com.rigstudio.core.rig.SpriteAsset

/** What a draw call is for: body artwork, or one of the face overlays. */
enum class PuppetPart { BODY, EYES, MOUTH }

/**
 * One sprite to blit, fully resolved.
 *
 * [world] maps the sprite's rest-space rectangle ([restRect], in view units) into output space.
 * A renderer therefore needs no geometry knowledge at all: it converts [world] to a matrix,
 * draws its bitmap into [restRect] and moves on. Because the same list drives the on-screen
 * preview and the video encoder, the exported MP4 is frame-identical to what the user saw.
 */
data class PuppetDraw(
    /** Slot id of the artwork — the key into the app's bitmap cache. */
    val slotId: String,
    val sprite: SpriteAsset,
    val world: Affine,
    val restRect: FloatRect,
    val z: Int,
    /** Depth shading (1 = full colour, <1 = a limb sitting behind the body). */
    val shade: Float,
    val part: PuppetPart,
) {
    /** Centre of the artwork in output space — handy for hit testing and thumbnails. */
    val centre get() = world.transform(restRect.centerX, restRect.centerY)
}

/**
 * Turns a pose into an ordered draw list: the whole "what does the character look like" decision.
 *
 * Body parts come from forward kinematics (which already resolves per-pose layering, so a walking
 * character swaps its legs in front of and behind the torso). Face sprites are attached to the
 * head's transform, so they rotate, translate and mirror with it — and a view without face
 * artwork, or a back view, simply produces none.
 */
object PuppetComposer {

    /** Face layers sit above the head (`z = 40`) and below nothing else. */
    const val EYES_Z = 60
    const val MOUTH_Z = 61

    fun compose(
        rig: CharacterRig,
        pose: Pose,
        viewTransform: Affine = Affine.IDENTITY,
    ): List<PuppetDraw> {
        val solution = ForwardKinematics.solve(rig, pose, viewTransform)
        val draws = ArrayList<PuppetDraw>(solution.draws.size + 2)

        for (draw in solution.draws) {
            val sprite = draw.bone.sprite ?: continue
            draws += PuppetDraw(
                slotId = sprite.slotId,
                sprite = sprite,
                world = draw.world,
                restRect = draw.restRect,
                z = draw.z,
                shade = draw.depthShade,
                part = PuppetPart.BODY,
            )
        }
        draws += faceDraws(rig, solution, pose)
        draws.sortWith(compareBy({ it.z }, { it.slotId }))
        return draws
    }

    /**
     * Eye and mouth overlays for the current pose.
     *
     * Anchors are fractions of the head's rest rectangle, so the face scales with whatever head
     * the user drew. Sprites the user did not draw fall back through [com.rigstudio.core.rig.FaceSet],
     * which is why Talk still plays — silently — when no mouth shapes exist.
     */
    private fun faceDraws(
        rig: CharacterRig,
        solution: FkSolution,
        pose: Pose,
    ): List<PuppetDraw> {
        if (rig.faceSet.isEmpty()) return emptyList()
        val headDraw = solution.draws.firstOrNull { it.bone.id == BoneIds.HEAD } ?: return emptyList()
        val headRect = headDraw.restRect
        if (headRect.isEmpty()) return emptyList()

        val anchors = rig.faceAnchors
        val out = ArrayList<PuppetDraw>(2)

        rig.faceSet.eyeSprite(pose.expression)?.let { eyes ->
            centredRect(headRect, anchors.eyeCenter.x, anchors.eyeCenter.y, anchors.eyeWidth, eyes)?.let { rect ->
                out += PuppetDraw(
                    slotId = eyes.slotId,
                    sprite = eyes,
                    // The head's draw transform already contains its mirror, so profile faces
                    // land on the facing side of the head and flip with the artwork.
                    world = headDraw.world,
                    restRect = rect,
                    z = EYES_Z,
                    shade = 1f,
                    part = PuppetPart.EYES,
                )
            }
        }

        rig.faceSet.mouthSprite(pose.mouth)?.let { mouth ->
            centredRect(headRect, anchors.mouthCenter.x, anchors.mouthCenter.y, anchors.mouthWidth, mouth)
                ?.let { rect ->
                    out += PuppetDraw(
                        slotId = mouth.slotId,
                        sprite = mouth,
                        world = headDraw.world,
                        restRect = rect,
                        z = MOUTH_Z,
                        shade = 1f,
                        part = PuppetPart.MOUTH,
                    )
                }
        }
        return out
    }

    /**
     * Rectangle of width `widthFraction * head width`, centred on an anchor point, with the
     * height derived from the sprite's own aspect so faces never stretch.
     */
    private fun centredRect(
        headRect: FloatRect,
        anchorX: Float,
        anchorY: Float,
        widthFraction: Float,
        sprite: SpriteAsset,
    ): FloatRect? {
        val width = headRect.width * widthFraction
        if (width <= 0f || sprite.aspect <= 0f) return null
        val height = width / sprite.aspect
        val cx = headRect.left + headRect.width * anchorX
        val cy = headRect.top + headRect.height * anchorY
        return FloatRect(cx - width * 0.5f, cy - height * 0.5f, cx + width * 0.5f, cy + height * 0.5f)
    }

    private fun FaceSet.isEmpty(): Boolean = !hasEyes && !hasMouths
}
