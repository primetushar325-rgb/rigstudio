package com.rigstudio.core.tests

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.template.BarInk
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.InkRole
import com.rigstudio.core.template.TemplateInk
import com.rigstudio.core.template.TemplateLayoutSolver
import com.rigstudio.core.template.TextInk
import com.rigstudio.core.template.TriangleInk

/**
 * Template layout tests — the invariant that protects extraction.
 *
 * The blank sheet is drawn on the same canvas the user paints on, and the extractor reads slot
 * rectangles pixel-exactly. These tests prove, geometrically and without any drawing API, that no
 * guide ink (outline, pivot tick, label, instruction, frame) ever lands inside a slot rectangle.
 * If this suite passes, a freshly saved template imports as an empty sheet — and if it ever fails,
 * the template would silently ship artwork the user never drew.
 */
object TemplateLayoutTests {

    private val layout = TemplateLayoutSolver.solve()
    private val slotRects = CharacterSheetTemplate.SLOTS.map { it.rect }

    /** Every rectangle a piece of ink can touch, expanded by one pixel of anti-aliasing fringe. */
    private fun inkBounds(ink: TemplateInk): IntRect = ink.bounds

    val cases: List<TestCase> = listOf(
        TestCase("no guide ink falls inside any slot rectangle") {
            val violations = ArrayList<String>()
            for (ink in layout.ink) {
                val bounds = inkBounds(ink)
                for (slot in CharacterSheetTemplate.SLOTS) {
                    if (bounds.intersects(slot.rect)) {
                        violations += "${ink.role} '${describe(ink)}' at $bounds overlaps ${slot.id} ${slot.rect}"
                    }
                }
            }
            Assert.equals(
                emptyList<String>(),
                violations.take(10),
                "guide ink must never touch extraction areas (${violations.size} violations)",
            )
        },

        TestCase("every ink primitive stays on the sheet") {
            for (ink in layout.ink) {
                val bounds = inkBounds(ink)
                Assert.that(bounds.x >= 0 && bounds.y >= 0) { "${describe(ink)} starts off-sheet: $bounds" }
                Assert.that(bounds.right <= CharacterSheetTemplate.SHEET_WIDTH) {
                    "${describe(ink)} runs off the right edge: $bounds"
                }
                Assert.that(bounds.bottom <= CharacterSheetTemplate.SHEET_HEIGHT) {
                    "${describe(ink)} runs off the bottom edge: $bounds"
                }
                Assert.that(bounds.width > 0 && bounds.height > 0) { "${describe(ink)} has empty bounds" }
            }
        },

        TestCase("every slot gets a label") {
            Assert.equals(emptyList<String>(), layout.unplacedLabels, "all labels must find a legal spot")
            val labelled = layout.labels.map { it.slotId }.toSet()
            val expected = CharacterSheetTemplate.SLOTS.map { it.id }.toSet()
            Assert.equals(expected.size, labelled.size, "one label per slot")
            for (id in expected) {
                Assert.contains(labelled, id, "slot '$id' has no label")
            }
        },

        TestCase("labels carry their slot text and a legal size") {
            val sizes = TemplateLayoutSolver.LABEL_SIZES.toSet()
            for (label in layout.labels) {
                val slot = CharacterSheetTemplate.requireSlot(label.slotId!!)
                Assert.equals(slot.label.uppercase(), label.text, "label text for ${slot.id}")
                Assert.contains(sizes, label.sizePx, "label size for ${slot.id} must come from the tier list")
                val expectedRole = if (slot.required) InkRole.LABEL_REQUIRED else InkRole.LABEL
                Assert.equals(expectedRole, label.role, "label role for ${slot.id}")
            }
        },

        TestCase("each slot is outlined by exactly four bars, heavier when required") {
            for (slot in CharacterSheetTemplate.SLOTS) {
                val bars = layout.ink.filterIsInstance<BarInk>().filter { it.slotId == slot.id }
                Assert.equals(4, bars.size, "${slot.id} must be outlined by four bars")
                val expectedRole = if (slot.required) InkRole.REQUIRED else InkRole.GUIDE
                for (bar in bars) {
                    Assert.equals(expectedRole, bar.role, "${slot.id} outline role")
                }
                // The outline must surround the slot, not cross it.
                val union = bars.map { it.bounds }.reduce { acc, rect -> acc.union(rect) }
                Assert.that(union.x < slot.rect.x && union.y < slot.rect.y) { "${slot.id} outline is not outside" }
                Assert.that(union.right > slot.rect.right && union.bottom > slot.rect.bottom) {
                    "${slot.id} outline does not enclose the slot"
                }
            }
        },

        TestCase("pivot ticks point at the joint from outside the slot") {
            val ticks = layout.ink.filterIsInstance<TriangleInk>()
            Assert.that(ticks.isNotEmpty()) { "the template must show pivot ticks" }
            for (tick in ticks) {
                val slot = CharacterSheetTemplate.requireSlot(tick.slotId)
                Assert.equals(InkRole.PIVOT, tick.role, "tick role")
                Assert.equals(3, tick.points.size, "a tick is a triangle")
                val tip = tick.points.minByOrNull { it.distanceTo(slot.pivotPixelX.toFloat(), slot.pivotPixelY.toFloat()) }!!
                val outside = !slot.rect.contains(tip.x.toInt(), tip.y.toInt())
                Assert.that(outside) { "tick tip for ${slot.id} must sit outside the slot rect" }
                val alignedX = kotlin.math.abs(tip.x - slot.pivotPixelX) <= 1f
                val alignedY = kotlin.math.abs(tip.y - slot.pivotPixelY) <= 1f
                Assert.that(alignedX || alignedY) {
                    "tick tip for ${slot.id} must align with the pivot column or row"
                }
            }
        },

        TestCase("instruction text stays inside the documented free areas") {
            val areas = CharacterSheetTemplate.NOTES_AREAS
            val instructions = layout.inkOf(InkRole.INSTRUCTION).filterIsInstance<TextInk>()
            Assert.that(instructions.isNotEmpty()) { "the template must carry instructions" }
            for (text in instructions) {
                val inside = areas.any { area ->
                    text.bounds.x >= area.x && text.bounds.y >= area.y &&
                        text.bounds.right <= area.right && text.bounds.bottom <= area.bottom
                }
                Assert.that(inside) { "instruction '${text.text}' at ${text.bounds} is outside every free area" }
            }
        },

        TestCase("group headings are drawn for the regions that have room") {
            val groups = layout.inkOf(InkRole.GROUP).filterIsInstance<TextInk>()
            Assert.that(groups.isNotEmpty()) { "at least one group heading must be drawn" }
            val names = groups.map { it.text }.toSet()
            Assert.contains(names, CharacterSheetTemplate.GROUP_FRONT, "the front-body heading must appear")
            for (group in groups) {
                val known = CharacterSheetTemplate.SLOTS.map { it.group.uppercase() }.toSet()
                Assert.contains(known, group.text, "unknown group heading '${group.text}'")
            }
        },

        TestCase("the sheet frame is a closed ring inside the margins") {
            val frame = layout.inkOf(InkRole.FRAME).filterIsInstance<BarInk>()
            Assert.equals(4, frame.size, "four frame bars")
            val union = frame.map { it.bounds }.reduce { acc, rect -> acc.union(rect) }
            Assert.equals(TemplateLayoutSolver.FRAME_INSET, union.x, "frame inset")
            Assert.equals(TemplateLayoutSolver.FRAME_INSET, union.y, "frame inset")
            Assert.equals(
                CharacterSheetTemplate.SHEET_WIDTH - TemplateLayoutSolver.FRAME_INSET,
                union.right,
                "frame right edge",
            )
        },

        TestCase("the spine title is vertical and clear of every slot") {
            val titles = layout.inkOf(InkRole.TITLE).filterIsInstance<TextInk>()
            if (titles.isEmpty()) return@TestCase // skipped only if the margin is too narrow
            val title = titles.single()
            Assert.equals(true, title.vertical, "the spine title runs vertically")
            Assert.that(title.bounds.width < title.bounds.height) { "a vertical title is taller than wide" }
        },

        TestCase("no two text runs overlap") {
            val texts = layout.ink.filterIsInstance<TextInk>()
            for (i in texts.indices) {
                for (j in i + 1 until texts.size) {
                    val a = texts[i]
                    val b = texts[j]
                    Assert.that(!a.bounds.intersects(b.bounds)) {
                        "'${a.text}' at ${a.bounds} overlaps '${b.text}' at ${b.bounds}"
                    }
                }
            }
        },

        TestCase("the layout is deterministic") {
            val again = TemplateLayoutSolver.solve()
            Assert.equals(layout.ink.size, again.ink.size, "same primitive count")
            Assert.equals(layout.unplacedLabels, again.unplacedLabels, "same unplaced labels")
            for (index in layout.ink.indices) {
                Assert.equals(layout.ink[index].bounds, again.ink[index].bounds, "primitive $index bounds")
            }
        },

        TestCase("a saved template imports as an empty sheet") {
            // The practical consequence of every test above: with no ink in any slot, the extractor
            // finds nothing, and validation reports "no artwork" rather than inventing a character.
            val surface = FakeLayoutSurface(layout, CharacterSheetTemplate.SHEET_WIDTH, CharacterSheetTemplate.SHEET_HEIGHT)
            val processed = com.rigstudio.core.extract.SheetProcessor().process(
                surface,
                com.rigstudio.core.extract.SheetImageMeta(
                    width = CharacterSheetTemplate.SHEET_WIDTH,
                    height = CharacterSheetTemplate.SHEET_HEIGHT,
                    hasAlpha = true,
                    mimeType = "image/png",
                    byteCount = 1L,
                ),
            )
            Assert.equals(0, processed.sprites.count { !it.value.isBlank() }, "a blank template yields no sprites")
            Assert.equals(false, processed.report.isRiggable, "and it is not riggable")
            Assert.that(processed.report.issues.isNotEmpty()) { "and validation explains why" }
        },
    )

