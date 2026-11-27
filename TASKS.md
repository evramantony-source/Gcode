# GCode — Task list

One PR per phase into `main`. A phase is done only when CI is green and I have
reported what works, what is stubbed, and the Actions run link with the
`gcode-debug-apk` artifact. Section numbers refer to `ARCHITECTURE.md`.

Legend: `[ ]` to do · `[x]` done · *(stub)* placeholder allowed in that phase

---

## Phase 0: Planning (this commit)
- [x] Verify current toolchain versions, Gemini API and GitHub API facts
- [x] `ARCHITECTURE.md`
- [x] `TASKS.md`
- [ ] **Your approval, plus answers to decisions D1–D5 (section 2)**
- [ ] You: register a GitHub OAuth App with Device Flow enabled, and add
      repository variable `GCODE_GITHUB_CLIENT_ID` (needed by Phase 3)

## Phase 1: Repo, Gradle, CI, app shell
- [ ] `.gitignore` (secrets denylist), `.editorconfig`, `LICENSE` (your choice)
- [ ] Gradle wrapper 9.9.2, `settings.gradle.kts`, version catalog
      `gradle/libs.versions.toml` with stable pins
- [ ] Modules: `:app`, `:core:model`, `:core:common`, `:core:data`, `:gemini`,
      `:github`, `:git`, `:agent`, `:mcp`. Empty but compiling, each with
      one unit test
- [ ] Shared build logic for Android and JVM modules (convention plugins in
      `build-logic/`)
- [ ] `:app`: Hilt application, Compose Material 3 theme (light, dark,
      dynamic), `NavigationSuiteScaffold` with empty top-level destinations,
      `values-ar` strings, `supportsRtl`
- [ ] `BuildConfig.GITHUB_CLIENT_ID` from a Gradle property or env var (empty
      if unset)
- [ ] `.github/workflows/android.yml`:
      - build, lint and unit tests, plus artifact `gcode-debug-apk`
      - Gradle caching
      - `ui-test` emulator job (PRs and `main`)
      - signed `release` job on `v*`
      - denylist guard step
- [ ] Optional stable debug signing from `DEBUG_KEYSTORE_BASE64`
- [ ] Instrumented smoke-test scaffold: app launches and shows the shell
- [ ] `docs/SIGNING.md`: keytool, base64, `gh secret set` / web UI steps
- [ ] `README.md` (short; finished in Phase 10)
- **Done when:** CI green, the APK installs and shows the shell, and a test
  tag builds a signed release once you add the secrets.

## Phase 2: Gemini onboarding, model discovery, streaming chat
- [ ] `SecretStore` (Keystore AES-GCM + DataStore), with Robolectric tests
- [ ] Onboarding step 1: key entry, "restrict to Gemini API" tip, check with
      `models.list`
- [ ] `GeminiClient`: models.list (paged), streamGenerateContent SSE,
      generateContent, countTokens; redacting logging
- [ ] Model catalog in Room with a 24 h cache; `ModelSelector`
      (gemini-4-hydrogen → newest Flash → newest Flash-Lite)
- [ ] Model picker (every generateContent model), thinking-level setting
      (table for each model, safe fallback)
- [ ] 429 backoff (honours RetryInfo), auto-switch toggle with cool-downs
- [ ] Chat screen: streaming Markdown, code blocks, thought summaries,
      Stop, sessions saved in Room (parts kept verbatim), status bar
      (model, context %)
- [ ] Tests: SSE parser, chunk folding that keeps thought signatures,
      ModelSelector fixtures, backoff with a virtual clock, client through
      MockWebServer
- *(stub)* repo, branch and mode in the status bar; tools not wired yet

## Phase 3: GitHub sign-in, repos, JGit
- [ ] **Spike first (risk R1):** JGit 7.x clone/commit/push in an
      instrumented test on API 26 and 37; fall back to 6.10.x or 5.13.x if
      needed
- [ ] Device flow (state machine with every error code), PAT fallback with a
      scope/permission checklist, token in SecretStore
- [ ] GitHub REST client: headers, `X-GitHub-Api-Version: 2026-03-10`, Link
      pagination, ETag, rate-limit handling
- [ ] Repositories screen: user and org repos, search, create (gitignore and
      license templates), fork, clone with progress, delete the local copy
- [ ] `GitGateway`: status, diff, add/reset, commit, push, pull, fetch, with
      the commit author set up
- [ ] Tests: device-flow polling, pagination, ETag, create/fork request
      bodies; JGit operations against temporary local repos
- *(stub)* the Changes screen is a minimal list; the full source-control UI
  comes in Phase 5

## Phase 4: Workspace tools, agent loop, diff approval, permission modes
- [ ] `Workspace` with path sandboxing; denylist and `.gitignore`
      enforcement
- [ ] File tools: read_file, write_file, edit_file, multi_edit, list_dir,
      glob, grep, delete_file, move_file
- [ ] Git tools: git_status … git_log
- [ ] Tool registry turns Kotlin tool specs into Gemini `functionDeclarations`
- [ ] Agent loop: function calling, parallel READ groups, ordered results,
      every call answered on Stop or cancel, step limit
