package com.rigstudio.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.rigstudio.core.render.PuppetDraw

/**
 * What sits behind the character.
 *
 * Transparency is only offered where it can be honoured: the on-screen preview shows a
 * checkerboard, PNG frame exports keep real alpha, and MP4 export refuses it (H.264 in an MP4 has
 * no alpha channel) — see `ExportSettings.validate`.
 */
sealed interface StageBackground {
    /** Nothing behind the character: alpha is preserved (PNG export) or shown as a checker. */
    data object Transparent : StageBackground

    /** A flat colour, composited under the character. */
    data class Solid(val argb: Int) : StageBackground

    /** A picture chosen from the gallery, scaled to cover the frame. */
    data class Image(val bitmap: Bitmap) : StageBackground

    companion object {
        val DEFAULT: StageBackground = Solid(DEFAULT_BACKGROUND_ARGB)

        /** The near-black stage colour used by the editor and by MP4 exports. */
        const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF11151F.toInt()

        /** Small palette offered in the editor's colour row. */
        val PRESETS: List<Int> = listOf(
            0xFF11151F.toInt(),
            0xFF1E2532.toInt(),
            0xFF3A2C55.toInt(),
            0xFF10312B.toInt(),
            0xFF4A2028.toInt(),
            0xFFEDE7DA.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
        )
    }
}

/**
 * Blits a composed draw list onto an Android [Canvas].
 *
 * This is the *only* place RigStudio turns geometry into pixels, and it is deliberately dumb:
 * the engine decided what to draw, where and in which order, so the painter just maps affines to
 * matrices and copies bitmaps. The on-screen preview and the 1080p encoder call the exact same
 * function, which is why the exported MP4 matches what the user saw.
 */
class PuppetPainter {

    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkerPaint = Paint()
    private val scratchMatrix = Matrix()
    private val scratchRectF = RectF()
    private val scratchSrcRect = Rect()
    private val shadeFilters = HashMap<Int, ColorMatrixColorFilter>()

    /**
     * @param bitmaps resolves a slot id to its artwork, or null when that part has no bitmap.
     * @param drawChecker when true, transparent areas show a subtle checkerboard (editor only —
     *   never used for export, where transparency must stay transparent).
     */
    fun paint(
        canvas: Canvas,
        width: Int,
        height: Int,
        draws: List<PuppetDraw>,
        bitmaps: (String) -> Bitmap?,
        background: StageBackground,
        drawChecker: Boolean = false,
    ) {
        if (drawChecker && background is StageBackground.Transparent) {
            drawCheckerboard(canvas, width, height)
        } else {
            when (background) {
                is StageBackground.Solid -> {
                    canvas.drawColor(background.argb)
                }
                is StageBackground.Image -> {
                    canvas.drawColor(Color.BLACK)
                    drawCover(canvas, background.bitmap, width, height)
                }
                StageBackground.Transparent -> canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        }

        for (draw in draws) {
            val bitmap = bitmaps(draw.slotId) ?: continue
            draw.world.toMatrix(scratchMatrix)
            canvas.save()
            canvas.concat(scratchMatrix)
            scratchRectF.set(
                draw.restRect.left,
                draw.restRect.top,
                draw.restRect.right,
                draw.restRect.bottom,
            )
            spritePaint.colorFilter = shadeFilterFor(draw.shade)
            // Source rect in sprite pixels: the whole trimmed sprite fills its rest rectangle.
            scratchSrcRect.set(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, scratchSrcRect, scratchRectF, spritePaint)
            canvas.restore()
        }
        spritePaint.colorFilter = null
    }

    /** Convenience for painting into a bitmap of the given size (thumbnails, export frames). */
    fun paintInto(
        target: Bitmap,
        draws: List<PuppetDraw>,
        bitmaps: (String) -> Bitmap?,
        background: StageBackground,
    ) {
        val canvas = Canvas(target)
        paint(canvas, target.width, target.height, draws, bitmaps, background, drawChecker = false)
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        scratchRectF.set((width - w) * 0.5f, (height - h) * 0.5f, (width + w) * 0.5f, (height + h) * 0.5f)
        canvas.drawBitmap(bitmap, null, scratchRectF, backgroundPaint)
    }

    /**
     * Depth shading multiplies RGB and leaves alpha alone, so a far limb darkens without
     * becoming see-through. Filters are cached: there are only ever two shades in a profile.
     */
    private fun shadeFilterFor(shade: Float): ColorMatrixColorFilter? {
        if (shade >= 0.999f) return null
        val key = (shade * 1000f).toInt()
        shadeFilters[key]?.let { return it }
        val matrix = ColorMatrix(
            floatArrayOf(
                shade, 0f, 0f, 0f, 0f,
                0f, shade, 0f, 0f, 0f,
                0f, 0f, shade, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        return ColorMatrixColorFilter(matrix).also { shadeFilters[key] = it }
    }

    private fun drawCheckerboard(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(CHECKER_LIGHT)
        val cell = CHECKER_CELL_PX
        checkerPaint.color = CHECKER_DARK
        var y = 0
        var row = 0
        while (y < height) {
            var x = if (row % 2 == 0) 0 else cell
            while (x < width) {
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    minOf(x + cell, width).toFloat(),
                    minOf(y + cell, height).toFloat(),
                    checkerPaint,
                )
                x += cell * 2
            }
            y += cell
            row++
        }
    }

    private companion object {
        const val CHECKER_CELL_PX = 24
        val CHECKER_LIGHT = 0xFF232A38.toInt()
        val CHECKER_DARK = 0xFF1A202C.toInt()
    }
}
