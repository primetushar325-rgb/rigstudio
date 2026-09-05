package com.rigstudio.core.extract

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.SlotKind
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot
import com.rigstudio.core.util.ColorUtils

/** How much a validation finding should shout at the user. */
enum class SheetIssueLevel { ERROR, WARNING, INFO }

/**
 * One validation finding. Messages are written for humans (spec §32) — the UI shows them
 * verbatim, so they must never contain stack traces or internal jargon.
 */
data class SheetIssue(
    val level: SheetIssueLevel,
    val message: String,
    val slotId: String? = null,
)

/** What the platform told us about the imported file before we looked at a single pixel. */
data class SheetImageMeta(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val mimeType: String? = null,
    val byteCount: Long = -1L,
)

/** Per-slot result of the pixel scan. */
data class SlotScan(
    val slotId: String,
    val filled: Boolean,
    val coverage: Float,
    val touchesEdge: Boolean,
)

/**
 * Outcome of analysing a character sheet: what was found, what is usable and what the user
 * needs to fix. Deliberately a plain value object so it can be rendered by any UI and asserted
 * in tests.
 */
/**
 * Fraction of the drawing that may sit outside every template slot before RigStudio warns.
 *
 * Anti-aliasing at a slot border keeps this near zero on a clean sheet, so the threshold only
 * trips when the user has really painted somewhere the importer will ignore.
 */
private const val OUTSIDE_INK_WARNING_THRESHOLD = 0.02f

class SheetValidationReport(
    val meta: SheetImageMeta,
    val scans: Map<String, SlotScan>,
    val issues: List<SheetIssue>,
    /** Fraction (0..1) of sampled sheet pixels that are not transparent. */
    val inkCoverage: Float,
    /** Fraction (0..1) of sampled ink pixels that fall outside every template slot. */
    val inkOutsideSlots: Float,
) {
    val errors: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.ERROR }
    val warnings: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.WARNING }
    val infos: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.INFO }

    val filledSlotIds: Set<String>
        get() = scans.filterValues { it.filled }.keys

    fun isFilled(slotId: String): Boolean = scans[slotId]?.filled == true

    fun missingSlots(view: ViewKind): List<SheetSlot> =
        CharacterSheetTemplate.slotsFor(view).filterNot { isFilled(it.id) }

    fun isViewComplete(view: ViewKind): Boolean =
        CharacterSheetTemplate.slotsFor(view).all { isFilled(it.id) }

    /**
     * A view is usable when every slot it needs is filled. Front additionally requires the
     * sheet itself to be valid (right size, transparent, mandatory parts present).
     */
    fun isViewAvailable(view: ViewKind): Boolean = when (view) {
        // The front view is usable as soon as the sheet is valid and every *mandatory* part was
        // drawn. Hands, feet and face are optional and simply do not exist on the rig.
        ViewKind.FRONT -> hasValidSize && hasAlphaChannel && requiredSlotsFilled
        else -> isViewComplete(view)
    }

    val availableViews: List<ViewKind>
        get() = ViewKind.entries.filter { isViewAvailable(it) }

    val hasValidSize: Boolean
        get() = meta.width == CharacterSheetTemplate.SHEET_WIDTH &&
            meta.height == CharacterSheetTemplate.SHEET_HEIGHT

    val hasAlphaChannel: Boolean get() = meta.hasAlpha

    val requiredSlotsFilled: Boolean
        get() = CharacterSheetTemplate.requiredSlots.all { isFilled(it.id) }

    /** True when RigStudio can build a front-view rig from this sheet. */
    val isRiggable: Boolean get() = errors.isEmpty() && requiredSlotsFilled

    /** Side-right can be synthesised from side-left artwork when the user opts in. */
    val canMirrorSideView: Boolean
        get() = isViewComplete(ViewKind.SIDE_LEFT) && !isViewComplete(ViewKind.SIDE_RIGHT)

    val availableExpressions: List<Expression>
        get() = CharacterSheetTemplate.eyeSlots
            .filter { isFilled(it.id) }
            .mapNotNull { it.expression }

    val availableMouthShapes: List<MouthShape>
        get() = CharacterSheetTemplate.mouthSlots
            .filter { isFilled(it.id) }
            .mapNotNull { it.mouthShape }

    val hasEyes: Boolean get() = availableExpressions.isNotEmpty()
    val hasMouths: Boolean get() = availableMouthShapes.isNotEmpty()

    /** The single most important message to show, or null when the sheet is clean. */
    val headlineMessage: String?
        get() = errors.firstOrNull()?.message ?: warnings.firstOrNull()?.message
}

