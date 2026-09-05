@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.content.res

class Resources {
    fun getString(id: Int): String = ""
    fun getDimension(id: Int): Float = 0f
    val displayMetrics: android.util.DisplayMetrics get() = android.util.DisplayMetrics()
}

class Configuration {
    @JvmField var orientation: Int = 1
    @JvmField var fontScale: Float = 1f
}
