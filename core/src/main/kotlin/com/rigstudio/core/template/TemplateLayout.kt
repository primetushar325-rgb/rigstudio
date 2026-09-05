package com.rigstudio.core.template

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.geom.Vec2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The **geometry** of the blank character sheet, solved without any drawing API.
 *
 * Why this exists: the template's guide ink (slot outlines, pivot ticks, labels, instructions) is
 * drawn on the same 2048×2048 canvas the user paints on, and the extractor reads slot rectangles
 * pixel-exactly. A single guide pixel inside a slot would become part of the character — silently,
 * for every user, forever. So the layout is computed here as plain rectangles and triangles, which
 * means it can be *proved* correct by the core test suite (no ink inside any slot rect) before any
 * platform ever rasterises it.
 *
 * Two renderers consume this: the app's Android canvas renderer (`app/.../art/TemplateArt.kt`) and
 * the dependency-free Python reference renderer (`tools/render_template.py`). Both draw exactly
 * these primitives, so the sheet a user saves from the app and the sheet in the documentation are
 * the same sheet.
 */

/** What a piece of ink means, which is all a renderer needs to pick a colour. */
enum class InkRole {
    /** Thin sheet border. */
    FRAME,

    /** Outline of an optional slot. */
    GUIDE,

    /** Outline of a required slot (drawn heavier and in the accent colour). */
    REQUIRED,

    /** Joint marker. */
    PIVOT,

    /** Slot name. */
    LABEL,

    /** Slot name of a required slot. */
    LABEL_REQUIRED,

    /** Region heading ("FRONT BODY", "FACE — EYES", …). */
    GROUP,

    /** The rotated spine title. */
    TITLE,

    /** How-to text inside a documented free area. */
    INSTRUCTION,
}

/** One drawing primitive, with the bounding box of every pixel it can touch. */
sealed interface TemplateInk {
    /**
     * Conservative ink bounds. The test suite asserts this never intersects a slot rectangle, so it
     * must include anti-aliasing fringe — renderers stroke *inside* these bounds.
     */
    val bounds: IntRect

    val role: InkRole
}

/** An axis-aligned bar (frame edges and the four sides of a slot ring). */
data class BarInk(
    override val bounds: IntRect,
    override val role: InkRole,
    val slotId: String? = null,
) : TemplateInk

/** A filled triangle (pivot ticks point at the joint from outside the slot). */
data class TriangleInk(
    override val bounds: IntRect,
    val points: List<Vec2>,
    override val role: InkRole,
    val slotId: String,
) : TemplateInk

/**
 * A run of text.
 *
 * [bounds] is the ink box the glyphs can occupy; [baselineY] is where a canvas puts the baseline
 * for horizontal text, and [anchorX] is the left edge (horizontal) or the column the text is
 * rotated around ([vertical] = true, read bottom-to-top).
 */
data class TextInk(
    override val bounds: IntRect,
    val text: String,
    val sizePx: Int,
    val anchorX: Int,
    val baselineY: Int,
    override val role: InkRole,
    val slotId: String? = null,
    val vertical: Boolean = false,
) : TemplateInk

/** A solved sheet: every piece of guide ink, plus anything that could not be placed. */
data class TemplateLayout(
    val width: Int,
    val height: Int,
    val ink: List<TemplateInk>,
    /** Slot ids whose label found no legal spot. Must be empty — the tests enforce it. */
    val unplacedLabels: List<String>,
) {
    fun inkOf(role: InkRole): List<TemplateInk> = ink.filter { it.role == role }

    val labels: List<TextInk> get() = ink.filterIsInstance<TextInk>().filter { it.slotId != null && it.role != InkRole.GROUP }
}

/**
 * Solves the layout for [CharacterSheetTemplate].
 *
 * Every placement rule has one hard constraint and one soft preference:
 *  - **hard**: no ink inside any slot rectangle (enforced by [fits] against all 60 slots);
 *  - **soft**: prefer the biggest legible text, prefer above the slot, prefer not to overlap other
 *    text.
 */
object TemplateLayoutSolver {

    /** Sheet border inset. */
    const val FRAME_INSET = 16

    /** Closest a guide stroke may come to the rectangle it describes. */
    const val GUIDE_CLEARANCE = 4

    const val GUIDE_STROKE = 3
    const val REQUIRED_STROKE = 5

    /** Length of a pivot tick, measured outward from the outline. */
    const val TICK_LENGTH = 10

