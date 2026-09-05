package com.rigstudio.core.harness

/**
 * A dependency-free test harness.
 *
 * RigStudio's core module is plain Kotlin/JVM, so its tests are written once against this tiny
 * API and run two ways:
 *  - on a device-less machine straight from `main` in `com.rigstudio.core.tests.CoreTestSuitesKt`;
 *  - under `./gradlew :core:test` through the JUnit wrapper, which calls the very same suites.
 *
 * Keeping the assertions in one place means there is no second, drifting copy of the test logic.
 */
class TestAssertionFailure(message: String) : AssertionError(message)

data class TestCase(val name: String, val body: () -> Unit)

data class SuiteFailure(val testName: String, val message: String)

data class SuiteResult(
    val suiteName: String,
    val passed: Int,
    val failures: List<SuiteFailure>,
    val durationMillis: Long,
) {
    val total: Int get() = passed + failures.size
    val isSuccess: Boolean get() = failures.isEmpty()
}

object Assert {

    fun that(condition: Boolean, message: () -> String = { "expected condition to hold" }) {
        if (!condition) throw TestAssertionFailure(message())
    }

    fun <T> equals(expected: T, actual: T, message: String = "") {
        if (expected != actual) {
            throw TestAssertionFailure(
                "${if (message.isBlank()) "" else "$message: "}expected <$expected> but was <$actual>",
            )
        }
    }

    fun notEquals(unexpected: Any?, actual: Any?, message: String = "") {
        if (unexpected == actual) {
            throw TestAssertionFailure(
                "${if (message.isBlank()) "" else "$message: "}expected value to differ from <$unexpected>",
            )
        }
    }

    fun close(expected: Float, actual: Float, tolerance: Float = 1e-3f, message: String = "") {
        val delta = kotlin.math.abs(expected - actual)
        if (delta > tolerance || actual.isNaN()) {
            throw TestAssertionFailure(
                "${if (message.isBlank()) "" else "$message: "}expected <$expected> ±$tolerance but was <$actual>",
            )
        }
    }

    fun inRange(value: Float, min: Float, max: Float, message: String = "") {
        if (value < min || value > max || value.isNaN()) {
            throw TestAssertionFailure(
                "${if (message.isBlank()) "" else "$message: "}expected <$value> within [$min, $max]",
            )
        }
    }

    fun <T> contains(collection: Collection<T>, element: T, message: String = "") {
        if (element !in collection) {
            throw TestAssertionFailure(
                "${if (message.isBlank()) "" else "$message: "}expected collection to contain <$element>",
            )
        }
    }

    fun fails(message: String = "expected an exception"): Nothing =
        throw TestAssertionFailure(message)

    inline fun <reified E : Throwable> throws(noinline body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            if (t is E) return
            throw TestAssertionFailure("expected ${E::class.simpleName} but got ${t::class.simpleName}: ${t.message}")
        }
        throw TestAssertionFailure("expected ${E::class.simpleName} but nothing was thrown")
    }
}

/** Runs one suite and collects failures instead of aborting on the first one. */
fun runSuite(suiteName: String, cases: List<TestCase>): SuiteResult {
    val started = System.nanoTime()
    var passed = 0
    val failures = mutableListOf<SuiteFailure>()
    for (case in cases) {
        try {
            case.body()
            passed++
        } catch (failure: TestAssertionFailure) {
            failures += SuiteFailure(case.name, failure.message ?: "assertion failed")
        } catch (unexpected: Throwable) {
            failures += SuiteFailure(
                case.name,
                "unexpected ${unexpected::class.simpleName}: ${unexpected.message}",
            )
        }
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    return SuiteResult(suiteName, passed, failures, millis)
}
