# Engineering decisions

Significant decisions, with a one-line rationale each. D1–D5 were approved before Phase 1
(see ARCHITECTURE.md section 2).

| # | Decision | Rationale |
|---|---|---|
| D1 | Gemini `generateContent` behind `GeminiTransport` | Stable, fully documented REST schema; history stays on the device anyway; swapping to the Interactions API touches only the transport. |
| D2 | GitHub OAuth App + device flow, PAT fallback | Reaches every repository the user can access without per-repo app installation; tokens do not expire every 8 hours. |
| D3 | Phases land as PRs from `ccr-ee862235-7b5f5g` into `main` | The session may push only to that branch; `main` is the PR target. |
| D4 | Sora Editor (LGPL-2.1) as an unmodified dependency; GCode is Apache-2.0 | Maintained editor with TextMate support; licence obligations met with notices and the full LGPL text in-app. |
| D5 | Emulator UI test only on PRs and pushes to `main` | Saves 6–10 minutes per branch push. |
| 6 | Five top-level destinations; files and source control share "Code" | Material bottom bars work best with up to five items; both views belong to the open repository. |
| 7 | AboutLibraries strict licence allow-list | New dependencies with unreviewed licences fail the build instead of shipping silently. |
| 8 | `Part` keeps the raw JSON object | Thought signatures and future fields round-trip unchanged; parts with signatures are never merged. |
| 9 | Retries only before the first streamed chunk | Replaying a stream that already produced output would duplicate text and tool calls. |
| 10 | Unavailable models (404/403/`limit: 0`) always fall back; rate limits switch models only with auto-switch on | Matches the spec: fallback is about key/tier availability, auto-switch is an opt-in for 429s. |
| 11 | "Next free model" = next model available to the key and not cooling down | The API does not expose which models are free for a key. |
| 12 | Agent turns run in an application-scoped `AgentSessionManager` | Streaming survives rotation and tab switches; Phase 4 adds a foreground service to survive backgrounding. |
| 13 | Secrets: Keystore AES-256-GCM key (StrongBox when available) + ciphertext in a dedicated DataStore file | `security-crypto` is deprecated; Google recommends the platform Keystore directly. |
| 14 | Room schemas exported from version 1; later versions use `@AutoMigration` | Installed debug builds keep sessions across phases. |
| 15 | Hilt modules split (`SecretsModule`, `GeminiModule`) | Instrumented tests replace them with an in-memory store and a scripted Gemini fake (no network, no Keystore). |
| 16 | Compose UI tests use the v2 test rules | The v1 rules are deprecated in Compose UI test 1.13 (v2 uses `StandardTestDispatcher`). |
| 17 | JGit **5.13.5** instead of 7.x | Static check against the API 26 android.jar: JGit 7.8 calls ~40 JDK 9–17 APIs missing on older Android (e.g. `InputStream.readAllBytes`, `String.isBlank`, `Stream.toList`); 5.13 only references JMX monitoring (off by default) and a server-side timestamp helper. Verified at runtime by an instrumented test on API 26 and 36. |
| 18 | Single-branch clone instead of shallow clone | Shallow clone (`setDepth`) needs JGit 6.3+; cloning only the default branch keeps downloads small on 5.13. |
| 19 | `SandboxedSystemReader` for JGit | On Android there is no system git or home directory; JGit's user config lives in app storage and the system config is empty. |
| 20 | One `GitHubHttp` core with small per-area interfaces (`UsersApi`, `ReposApi`, …) | Tests and agent tools fake only the area they use. |
| 21 | AboutLibraries metadata override for JGit (EDL-1.0 → BSD-3-Clause) plus an allow-list entry scoped to JGit | JGit's POM licence has no SPDX id; the strict licence check stays on for everything else. |
| 22 | Instrumented tests run on API 26 and 36 | The minimum SDK and a recent release both run JGit, the Keystore and the UI. |
| 23 | Tools are prepared, then applied | `Tool.prepare` computes the exact change (and its diff) before anything is written, so Ask mode can show a diff sheet and the user's edits replace the proposal. |
| 24 | Agent loop uses `channelFlow` | Parallel read-only tool calls report progress from several coroutines; a plain `flow` forbids that (caught by a unit test). |
| 25 | Workspace = cloned repo (Git) or SAF folder (files only) | JGit needs a real directory; SAF folders give access to files elsewhere on the device without broad storage permissions. |
| 26 | Protected-file writes use the always-confirm secret prompt | Editing `.env`, keystores or git-ignored files should never happen silently, even with auto-accept. |
| 27 | Agent foreground service type `dataSync`, `START_NOT_STICKY`, stop on `onTimeout` | Matches the docs for user-started network work; Android 15+ caps dataSync at 6 h per 24 h. |
| 28 | Checkpoint bytes in app files, metadata in Room (newest 100 per session) | Keeps the database small; rewind restores newest-first. |
