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
| 29 | Sora Editor with bundled MIT TextMate grammars and GCode's own themes | Kotlin (fwcd/vscode-kotlin) and VS Code grammars are MIT; writing the two themes avoids third-party theme licences. |
| 30 | One Sora `CodeEditor` per open tab, kept outside composition | Undo history and cursor survive tab switches; views are released when a tab closes. |
| 31 | Explorer/editor are two panes from 840 dp; the chat pane stays left of every destination on wide screens | Tablet split-pane layout without duplicating the chat. |
| 32 | Side-by-side diff from a pure unified-diff parser | Unit-testable; reused for file diffs and commit diffs. |
| 33 | Grant POST_NOTIFICATIONS in instrumented tests on API 33+ | The permission dialog covers the app on Android 13+ emulators and hides the Compose hierarchy. |
| 34 | One DataStore per file for the whole process (`ProcessDataStores`) | DataStore refuses two active instances on one file; Hilt re-creates the singleton component for each instrumented test. |
| 35 | Sealed boxes with Bouncy Castle primitives (X25519, XSalsa20, Poly1305, BLAKE2b) instead of a native libsodium | No NDK library to ship; checked against a PyNaCl/libsodium test vector. The Bouncy Castle licence is MIT text (AboutLibraries override). |
| 36 | Build monitoring with short one-shot WorkManager jobs that re-enqueue every 30 s, not a long-running worker | No foreground service for a build that can take 30+ minutes; jobs survive process death and respect network constraints and job quotas. |
| 37 | "Build" first looks for a run with the pushed `head_sha`, then dispatches | A push to a workflow with a `push` trigger already starts a run; dispatching as well would build twice. Dispatch uses the 200 response's `workflow_run_id` (API 2026-03-10). |
| 38 | The GCode workflow template is versioned (`# gcode-build-template: 1`) and pins action versions | The app can recognise and later update its own workflow; pinned versions keep builds reproducible. |
| 39 | APKs are extracted into app storage (`files/builds/<owner>/<repo>/<run>`) and installed with a `PackageInstaller` session that always requires user action | No storage permission needed; installing is in the always-confirm set, and the system shows its own prompt too. |
| 40 | Stable debug key generated on the device (PKCS12, `androiddebugkey`/`android`), kept encrypted in the SecretStore and saved as `DEBUG_KEYSTORE_BASE64` | One key for all repositories, so new debug builds update old installs; saving a secret is always confirmed. |
| 41 | Artifact and log downloads follow GitHub's 302 without forwarding the token | OkHttp drops `Authorization` on cross-host redirects (verified in a unit test); 410 is reported as an expired artifact. |
| 42 | Phases merge with a merge commit (from #6 on) | The work branch then fast-forwards to `main` after each merge; restarting it after a squash merge needs a force push, which is not allowed here. |
| 43 | `:agent` depends on `:github`; tools take the small API interfaces plus an `AgentHost` for app services (clone, build pipeline, APK files, installer) | Tools are unit-tested against an in-memory GitHub, and the app keeps Android-only code out of `:agent`. |
| 44 | Tool effects follow the always-confirm rules: `gh_create_repo` with `private=false`, `gh_merge_pr` into the default branch and `gh_install_apk` are DANGEROUS | Making a repository public, merging into the default branch and installing an APK must be confirmed in every mode. |
| 45 | Build-fix guard: consecutive failed runs per repository within one agent turn; a new build may start while failures ≤ the repository's limit (default 3) | The first build is free and the limit counts fix attempts; a new user message starts a new budget. |
| 46 | `gh_wait_for_run` polls every 15 s (up to 60 min) inside the tool call | The agent stays in its loop while the build runs; the Builds screen and notifications show the same run. |
| 47 | `web_fetch` uses OkHttp + jsoup 1.24.1, converts HTML to Markdown-like text, refuses non-text types, caps bodies at 2 MB and refuses loopback/private/link-local/CGNAT hosts (also after redirects) | Readable pages for the model, and a prompt-injected page cannot make the agent probe the user's LAN. jsoup only references JDK APIs present on API 26 (checked with the class-file scan). |
| 48 | `web_search` is a separate `generateContent` call with the `google_search` tool on the first usable model | Grounding cannot be mixed with function declarations in every model; returning sources lets the user check them. |
| 49 | The turn's workspace is re-read after `gh_clone_repo` (`TurnWorkspace`), and checkpoints go to the store of the open workspace | File and Git tools follow a repository the agent just cloned, and rewinds restore files in the right workspace. |
| 50 | Conflict model in `:git` (`Conflicts.parse/resolve`) keeps line endings byte for byte and supports diff3 bases | Resolving every hunk with one side reproduces that side exactly (CRLF files stay CRLF); stray `=======` lines outside conflicts are text. |
| 51 | Merges include the branch ref, not just its commit | JGit then writes the usual "Merge branch 'x'" message, which the resolver pre-fills for the merge commit. |
| 52 | Files without text markers (binary, delete/modify) are resolved by taking a whole side from the index stages, or removing the file when that side deleted it | Hunk resolution cannot apply; the index stages are the source of truth. |
| 53 | "Ask AI" sends one hunk with 30 lines of context to a separate `generateContent` call and shows the proposal for review; protected files (denylist, .gitignore) need explicit approval first | Smallest useful context, nothing applied without the user, and the secrets rule holds outside the agent loop too. |
| 54 | PR/issue/release screens work on the open clone's GitHub repository; their details live in the screen's ViewModel instead of separate routes | Matches how Builds works; Back closes the detail first. |
| 55 | PR merges are always confirmed in the UI, with the stronger default-branch warning when the base is the default branch (or unknown) | The always-confirm rule for the default branch, with a safe fallback. |
| 56 | Release assets are renamed `<apk>-<tag>.apk` unless the name already contains the tag | Assets from many releases are otherwise all `app-debug.apk`. |
| 57 | Per-repository settings (author, pull rebase, default mode, build workflow, fix limit 0–10) are edited from the Code tab; Full auto is never stored as a default | Settings live next to the repository they change; Full auto stays an explicit per-session opt-in. |
