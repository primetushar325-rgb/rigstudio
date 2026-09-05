package com.rigstudio.core.util

/**
 * ARGB_8888 pixel helpers.
 *
 * RigStudio keeps pixels as packed `Int` (the same layout `android.graphics.Bitmap` uses), so
 * the extraction code here runs unchanged on the device and in plain JVM unit tests.
 */
object ColorUtils {

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(0xFF, r, g, b)

    /**
     * Multiplies the colour channels by [shade] (0..1) leaving alpha untouched.
     * Used for the subtle depth tint on far limbs in a profile view.
     */
    fun shade(argb: Int, shade: Float): Int {
        if (shade >= 0.999f) return argb
        val s = shade.coerceIn(0f, 1f)
        return argb(
            alpha(argb),
            (red(argb) * s).toInt(),
            (green(argb) * s).toInt(),
            (blue(argb) * s).toInt(),
        )
    }

    /** Euclidean RGB distance, used by the optional chroma-key style cleanup. */
    fun distanceRgb(a: Int, b: Int): Float {
        val dr = (red(a) - red(b)).toFloat()
        val dg = (green(a) - green(b)).toFloat()
        val db = (blue(a) - blue(b)).toFloat()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }
}
