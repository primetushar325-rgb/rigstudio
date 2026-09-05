package com.rigstudio.app.render

import android.graphics.Bitmap
import android.graphics.Matrix
import com.rigstudio.core.extract.ExtractedSprite
import com.rigstudio.core.extract.PixelSurface
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.IntRect

/**
 * The whole bridge between the pure engine and `android.graphics`.
 *
 * The core module never imports Android, so every conversion lives here: a 2x3 affine becomes a
 * [Matrix], a [Bitmap] becomes a [PixelSurface] the extractor can read, and an extracted sprite
 * becomes a bitmap the painter and the PNG writer can use.
 */

/** Copies this transform into an Android [Matrix] (allocated once per draw call is avoided by
 *  passing a reusable target). */
fun Affine.toMatrix(target: Matrix = Matrix()): Matrix = target.apply {
    setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))
}

/** Allocating convenience for one-off use (thumbnails, template rendering). */
fun Affine.toNewMatrix(): Matrix = Matrix().also { toMatrix(it) }

/**
 * Read-only [PixelSurface] over an Android bitmap.
 *
 * Extraction reads each slot exactly once and only the slot's own pixels, so importing a
 * 2048² sheet never copies the whole image — peak extra memory is the largest slot.
 */
class BitmapPixelSurface(
    private val bitmap: Bitmap,
    override val hasAlphaChannel: Boolean = bitmap.hasAlpha(),
) : PixelSurface {

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun readRect(rect: IntRect): IntArray {
        val clipped = rect.clampTo(bounds())
        if (clipped.isEmpty()) return IntArray(0)
        val out = IntArray(clipped.width * clipped.height)
        bitmap.getPixels(out, 0, clipped.width, clipped.x, clipped.y, clipped.width, clipped.height)
        return out
    }
}

/** Wraps a sprite's trimmed pixels as an immutable ARGB_8888 bitmap. */
fun ExtractedSprite.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(
        maxOf(1, width),
        maxOf(1, height),
        Bitmap.Config.ARGB_8888,
    )
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

/**
 * Scales a bitmap down so its longest side is at most [maxSize], without ever scaling up.
 * Used for thumbnails and for the on-screen template preview.
 */
fun Bitmap.downscaleTo(maxSize: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxSize || longest <= 0) return this
    val scale = maxSize.toFloat() / longest
    val w = maxOf(1, Math.round(width * scale))
    val h = maxOf(1, Math.round(height * scale))
    return Bitmap.createScaledBitmap(this, w, h, true)
}
