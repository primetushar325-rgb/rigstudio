@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File

class FileProvider : android.content.ContentProvider() {
    companion object {
        @JvmStatic
        fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.parse("")
    }
}
