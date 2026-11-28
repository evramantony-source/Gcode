import org.gradle.api.artifacts.dsl.RepositoryHandler

/**
 * Google's public mirror of Maven Central, tried first: CI runners share IP addresses and Maven Central
 * sometimes answers them with 429 Too Many Requests. Artifacts are the same; Maven Central stays the fallback.
 */
fun RepositoryHandler.mavenCentralMirror() = maven("https://maven-central.storage-download.googleapis.com/maven2/") {
    name = "MavenCentralGoogleMirror"
    mavenContent { releasesOnly() }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's mirror of Maven Central first (see mavenCentralMirror below; this block is evaluated on its own).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { mavenContent { releasesOnly() } }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentralMirror()
        mavenCentral()
    }
}

rootProject.name = "GCode"

include(":app")
include(":core:model")
include(":core:common")
include(":core:data")
include(":gemini")
include(":github")
include(":git")
include(":agent")
include(":mcp")
