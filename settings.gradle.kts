pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RigStudio"

// :core is the deterministic engine — pure Kotlin/JVM, zero dependencies, unit-testable without
// an Android device. :app is the Android shell: Compose UI, bitmap rendering, MediaCodec export
// and local storage. Keeping them apart is what makes the extraction/rig/animation logic
// verifiable on a plain JVM (`./gradlew :core:test`) and impossible for the UI to corrupt.
include(":core")
include(":app")