    /** Label sizes tried in order; the first that fits the free band wins. */
    /** Sideways steps tried when a pivot tick blocks a centred label. Fixed, so layout is stable. */
    val NUDGE_OFFSETS: IntArray = intArrayOf(0, -16, 16, -32, 32, -48, 48, -64, 64)
    val LABEL_SIZES: IntArray = intArrayOf(26, 20, 16, 13, 11)

    /** Region heading sizes, tried the same way. */
    val GROUP_SIZES: IntArray = intArrayOf(30, 24, 20, 16)
    /** Small on purpose: the spine column is the 20 px strip between frame and pivot ticks. */
    const val TITLE_SIZE = 20
    const val INSTRUCTION_SIZE = 18

    /** Conservative text metrics: uppercase sans glyphs are ~0.66 em wide, ~0.9 em tall. */
    const val CHAR_WIDTH_FACTOR = 0.66f
    const val INK_HEIGHT_FACTOR = 0.9f

    /** Breathing room kept between text ink and the nearest outline or slot edge. */
    const val TEXT_MARGIN = 3

    private const val SHEET_WIDTH = CharacterSheetTemplate.SHEET_WIDTH
    private const val SHEET_HEIGHT = CharacterSheetTemplate.SHEET_HEIGHT

    fun solve(): TemplateLayout {
        val slots = CharacterSheetTemplate.SLOTS
        val rects = slots.map { it.rect }
        val ink = ArrayList<TemplateInk>(768)

        ink += frameBars()

        for (slot in slots) {
            ink += ringBars(slot)
        }
        // Every mark already on the sheet is an obstacle for text: pivot ticks first (a label
        // centred over one prints through the tick), then the slot outlines and the frame. The
        // outline set is tried first; in the densest gutters nothing clears the outlines, and a
        // readable label over a guide line beats no label, so placement retries with ticks only.
        val tickObstacles = ArrayList<IntRect>(slots.size * 2)
        for (slot in slots) {
            val ticks = pivotTicks(slot, rects)
            ink += ticks
            for (tick in ticks) tickObstacles += tick.bounds
        }
        val inkObstacles = ArrayList<IntRect>(tickObstacles.size + slots.size * 4 + 4)
        inkObstacles += tickObstacles
        for (slot in slots) {
            for (bar in ringBars(slot)) inkObstacles += bar.bounds
        }
        for (bar in frameBars()) inkObstacles += bar.bounds

        // Text is placed in priority order, each run avoiding everything already on the sheet:
        // the fixed spine title, then one label per slot (what users actually need), then region
        // headings, then the how-to copy in the documented free areas.
        val placedText = ArrayList<IntRect>(slots.size + 32)
        val unplaced = ArrayList<String>()

        for (title in spineTitle(rects, tickObstacles)) {
            ink += title
            placedText += title.bounds
        }

        for (slot in slots) {
            val label = placeLabel(slot, rects, placedText, inkObstacles, tickObstacles)
            if (label == null) {
                unplaced += slot.id
            } else {
                ink += label
                placedText += label.bounds
            }
        }

        for (group in groupLabels(rects, placedText, inkObstacles, tickObstacles)) {
            ink += group
            placedText += group.bounds
        }

        ink += instructions(rects, placedText, inkObstacles, tickObstacles)

        return TemplateLayout(SHEET_WIDTH, SHEET_HEIGHT, ink, unplaced)
    }

    // --- frame & outlines ---------------------------------------------------------------------

    private fun frameBars(): List<BarInk> {
        val left = FRAME_INSET
        val top = FRAME_INSET
        val right = SHEET_WIDTH - FRAME_INSET
        val bottom = SHEET_HEIGHT - FRAME_INSET
        val thickness = 2
        return listOf(
            BarInk(IntRect(left, top, right - left, thickness), InkRole.FRAME),
            BarInk(IntRect(left, bottom - thickness, right - left, thickness), InkRole.FRAME),
            BarInk(IntRect(left, top, thickness, bottom - top), InkRole.FRAME),
            BarInk(IntRect(right - thickness, top, thickness, bottom - top), InkRole.FRAME),
        )
    }

