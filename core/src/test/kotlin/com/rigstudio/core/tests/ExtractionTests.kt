package com.rigstudio.core.tests

import com.rigstudio.core.extract.ArrayPixelSurface
import com.rigstudio.core.extract.SheetIssueLevel
import com.rigstudio.core.extract.SheetProcessResult
import com.rigstudio.core.extract.SheetProcessor
import com.rigstudio.core.extract.SpriteExtractor
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.support.Fixtures
import com.rigstudio.core.support.SyntheticSheet
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot
import com.rigstudio.core.util.ColorUtils

/**
 * Extraction and validation (spec §15–§18): fixed coordinates in, trimmed sprites out, plus the
 * honest report the import screen shows.
 */
object ExtractionTests {

    private fun sprite(result: SheetProcessResult, slotId: String) =
        result.sprites[slotId] ?: Assert.fails("slot '$slotId' produced no sprite")

    private fun scale(): Float = SyntheticSheet.scaleFor(Fixtures.TEST_SHEET_SIZE)

    private fun frontWithFace(slot: SheetSlot): Boolean = slot.required || slot.isFace

    val cases: List<TestCase> = listOf(
        TestCase("a complete sheet extracts every slot") {
            val result = Fixtures.process()
            Assert.equals(CharacterSheetTemplate.SLOTS.size, result.sprites.size, "extracted sprite count")
            Assert.that(result.isRiggable) { "sheet should be riggable: ${result.report.errors}" }
            Assert.equals(emptyList(), result.report.errors.map { it.message })
        },
        TestCase("extracted sprites are trimmed smaller than their slot") {
            val result = Fixtures.process()
            val head = sprite(result, "front_head")
            val slot = CharacterSheetTemplate.requireSlot("front_head")
            Assert.that(head.width < slot.rect.width) { "head was not trimmed: ${head.width}" }
            Assert.that(head.height < slot.rect.height) { "head was not trimmed vertically" }
            Assert.that(head.coverage > 0.2f) { "head coverage too low: ${head.coverage}" }
            Assert.that(head.coverage < 1f) { "head coverage should exclude transparent margins" }
        },
        TestCase("the joint stays inside every trimmed sprite") {
            val result = Fixtures.process()
            for (slot in CharacterSheetTemplate.bodySlots) {
                val extracted = sprite(result, slot.id)
                Assert.inRange(extracted.pivot.x, 0f, 1f, "${slot.id} pivot x")
                Assert.inRange(extracted.pivot.y, 0f, 1f, "${slot.id} pivot y")
                // The pivot must map back to the sheet position the template declares.
                val sheetX = extracted.sourceRect.x + extracted.pivot.x * extracted.sourceRect.width
                val sheetY = extracted.sourceRect.y + extracted.pivot.y * extracted.sourceRect.height
                val tolerance = 24f // a downscaled 512px test sheet is coarse; real sheets are exact
                Assert.close(slot.pivotPixelX.toFloat(), sheetX, tolerance, "${slot.id} pivot x in sheet space")
                Assert.close(slot.pivotPixelY.toFloat(), sheetY, tolerance, "${slot.id} pivot y in sheet space")
            }
        },
        TestCase("no slot ever reads another slot's pixels") {
            val result = Fixtures.process()
            for (slot in CharacterSheetTemplate.SLOTS) {
                val extracted = sprite(result, slot.id)
                val expected = SyntheticSheet.colorFor(slot.id)
                for (pixel in extracted.pixels) {
                    if (ColorUtils.alpha(pixel) == 0) continue
                    Assert.equals(ColorUtils.red(expected), ColorUtils.red(pixel), "${slot.id} foreign red")
                    Assert.equals(ColorUtils.green(expected), ColorUtils.green(pixel), "${slot.id} foreign green")
                    Assert.equals(ColorUtils.blue(expected), ColorUtils.blue(pixel), "${slot.id} foreign blue")
                }
            }
        },
        TestCase("crops never leave their own slot rectangle") {
            val result = Fixtures.process()
            for (slot in CharacterSheetTemplate.SLOTS) {
                val extracted = sprite(result, slot.id)
                val inside = slot.rect.contains(extracted.sourceRect.x, extracted.sourceRect.y) &&
                    extracted.sourceRect.right <= slot.rect.right + 2 &&
                    extracted.sourceRect.bottom <= slot.rect.bottom + 2
                Assert.that(inside) {
                    "${slot.id} crop ${extracted.sourceRect} escaped slot ${slot.rect}"
                }
            }
        },
        TestCase("empty slots are reported, not fatal") {
            val result = Fixtures.process(include = Fixtures.minimalInclude())
            Assert.that(result.isRiggable) { "minimal front sheet must still rig: ${result.report.errors}" }
            Assert.equals(10, result.sprites.size, "only the required parts are present")
            Assert.that(result.report.isFilled("front_head")) { "head should be filled" }
            Assert.that(!result.report.isFilled("front_hand_l")) { "hand should be empty" }
            Assert.that(!result.report.isFilled("eye_open")) { "eyes should be empty" }
            Assert.that(!result.report.isViewAvailable(ViewKind.SIDE_LEFT)) { "no profile artwork" }
            Assert.that(!result.report.isViewAvailable(ViewKind.BACK)) { "no back artwork" }
            Assert.that(result.report.isViewAvailable(ViewKind.FRONT)) { "front must be available" }
            val info = result.report.infos.map { it.message }
            Assert.that(info.any { it.contains("Hand") }) { "missing optional parts should be reported: $info" }
            Assert.that(info.any { it.contains("Side View unavailable") }) {
                "missing profile artwork must say so: $info"
            }
            Assert.that(info.any { it.contains("Back View unavailable") }) {
                "missing back artwork must say so: $info"
            }
        },
        TestCase("a required empty slot blocks rigging with a clear message") {
            val result = Fixtures.process(include = { slot -> slot.required && slot.id != "front_head" })
            Assert.that(!result.isRiggable) { "sheet without a head must not rig" }
            val message = result.report.errors.first().message
            Assert.that(message.contains("Head")) { "error should name the missing part: $message" }
            Assert.that(message.contains("missing")) { "error should explain the problem: $message" }
            Assert.that(!message.contains("Exception")) { "no technical text in user messages: $message" }
        },
        TestCase("an empty sheet is rejected") {
            val result = Fixtures.process(include = { false })
            Assert.equals(0, result.sprites.size)
            Assert.that(!result.isRiggable) { "empty sheet must not rig" }
            Assert.equals(10, result.report.errors.size, "one error per missing required part")
        },
        TestCase("a non transparent background is rejected") {
            val result = Fixtures.process(opaqueBackground = true)
            Assert.that(!result.isRiggable) { "opaque background must not rig" }
            val message = result.report.errors.map { it.message }.joinToString(" ")
            Assert.that(message.contains("transparent")) { "must explain transparency: $message" }
        },
        TestCase("wrong resolution is rejected with the required size") {
            val surface = SyntheticSheet.build(size = 256)
            val result = SheetProcessor().process(surface, Fixtures.meta(width = 1024, height = 1024))
            Assert.that(!result.isRiggable) { "wrong size must not rig" }
            val message = result.report.errors.first().message
            Assert.that(message.contains("2048")) { "message must state the required size: $message" }
            Assert.that(message.contains("1024")) { "message must state the actual size: $message" }
        },
        TestCase("a missing alpha channel is rejected") {
            val noAlpha = ArrayPixelSurface(
                Fixtures.TEST_SHEET_SIZE,
                Fixtures.TEST_SHEET_SIZE,
                IntArray(Fixtures.TEST_SHEET_SIZE * Fixtures.TEST_SHEET_SIZE) { ColorUtils.rgb(200, 200, 200) },
                hasAlphaChannel = false,
            )
            val result = SheetProcessor().process(noAlpha, Fixtures.meta(hasAlpha = false))
            Assert.that(!result.isRiggable) { "no alpha channel must not rig" }
            Assert.that(result.report.errors.any { it.message.contains("alpha") }) {
                "must mention the alpha channel: ${result.report.errors}"
            }
        },
        TestCase("artwork outside the template areas is reported and ignored") {
            val result = Fixtures.process(paintOutsideSlots = true)
            Assert.that(result.report.warnings.any { it.message.contains("outside the template") }) {
                "stray artwork should warn: ${result.report.warnings}"
            }
            Assert.that(result.isRiggable) { "stray artwork is not fatal: ${result.report.errors}" }
            val head = sprite(result, "front_head")
            Assert.that(head.pixels.none { ColorUtils.alpha(it) > 0 && ColorUtils.red(it) == 255 && ColorUtils.green(it) == 255 }) {
                "stray artwork leaked into the head sprite"
            }
        },
        TestCase("side-left only suggests mirroring for the right profile") {
            val result = Fixtures.process(include = Fixtures.sideLeftOnlyInclude())
            Assert.that(result.report.isViewComplete(ViewKind.SIDE_LEFT)) { "side left should be complete" }
            Assert.that(!result.report.isViewComplete(ViewKind.SIDE_RIGHT)) { "side right should be empty" }
            Assert.that(result.report.canMirrorSideView) { "mirroring must be offered" }
            Assert.that(result.report.infos.any { it.message.contains("Mirror Side View") }) {
                "the report should mention the mirror option"
            }
        },
        TestCase("partially filled views warn and stay disabled") {
            val result = Fixtures.process(include = { slot -> slot.required || slot.id == "side_left_head" })
            Assert.that(!result.report.isViewAvailable(ViewKind.SIDE_LEFT)) { "incomplete profile is unusable" }
            val warning = result.report.warnings.firstOrNull { it.message.contains("Side Left") }
            Assert.that(warning != null) { "incomplete profile must warn: ${result.report.warnings}" }
            Assert.that(warning!!.message.contains("1/8")) {
                "warning should count filled areas: ${warning.message}"
            }
        },
        TestCase("face availability drives expressions and mouths") {
            val withoutFace = Fixtures.process(include = Fixtures.minimalInclude()).report
            Assert.that(!withoutFace.hasEyes && !withoutFace.hasMouths) { "no face sprites" }
            Assert.equals(emptyList(), withoutFace.availableExpressions)

            val withFace = Fixtures.process(include = ::frontWithFace).report
            Assert.equals(5, withFace.availableExpressions.size, "all expressions available")
            Assert.equals(11, withFace.availableMouthShapes.size, "all mouth shapes available")
            Assert.that(withFace.hasEyes && withFace.hasMouths) { "face should be usable" }
        },
        TestCase("the extractor returns null for a blank slot") {
            val surface = SyntheticSheet.build(size = 256, include = { false })
            val extracted = SpriteExtractor().extract(
                surface,
                CharacterSheetTemplate.requireSlot("front_head"),
                scale = 256f / CharacterSheetTemplate.SHEET_WIDTH,
            )
            Assert.equals(null, extracted, "blank slot must produce no sprite")
        },
        TestCase("alpha threshold ignores near-transparent fringes") {
            val surface = SyntheticSheet.build(size = Fixtures.TEST_SHEET_SIZE)
            val slot = CharacterSheetTemplate.requireSlot("front_torso")
            val strict = SpriteExtractor(alphaThreshold = 200).extract(surface, slot, scale())
            val loose = SpriteExtractor(alphaThreshold = 1).extract(surface, slot, scale())
            Assert.that(strict != null && loose != null) { "both thresholds should find the torso" }
            Assert.that(loose!!.width >= strict!!.width) {
                "a lower alpha threshold must not trim more: ${loose.width} vs ${strict.width}"
            }
            Assert.that(loose.height >= strict.height) { "a lower alpha threshold must not trim vertically" }
        },
        TestCase("user facing messages never contain technical detail") {
            val result = Fixtures.process(include = { false })
            for (issue in result.report.issues) {
                Assert.that(issue.message.isNotBlank()) { "empty message" }
                Assert.that(!issue.message.contains("\tat ")) { "stack trace in message" }
                Assert.that(!issue.message.contains("null")) { "raw null in message: ${issue.message}" }
                Assert.that(issue.level == SheetIssueLevel.ERROR || issue.level == SheetIssueLevel.WARNING ||
                    issue.level == SheetIssueLevel.INFO) { "unknown severity" }
            }
        },
    )
}
