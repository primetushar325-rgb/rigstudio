package com.rigstudio.core.rig

import com.rigstudio.core.extract.ExtractedSprite
import com.rigstudio.core.extract.SheetProcessResult
import com.rigstudio.core.extract.SheetValidationReport
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.template.CharacterSheetTemplate

/** User-facing rigging options. There is no manual rigging — these are the only choices. */
data class RigOptions(
    /**
     * Build the right-facing profile by mirroring the left-facing artwork. Only offered when the
     * sheet actually contains side-left artwork and no side-right artwork.
     */
    val mirrorSideView: Boolean = false,
)

/** Which views a character has artwork for. Persisted with the project so reopening is instant. */
data class ViewAvailability(
    val front: Boolean = false,
    val sideLeft: Boolean = false,
    val sideRight: Boolean = false,
    val back: Boolean = false,
    /** True when the right profile was synthesised by mirroring the left artwork. */
    val mirroredSideView: Boolean = false,
) {
    fun has(view: ViewKind): Boolean = when (view) {
        ViewKind.FRONT -> front
        ViewKind.SIDE_LEFT -> sideLeft
        ViewKind.SIDE_RIGHT -> sideRight
        ViewKind.BACK -> back
    }

    val hasProfile: Boolean get() = sideLeft || sideRight

    val views: List<ViewKind> get() = ViewKind.entries.filter { has(it) }

    companion object {
        fun from(availableViews: List<ViewKind>, mirroredSideView: Boolean) = ViewAvailability(
            front = ViewKind.FRONT in availableViews,
            sideLeft = ViewKind.SIDE_LEFT in availableViews,
            sideRight = ViewKind.SIDE_RIGHT in availableViews,
            back = ViewKind.BACK in availableViews,
            mirroredSideView = mirroredSideView,
        )
    }
}

/** Thrown when a rig cannot be built; the message is safe to show to the user. */
class RiggingException(message: String) : Exception(message)

/** Everything the automatic rigging step produced. */
class RigBuildResult(
    val rigs: Map<ViewKind, CharacterRig>,
    val report: SheetValidationReport,
    val sprites: Map<String, ExtractedSprite>,
) {
    val availableViews: List<ViewKind>
        get() = ViewKind.entries.filter { rigs.containsKey(it) }

    val availability: ViewAvailability
        get() = ViewAvailability(
            front = rigs.containsKey(ViewKind.FRONT),
            sideLeft = rigs.containsKey(ViewKind.SIDE_LEFT),
            sideRight = rigs.containsKey(ViewKind.SIDE_RIGHT),
            back = rigs.containsKey(ViewKind.BACK),
            mirroredSideView = rigs[ViewKind.SIDE_RIGHT]?.mirroredFrom == ViewKind.SIDE_LEFT,
        )

    fun rigFor(view: ViewKind): CharacterRig? = rigs[view]

    val front: CharacterRig? get() = rigs[ViewKind.FRONT]

    fun requireFront(): CharacterRig = front ?: throw RiggingException(
        report.headlineMessage
            ?: "Could not build this character. Please import a completed RigStudio character sheet.",
    )

    val isRigged: Boolean get() = rigs.isNotEmpty()
}

/**
 * Turns an extracted character sheet into animation-ready rigs — the "AUTOMATIC RIG" step.
 *
 * There is nothing adaptive in here, and that is the point: the template already knows where
 * every part is, so building a rig is table lookup plus pivot maths. Given the same sheet it
 * always produces the same rig, on any device, offline, in a few milliseconds.
 *
 * Two entry points:
 *  - [build] straight from a freshly processed sheet (import);
 *  - [buildFromAssets] from the sprite manifest of a saved project (reopen without re-extracting).
 */
object RigBuilder {

    fun build(result: SheetProcessResult, options: RigOptions = RigOptions()): RigBuildResult {
        val report = result.report
        if (!report.isRiggable) {
            return RigBuildResult(emptyMap(), report, result.sprites)
        }

        val assets = assetsOf(result)
        val availability = ViewAvailability(
            front = true,
            sideLeft = report.isViewComplete(ViewKind.SIDE_LEFT),
            sideRight = report.isViewComplete(ViewKind.SIDE_RIGHT),
            back = report.isViewComplete(ViewKind.BACK),
            mirroredSideView = options.mirrorSideView && report.canMirrorSideView,
        )
        return RigBuildResult(buildFromAssets(assets, availability), report, result.sprites)
    }

