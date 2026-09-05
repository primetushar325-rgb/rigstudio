package com.rigstudio.app.export

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/**
 * ARGB → YUV 4:2:0 conversion for the H.264 encoder.
 *
 * RigStudio feeds [android.media.MediaCodec] buffers rather than an input surface on purpose:
 * buffer input carries an explicit `presentationTimeUs` per frame, so the exported file has exact,
 * jitter-free timing (a surface would timestamp frames with the wall clock). The cost is this
 * conversion, which is straight-line integer maths — about 20 ms for a 1080p frame.
 *
 * Coefficients are the classic BT.601 limited-range integers used across Android, so colours match
 * what the display shows and every decoder understands the result.
 */
object YuvConverter {

    /** Reusable per-frame pixel buffer, so exporting never allocates in the frame loop. */
    class FrameBuffer(val width: Int, val height: Int) {
        val pixels: IntArray = IntArray(width * height)

        fun readFrom(bitmap: Bitmap) {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Writes [pixels] into [image] honouring every plane's row and pixel stride, which is what
     * makes this work for both planar (I420) and semi-planar (NV12) encoders.
     */
    fun argbToYuv420(pixels: IntArray, width: Int, height: Int, image: Image) {
        val planes = image.planes
        require(planes.size >= 3) { "Encoder returned ${planes.size} planes, expected 3" }
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var pixelIndex = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1)
            val writeUv = (row and 1) == 0
            for (col in 0 until width) {
                val argb = pixels[pixelIndex++]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(yRow + col, clampByte(y))

                if (writeUv && (col and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvCol = col shr 1
                    uBuffer.put(uvRow * uRowStride + uvCol * uPixelStride, clampByte(u))
                    vBuffer.put(uvRow * vRowStride + uvCol * vPixelStride, clampByte(v))
                }
            }
        }
    }

    /** Bytes handed to `queueInputBuffer`: the unpadded 4:2:0 sample size. */
    fun yuvSize(width: Int, height: Int): Int = width * height * 3 / 2

    private fun clampByte(value: Int): Byte = value.coerceIn(0, 255).toByte()
}
