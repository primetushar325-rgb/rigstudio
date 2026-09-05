package com.rigstudio.core.tests

import com.rigstudio.core.harness.SuiteResult
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.harness.runSuite
import kotlin.system.exitProcess

/**
 * Registry of every core test suite.
 *
 * [main] runs them directly (no JUnit on the classpath needed, which is how CI and sandboxed
 * machines verify the engine), and `core/src/test/.../CoreLibraryTest.kt` runs the exact same
 * registry under `./gradlew :core:test`. One copy of the test logic, two ways to execute it.
 */
object CoreTestSuites {

    val suites: List<Pair<String, List<TestCase>>> = listOf(
        "Character sheet template" to TemplateTests.cases,
        "Template layout (guide ink)" to TemplateLayoutTests.cases,
        "Sheet extraction & validation" to ExtractionTests.cases,
        "Rig, kinematics & framing" to RigTests.cases,
        "Draw-list composition" to RenderTests.cases,
        "Animation library" to AnimationTests.cases,
        "Playback clock" to PlaybackTests.cases,
        "Export settings & file validation" to ExportTests.cases,
        "Project persistence" to ProjectTests.cases,
    )

    val totalTests: Int get() = suites.sumOf { it.second.size }

    fun runAll(verbose: Boolean = true): List<SuiteResult> = suites.map { (name, cases) ->
        val result = runSuite(name, cases)
        if (verbose) print(result)
        result
    }

    private fun print(result: SuiteResult) {
        val status = if (result.isSuccess) "PASS" else "FAIL"
        println("[$status] ${result.suiteName}: ${result.passed}/${result.total} (${result.durationMillis} ms)")
        for (failure in result.failures) {
            println("        ✗ ${failure.testName}")
            println("          ${failure.message}")
        }
    }
}

fun main() {
    println("RigStudio core engine — ${CoreTestSuites.totalTests} tests")
    println("----------------------------------------------------------------")
    val started = System.nanoTime()
    val results = CoreTestSuites.runAll()
    val millis = (System.nanoTime() - started) / 1_000_000

    val passed = results.sumOf { it.passed }
    val failed = results.sumOf { it.failures.size }
    println("----------------------------------------------------------------")
    println("$passed passed, $failed failed in ${millis} ms")
    if (failed > 0) {
        println("FAILED SUITES: ${results.filterNot { it.isSuccess }.joinToString { it.suiteName }}")
        exitProcess(1)
    }
    println("ALL CORE TESTS PASSED")
}