    /**
     * The four sides of a slot's outline, each pushed fully outside the rectangle: the stroke's
     * inner edge sits [GUIDE_CLEARANCE] away, so even its anti-aliased fringe cannot reach the
     * pixels the extractor reads.
     */
    private fun ringBars(slot: SheetSlot): List<BarInk> {
        val stroke = if (slot.required) REQUIRED_STROKE else GUIDE_STROKE
        val role = if (slot.required) InkRole.REQUIRED else InkRole.GUIDE
        val rect = slot.rect
        val innerLeft = rect.x - GUIDE_CLEARANCE
        val innerTop = rect.y - GUIDE_CLEARANCE
        val innerRight = rect.right + GUIDE_CLEARANCE
        val innerBottom = rect.bottom + GUIDE_CLEARANCE

        return listOf(
            // top bar: occupies [innerTop - stroke, innerTop)
            BarInk(IntRect(innerLeft - stroke, innerTop - stroke, (innerRight - innerLeft) + 2 * stroke, stroke), role, slot.id),
            // bottom bar: occupies [innerBottom, innerBottom + stroke)
            BarInk(IntRect(innerLeft - stroke, innerBottom, (innerRight - innerLeft) + 2 * stroke, stroke), role, slot.id),
            // left bar: between the two horizontals
            BarInk(IntRect(innerLeft - stroke, innerTop, stroke, innerBottom - innerTop), role, slot.id),
            // right bar
            BarInk(IntRect(innerRight, innerTop, stroke, innerBottom - innerTop), role, slot.id),
        )
    }

    /**
     * Pivot ticks. A tick is skipped when it would land inside a neighbouring rectangle — tight
     * grids (the side and back shelves) simply show fewer ticks, which is far better than showing a
     * tick that the extractor would later read as artwork.
     */
    private fun pivotTicks(slot: SheetSlot, rects: List<IntRect>): List<TriangleInk> {
        val stroke = if (slot.required) REQUIRED_STROKE else GUIDE_STROKE
        val distance = GUIDE_CLEARANCE + stroke * 2
        val rect = slot.rect
        val px = slot.pivotPixelX.toFloat()
        val py = slot.pivotPixelY.toFloat()
        val tick = TICK_LENGTH.toFloat()
        val half = tick * 0.5f
        val result = ArrayList<TriangleInk>(2)

        // Tick above the top edge, pointing down at the pivot column.
        val tipY = (rect.top - distance).toFloat()
        val topPoints = listOf(Vec2(px - half, tipY - tick), Vec2(px + half, tipY - tick), Vec2(px, tipY))
        val topBounds = boundsOf(topPoints)
        if (fits(topBounds, rects)) {
            result += TriangleInk(topBounds, topPoints, InkRole.PIVOT, slot.id)
        }

        // Tick left of the left edge, pointing right at the pivot row.
        val tipX = (rect.left - distance).toFloat()
        val leftPoints = listOf(Vec2(tipX - tick, py - half), Vec2(tipX - tick, py + half), Vec2(tipX, py))
        val leftBounds = boundsOf(leftPoints)
        if (fits(leftBounds, rects)) {
            result += TriangleInk(leftBounds, leftPoints, InkRole.PIVOT, slot.id)
        }

        return result
    }

    // --- text ---------------------------------------------------------------------------------

    /**
     * Places one slot label.
     *
     * Sizes are tried largest-first, and for each size the comfortable margin (clear of the slot's
     * own outline) is preferred over the tight margin (clear of the rectangle, which is all the
     * hard invariant demands). Only the densest regions — the back-view shelves, where neighbouring
     * rectangles leave a 16 px band — fall back to small text at a tight margin.
     */
    private fun placeLabel(
        slot: SheetSlot,
        rects: List<IntRect>,
        placedText: List<IntRect>,
        obstacles: List<IntRect>,
        fallbackObstacles: List<IntRect>,
    ): TextInk? {
        val stroke = if (slot.required) REQUIRED_STROKE else GUIDE_STROKE
        return placeText(
            text = slot.label.uppercase(),
            sizes = LABEL_SIZES,
            rect = slot.rect,
            rects = rects,
            placedText = placedText,
            obstacles = obstacles,
            fallbackObstacles = fallbackObstacles,
            role = if (slot.required) InkRole.LABEL_REQUIRED else InkRole.LABEL,
            slotId = slot.id,
            comfortableMargin = GUIDE_CLEARANCE + stroke + TEXT_MARGIN,
        )
    }

