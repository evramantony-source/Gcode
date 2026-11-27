package dev.gcode.github.actions

/**
 * The build workflow GCode adds to Android projects ("Set up builds", ARCHITECTURE.md section 8.4).
 * Debug APK on every push, PR and manual run; on v* tags, a release APK signed with apksigner when
 * the signing secrets exist. A stable debug key (DEBUG_KEYSTORE_BASE64) goes to ~/.android/debug.keystore,
 * where the Android Gradle plugin looks for it, so successive debug APKs can update each other.
 * Action versions checked on 2026-11-27; third-party actions are pinned to commit SHAs.
 */
object WorkflowTemplate {
    const val VERSION = 1
    const val FILE_NAME = "gcode-build.yml"
    const val PATH = ".github/workflows/$FILE_NAME"
    const val ARTIFACT_NAME = "app-debug-apk"
    const val MARKER = "# gcode-build-template: $VERSION"

    fun android(javaVersion: String = "17", appModule: String = "app"): String = """
$MARKER
# Added by GCode. Builds run on GitHub Actions; the phone never compiles code.
name: GCode build

on:
  push:
    branches: ["**"]
    tags: ["v*"]
  pull_request:
  workflow_dispatch:

concurrency:
  group: gcode-build-${'$'}{{ github.ref }}
  cancel-in-progress: ${'$'}{{ !startsWith(github.ref, 'refs/tags/') }}

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "$javaVersion"

      - uses: gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c # v6.4.1

      - name: Use the stable debug key (optional)
        env:
          DEBUG_KEYSTORE_BASE64: ${'$'}{{ secrets.DEBUG_KEYSTORE_BASE64 }}
        run: |
          if [ -n "${'$'}{DEBUG_KEYSTORE_BASE64}" ]; then
            mkdir -p ~/.android
            printf '%s' "${'$'}{DEBUG_KEYSTORE_BASE64}" | base64 --decode > ~/.android/debug.keystore
          fi

      - name: Build debug APK
        run: |
          chmod +x ./gradlew
          ./gradlew assembleDebug --stacktrace

      - uses: actions/upload-artifact@v7
        with:
          name: $ARTIFACT_NAME
          path: "**/build/outputs/apk/debug/*.apk"
          if-no-files-found: error
          retention-days: 30

  release:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 60
    env:
      KEYSTORE_BASE64: ${'$'}{{ secrets.KEYSTORE_BASE64 }}
      KEYSTORE_PASSWORD: ${'$'}{{ secrets.KEYSTORE_PASSWORD }}
      KEY_ALIAS: ${'$'}{{ secrets.KEY_ALIAS }}
      KEY_PASSWORD: ${'$'}{{ secrets.KEY_PASSWORD }}
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "$javaVersion"

      - uses: gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c # v6.4.1

      - name: Build release APK
        run: |
          chmod +x ./gradlew
          ./gradlew assembleRelease --stacktrace

      - name: Sign and publish the release
        env:
          GH_TOKEN: ${'$'}{{ github.token }}
        run: |
          apk=${'$'}(find . -path "*/build/outputs/apk/release/*.apk" | head -n 1)
          if [ -z "${'$'}{apk}" ]; then echo "::error::No release APK was built"; exit 1; fi
          out="${'$'}{GITHUB_REF_NAME}.apk"
          if [ -n "${'$'}{KEYSTORE_BASE64}" ]; then
            printf '%s' "${'$'}{KEYSTORE_BASE64}" | base64 --decode > "${'$'}{RUNNER_TEMP}/release.keystore"
            apksigner="${'$'}(ls -d "${'$'}{ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)/apksigner"
            "${'$'}{apksigner}" sign --ks "${'$'}{RUNNER_TEMP}/release.keystore" --ks-pass env:KEYSTORE_PASSWORD \
              --ks-key-alias "${'$'}{KEY_ALIAS}" --key-pass env:KEY_PASSWORD --out "${'$'}{out}" "${'$'}{apk}"
            rm -f "${'$'}{RUNNER_TEMP}/release.keystore"
          else
            echo "::warning::No signing secrets: attaching the unsigned APK"
            cp "${'$'}{apk}" "${'$'}{out}"
          fi
          gh release create "${'$'}{GITHUB_REF_NAME}" "${'$'}{out}" --generate-notes --verify-tag
""".trimStart()

    fun isGCodeTemplate(content: String): Boolean = content.contains("# gcode-build-template:")
}

/** Recognises Android application projects from their build files. */
object AndroidProjectDetector {
    private val APP_PLUGIN = Regex("""com\.android\.application|android\.application|androidApplication""")

    /** [files] maps relative paths to contents for the root build files and module build files. */
    fun isAndroidApp(files: Map<String, String>): Boolean {
        val hasSettings = files.keys.any { it == "settings.gradle" || it == "settings.gradle.kts" }
        return hasSettings && files.entries.any { (path, text) -> path.endsWith("build.gradle") || path.endsWith("build.gradle.kts") }.let { hasBuild ->
            hasBuild && files.entries.any { (path, text) -> (path.endsWith(".gradle") || path.endsWith(".gradle.kts") || path.endsWith(".toml")) && APP_PLUGIN.containsMatchIn(text) }
        }
    }

    /** Workflows that declare workflow_dispatch can be started from the app. */
    fun supportsDispatch(workflowYaml: String): Boolean = Regex("""(?m)^\s*workflow_dispatch\s*:""").containsMatchIn(workflowYaml) ||
        Regex("""(?m)^on:\s*\[[^\]]*workflow_dispatch""").containsMatchIn(workflowYaml) ||
        Regex("""(?m)^\s*-\s*workflow_dispatch\s*$""").containsMatchIn(workflowYaml)

    /** Names of `workflow_dispatch.inputs` keys (simple YAML scan; values are entered by the user). */
    fun dispatchInputs(workflowYaml: String): List<String> {
        val lines = workflowYaml.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("workflow_dispatch:") }
        if (start < 0) return emptyList()
        val inputsLine = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "inputs:" } ?: return emptyList()
        val inputsIndent = lines[inputsLine].indexOfFirst { !it.isWhitespace() }
        val keyIndent = (inputsLine + 1 until lines.size).map { lines[it] }.firstOrNull { it.isNotBlank() }?.indexOfFirst { !it.isWhitespace() } ?: return emptyList()
        if (keyIndent <= inputsIndent) return emptyList()
        return (inputsLine + 1 until lines.size).map { lines[it] }
            .takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > inputsIndent }
            .filter { it.isNotBlank() && it.indexOfFirst { c -> !c.isWhitespace() } == keyIndent && it.trim().endsWith(":") }
            .map { it.trim().removeSuffix(":").trim('"', '\'') }
    }
}