    /**
     * Geometry-only view of every extracted sprite, keyed by slot id.
     *
     * This is the hand-off point between extraction and rigging: pixels stay in the app module,
     * measurements travel with the rig.
     */
    fun assetsOf(result: SheetProcessResult): Map<String, SpriteAsset> =
        result.sprites.mapValues { (_, sprite) -> sprite.toAsset() }

    /**
     * Builds every available view's rig from sprite assets alone.
     *
     * Optional parts the user did not draw simply produce bones without artwork; the renderer
     * skips them, so a character with no hands still walks.
     */
    fun buildFromAssets(
        assets: Map<String, SpriteAsset>,
        availability: ViewAvailability,
    ): Map<ViewKind, CharacterRig> {
        val faceSet = buildFaceSet(assets)
        val rigs = LinkedHashMap<ViewKind, CharacterRig>()

        if (availability.front) {
            assemble(ViewAssemblies.FRONT, assets, faceSet)?.let { rigs[ViewKind.FRONT] = it }
        }
        if (availability.sideLeft) {
            assemble(ViewAssemblies.SIDE_LEFT, assets, faceSet)?.let { rigs[ViewKind.SIDE_LEFT] = it }
        }
        // A mirrored profile is built from side-left artwork, so it exists precisely when the
        // right-hand slots are empty — checking availability.sideRight alone would never fire.
        if (availability.sideRight || availability.mirroredSideView) {
            val assembly = if (availability.mirroredSideView) {
                ViewAssemblies.SIDE_RIGHT_MIRRORED
            } else {
                ViewAssemblies.SIDE_RIGHT
            }
            assemble(assembly, assets, faceSet)?.let { rigs[ViewKind.SIDE_RIGHT] = it }
        }
        if (availability.back) {
            // A back view never shows a face: overlaying eyes and mouth on the back of a head
            // would be invented artwork, which RigStudio refuses to produce.
            assemble(ViewAssemblies.BACK, assets, FaceSet.EMPTY)?.let { rigs[ViewKind.BACK] = it }
        }
        return rigs
    }

    /** @return null when the view cannot be assembled (its torso artwork is missing). */
    private fun assemble(
        assembly: ViewAssembly,
        assets: Map<String, SpriteAsset>,
        faceSet: FaceSet,
    ): CharacterRig? {
        val torsoSlot = assembly.bone(BoneIds.TORSO)?.slotId ?: return null
        if (assets[torsoSlot] == null) return null

        val bones = assembly.bones.mapNotNull { part ->
            val asset = assets[part.slotId] ?: return@mapNotNull null // optional part not drawn
            RigBone(
                id = part.boneId,
                parentId = BoneIds.PARENTS[part.boneId],
                sprite = asset,
                joint = part.joint,
                targetHeight = part.targetHeight,
                z = part.z,
                // Mirrored artwork swings the opposite way for the same signed angle, so the
                // limit table is mirrored with it; clip data stays view-agnostic.
                constraint = if (part.flipX) part.constraint.mirrored() else part.constraint,
                depthShade = part.depthShade,
                flipX = part.flipX,
            )
        }

        return CharacterRig(
            view = assembly.view,
            bones = bones,
            rootJoint = assembly.rootJoint,
            characterWidth = assembly.characterWidth,
            faceAnchors = assembly.faceAnchors,
            faceSet = if (assembly.bone(BoneIds.HEAD) != null) faceSet else FaceSet.EMPTY,
            mirroredFrom = assembly.mirroredFrom,
        )
    }

    /** Collects the facial sprites that actually exist on the sheet. */
    fun buildFaceSet(assets: Map<String, SpriteAsset>): FaceSet {
        val eyes = LinkedHashMap<Expression, SpriteAsset>()
        for (slot in CharacterSheetTemplate.eyeSlots) {
            val expression = slot.expression ?: continue
            assets[slot.id]?.let { eyes[expression] = it }
        }
        val mouths = LinkedHashMap<MouthShape, SpriteAsset>()
        for (slot in CharacterSheetTemplate.mouthSlots) {
            val shape = slot.mouthShape ?: continue
            assets[slot.id]?.let { mouths[shape] = it }
        }
        return FaceSet(eyes, mouths)
    }

    private fun ExtractedSprite.toAsset() = SpriteAsset(
        slotId = slotId,
        width = width,
        height = height,
        pivot = pivot,
        coverage = coverage,
        sourceRect = sourceRect,
        contentRect = contentRect,
    )
}