    private fun groupLabels(
        rects: List<IntRect>,
        placedText: MutableList<IntRect>,
        obstacles: List<IntRect>,
        fallbackObstacles: List<IntRect>,
    ): List<TextInk> {
        val result = ArrayList<TextInk>()
        val groups = CharacterSheetTemplate.SLOTS.groupBy { it.group }
        for ((name, slots) in groups) {
            val bounds = slots.map { it.rect }.reduce { acc, rect -> acc.union(rect) }
            val heading = placeText(
                text = name.uppercase(),
                sizes = GROUP_SIZES,
                rect = bounds,
                rects = rects,
                placedText = placedText,
                obstacles = obstacles,
                fallbackObstacles = fallbackObstacles,
                role = InkRole.GROUP,
                comfortableMargin = GUIDE_CLEARANCE + REQUIRED_STROKE + TEXT_MARGIN,
            ) ?: continue
            result += heading
            placedText += heading.bounds
        }
        return result
    }

    /**
     * The shared placement rule for every text run on the sheet.
     *
     * Candidate order per size: above the rectangle, below it, then rotated in the gap to its left.
     * Every candidate must clear all 60 slot rectangles ([fits]) and every text run already placed,
     * so the result is deterministic and independent of any rendering backend.
     */
    private fun placeText(
        text: String,
        sizes: IntArray,
        rect: IntRect,
        rects: List<IntRect>,
        placedText: List<IntRect>,
        obstacles: List<IntRect>,
        fallbackObstacles: List<IntRect>,
        role: InkRole,
        slotId: String? = null,
        comfortableMargin: Int,
    ): TextInk? =
        placeTextPass(text, sizes, rect, rects, placedText, obstacles, role, slotId, comfortableMargin)
            ?: placeTextPass(text, sizes, rect, rects, placedText, fallbackObstacles, role, slotId, comfortableMargin)

    private fun placeTextPass(
        text: String,
        sizes: IntArray,
        rect: IntRect,
        rects: List<IntRect>,
        placedText: List<IntRect>,
        obstacles: List<IntRect>,
        role: InkRole,
        slotId: String? = null,
        comfortableMargin: Int,
    ): TextInk? {
        val above = freeSpaceAbove(rect, rects)
        val below = freeSpaceBelow(rect, rects)
        val left = freeSpaceLeft(rect, rects)

        for (margin in intArrayOf(comfortableMargin, TEXT_MARGIN)) {
            for (size in sizes) {
                val inkHeight = textInkHeight(size)
                val width = textWidth(text, size)
                val needed = inkHeight + 2 * margin
                val centerX = centerXOf(rect)

                if (above >= needed) {
                    val candidate = nudgedText(centerX, obstacles, rects, placedText) { x ->
                        centeredText(text, size, x, role, bandBottom = rect.y - margin, slotId = slotId)
                    }
                    if (candidate != null) return candidate
                }
                if (below >= needed) {
                    val candidate = nudgedText(centerX, obstacles, rects, placedText) { x ->
                        centeredText(text, size, x, role, bandTop = rect.bottom + margin, slotId = slotId)
                    }
                    if (candidate != null) return candidate
                }
                // Rotated, read bottom-to-top, in the vertical gap to the left of the rectangle.
                if (left >= inkHeight + 2 * margin && rect.height >= width + 2 * margin) {
                    val candidate = verticalText(
                        text = text,
                        size = size,
                        columnRight = rect.x - margin,
                        centerY = centerYOf(rect),
                        role = role,
                        slotId = slotId,
                    )
                    if (acceptable(candidate, rects, placedText, obstacles)) return candidate
                }
            }
        }
        return null
    }

    /**
     * Centred first; if a pivot tick sits in the label's path, slid sideways by the smallest step
     * that clears it. The ladder is fixed so the layout stays deterministic.
     */
    private inline fun nudgedText(
        centerX: Int,
        obstacles: List<IntRect>,
        rects: List<IntRect>,
        placedText: List<IntRect>,
        build: (Int) -> TextInk,
    ): TextInk? {
        for (offset in NUDGE_OFFSETS) {
            val candidate = build(centerX + offset)
            if (acceptable(candidate, rects, placedText, obstacles)) return candidate
        }
        return null
    }

    private fun acceptable(
        candidate: TextInk,
        rects: List<IntRect>,
        placedText: List<IntRect>,
        obstacles: List<IntRect>,
    ): Boolean =
        fits(candidate.bounds, rects) &&
            !overlapsText(candidate.bounds, placedText) &&
            !overlapsText(candidate.bounds, obstacles)

