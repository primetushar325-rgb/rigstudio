@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri

open class ContentProvider {
    open fun onCreate(): Boolean = true
    open fun getType(uri: Uri): String? = null
}
