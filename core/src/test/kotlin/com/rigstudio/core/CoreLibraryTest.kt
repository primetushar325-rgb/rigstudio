package com.rigstudio.core

import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.harness.runSuite
import com.rigstudio.core.tests.CoreTestSuites
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * JUnit view of the shared test registry, so `./gradlew :core:test` reports every engine test
 * individually in the HTML report.
 *
 * The assertions themselves live in `com.rigstudio.core.tests.*` against a dependency-free
 * harness; [com.rigstudio.core.tests.CoreTestSuites] runs the exact same registry from `main`
 * (see `tools/run_core_tests.sh`) for machines without Gradle. One copy of the logic, two runners.
 */
@RunWith(Parameterized::class)
class CoreLibraryTest(
    private val suiteName: String,
    private val testName: String,
    private val case: TestCase,
) {

    @Test
    fun engineBehaviourIsCorrect() {
        val result = runSuite(suiteName, listOf(case))
        val report = result.failures.joinToString("\n") { "${it.testName}: ${it.message}" }
        assertTrue("$suiteName / $testName failed\n$report", result.isSuccess)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} — {1}")
        fun parameters(): List<Array<Any>> = CoreTestSuites.suites.flatMap { (suiteName, cases) ->
            cases.map { case -> arrayOf(suiteName, case.name, case) }
        }
    }
}