    /** Rotated sheet title running up the left margin, clear of the frame and the pivot ticks. */
    private fun spineTitle(rects: List<IntRect>, obstacles: List<IntRect>): List<TextInk> {
        val text = "RIGSTUDIO \u00b7 CHARACTER SHEET \u00b7 $SHEET_WIDTH x $SHEET_HEIGHT"
        val size = TITLE_SIZE
        val length = textWidth(text, size)
        val thickness = textInkHeight(size)
        val columnRight = FRAME_INSET + 5 + thickness
        val bottom = SHEET_HEIGHT - FRAME_INSET - 40
        val top = bottom - length
        if (top < FRAME_INSET + 8) return emptyList()
        val bounds = IntRect(columnRight - thickness, top, thickness, length)
        if (!fits(bounds, rects) || overlapsText(bounds, obstacles)) return emptyList()
        return listOf(
            TextInk(
                bounds = bounds,
                text = text,
                sizePx = size,
                anchorX = columnRight,
                baselineY = bottom,
                role = InkRole.TITLE,
                vertical = true,
            ),
        )
    }

    /** How-to text, wrapped into the template's documented free areas. */
    private fun instructions(
        rects: List<IntRect>,
        placedText: MutableList<IntRect>,
        obstacles: List<IntRect>,
        fallbackObstacles: List<IntRect>,
    ): List<TextInk> {
        val result = ArrayList<TextInk>()
        val size = INSTRUCTION_SIZE
        val lineHeight = textInkHeight(size) + 8
        for ((areaIndex, area) in CharacterSheetTemplate.NOTES_AREAS.withIndex()) {
            val block = if (areaIndex == 0) INSTRUCTIONS else TIPS
            val maxChars = ((area.width - 16) / (size * CHAR_WIDTH_FACTOR)).toInt()
            var y = area.y + 10
            for (paragraph in block) {
                for (line in wrap(paragraph, maxChars)) {
                    if (y + textInkHeight(size) > area.bottom - 6) break
                    val candidate = leftText(
                        text = line,
                        size = size,
                        left = area.x + 8,
                        bandTop = y,
                        role = InkRole.INSTRUCTION,
                    )
                    if (acceptable(candidate, rects, placedText, obstacles) ||
                        acceptable(candidate, rects, placedText, fallbackObstacles)
                    ) {
                        result += candidate
                        placedText += candidate.bounds
                    }
                    y += lineHeight
                }
            }
        }
        return result
    }

    // --- geometry helpers ---------------------------------------------------------------------

    /** Horizontal text centred on [centerX], with its ink bottom at [bandBottom]. */
    private fun centeredText(
        text: String,
        size: Int,
        centerX: Int,
        role: InkRole,
        bandBottom: Int = Int.MIN_VALUE,
        bandTop: Int = Int.MIN_VALUE,
        slotId: String? = null,
    ): TextInk {
        val resolvedBottom = if (bandBottom != Int.MIN_VALUE) bandBottom else bandTop + textInkHeight(size)
        val width = textWidth(text, size)
        val height = textInkHeight(size)
        val left = centerX - width / 2
        val top = resolvedBottom - height
        return TextInk(
            bounds = IntRect(left, top, width, height),
            text = text,
            sizePx = size,
            anchorX = left,
            baselineY = resolvedBottom,
            role = role,
            slotId = slotId,
        )
    }

    /** Horizontal text left-aligned at [left], with its ink top at [bandTop]. */
    private fun leftText(
        text: String,
        size: Int,
        left: Int,
        bandTop: Int,
        role: InkRole,
        slotId: String? = null,
    ): TextInk {
        val width = textWidth(text, size)
        val height = textInkHeight(size)
        return TextInk(
            bounds = IntRect(left, bandTop, width, height),
            text = text,
            sizePx = size,
            anchorX = left,
            baselineY = bandTop + height,
            role = role,
            slotId = slotId,
        )
    }

    /** Text rotated 90° counter-clockwise, read bottom-to-top, its right edge at [columnRight]. */
    private fun verticalText(
        text: String,
        size: Int,
        columnRight: Int,
        centerY: Int,
        role: InkRole,
        slotId: String? = null,
    ): TextInk {
        val length = textWidth(text, size)
        val thickness = textInkHeight(size)
        val left = columnRight - thickness
        val top = centerY - length / 2
        return TextInk(
            bounds = IntRect(left, top, thickness, length),
            text = text,
            sizePx = size,
            anchorX = columnRight,
            baselineY = top + length,
            role = role,
            slotId = slotId,
            vertical = true,
        )
    }

