@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.net

class Uri private constructor(private val value: String) : android.os.Parcelable {
    val scheme: String? get() = value.substringBefore(":", "").ifEmpty { null }
    val path: String? get() = null
    val authority: String? get() = null
    val lastPathSegment: String? get() = null
    val isAbsolute: Boolean get() = true
    fun getQueryParameter(key: String): String? = null
    fun buildUpon(): Builder = Builder()
    override fun toString(): String = value

    class Builder {
        fun appendPath(newSegment: String): Builder = this
        fun appendQueryParameter(key: String, value: String): Builder = this
        fun build(): Uri = Uri("")
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
        @JvmStatic
        fun fromFile(file: java.io.File): Uri = Uri(file.absolutePath)
        @JvmStatic
        val EMPTY: Uri = Uri("")
    }
}
