@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class CancellationException(message: String? = null, cause: Throwable? = null) :
    IllegalStateException(message, cause)

abstract class CoroutineDispatcher : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    companion object Key : CoroutineContext.Key<CoroutineDispatcher>
}

internal class StubDispatcher : CoroutineDispatcher()

object Dispatchers {
    val Default: CoroutineDispatcher = StubDispatcher()
    val IO: CoroutineDispatcher = StubDispatcher()
    val Main: CoroutineDispatcher = StubDispatcher()
    val Unconfined: CoroutineDispatcher = StubDispatcher()
}

interface CoroutineScope {
    val coroutineContext: CoroutineContext
}

interface Job : CoroutineContext.Element {
    val isActive: Boolean
    val isCancelled: Boolean
    val isCompleted: Boolean
    fun cancel()
    fun start(): Boolean

    companion object Key : CoroutineContext.Key<Job>
}

class CompletableJob : Job {
    override val key: CoroutineContext.Key<*> get() = Job.Key
    override val isActive: Boolean get() = true
    override val isCancelled: Boolean get() = false
    override val isCompleted: Boolean get() = false
    override fun cancel() {}
    override fun start(): Boolean = true
}

fun Job(): Job = CompletableJob()
fun SupervisorJob(): Job = CompletableJob()

suspend fun <T> withContext(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T =
    throw UnsupportedOperationException("stub")

fun CoroutineScope.launch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = CompletableJob()

enum class CoroutineStart { DEFAULT, LAZY, ATOMIC, UNDISPATCHED }

fun CoroutineContext.ensureActive() {}
fun Job.ensureActive() {}
fun CoroutineScope.ensureActive() {}

suspend fun yield() {}
suspend fun delay(timeMillis: Long) {}

internal class CoroutineScopeImpl(override val coroutineContext: CoroutineContext) : CoroutineScope

fun CoroutineScope(context: CoroutineContext): CoroutineScope = CoroutineScopeImpl(context)

class MainScope : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + CompletableJob()
}
