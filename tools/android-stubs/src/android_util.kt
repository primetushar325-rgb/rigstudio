@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.util

open class LruCache<K : Any, V : Any>(private val maximumSize: Int) {
    fun get(key: K): V? = null
    fun put(key: K, value: V): V? = null
    fun remove(key: K): V? = null
    fun size(): Int = 0
    fun maxSize(): Int = maximumSize
    fun evictAll() {}
    fun snapshot(): Map<K, V> = emptyMap()
    protected open fun sizeOf(key: K, value: V): Int = 1
}

interface AttributeSet {
    val attributeCount: Int get() = 0
    fun getAttributeName(index: Int): String? = null
    fun getAttributeValue(name: String): String? = null
}

class DisplayMetrics {
    @JvmField var density: Float = 1f
    @JvmField var widthPixels: Int = 0
    @JvmField var heightPixels: Int = 0
}

class Log {
    companion object {
        @JvmStatic fun d(tag: String, msg: String): Int = 0
        @JvmStatic fun i(tag: String, msg: String): Int = 0
        @JvmStatic fun w(tag: String, msg: String): Int = 0
        @JvmStatic fun e(tag: String, msg: String): Int = 0
        @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
    }
}

class TypedValue {
    companion object {
        @JvmStatic
        fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = value
        const val COMPLEX_UNIT_DIP = 1
    }
}