/**
 * Deterministic, offline character-sheet validation (spec §16 / §17).
 *
 * Only plain pixel statistics are used: alpha thresholds, bounding boxes, ink coverage and
 * region membership. There is no shape recognition and nothing leaves the device.
 */
class SheetValidator(
    /** Pixels above this alpha count as artwork. */
    private val alphaThreshold: Int = 8,
    /** Sampling step for whole-sheet statistics (4 keeps a 2048² scan around 260k samples). */
    private val sampleStep: Int = 4,
) {

    fun analyze(meta: SheetImageMeta, surface: PixelSurface, sprites: Map<String, ExtractedSprite>): SheetValidationReport {
        val issues = mutableListOf<SheetIssue>()
        val template = CharacterSheetTemplate

        // --- sheet level checks ------------------------------------------------------------
        if (meta.width != template.SHEET_WIDTH || meta.height != template.SHEET_HEIGHT) {
            issues += SheetIssue(
                SheetIssueLevel.ERROR,
                "Character Sheet must be ${template.SHEET_WIDTH}×${template.SHEET_HEIGHT} PNG. " +
                    "This image is ${meta.width}×${meta.height}.",
            )
        }
        if (!meta.hasAlpha) {
            issues += SheetIssue(
                SheetIssueLevel.ERROR,
                "The Character Sheet must be a transparent RGBA PNG. This image has no alpha channel.",
            )
        }
        val mimeType = meta.mimeType
        if (mimeType != null && mimeType != "image/png" && !mimeType.startsWith("image/")) {
            issues += SheetIssue(
                SheetIssueLevel.ERROR,
                "Unsupported file type '$mimeType'. Please import a PNG character sheet.",
            )
        }

        val scale = if (surface.width > 0) surface.width.toFloat() / template.SHEET_WIDTH else 1f

        // --- whole-sheet ink statistics -----------------------------------------------------
        var sampled = 0
        var ink = 0
        var inkOutside = 0
        var opaqueCorners = 0
        val step = sampleStep.coerceAtLeast(1)
        // Rows -> x intervals of the slots that intersect them (keeps the membership test cheap).
        val intervalsByRow = HashMap<Int, List<IntArray>>()
        for (y in 0 until surface.height step step) {
            val sheetY = (y / scale).toInt()
            val intervals = intervalsByRow.getOrPut(sheetY) {
                buildRowIntervals(sheetY, template.SLOTS, template.NOTES_AREAS)
            }
            val row = surface.readRect(IntRect(0, y, surface.width, 1))
            var x = 0
            while (x < row.size) {
                sampled++
                if (ColorUtils.alpha(row[x]) > alphaThreshold) {
                    ink++
                    val sheetX = (x / scale).toInt()
                    if (!insideAny(sheetX, sheetY, intervals)) inkOutside++
                }
                x += step
            }
        }
        for (corner in listOf(
            IntRect(0, 0, 1, 1),
            IntRect(surface.width - 1, 0, 1, 1),
            IntRect(0, surface.height - 1, 1, 1),
            IntRect(surface.width - 1, surface.height - 1, 1, 1),
        )) {
            val px = surface.readRect(corner.clampTo(surface.bounds()))
            if (px.isNotEmpty() && ColorUtils.alpha(px[0]) > alphaThreshold) opaqueCorners++
        }

        val inkCoverage = if (sampled == 0) 0f else ink.toFloat() / sampled
        val inkOutsideSlots = if (ink == 0) 0f else inkOutside.toFloat() / ink

        if (opaqueCorners == 4 || inkCoverage > 0.60f) {
            issues += SheetIssue(
                SheetIssueLevel.ERROR,
                "The sheet background is not transparent. Export your character sheet as a PNG " +
                    "with a transparent background and import it again.",
            )
        }
        if (inkOutsideSlots > OUTSIDE_INK_WARNING_THRESHOLD) {
            issues += SheetIssue(
                SheetIssueLevel.WARNING,
                "Some artwork sits outside the template areas and will be ignored " +
                    "(${(inkOutsideSlots * 100).toInt()}% of the drawing).",
            )
        }

        // --- per slot checks ----------------------------------------------------------------
        val scans = LinkedHashMap<String, SlotScan>(template.SLOTS.size)
        for (slot in template.SLOTS) {
            val sprite = sprites[slot.id]
            val filled = sprite != null && !sprite.isBlank()
            scans[slot.id] = SlotScan(
                slotId = slot.id,
                filled = filled,
                coverage = sprite?.coverage ?: 0f,
                touchesEdge = sprite?.touchesEdge ?: false,
            )
            if (!filled) {
                when {
                    slot.required -> issues += SheetIssue(
                        SheetIssueLevel.ERROR,
                        "${titleCase(slot.label)} is missing. Please place it inside the " +
                            "'${slot.label}' area of the template.",
                        slot.id,
                    )
                    slot.kind == SlotKind.BODY -> issues += SheetIssue(
                        SheetIssueLevel.INFO,
                        "${titleCase(slot.label)} is empty — the character will be built without it.",
                        slot.id,
                    )
                    // Individual face sprites are silently optional; the face summary below
                    // reports what the character can actually do.
                }
            } else if (sprite.touchesEdge && slot.kind == SlotKind.BODY) {
                issues += SheetIssue(
                    SheetIssueLevel.WARNING,
                    "${titleCase(slot.label)} reaches the edge of its area and may be cut off.",
                    slot.id,
                )
            }
        }

        // --- view level summaries -----------------------------------------------------------
        val sideLeftSlots = template.slotsFor(ViewKind.SIDE_LEFT)
        val sideRightSlots = template.slotsFor(ViewKind.SIDE_RIGHT)
        val sideLeftFilled = sideLeftSlots.count { scans[it.id]?.filled == true }
        val sideRightFilled = sideRightSlots.count { scans[it.id]?.filled == true }
        val sideLeftComplete = sideLeftFilled == sideLeftSlots.size
        val sideRightComplete = sideRightFilled == sideRightSlots.size

        for (view in listOf(ViewKind.SIDE_LEFT, ViewKind.SIDE_RIGHT, ViewKind.BACK)) {
            val slots = template.slotsFor(view)
            val filledCount = slots.count { scans[it.id]?.filled == true }
            if (filledCount == slots.size) continue // view is complete: nothing to report
            val missing = slots.filter { scans[it.id]?.filled != true }
                .joinToString(", ") { titleCase(it.label) }
            val incomplete = "${view.displayName} artwork is incomplete " +
                "($filledCount/${slots.size} areas filled): $missing."
            when {
                view == ViewKind.BACK && filledCount == 0 -> issues += SheetIssue(
                    SheetIssueLevel.INFO,
                    "Back View unavailable. Add back-view artwork to enable it.",
                )
                view == ViewKind.BACK -> issues += SheetIssue(SheetIssueLevel.WARNING, incomplete)

                // Neither profile was drawn: one message for both.
                sideLeftFilled == 0 && sideRightFilled == 0 && view == ViewKind.SIDE_LEFT ->
                    issues += SheetIssue(
                        SheetIssueLevel.INFO,
                        "Side View unavailable. Add side-view artwork to enable " +
                            "Side Walk, Side Run and Side Talk.",
                    )
                sideLeftFilled == 0 && sideRightFilled == 0 -> Unit

                // One complete profile: the other can be derived, or must be drawn.
                view == ViewKind.SIDE_RIGHT && sideLeftComplete && filledCount == 0 ->
                    issues += SheetIssue(
                        SheetIssueLevel.INFO,
                        "Side Right artwork is missing. Turn on 'Mirror Side View' to build the " +
                            "right-facing profile from your left-facing artwork.",
                    )
                view == ViewKind.SIDE_LEFT && sideRightComplete && filledCount == 0 ->
                    issues += SheetIssue(
                        SheetIssueLevel.INFO,
                        "Side Left artwork is missing, so only the right-facing profile can " +
                            "animate. Draw the left-facing profile to unlock both.",
                    )

                else -> issues += SheetIssue(SheetIssueLevel.WARNING, incomplete)
            }
        }

        if (!scans.values.any { it.slotId.startsWith("eye_") && it.filled }) {
            issues += SheetIssue(
                SheetIssueLevel.INFO,
                "No eye sprites found — facial expressions will stay neutral.",
            )
        }
        if (!scans.values.any { it.slotId.startsWith("mouth_") && it.filled }) {
            issues += SheetIssue(
                SheetIssueLevel.INFO,
                "No mouth sprites found — Talk will play without mouth movement.",
            )
        }

        return SheetValidationReport(
            meta = meta,
            scans = scans,
            issues = issues,
            inkCoverage = inkCoverage,
            inkOutsideSlots = inkOutsideSlots,
        )
    }

    private fun buildRowIntervals(
        sheetY: Int,
        slots: List<SheetSlot>,
        notes: List<IntRect>,
    ): List<IntArray> {
        val out = ArrayList<IntArray>(8)
        for (slot in slots) {
            val r = slot.rect
            if (sheetY in r.y until r.bottom) out += intArrayOf(r.x, r.right)
        }
        for (r in notes) {
            if (sheetY in r.y until r.bottom) out += intArrayOf(r.x, r.right)
        }
        return out
    }

    private fun insideAny(sheetX: Int, sheetY: Int, intervals: List<IntArray>): Boolean {
        for (interval in intervals) {
            if (sheetX in interval[0] until interval[1]) return true
        }
        return false
    }

    private fun titleCase(label: String): String =
        label.split(' ').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
}

