# GCode

GCode is a native Android app that works as an agentic coding assistant on your phone. You talk to
an agent backed by the **Google Gemini API** (your own key). It reads and edits code in a local Git
workspace, commits and pushes, and drives GitHub: repositories, pull requests, issues, releases and
GitHub Actions.

**The phone never compiles code.** Every build runs on GitHub Actions. GCode pushes, starts the
workflow, follows it live, reads the logs, downloads the APK artifact and installs it.

> **Status:** Phase 1 of 10. The app shell, the module structure and CI are in place; the features
> arrive phase by phase. See [TASKS.md](TASKS.md).

## Documents

* [ARCHITECTURE.md](ARCHITECTURE.md): design, verified API facts, decisions
* [TASKS.md](TASKS.md): phased task list
* [docs/SIGNING.md](docs/SIGNING.md): CI variables and secrets, OAuth App setup, release signing
* [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): third-party software and licences

## Building

Requirements: JDK 17 or newer (CI uses 21) and the Android SDK with platform 37.2.

```sh
export GCODE_GITHUB_CLIENT_ID=<your OAuth App client ID>   # required, see docs/SIGNING.md
./gradlew lint test assembleDebug
```

Modules:

| Module | Type | Purpose |
|---|---|---|
| `:app` | Android app | Compose UI, navigation, Android services |
| `:core:model` | JVM | shared data types |
| `:core:common` | JVM | shared utilities |
| `:core:data` | Android library | Room, DataStore, Keystore-backed secret store |
| `:gemini` | JVM | Gemini API client and model selection |
| `:github` | JVM | GitHub REST client and device flow |
| `:git` | JVM | JGit wrapper |
| `:agent` | JVM | agent loop, tools, permissions |
| `:mcp` | JVM | MCP client |

Shared Gradle configuration lives in the convention plugins under `build-logic/`, and all versions
are in `gradle/libs.versions.toml`.

## Continuous integration

`.github/workflows/android.yml`:

* **Every push and pull request:** secret scan, lint, unit tests, debug APK uploaded as the artifact
  `gcode-debug-apk`.
* **Pull requests and pushes to `main`:** instrumented UI smoke test on an emulator.
* **Tags `v*`:** signed release APK attached to a GitHub Release.

## Licence

GCode is licensed under the [Apache License 2.0](LICENSE). Third-party licences are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the app under
**More > Open-source licences**.
