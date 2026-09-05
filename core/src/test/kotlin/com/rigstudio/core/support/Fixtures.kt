package com.rigstudio.core.support

import com.rigstudio.core.extract.SheetImageMeta
import com.rigstudio.core.extract.SheetProcessResult
import com.rigstudio.core.extract.SheetProcessor
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.RigBuildResult
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.RigOptions
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot

/**
 * Shared test fixtures.
 *
 * The synthetic surfaces are downscaled stand-ins for a real 2048² sheet, so the metadata always
 * claims the true sheet size — exactly what the app passes after decoding a correct PNG.
 */
object Fixtures {

    const val TEST_SHEET_SIZE = 512

    fun meta(width: Int = CharacterSheetTemplate.SHEET_WIDTH, height: Int = CharacterSheetTemplate.SHEET_HEIGHT, hasAlpha: Boolean = true) =
        SheetImageMeta(width, height, hasAlpha, "image/png", 250_000L)

    fun process(
        size: Int = TEST_SHEET_SIZE,
        include: (SheetSlot) -> Boolean = { true },
        opaqueBackground: Boolean = false,
        paintOutsideSlots: Boolean = false,
        meta: SheetImageMeta = meta(),
    ): SheetProcessResult = SheetProcessor().process(
        SyntheticSheet.build(
            size = size,
            include = include,
            opaqueBackground = opaqueBackground,
            paintOutsideSlots = paintOutsideSlots,
        ),
        meta,
    )

    fun buildRigs(
        include: (SheetSlot) -> Boolean = { true },
        options: RigOptions = RigOptions(),
        opaqueBackground: Boolean = false,
    ): RigBuildResult = RigBuilder.build(process(include = include, opaqueBackground = opaqueBackground), options)

    fun rig(
        view: ViewKind = ViewKind.FRONT,
        include: (SheetSlot) -> Boolean = { true },
        options: RigOptions = RigOptions(),
    ): CharacterRig {
        val built = buildRigs(include, options)
        return requireNotNull(built.rigFor(view)) { "no rig built for $view (issues: ${built.report.issues})" }
    }

    /** Only the mandatory front parts: no hands, feet, face, profile or back artwork. */
    fun minimalInclude(): (SheetSlot) -> Boolean = { it.required }

    /** Front body + face + side-left only (the "Mirror Side View" case). */
    fun sideLeftOnlyInclude(): (SheetSlot) -> Boolean = { slot ->
        slot.required || slot.isFace || slot.view == ViewKind.SIDE_LEFT
    }
}
