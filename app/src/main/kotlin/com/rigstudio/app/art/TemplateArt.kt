package com.rigstudio.app.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.rigstudio.app.render.downscaleTo
import com.rigstudio.core.template.BarInk
import com.rigstudio.core.template.InkRole
import com.rigstudio.core.template.TemplateInk
import com.rigstudio.core.template.TemplateLayout
import com.rigstudio.core.template.TemplateLayoutSolver
import com.rigstudio.core.template.TextInk
import com.rigstudio.core.template.TriangleInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * Draws the **original, bundled blank character sheet template** on Android.
 *
 * This class does no layout at all. Every outline, pivot tick, label, heading and instruction line
 * is computed by [TemplateLayoutSolver] in the core module as plain geometry, which the core test
 * suite then proves satisfies the one rule that protects extraction:
 *
 * > No guide ink may ever land inside a slot rectangle.
 *
 * Extraction decides "is this part drawn?" from alpha inside the fixed slot rectangles, so a
 * template that printed its own guides inside them would make every empty slot look filled and glue
 * guide pixels into the user's artwork. Keeping the layout in the core means the rule is verified by
 * tests instead of by careful painting here — and the dependency-free Python renderer
 * (`tools/render_template.py`) draws the very same primitives, so the documentation preview and the
 * sheet a user saves from the app cannot disagree.
 *
 * All this class adds is pixels: colours per [InkRole], anti-aliasing, and one safety net — text is
 * re-measured with the real typeface and shrunk to stay inside its solved box, because the solver
 * estimates glyph widths conservatively.
 */
class TemplateArt(private val context: Context) {

    /** The solved layout. Computed once; it is pure geometry and cheap to hold. */
    val layout: TemplateLayout = TemplateLayoutSolver.solve()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val scratchRect = RectF()
    private val scratchPath = Path()

    /**
     * The blank template at full sheet resolution, ready to save as a PNG.
     * Fully transparent except for the guides.
     */
    fun renderBlankSheet(): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (ink in layout.ink) {
            draw(canvas, ink)
        }
        return bitmap
    }

    /** A downscaled copy on a dark backdrop, for on-screen previews (never for saving). */
    fun renderPreview(maxSize: Int = 1024): Bitmap {
        val sheet = renderBlankSheet()
        val preview = sheet.downscaleTo(maxSize)
        if (preview !== sheet) sheet.recycle()

        val withBackdrop = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(withBackdrop)
        canvas.drawColor(PREVIEW_BACKDROP)
        canvas.drawBitmap(preview, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (preview !== withBackdrop) preview.recycle()
        return withBackdrop
    }

    /** Writes the blank template as a PNG to a user-chosen location. */
    suspend fun saveSheet(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        val sheet = renderBlankSheet()
        try {
            context.contentResolver.openFileDescriptor(uri, "wt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { out ->
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("That location could not be opened for writing."),
            )
            Result.success(sheet.width.toLong() * sheet.height)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            sheet.recycle()
        }
    }

    /**
     * Labels that found no legal spot. Always empty for the shipped template — the core tests
     * assert it — and exposed so a future layout change cannot regress silently.
     */
    fun unplaceableLabels(): List<String> = layout.unplacedLabels

    // --- rasterisation ------------------------------------------------------------------------

    private fun draw(canvas: Canvas, ink: TemplateInk) {
        when (ink) {
            is BarInk -> {
                barPaint.color = colorFor(ink.role)
                scratchRect.set(
                    ink.bounds.x.toFloat(),
                    ink.bounds.y.toFloat(),
                    ink.bounds.right.toFloat(),
                    ink.bounds.bottom.toFloat(),
                )
                canvas.drawRect(scratchRect, barPaint)
            }

            is TriangleInk -> {
                tickPaint.color = colorFor(ink.role)
                scratchPath.rewind()
                val points = ink.points
                scratchPath.moveTo(points[0].x, points[0].y)
                scratchPath.lineTo(points[1].x, points[1].y)
                scratchPath.lineTo(points[2].x, points[2].y)
                scratchPath.close()
                canvas.drawPath(scratchPath, tickPaint)
            }

            is TextInk -> drawText(canvas, ink)
        }
    }

    private fun drawText(canvas: Canvas, ink: TextInk) {
        textPaint.color = colorFor(ink.role)
        textPaint.textSize = ink.sizePx.toFloat()

        val budget = if (ink.vertical) ink.bounds.height.toFloat() else ink.bounds.width.toFloat()
        var measured = textPaint.measureText(ink.text)
        if (measured > budget && measured > 0f) {
            // Real typeface is wider than the solver's estimate: shrink so the glyphs stay inside
            // the box that was proved clear of every slot rectangle.
            textPaint.textSize = ink.sizePx * (budget / measured)
            measured = textPaint.measureText(ink.text)
        }

        if (ink.vertical) {
            // Read bottom-to-top: baseline runs up the column at anchorX.
            canvas.save()
            canvas.translate(ink.anchorX.toFloat(), ink.baselineY.toFloat())
            canvas.rotate(-90f)
            canvas.drawText(ink.text, 0f, 0f, textPaint)
            canvas.restore()
            return
        }

        val left = ink.bounds.x + (ink.bounds.width - measured) / 2f
        canvas.drawText(ink.text, left.coerceAtLeast(ink.bounds.x.toFloat()), ink.baselineY.toFloat(), textPaint)
    }

    private fun colorFor(role: InkRole): Int = when (role) {
        InkRole.FRAME -> FRAME_COLOR
        InkRole.GUIDE -> GUIDE_COLOR
        InkRole.REQUIRED -> REQUIRED_COLOR
        InkRole.PIVOT -> PIVOT_COLOR
        InkRole.LABEL -> LABEL_COLOR
        InkRole.LABEL_REQUIRED -> REQUIRED_LABEL_COLOR
        InkRole.GROUP -> GROUP_COLOR
        InkRole.TITLE -> TITLE_COLOR
        InkRole.INSTRUCTION -> INSTRUCTION_COLOR
    }

    companion object {
        /** Preview backdrop only — the saved sheet stays transparent. */
        private const val PREVIEW_BACKDROP = 0xFF0B0E14.toInt()

        // Values with the high bit set are written as Long literals and converted; `const` cannot
        // hold a conversion, so these are ordinary vals (read once per draw call, no allocation).
        private val FRAME_COLOR = 0x50788CAA
        private val GUIDE_COLOR = 0x6E7A8CAA
        private val REQUIRED_COLOR = 0xCD3FBFAE.toInt()
        private val PIVOT_COLOR = 0xA03FBFAE.toInt()
        private val LABEL_COLOR = 0xCD9AA6BC.toInt()
        private val REQUIRED_LABEL_COLOR = 0xE13FBFAE.toInt()
        private val GROUP_COLOR = 0xB4E7ECF5.toInt()
        private val TITLE_COLOR = 0x87E7ECF5.toInt()
        private val INSTRUCTION_COLOR = 0xD79AA6BC.toInt()
    }
}
