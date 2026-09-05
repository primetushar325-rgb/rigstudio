package com.rigstudio.core.extract

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot
import com.rigstudio.core.util.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One body part / facial sprite lifted out of the character sheet.
 *
 * Holds its own pixels (already trimmed) plus everything needed to put it back into the rig:
 * the pivot in *sprite* space, the crop rect in sheet space and the original slot rect.
 */
class ExtractedSprite(
    val slotId: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Joint position inside this sprite, normalised 0..1. */
    val pivot: Vec2,
    /** The crop this sprite came from, in sheet pixels. */
    val sourceRect: IntRect,
    /** Tight bounding box of the artwork, in sheet pixels. */
    val contentRect: IntRect,
    /** Slot rectangle from the template, in sheet pixels. */
    val slotRect: IntRect,
    /** Fraction of the crop that is not fully transparent. */
    val coverage: Float,
    /** True when artwork touches the crop edge (it may be clipped by the slot boundary). */
    val touchesEdge: Boolean,
) {
    init {
        require(pixels.size == width * height) {
            "Sprite $slotId pixel buffer ${pixels.size} != ${width}x$height"
        }
    }

    val pivotX: Int get() = (pivot.x * width).roundToInt().coerceIn(0, max(0, width - 1))
    val pivotY: Int get() = (pivot.y * height).roundToInt().coerceIn(0, max(0, height - 1))

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun isBlank(): Boolean = coverage <= 0f

    override fun toString(): String =
        "ExtractedSprite($slotId, ${width}x$height, pivot=$pivot, coverage=$coverage)"
}

/**
 * Cuts every slot out of a character sheet using the fixed template coordinates.
 *
 * This is the whole "automatic rigging" story: no segmentation, no heuristics about what a
 * hand looks like, no network. Read the rectangle, find the artwork inside it, trim the
 * transparent margins, keep the joint inside the crop, done.
 *
 * Two details matter for a puppet that does not fall apart at the joints:
 *
 *  1. **Pivot padding.** Trimming is expanded so the slot's pivot pixel always stays inside the
 *     sprite (plus a couple of pixels of slack). A joint cropped away would make the limb
 *     rotate around a point it no longer contains.
 *  2. **Slot clamping.** Crops can never leave their own slot rectangle, so one slot can never
 *     read another slot's artwork.
 */
class SpriteExtractor(
    /** Pixels with alpha <= this are treated as empty. */
    private val alphaThreshold: Int = 8,
    /** Extra transparent pixels kept around the joint after trimming. */
    private val pivotPadding: Int = 4,
) {

    /**
     * Extracts [slot] from [surface].
     *
     * @param scale maps sheet coordinates to surface coordinates. It is 1.0 for a correct
     *   2048x2048 sheet; tests use a smaller surface with a matching scale so the same code
     *   path is exercised without allocating 16 MB per test.
     * @return `null` when the slot contains no artwork at all.
     */
    fun extract(surface: PixelSurface, slot: SheetSlot, scale: Float = 1f): ExtractedSprite? {
        val sheetRect = slot.rect
        val srcRect = IntRect(
            x = (sheetRect.x * scale).roundToInt(),
            y = (sheetRect.y * scale).roundToInt(),
            width = max(1, (sheetRect.width * scale).roundToInt()),
            height = max(1, (sheetRect.height * scale).roundToInt()),
        ).clampTo(surface.bounds())

        if (srcRect.isEmpty()) return null

        val pixels = surface.readRect(srcRect)
        if (pixels.isEmpty()) return null

        // --- 1. tight bounding box of non-transparent content -------------------------------
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var opaque = 0
        for (y in 0 until srcRect.height) {
            val row = y * srcRect.width
            for (x in 0 until srcRect.width) {
                if (ColorUtils.alpha(pixels[row + x]) > alphaThreshold) {
                    opaque++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (opaque == 0) return null

        // --- 2. keep the joint inside the crop ---------------------------------------------
        val pivotLocalX = ((sheetRect.x + slot.pivot.x * sheetRect.width) * scale).roundToInt() - srcRect.x
        val pivotLocalY = ((sheetRect.y + slot.pivot.y * sheetRect.height) * scale).roundToInt() - srcRect.y
        val pad = (pivotPadding * scale).roundToInt().coerceAtLeast(1)

        val cropLeft = min(minX, pivotLocalX - pad).coerceAtLeast(0)
        val cropTop = min(minY, pivotLocalY - pad).coerceAtLeast(0)
        val cropRight = max(maxX, pivotLocalX + pad).coerceAtMost(srcRect.width - 1)
        val cropBottom = max(maxY, pivotLocalY + pad).coerceAtMost(srcRect.height - 1)

        val cropW = cropRight - cropLeft + 1
        val cropH = cropBottom - cropTop + 1
        if (cropW <= 0 || cropH <= 0) return null

        // --- 3. copy out the crop -----------------------------------------------------------
        val out = IntArray(cropW * cropH)
        var index = 0
        for (y in cropTop..cropBottom) {
            System.arraycopy(pixels, y * srcRect.width + cropLeft, out, index, cropW)
            index += cropW
        }

        val touchesEdge = minX <= 0 || minY <= 0 ||
            maxX >= srcRect.width - 1 || maxY >= srcRect.height - 1

        val pivotX = (pivotLocalX - cropLeft).toFloat() / cropW.toFloat()
        val pivotY = (pivotLocalY - cropTop).toFloat() / cropH.toFloat()

        val toSheet = { localX: Int, localY: Int ->
            Pair(
                (srcRect.x + localX) / scale.toDouble(),
                (srcRect.y + localY) / scale.toDouble(),
            )
        }
        val (contentLeftSheet, contentTopSheet) = toSheet(minX, minY)
        val (contentRightSheet, contentBottomSheet) = toSheet(maxX + 1, maxY + 1)

        return ExtractedSprite(
            slotId = slot.id,
            width = cropW,
            height = cropH,
            pixels = out,
            pivot = Vec2(pivotX.coerceIn(0f, 1f), pivotY.coerceIn(0f, 1f)),
            sourceRect = IntRect(
                ((srcRect.x + cropLeft) / scale).roundToInt(),
                ((srcRect.y + cropTop) / scale).roundToInt(),
                max(1, (cropW / scale).roundToInt()),
                max(1, (cropH / scale).roundToInt()),
            ),
            contentRect = IntRect(
                contentLeftSheet.roundToInt(),
                contentTopSheet.roundToInt(),
                max(1, (contentRightSheet - contentLeftSheet).roundToInt()),
                max(1, (contentBottomSheet - contentTopSheet).roundToInt()),
            ),
            slotRect = sheetRect,
            coverage = opaque.toFloat() / (srcRect.width * srcRect.height).toFloat(),
            touchesEdge = touchesEdge,
        )
    }

    /** Extracts every body slot of the sheet, skipping empty ones. */
    fun extractAll(
        surface: PixelSurface,
        slots: List<SheetSlot> = CharacterSheetTemplate.SLOTS,
        scale: Float = 1f,
    ): Map<String, ExtractedSprite> {
        val out = LinkedHashMap<String, ExtractedSprite>(slots.size)
        for (slot in slots) {
            extract(surface, slot, scale)?.let { out[slot.id] = it }
        }
        return out
    }
}
