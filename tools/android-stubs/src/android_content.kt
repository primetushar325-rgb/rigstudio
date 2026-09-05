@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = null
    fun openOutputStream(uri: Uri): java.io.OutputStream? = null
    fun openOutputStream(uri: Uri, mode: String): java.io.OutputStream? = null
    fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? = null
    fun getType(uri: Uri): String? = null
    fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}

class ComponentName(val packageName: String, val className: String)

open class Context {
    open val filesDir: File get() = File(".")
    open val cacheDir: File get() = File(".")
    open val externalFilesDir: File? get() = null
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val applicationContext: Context get() = this
    open val packageName: String get() = ""
    open val resources: android.content.res.Resources get() = android.content.res.Resources()

    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any): String = ""
    open fun startActivity(intent: Intent) {}
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    open fun checkSelfPermission(permission: String): Int = 0

    companion object {
        const val MODE_PRIVATE = 0
    }
}

class SharedPreferences {
    fun getString(key: String, defValue: String?): String? = defValue
    fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    fun getInt(key: String, defValue: Int): Int = defValue
    fun getLong(key: String, defValue: Long): Long = defValue
    fun edit(): Editor = Editor()

    class Editor {
        fun putString(key: String, value: String?): Editor = this
        fun putBoolean(key: String, value: Boolean): Editor = this
        fun putInt(key: String, value: Int): Editor = this
        fun putLong(key: String, value: Long): Editor = this
        fun remove(key: String): Editor = this
        fun apply() {}
        fun commit(): Boolean = true
    }
}

class Intent(action: String? = null, uri: Uri? = null) {
    var data: Uri? = uri
    var type: String? = null
    var action: String? = action
    var flags: Int = 0
    var component: ComponentName? = null

    fun setDataAndType(uri: Uri?, type: String?): Intent = this
    fun setType(type: String): Intent = this
    fun putExtra(name: String, value: String): Intent = this
    fun putExtra(name: String, value: Boolean): Intent = this
    fun putExtra(name: String, value: Int): Intent = this
    fun putExtra(name: String, value: Long): Intent = this
    fun putExtra(name: String, value: android.os.Parcelable): Intent = this
    fun putExtra(name: String, value: Array<String>): Intent = this
    fun addFlags(flags: Int): Intent = this
    fun setFlags(flags: Int): Intent = this
    fun addCategory(category: String): Intent = this
    fun resolveActivity(pm: PackageManager): ComponentName? = null
    fun putParcelableExtra(name: String, value: Any?): Intent = this
    fun <T> getParcelableExtra(name: String): T? = null

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        fun createChooser(target: Intent, title: CharSequence?): Intent = Intent()
    }
}

class PackageManager {
    fun queryIntentActivities(intent: Intent, flags: Int): List<Any> = emptyList()
}

class ActivityNotFoundException(message: String? = null) : Exception(message)