- [ ] `todo_write` and the todo panel; collapsible tool-call cards
- [ ] `PermissionPolicy` (Ask / Auto-accept edits / Plan / Full auto),
      always-confirm dialogs, Full-auto opt-in warning
- [ ] Diff sheet with Accept / Reject / Edit; Plan mode with `submit_plan`
- [ ] Checkpoints before each edit (Rewind UI comes in Phase 9)
- [ ] `AgentService` foreground service with a Stop action (FGS type checked
      on API 37)
- [ ] Tests: every tool, PermissionPolicy for each tool and mode, agent loop
      with a scripted fake model

## Phase 5: Code editor, file explorer, source control
- [ ] File tree explorer, Sora Editor with tabs, find/replace, undo/redo,
      TextMate grammars, LTR editor
- [ ] Changes screen: stage/unstage, unified and side-by-side diff, commit,
      push/pull/fetch
- [ ] Branch list (create, switch, delete; delete is always confirmed)
- [ ] History with per-commit diffs
- [ ] Tablet two-pane layout for chat + file/diff
- [ ] Tests: diff model, ViewModels (Turbine)

## Phase 6: Remote build pipeline
- [ ] Android-project detection; workflow template (with pinned action
      versions) and "Set up builds" commit
- [ ] Build button: commit → push → dispatch (200 `workflow_run_id`) or find
      the run by `head_sha`; no duplicate runs
- [ ] Live run screen: jobs and steps, 5 s ETag polling
- [ ] Log download (302) and parser; failed steps highlighted
- [ ] Artifacts: zip download (302, token not forwarded), extract APK, check
      it, confirm, `PackageInstaller` session, unknown-sources flow, clear
      handling of signature mismatch
- [ ] Build history in Room, APK download again, HTTP 410 for expired
      artifacts
- [ ] `BuildMonitorWorker` (long-running, ETag) and completion notifications
- [ ] Optional stable debug keystore: generated on the device, saved as a
      secret with a sealed box (always confirmed)
- [ ] Non-Android projects: run any workflow (with dispatch inputs), monitor
      it, read its logs
- [ ] Tests: run/job/artifact parsing, redirect without the token, log
      parser, sealed box (libsodium vectors), worker logic

## Phase 7: Agent GitHub and Actions tools, build-fix loop
- [ ] gh_list_repos, gh_search_repos, gh_create_repo, gh_clone_repo,
      gh_fork_repo, gh_get_file
- [ ] gh_create_pr, gh_list_prs, gh_merge_pr, gh_comment, gh_list_issues,
      gh_create_issue
- [ ] gh_setup_build_workflow, gh_trigger_workflow, gh_get_run_status,
      gh_wait_for_run, gh_get_run_logs (failed steps only), gh_list_artifacts,
      gh_download_apk, gh_install_apk (always confirmed), gh_create_release
- [ ] web_fetch (Jsoup), web_search (separate `google_search` grounding
      call, with sources)
- [ ] Build-fix guard (per-repo limit, default 3) and system-prompt guidance
- [ ] Tests: each tool against a fake gateway; the guard; a full scripted
      build-fail → fix → success scenario

## Phase 8: PRs, issues, releases, branches, conflicts
- [ ] PR list, detail (files, diff, comments), create, comment, merge
      (always confirmed into the default branch)
- [ ] Issue list, detail, create, comment
- [ ] Releases: list, create tag and release, upload APK asset
- [ ] Branch merge; conflict resolver (Ours / Theirs / Both / Edit / Ask AI)
- [ ] Secrets screen: link to repo settings, or set through the API (always
      confirmed)
- [ ] Tests: conflict model, merge flows, release upload request

## Phase 9: Compaction, fallback polish, sessions, checkpoints, MCP, commands, sub-agents
- [ ] countTokens tracking, auto-compact at 80% (turn boundaries only),
      manual Compact
- [ ] Sessions screen (search, rename, delete, resume)
- [ ] Rewind UI (code, conversation, or both; warns about remote actions)
- [ ] GEMINI.md loading and `/init`
- [ ] Slash commands (built-in + `.gcode/commands/*.md` with `$ARGUMENTS`)
- [ ] `task` sub-agents and `.gcode/agents/*.md` definitions
- [ ] MCP client (streamable HTTP, version negotiation), settings UI, tools
      added to the registry
- [ ] Image attachments and voice input
- [ ] Tests: compaction boundary rules, rewind, command expansion,
      frontmatter parsing, MCP client against MockWebServer (2026-07-28 and
      2025-11-25 servers)

## Phase 10: Release signing, polish, README, tests
- [ ] Release signing check end to end (tag → signed APK in a GitHub
      Release)
- [ ] R8 rules checked on a release build (serialization, JGit,
      BouncyCastle)
- [ ] Accessibility pass (content descriptions, touch targets, font scale),
      RTL pass, tablet pass
- [ ] Finish the instrumented smoke test (onboarding → chat → tool card with
      fakes)
- [ ] `README.md` (setup, OAuth App, key, signing, screenshots),
      `docs/TESTING.md` manual checklist
- [ ] Coverage report for `:agent`, `:gemini`, `:github`