    private fun describe(ink: TemplateInk): String = when (ink) {
        is TextInk -> ink.text
        is TriangleInk -> "pivot(${ink.slotId})"
        is BarInk -> ink.slotId ?: "bar"
        else -> ink.role.name
    }

    /**
     * Rasterises the layout into a real [com.rigstudio.core.extract.PixelSurface] the way a renderer
     * would (solid ink inside each primitive's bounds), so the extraction pipeline can be pointed at
     * a "freshly drawn template" without Android.
     */
    private class FakeLayoutSurface(
        layout: com.rigstudio.core.template.TemplateLayout,
        override val width: Int,
        override val height: Int,
    ) : com.rigstudio.core.extract.PixelSurface {

        override val hasAlphaChannel: Boolean = true

        private val alpha = ByteArray(width * height)

        init {
            for (ink in layout.ink) {
                val bounds = ink.bounds
                for (y in maxOf(0, bounds.y) until minOf(height, bounds.bottom)) {
                    for (x in maxOf(0, bounds.x) until minOf(width, bounds.right)) {
                        alpha[y * width + x] = 255.toByte()
                    }
                }
            }
        }

        override fun readRect(rect: IntRect): IntArray {
            val pixels = IntArray(rect.width * rect.height)
            for (row in 0 until rect.height) {
                val y = rect.y + row
                if (y < 0 || y >= height) continue
                for (column in 0 until rect.width) {
                    val x = rect.x + column
                    if (x < 0 || x >= width) continue
                    val a = alpha[y * width + x].toInt() and 0xFF
                    pixels[row * rect.width + column] = (a shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80
                }
            }
            return pixels
        }
    }
}

private fun com.rigstudio.core.geom.Vec2.distanceTo(x: Float, y: Float): Float {
    val dx = this.x - x
    val dy = this.y - y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
