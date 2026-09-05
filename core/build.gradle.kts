plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The engine is pure Kotlin/JVM: no Android imports, no reflection, no third-party libraries.
// That is what allows `./gradlew :core:test` (and tools/run_core_tests.sh) to verify sheet
// extraction, rigging, animation, framing and export validation on any machine.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
