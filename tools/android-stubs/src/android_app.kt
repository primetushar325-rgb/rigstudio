@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.app

import android.content.Context

open class Application : Context() {
    open fun onCreate() {}
    open fun onTerminate() {}
}

open class Activity : Context() {
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    open fun finish() {}
}
