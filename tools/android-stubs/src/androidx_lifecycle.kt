@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

open class ViewModel {
    open fun onCleared() {}
}

private val viewModelScopes = HashMap<ViewModel, CoroutineScope>()

val ViewModel.viewModelScope: CoroutineScope
    get() = viewModelScopes.getOrPut(this) { CoroutineScopeImpl(SupervisorJob() + Dispatchers.Main) }

internal class CoroutineScopeImpl(override val coroutineContext: kotlin.coroutines.CoroutineContext) : CoroutineScope

abstract class ViewModelProvider {
    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T
    }
}

open class AbstractSavedStateViewModelFactory

class SavedStateHandle {
    operator fun <T> get(key: String): T? = null
    operator fun <T> set(key: String, value: T?) {}
}
