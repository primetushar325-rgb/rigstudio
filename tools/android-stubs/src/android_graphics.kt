@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER", "ClassName")

package android.graphics

import java.io.OutputStream

class PorterDuff {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR }
}

open class ColorFilter

class ColorMatrix(val array: FloatArray = FloatArray(20)) {
    fun setScale(r: Float, g: Float, b: Float, a: Float) {}
}

class ColorMatrixColorFilter(matrix: ColorMatrix) : ColorFilter()

open class PathEffect

class DashPathEffect(intervals: FloatArray, phase: Float) : PathEffect()

class Typeface {
    companion object {
        val DEFAULT: Typeface = Typeface()
        val DEFAULT_BOLD: Typeface = Typeface()
        const val BOLD = 1
        const val ITALIC = 2
        const val NORMAL = 0
        fun create(family: Typeface?, style: Int): Typeface = Typeface()
        fun create(familyName: String?, style: Int): Typeface = Typeface()
    }
}

class Color {
    companion object {
        const val TRANSPARENT = 0
        const val BLACK = -0x1000000
        const val WHITE = -0x1
        const val RED = -0x10000
        const val GREEN = -0xff0100
        const val BLUE = -0xffff01
        const val GRAY = -0x777778
        const val LTGRAY = -0x333334
        const val DKGRAY = -0xbbbbbcc
        fun parseColor(colorString: String): Int = 0
        fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = 0
        fun rgb(red: Int, green: Int, blue: Int): Int = 0
        fun alpha(color: Int): Int = 0
        fun red(color: Int): Int = 0
        fun green(color: Int): Int = 0
        fun blue(color: Int): Int = 0
    }
}

class Matrix(val values: FloatArray = FloatArray(9)) {
    fun setValues(vals: FloatArray) {}
    fun getValues(vals: FloatArray) {}
    fun setConcat(a: Matrix, b: Matrix): Boolean = true
    fun reset() {}
    fun mapPoints(pts: FloatArray) {}
    fun postScale(sx: Float, sy: Float): Boolean = true
    fun postTranslate(dx: Float, dy: Float): Boolean = true
    fun setScale(sx: Float, sy: Float) {}
    fun setTranslate(dx: Float, dy: Float) {}
}

class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun set(l: Int, t: Int, r: Int, b: Int) {}
    fun set(src: Rect) {}
    fun isEmpty(): Boolean = true
}

class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f,
) {
    constructor(src: RectF) : this(src.left, src.top, src.right, src.bottom)
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
    fun set(l: Float, t: Float, r: Float, b: Float) {}
    fun set(src: RectF) {}
    fun isEmpty(): Boolean = true
}

class Path {
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun rewind() {}
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {}
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    fun addRoundRect(rect: RectF, rx: Float, ry: Float, dir: Direction = Direction.CW) {}
    fun close() {}
    fun reset() {}
    enum class Direction { CW, CCW }
}

class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }

    var style: Style = Style.FILL
    var color: Int = 0
    var alpha: Int = 255
    var strokeWidth: Float = 0f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var textAlign: Align = Align.LEFT
    var textSize: Float = 12f
    var typeface: Typeface? = null
    var colorFilter: ColorFilter? = null
    var pathEffect: PathEffect? = null
    var isAntiAlias: Boolean = false
    var isFilterBitmap: Boolean = false
    var isDither: Boolean = false
    var letterSpacing: Float = 0f

    fun measureText(text: String): Float = 0f
    fun measureText(text: String, start: Int, end: Int): Float = 0f
    fun breakText(text: String, start: Int, end: Int, measureForwards: Boolean, maxWidth: Float, measuredText: FloatArray?): Int = 0
    fun breakText(text: String, measureForwards: Boolean, maxWidth: Float, measuredText: FloatArray?): Int = 0
    fun getTextBounds(text: String, start: Int, end: Int, bounds: Rect) {}
    fun setShader(shader: Shader?): Shader? = shader
    fun reset() {}

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
        const val DITHER_FLAG = 4
        const val LINEAR_TEXT_FLAG = 64
        const val SUBPIXEL_TEXT_FLAG = 128
    }
}

open class Shader

class Bitmap {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    val width: Int get() = 0
    val height: Int get() = 0
    val byteCount: Int get() = 0
    val isRecycled: Boolean get() = false
    val config: Config? get() = Config.ARGB_8888
    val density: Int get() = 0

    fun hasAlpha(): Boolean = true
    fun recycle() {}
    fun eraseColor(color: Int) {}
    fun getPixel(x: Int, y: Int): Int = 0
    fun setPixel(x: Int, y: Int, color: Int) {}
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = true
    fun copy(config: Config, isMutable: Boolean): Bitmap? = null
    fun extractAlpha(): Bitmap = Bitmap()

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap = Bitmap()
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int, m: Matrix, filter: Boolean): Bitmap = Bitmap()
        fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createScaledBitmap(src: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap = src
    }
}

class Canvas(target: Bitmap? = null) {
    val width: Int get() = 0
    val height: Int get() = 0

    fun save(): Int = 0
    fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?): Int = 0
    fun restore() {}
    fun restoreToCount(saveCount: Int) {}
    fun translate(dx: Float, dy: Float) {}
    fun scale(sx: Float, sy: Float) {}
    fun rotate(degrees: Float) {}
    fun rotate(degrees: Float, px: Float, py: Float) {}
    fun concat(matrix: Matrix) {}
    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean = true
    fun clipRect(rect: RectF): Boolean = true
    fun clipPath(path: Path): Boolean = true
    fun drawColor(color: Int) {}
    fun drawColor(color: Int, mode: PorterDuff.Mode) {}
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {}
    fun drawRect(rect: RectF, paint: Paint) {}
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {}
    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {}
    fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {}
    fun drawOval(oval: RectF, paint: Paint) {}
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {}
    fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {}
    fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {}
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {}
    fun drawPath(path: Path, paint: Paint) {}
    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, src: Rect, dst: Rect, paint: Paint?) {}
    fun drawBitmap(pixels: IntArray, offset: Int, stride: Int, x: Float, y: Float, width: Int, height: Int, paint: Paint?) {}
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
    fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {}
    fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {}
    fun getMaximumBitmapWidth(): Int = 0
}

class BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var inPreferredConfig: Bitmap.Config? = null
        @JvmField var inMutable: Boolean = false
        @JvmField var inDensity: Int = 0
        @JvmField var outWidth: Int = -1
        @JvmField var outHeight: Int = -1
        @JvmField var outMimeType: String? = null
        @JvmField var outConfig: Bitmap.Config? = null
    }

    companion object {
        fun decodeFile(path: String): Bitmap? = null
        fun decodeFile(path: String, opts: Options?): Bitmap? = null
        fun decodeStream(stream: java.io.InputStream): Bitmap? = null
        fun decodeStream(stream: java.io.InputStream, outPadding: Rect?, opts: Options?): Bitmap? = null
        fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = null
        fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? = null
        fun decodeResource(res: Any, id: Int): Bitmap? = null
    }
}
