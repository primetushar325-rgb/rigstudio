@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.os

interface Parcelable

class Bundle {
    fun putString(key: String, value: String?) {}
    fun getString(key: String): String? = null
    fun containsKey(key: String): Boolean = false
}

class ParcelFileDescriptor : java.io.Closeable {
    val fileDescriptor: java.io.FileDescriptor get() = java.io.FileDescriptor()
    override fun close() {}
    companion object {
        const val MODE_READ_ONLY = 0x10000000
        const val MODE_WRITE_ONLY = 0x20000000
        const val MODE_TRUNCATE = 0x40000000
    }
}

class Build {
    class VERSION {
        companion object {
            @JvmField var SDK_INT: Int = 34
        }
    }

    class VERSION_CODES {
        companion object {
            const val N = 24
            const val O = 26
            const val P = 28
            const val Q = 29
            const val R = 30
            const val S = 31
            const val TIRAMISU = 33
        }
    }
}

class Handler(looper: Looper) {
    fun post(r: Runnable): Boolean = true
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacksAndMessages(token: Any?) {}
}

class Looper {
    companion object {
        @JvmStatic
        fun getMainLooper(): Looper = Looper()
    }
}

class StatFs(path: String) {
    fun availableBytes(): Long = 0L
    fun totalBytes(): Long = 0L
}
