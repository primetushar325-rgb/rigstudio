@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.view

import android.content.Context
import android.graphics.Canvas

class Surface {
    fun isValid(): Boolean = true
    fun release() {}
    fun lockCanvas(dirty: android.graphics.Rect?): Canvas = Canvas()
    fun unlockCanvasAndPost(canvas: Canvas) {}
}

class LayoutInflater {
    companion object {
        @JvmStatic
        fun from(context: Context): LayoutInflater = LayoutInflater()
    }
}

class WindowManager {
    class LayoutParams {
        @JvmField var flags: Int = 0
    }
}
