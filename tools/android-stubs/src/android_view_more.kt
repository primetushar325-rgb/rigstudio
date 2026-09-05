@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet

open class View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) {
    val context: Context get() = context
    open val width: Int get() = 0
    open val height: Int get() = 0
    open var isClickable: Boolean = false
    open var visibility: Int = 0
    open var alpha: Float = 1f

    open fun invalidate() {}
    open fun requestLayout() {}
    open fun setWillNotDraw(willNotDraw: Boolean) {}
    open fun setLayerType(layerType: Int, paint: android.graphics.Paint?) {}
    open fun postInvalidateOnAnimation() {}
    open fun removeCallbacks(action: Runnable): Boolean = true

    protected open fun onDraw(canvas: Canvas) {}
    protected open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    protected open fun onAttachedToWindow() {}
    protected open fun onDetachedFromWindow() {}
    protected open fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val LAYER_TYPE_NONE = 0
        const val LAYER_TYPE_SOFTWARE = 1
        const val LAYER_TYPE_HARDWARE = 2
    }
}

open class ViewGroup(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    open fun addView(child: View) {}
    open fun removeAllViews() {}
}

class Choreographer private constructor() {
    interface FrameCallback {
        fun doFrame(frameTimeNanos: Long)
    }

    fun postFrameCallback(callback: FrameCallback) {}
    fun postFrameCallbackDelayed(callback: FrameCallback, delayMillis: Long) {}
    fun removeFrameCallback(callback: FrameCallback) {}

    companion object {
        @JvmStatic
        fun getInstance(): Choreographer = Choreographer()
    }
}
