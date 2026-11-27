import com.android.build.api.variant.BuildConfigField
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.StrictMode

plugins {
    alias(libs.plugins.gcode.android.application)
    alias(libs.plugins.gcode.android.compose)
    alias(libs.plugins.gcode.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries.android)
}

// ---------------------------------------------------------------------------------------------
// Build inputs from the environment (CI) or Gradle properties (local). Nothing here is hard-coded.
// ---------------------------------------------------------------------------------------------
fun envOrProperty(env: String, property: String) =
    providers.environmentVariable(env).map { it.trim() }.filter { it.isNotEmpty() }
        .orElse(providers.gradleProperty(property).map { it.trim() }.filter { it.isNotEmpty() })

/** GitHub OAuth App client ID (not a secret). Required: the build fails with a clear message without it. */
val githubClientId = envOrProperty("GCODE_GITHUB_CLIENT_ID", "gcode.githubClientId")

val appVersionCode = envOrProperty("GCODE_VERSION_CODE", "gcode.versionCode").orElse("1").map { it.toInt() }
val appVersionName = envOrProperty("GCODE_VERSION_NAME", "gcode.versionName").orElse("0.1.0")

// Release signing (CI decodes KEYSTORE_BASE64 into a temp file and exports its path).
val releaseKeystoreFile = providers.environmentVariable("GCODE_RELEASE_KEYSTORE_FILE").orNull
val releaseSigningConfigured = releaseKeystoreFile != null &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !providers.environmentVariable(it).orNull.isNullOrEmpty() }

// Optional stable debug key (CI decodes DEBUG_KEYSTORE_BASE64), so new debug APKs update old installs.
val debugKeystoreFile = providers.environmentVariable("GCODE_DEBUG_KEYSTORE_FILE").orNull

android {
    namespace = "dev.gcode.app"

    defaultConfig {
        applicationId = "dev.gcode.app"
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        testInstrumentationRunner = "dev.gcode.app.HiltTestRunner"
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile != null) {
                storeFile = file(debugKeystoreFile)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned unless the release keystore is provided (CI release job only).
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        // Only ship the languages GCode is translated into (English, Arabic); drops partial library translations.
        localeFilters += listOf("en", "ar")
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "GITHUB_CLIENT_ID",
            githubClientId.orElse("").map { id ->
                val escaped = id.replace("\\", "\\\\").replace("\"", "\\\"")
                BuildConfigField("String", "\"$escaped\"", "GitHub OAuth App client ID (public, not a secret)")
            },
        )
    }
}

val verifyGitHubClientId = tasks.register<VerifyGitHubClientIdTask>("verifyGitHubClientId") {
    group = "verification"
    description = "Fails the build if GCODE_GITHUB_CLIENT_ID is missing or malformed."
    clientId.set(githubClientId)
}
tasks.named("preBuild") { dependsOn(verifyGitHubClientId) }

aboutLibraries {
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
        includePlatform = true
    }
    export {
        prettyPrint = true
        excludeFields.addAll("funding")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
    }
    license {
        // Fail the build if a dependency brings a licence we have not reviewed.
        strictMode = StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0",
            "MIT",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "EDL-1.0",
            "EPL-1.0",
            "EPL-2.0",
            "LGPL-2.1-only",
            "LGPL-2.1-or-later",
            "ISC",
            "Unicode-DFS-2016",
        )
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":gemini"))
    implementation(project(":github"))
    implementation(project(":git"))
    implementation(project(":agent"))
    implementation(project(":mcp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.okhttp)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