/** Everything the import step produces: sprites plus the report that describes them. */
class SheetProcessResult(
    val sprites: Map<String, ExtractedSprite>,
    val report: SheetValidationReport,
) {
    val isRiggable: Boolean get() = report.isRiggable

    fun sprite(slotId: String): ExtractedSprite? = sprites[slotId]

    fun spritesFor(view: ViewKind): Map<String, ExtractedSprite> =
        CharacterSheetTemplate.slotsFor(view)
            .mapNotNull { slot -> sprites[slot.id]?.let { slot.id to it } }
            .toMap()
}

/**
 * Import pipeline entry point: validate the image, cut every slot, report what was found.
 *
 * Runs entirely on the calling dispatcher — the app module calls it from a background
 * coroutine. On a modern phone a 2048² sheet processes in well under a second because each slot
 * is read once and only the trimmed pixels are kept.
 */
class SheetProcessor(
    private val extractor: SpriteExtractor = SpriteExtractor(),
    private val validator: SheetValidator = SheetValidator(),
) {

    fun process(surface: PixelSurface, meta: SheetImageMeta): SheetProcessResult {
        val scale = if (surface.width > 0) {
            surface.width.toFloat() / CharacterSheetTemplate.SHEET_WIDTH
        } else {
            1f
        }
        val sprites = extractor.extractAll(surface, scale = scale)
        val report = validator.analyze(meta, surface, sprites)
        return SheetProcessResult(sprites, report)
    }
}
