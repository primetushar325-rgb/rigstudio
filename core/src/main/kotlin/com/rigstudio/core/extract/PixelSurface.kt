package com.rigstudio.core.extract

import com.rigstudio.core.geom.IntRect

/**
 * Read-only view onto RGBA pixels.
 *
 * The Android layer implements this over `android.graphics.Bitmap` (one `getPixels` call per
 * slot, never a full-sheet copy); unit tests implement it over an `IntArray`. Keeping the
 * extraction engine behind this interface is what makes the whole rigging pipeline testable
 * off-device.
 */
interface PixelSurface {
    val width: Int
    val height: Int

    /** True when the source can carry transparency at all. */
    val hasAlphaChannel: Boolean

    /**
     * Returns `rect.width * rect.height` packed ARGB pixels, row-major, starting at the rect's
     * top-left. Implementations must clamp [rect] to the surface bounds.
     */
    fun readRect(rect: IntRect): IntArray

    fun bounds(): IntRect = IntRect(0, 0, width, height)
}

/** In-memory [PixelSurface] used by tests and by off-device tooling. */
class ArrayPixelSurface(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
    override val hasAlphaChannel: Boolean = true,
) : PixelSurface {

    init {
        require(pixels.size == width * height) {
            "ArrayPixelSurface needs ${width * height} pixels but got ${pixels.size}"
        }
    }

    override fun readRect(rect: IntRect): IntArray {
        val clipped = rect.clampTo(bounds())
        if (clipped.isEmpty()) return IntArray(0)
        val out = IntArray(clipped.width * clipped.height)
        var index = 0
        for (y in clipped.y until clipped.bottom) {
            val rowStart = y * width + clipped.x
            System.arraycopy(pixels, rowStart, out, index, clipped.width)
            index += clipped.width
        }
        return out
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun setPixel(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    /** Fills a rectangle — handy for building synthetic character sheets in tests. */
    fun fillRect(rect: IntRect, argb: Int) {
        val clipped = rect.clampTo(bounds())
        for (y in clipped.y until clipped.bottom) {
            for (x in clipped.x until clipped.right) {
                pixels[y * width + x] = argb
            }
        }
    }

    companion object {
        fun transparent(width: Int, height: Int) =
            ArrayPixelSurface(width, height, IntArray(width * height))
    }
}