    private fun centerXOf(rect: IntRect): Int = (rect.x + rect.right) / 2

    private fun centerYOf(rect: IntRect): Int = (rect.y + rect.bottom) / 2

    fun textWidth(text: String, size: Int): Int = (text.length * size * CHAR_WIDTH_FACTOR).roundToInt()

    fun textInkHeight(size: Int): Int = (size * INK_HEIGHT_FACTOR).roundToInt()

    private fun boundsOf(points: List<Vec2>): IntRect {
        val minX = points.minOf { it.x }.roundToInt()
        val minY = points.minOf { it.y }.roundToInt()
        val maxX = points.maxOf { it.x }.roundToInt()
        val maxY = points.maxOf { it.y }.roundToInt()
        return IntRect(minX, minY, max(1, maxX - minX), max(1, maxY - minY))
    }

    /** The hard constraint: ink may not touch a slot rectangle, and must stay on the sheet. */
    fun fits(bounds: IntRect, rects: List<IntRect>): Boolean {
        if (bounds.x < 2 || bounds.y < 2) return false
        if (bounds.right > SHEET_WIDTH - 2 || bounds.bottom > SHEET_HEIGHT - 2) return false
        for (rect in rects) {
            if (bounds.intersects(rect)) return false
        }
        return true
    }

    private fun overlapsText(bounds: IntRect, placed: List<IntRect>): Boolean =
        placed.any { it.intersects(bounds) }

    /** Vertical gap above [rect] up to the nearest rectangle that horizontally overlaps it. */
    fun freeSpaceAbove(rect: IntRect, rects: List<IntRect>): Int {
        var blocking = 0
        for (other in rects) {
            if (other == rect) continue
            if (other.right <= rect.x || other.left >= rect.right) continue
            if (other.bottom <= rect.y) blocking = max(blocking, other.bottom)
        }
        return rect.y - blocking
    }

    /** Vertical gap below [rect] down to the nearest rectangle that horizontally overlaps it. */
    fun freeSpaceBelow(rect: IntRect, rects: List<IntRect>): Int {
        var blocking = SHEET_HEIGHT
        for (other in rects) {
            if (other == rect) continue
            if (other.right <= rect.x || other.left >= rect.right) continue
            if (other.y >= rect.bottom) blocking = min(blocking, other.y)
        }
        return blocking - rect.bottom
    }

    /** Horizontal gap to the left of [rect] up to the nearest rectangle that vertically overlaps it. */
    fun freeSpaceLeft(rect: IntRect, rects: List<IntRect>): Int {
        var blocking = 0
        for (other in rects) {
            if (other == rect) continue
            if (other.bottom <= rect.y || other.y >= rect.bottom) continue
            if (other.right <= rect.x) blocking = max(blocking, other.right)
        }
        return rect.x - blocking
    }

    private fun wrap(text: String, maxChars: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (maxChars <= 4 || text.length <= maxChars) return listOf(text)
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= maxChars) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
                while (current.length > maxChars) {
                    lines += current.substring(0, maxChars)
                    current = StringBuilder(current.substring(maxChars))
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** Instruction copy for the first free area (narrow: 176 px). */
    val INSTRUCTIONS: List<String> = listOf(
        "HOW TO USE",
        "Draw one part per box.",
        "Teal boxes are REQUIRED:",
        "head, torso, upper arms,",
        "forearms, thighs, shins.",
        "Everything else is optional.",
        "",
        "Keep ink inside its own box:",
        "the extractor reads those",
        "exact coordinates.",
        "",
        "Side views are separate",
        "drawings (8 parts each).",
        "Left only? RigStudio can",
        "mirror it for the right.",
        "",
        "Back parts are optional.",
        "Empty means the back view",
        "is disabled - never faked.",
        "",
        "Save as 2048 x 2048 PNG",
        "with alpha, then import.",
    )

    /** Copy for the second free area. */
    val TIPS: List<String> = listOf(
        "TIP",
        "Export as PNG with alpha,",
        "exactly 2048 x 2048, then",
        "import it. Validation,",
        "extraction, rigging and",
        "saving all happen offline",
        "on this device.",
    )
}
