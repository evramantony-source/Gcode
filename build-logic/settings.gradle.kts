dependencyResolutionManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first; Maven Central sometimes rate-limits CI runners (HTTP 429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { mavenContent { releasesOnly() } }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
