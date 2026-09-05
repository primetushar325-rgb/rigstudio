package com.rigstudio.core.support

import com.rigstudio.core.extract.ArrayPixelSurface
import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.model.SlotKind
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot
import com.rigstudio.core.util.ColorUtils
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Builds synthetic character sheets in memory.
 *
 * Tests need a *realistic* sheet — artwork inside the right rectangles, transparent everywhere
 * else, joints where the template says they are — without shipping copyrighted artwork or a
 * 16 MB PNG. Each slot is filled with a distinct colour and a limb-like shape, which exercises
 * extraction, trimming, pivots, rig building, posing and framing exactly as a real import would.
 */
object SyntheticSheet {

    /** A different hue per slot, so tests can prove no slot ever reads another slot's pixels. */
    fun colorFor(slotId: String): Int {
        val hash = slotId.hashCode()
        val r = 60 + abs(hash % 180)
        val g = 60 + abs((hash / 7) % 180)
        val b = 60 + abs((hash / 13) % 180)
        return ColorUtils.argb(255, r, g, b)
    }

    /**
     * @param size sheet edge in pixels; the template is 2048² so a scale factor maps it down.
     * @param include which slots to paint (defaults to everything).
     * @param insetFraction how much smaller than its slot each part is drawn (exercises trimming).
     */
    fun build(
        size: Int = 512,
        include: (SheetSlot) -> Boolean = { true },
        insetFraction: Float = 0.14f,
        opaqueBackground: Boolean = false,
        paintOutsideSlots: Boolean = false,
    ): ArrayPixelSurface {
        val surface = ArrayPixelSurface(size, size, IntArray(size * size))
        val scale = size.toFloat() / CharacterSheetTemplate.SHEET_WIDTH

        if (opaqueBackground) {
            surface.fillRect(IntRect(0, 0, size, size), ColorUtils.rgb(255, 0, 255))
        }

        for (slot in CharacterSheetTemplate.SLOTS) {
            if (!include(slot)) continue
            val rect = scaled(slot.rect, scale, size)
            val inset = (minOf(rect.width, rect.height) * insetFraction).toInt().coerceAtLeast(1)
            val art = IntRect(
                rect.x + inset,
                rect.y + inset,
                (rect.width - inset * 2).coerceAtLeast(1),
                (rect.height - inset * 2).coerceAtLeast(1),
            )
            paintShape(surface, art, colorFor(slot.id), slot.kind)
        }

        if (paintOutsideSlots) {
            // Stray artwork in the free bottom-left band (sheet rows 1900..2020): it belongs to
            // no slot, so it must be reported and then ignored by the extractor.
            val stray = scaled(IntRect(96, 1900, 560, 120), scale, size)
            paintShape(surface, stray, ColorUtils.rgb(255, 255, 255), SlotKind.BODY)
        }
        return surface
    }

    fun scaleFor(size: Int): Float = size.toFloat() / CharacterSheetTemplate.SHEET_WIDTH

    private fun scaled(rect: IntRect, scale: Float, size: Int): IntRect {
        val x = (rect.x * scale).toInt().coerceIn(0, size - 1)
        val y = (rect.y * scale).toInt().coerceIn(0, size - 1)
        val w = (rect.width * scale).toInt().coerceAtLeast(1).coerceAtMost(size - x)
        val h = (rect.height * scale).toInt().coerceAtLeast(1).coerceAtMost(size - y)
        return IntRect(x, y, w, h)
    }

    /**
     * Paints a soft-edged blob with a guaranteed solid core.
     *
     * The solid core matters: the extractor expands the trimmed crop to contain the slot's pivot,
     * so a shape that is solid around its joint behaves like real artwork that overlaps the joint.
     */
    private fun paintShape(surface: ArrayPixelSurface, rect: IntRect, color: Int, kind: SlotKind) {
        val cx = rect.x + rect.width / 2f
        val cy = rect.y + rect.height / 2f
        val rx = rect.width / 2f
        val ry = rect.height / 2f
        val exponent = if (kind == SlotKind.BODY) 3.2f else 2.0f
        val solid = if (kind == SlotKind.BODY) 0.62f else 0.55f
        val outer = 0.95f

        for (y in rect.y until rect.bottom) {
            for (x in rect.x until rect.right) {
                val nx = abs((x + 0.5f - cx) / rx)
                val ny = abs((y + 0.5f - cy) / ry)
                // Super-ellipse distance: squarish for body parts, round for face sprites.
                val distance = (nx.toDouble().pow(exponent.toDouble()) +
                    ny.toDouble().pow(exponent.toDouble())).toFloat().pow(1f / exponent)
                if (distance > outer) continue
                val alpha = if (distance <= solid) {
                    255
                } else {
                    (255f * (outer - distance) / (outer - solid)).toInt().coerceIn(1, 255)
                }
                surface.setPixel(
                    x, y,
                    ColorUtils.argb(alpha, ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color)),
                )
            }
        }
    }

    /** A sheet with only the mandatory front body parts (no hands, feet, face, side or back). */
    fun minimalFront(size: Int = 512): ArrayPixelSurface =
        build(size, include = { slot -> slot.required })

    /** A sheet with the front body plus eyes and mouths. */
    fun frontWithFace(size: Int = 512): ArrayPixelSurface =
        build(size, include = { slot -> slot.required || slot.isFace })

    /** A complete sheet: front, face, both profiles and the back view. */
    fun complete(size: Int = 512): ArrayPixelSurface = build(size)
}
