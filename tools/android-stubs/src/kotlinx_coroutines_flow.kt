@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package kotlinx.coroutines.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface Flow<out T> {
    suspend fun collect(collector: FlowCollector<T>)
}

fun interface FlowCollector<in T> {
    suspend fun emit(value: T)
}

interface StateFlow<out T> : Flow<T> {
    val value: T
}

interface MutableStateFlow<T> : StateFlow<T>, Flow<T> {
    override var value: T
    fun compareAndSet(expect: T, update: T): Boolean
}

internal class MutableStateFlowImpl<T>(initial: T) : MutableStateFlow<T> {
    override var value: T = initial
    override fun compareAndSet(expect: T, update: T): Boolean = true
    override suspend fun collect(collector: FlowCollector<T>) { collector.emit(value) }
}

fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = MutableStateFlowImpl(value)

fun <T> StateFlow<T>.asStateFlow(): StateFlow<T> = this
fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this
fun <T> Flow<T>.stateIn(scope: CoroutineScope): StateFlow<T?> = MutableStateFlowImpl<T?>(null)

fun <T> Flow<T>.onEach(action: suspend (T) -> Unit): Flow<T> = this
@Suppress("UNCHECKED_CAST")
fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = this as Flow<R>
@Suppress("UNCHECKED_CAST")
fun <A, B, R> combine(a: Flow<A>, b: Flow<B>, transform: suspend (A, B) -> R): Flow<R> =
    MutableStateFlowImpl<R>(null as R)
suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit) {}

fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    value = function(value)
}

