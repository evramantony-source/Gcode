# GCode progress

This file lets a new session resume without other context. It is updated after every phase.

- Repository: `evramantony-source/Gcode`. Work branch: `ccr-ee862235-7b5f5g` (the only branch this
  agent may push to). Each phase is one PR from that branch into `main`, merged with a merge commit
  (decision 42); after a merge the branch fast-forwards to `main`.
- Plan: [TASKS.md](TASKS.md). Design: [ARCHITECTURE.md](ARCHITECTURE.md). Decisions:
  [docs/DECISIONS.md](docs/DECISIONS.md). Setup and signing: [docs/SIGNING.md](docs/SIGNING.md).
- Local build: Android SDK at `/opt/android-sdk` (platform 37.2, build-tools 37.0.0), `local.properties`
  has `sdk.dir`. Run `GCODE_GITHUB_CLIENT_ID=<any 8+ char id> ./gradlew lint test assembleDebug`.
  After dependency changes run `python3 scripts/generate_third_party_notices.py`.
- CI: `.github/workflows/android.yml`. The repository variable `GCODE_GITHUB_CLIENT_ID` is set.

## Phases

| Phase | Status | PR | Green Actions run |
|---|---|---|---|
| 1 Repo, Gradle, CI, app shell | Merged | [#1](https://github.com/evramantony-source/Gcode/pull/1) | [run](https://github.com/evramantony-source/Gcode/actions/runs/45978861795) |
| 2 Gemini onboarding, model discovery, streaming chat | Merged | [#2](https://github.com/evramantony-source/Gcode/pull/2) | [run](https://github.com/evramantony-source/Gcode/actions/runs/45986329590) |
| 3 GitHub sign-in, repos, JGit | Merged | [#3](https://github.com/evramantony-source/Gcode/pull/3) | [run](https://github.com/evramantony-source/Gcode/actions/runs/45990406165) |
| 4 Workspace tools, agent loop, permissions | Merged | [#4](https://github.com/evramantony-source/Gcode/pull/4) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46038035247) |
| 5 Editor, explorer, source control UI | Merged | [#5](https://github.com/evramantony-source/Gcode/pull/5) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46041082463) |
| 6 Remote build pipeline | Merged | [#6](https://github.com/evramantony-source/Gcode/pull/6) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46042568090) |
| 7 Agent GitHub/Actions tools, build-fix loop | Merged | [#7](https://github.com/evramantony-source/Gcode/pull/7) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46045167907) |
| 8 PRs, issues, releases, branches, conflicts | Merged | [#8](https://github.com/evramantony-source/Gcode/pull/8) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46146868812) |
| 9 Compaction, sessions, checkpoints, MCP, commands, sub-agents | Merged | [#9](https://github.com/evramantony-source/Gcode/pull/9) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46149311159) |
| 10 Release signing, polish, README, tests | Merged | [#10](https://github.com/evramantony-source/Gcode/pull/10) | [run](https://github.com/evramantony-source/Gcode/actions/runs/46151211082) |

## Features done

- **Phase 1:** Gradle 9.9.2 / AGP 9.5.3 / Kotlin 2.4.21 build with convention plugins; nine modules;
  Compose shell (five destinations, light/dark/dynamic, Arabic RTL); licences screen + generated
  THIRD_PARTY_NOTICES; CI (build/lint/tests/APK, emulator smoke test, signed release on tags).
- **Phase 2:** Gemini key onboarding (verified with models.list, stored with Keystore AES-GCM);
  `HttpGeminiTransport` (models.list paging, SSE streaming with cancellation, countTokens, error
  parsing incl. RetryInfo); stream folding that keeps thought signatures; model catalog cache (24 h);
  `ModelSelector` (gemini-4-hydrogen → newest Flash → newest Flash-Lite, unavailable/cool-down
  tracking); thinking levels per model; 429 backoff + optional auto-switch; agent loop core
  (streaming, saving before tools, partial text on error/stop, resume); chat screen (streaming
  Markdown, thoughts card, status bar with model + context %, error/retry, Stop, new chat);
  model picker; settings (key, model, thinking, thoughts, auto-switch, compaction threshold, theme).

- **Phase 3:** GitHub device-flow sign-in (code + countdown, slow_down/expiry/denied handling) with
  fine-grained/classic PAT fallback and scope checks; `GitHubHttp` (API version 2026-03-10, Link
  pagination, ETag/304 cache, primary and secondary rate limits, error/scope parsing); repos API
  (list, search, create user/org with gitignore/license templates, fork, delete, visibility, branches,
  contents/raw); JGit 5.13.5 wrapper (clone with progress/cancel, status, diff, stage/unstage,
  commit, push with force-with-lease, pull merge/rebase, fetch, branches, checkout of remote branches,
  merge with conflict detection/abort, log, commit diff); onboarding steps 2–3 (GitHub, workspace);
  Repositories screen (filters, search, create dialog, clone progress/cancel, fork, open, delete
  local copy); Changes panel; Settings GitHub section with client ID override; Room v2 with
  auto-migration (local and cached remote repos).

- **Phase 4:** tool framework (prepare/apply, schema DSL, `ToolRegistryExecutor` with parallel reads,
  ordered writes and serialised prompts, output truncation); 21 tools (read_file, write_file,
  edit_file, multi_edit, list_dir, glob, grep, delete_file, move_file, git_status/diff/log/add/commit/
  branch/checkout/merge/pull/push, todo_write, submit_plan); `PermissionPolicy` (Ask / Auto-accept
  edits / Plan / Full auto, always-confirm set: force push, branch delete, merge into default branch);
  `SecretGuard` (denylist + .gitignore via JGit IgnoreNode); workspaces (cloned repo or SAF folder,
  path sandbox incl. symlinks); checkpoints (Room v3 + files) with newest-first restore; approval UI
  (diff sheet Accept/Reject/Edit, dangerous confirmations naming the target, secret prompt, plan
  sheet with mode choice); chat tool cards, todo panel, mode menu with Full-auto opt-in, workspace
  chip; `AgentService` (dataSync FGS, Stop action, onTimeout); notification permission on first send.

- **Phase 5:** file explorer (lazy tree, works for repos and SAF folders); Sora Editor 0.24.7 with
  TextMate highlighting (Kotlin, Java, Groovy/Gradle, JSON, XML, YAML, Markdown, Shell, Python), tabs
  with dirty markers, undo/redo, find/replace (case, regex), save, read-only for large files, reload
  when the agent changed a clean file; Code tab with Files / Changes / History / Branches; full-screen
  diff viewer (unified or side by side); discard changes; branch create/switch/delete/merge with
  confirmations (delete, merge into default branch) and abort merge; tablet two-pane (explorer +
  editor; chat pane beside every destination from 840 dp). Instrumented test edits and saves a file
  in Sora on API 26 and 36.

- **Phase 6:** GitHub Actions client (workflows, dispatch with `workflow_run_id`, runs by branch/
  head_sha/event, jobs and steps, job logs and artifact zips through 302 without the token, cancel,
  re-run failed jobs, releases with asset upload, repository secrets, contents API); libsodium-compatible
  sealed box (Bouncy Castle, checked against a PyNaCl vector); workflow template `gcode-build.yml`
  (debug APK artifact on push/PR/dispatch, optional stable debug key, signed release on `v*` tags) with
  Android-project detection and "Set up builds" (commit + push, confirmed); Build button (commit dirty
  changes, push, follow the push-triggered run for the commit or dispatch); live run screen (5 s
  polling, jobs/steps, cancel, re-run failed, open on GitHub); log viewer split by step with failed
  steps opened and error lines in red; artifacts (download with progress, APK extraction, 410 →
  expired, label from the APK); install via `PackageInstaller` (always confirmed, unknown-sources flow,
  signature-mismatch hint); build history in Room v4; `BuildMonitorWorker` (Hilt WorkManager, re-enqueues
  every 30 s, completion notification that opens the run); secrets sheet (names, set secret through a
  sealed box, link to GitHub settings) and the stable debug key (generated on the device, saved as
  `DEBUG_KEYSTORE_BASE64`, always confirmed); workflows with `workflow_dispatch` inputs for any project.

- **Phase 7:** pull request and issue APIs (list/get/create/merge/files/state, issues without PRs,
  update with state_reason, comments); 21 agent tools: gh_list_repos, gh_search_repos, gh_create_repo
  (public = always confirmed), gh_clone_repo (opens it as the workspace mid-turn), gh_fork_repo,
  gh_get_file (denylisted paths need the secret prompt), gh_create_pr, gh_list_prs, gh_merge_pr (into
  the default branch = always confirmed; waits for mergeability; merges the reviewed head SHA),
  gh_comment, gh_list_issues (or one issue with comments), gh_create_issue, gh_setup_build_workflow,
  gh_trigger_workflow (push + find/dispatch, inputs), gh_get_run_status, gh_wait_for_run, gh_get_run_logs
  (failed steps only), gh_list_artifacts, gh_download_apk, gh_install_apk (always confirmed),
  gh_create_release (optional APK asset); web_fetch (jsoup, private-network guard) and web_search
  (Google Search grounding with sources); build-fix guard (per-repo limit, default 3) with guidance in
  the system prompt.

- **Phase 8:** Pull requests screen (open/closed/all, detail with Markdown body, mergeability, files
  with per-file diffs, comments, comment, merge with method choice (always confirmed, stronger
  warning into the default branch), close/reopen, create from branch pickers); Issues screen (filter,
  detail with comments, comment, close as completed / not planned, reopen, create with labels);
  Releases screen (list with assets, create tag + release with notes/generated notes/draft/pre-release
  and an APK from the build history, upload an APK to an existing release); merge conflict resolver
  (file chips, per-hunk Ours / Theirs / Both / Edit / Ask AI with review, base shown for diff3,
  whole-file side for binary or delete/modify conflicts, save and stage, commit or abort the merge);
  Secrets screen under More; per-repository settings from the Code tab (author, pull rebase, default
  mode for new chats, build workflow, build-fix limit). Merge commits now use the branch name.

- **Phase 9:** context compaction (estimate + countTokens near the threshold, auto before model calls
  at turn boundaries, manual Compact in the chat menu and /compact; summary from a transcript; the
  model sees the summary first; marker in the chat); Sessions screen (search, rename, delete, resume,
  new chat; chat top bar opens it); Rewind (long-press a message: code, conversation or both, with
  the list of restored files and of remote/Git actions that are not undone); memory (global
  instructions in Settings + the repository's GEMINI.md; Init action and /init); slash commands
  (built-ins plus .gcode/commands with $ARGUMENTS/$1…$9, suggestions while typing, /help); `task`
  sub-agents (general + .gcode/agents with frontmatter tools/model); MCP client (2026-07-28 with
  2025-11-25 fallback, SSE responses, sessions, version negotiation) with an MCP servers screen (add,
  edit, trust, enable, test, delete; credential in the SecretStore) and tools in the agent's
  registry; image attachments (photo picker, downscaled) and voice input (system recogniser);
  Settings: global instructions, default mode, max steps, extra protected files.

- **Phase 10:** README (features, install, setup, building, signed releases, CI, layout) and
  docs/TESTING.md (automated tests and a manual checklist); minified `qa` build type (R8 + resource
  shrinking, debug-signed) installed and launched on both emulators by
  `scripts/release-launch-check.sh`; signed release path verified locally with a throwaway keystore
  through the release job's environment (apksigner V2 signature OK); JaCoCo coverage for the JVM
  modules with a summary in the CI job summary and HTML reports as `coverage-report`; screenshots
  captured by the instrumented tests (API 36) for the README; plurals for count strings (Arabic's six
  forms); window-size based two-pane layouts; CI resilience (Google's Maven Central mirror first,
  test dependencies cached by the build job, pre-built emulator APKs with retries).

## Known limitations

- Only the repository's root GEMINI.md is loaded (no per-folder memory files).
- MCP: tools only (no resources, prompts, sampling or elicitation); servers that need OAuth must be
  given a token in the auth header field.
- PR review comments (line comments) are not shown; the PR thread shows issue-style comments.
- Dispatch inputs are read from the workflow file in the local clone (names only, no type-specific
  widgets); workflows that exist only on GitHub are dispatched without inputs.
- JGit 5.13 has no shallow clone; "only the default branch" is offered instead.
- The first tagged release has not run yet: it needs the owner's signing secrets (see blockers).

## Blockers on the repository owner

1. **Signed releases:** add the four repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD` (docs/SIGNING.md §2), then push a tag such as `v1.0.0`. Until then the
   **Signed release** job is skipped (no tag) or stops at "Check signing secrets" with a clear error.
   Everything else works without it.
2. (Already done by the owner: the OAuth App with Device Flow and the `GCODE_GITHUB_CLIENT_ID`
   variable.)

## Definition of Done

Checked against the code on the final branch. Status: **Done**, **Partial** or **Blocked**.

| Item | Status | Where |
|---|---|---|
| Gemini: encrypted key storage, dynamic model discovery, `gemini-4-hydrogen` default with fallback, model picker, thinking levels, 429 backoff and auto-switch, streaming, function calling, token tracking, auto and manual compaction | Done | `:gemini`, `core/data` SecretStore, `ModelPickerSheet`, `ConversationCompactor` |
| Agent: tool loop, parallel tool calls, todo list, sub-agents, autonomous build-fix loop with attempt limit, Stop | Done | `AgentLoop`, `ToolRegistryExecutor`, `TaskTool`, `BuildFixGuard`, `AgentService` |
| Permissions: Ask / Auto-accept edits / Plan / Full auto; mandatory confirmations (repo/branch deletion, force push, merge into default branch, public repo, secrets, APK install); diff approval sheet; secrets denylist; checkpoints and Rewind | Done | `PermissionPolicy`, `ApprovalHost`, `SecretGuard`, `RewindService` |
| File tools and workspace (SAF and internal storage), code editor with tabs, search, undo/redo, file explorer | Done | `FileTools`, `SafWorkspaceFs`, `CodeScreen` (Sora Editor) |
| GitHub: device flow + PAT; list/search/create/clone/fork repos; status, diff, stage, commit, push, pull, fetch; branches, merge, conflict resolver; history; PRs; issues; releases | Done | `:github`, `:git`, Repos/Code/PR/Issues/Releases screens |
| Remote builds: workflow template setup, trigger, live status, logs with highlighted failures, APK download/extraction/install, build history, WorkManager notifications, encrypted secrets API | Done | `BuildService`, `BuildMonitorWorker`, `ApkInstaller`, `BuildsScreen`, `SealedBox` |
| All specified agent tools exposed to Gemini as functions | Done | 21 core + 21 GitHub/Actions + web_fetch, web_search, task (+ MCP tools) |
| GEMINI.md memory and Init action, per-repo and global settings, sessions with resume | Done | `WorkspaceExtensions`, `RepoSettingsDialog`, `SettingsScreen`, `SessionsScreen` |
| MCP client (streamable HTTP), custom slash commands, custom sub-agents | Done | `:mcp`, `SlashCommands`, `AgentDefinition` |
| UI: all specified screens, dark and light themes, tablet split-pane, Arabic RTL | Done | `ui/*`, `values-ar`, `isWideLayout` |
| Open-source licences screen, THIRD_PARTY_NOTICES, Apache-2.0 LICENSE | Done | `LicensesScreen`, `THIRD_PARTY_NOTICES.md` (checked in CI), `LICENSE` |
| Signed release workflow on version tags; README with installation and setup | Done (workflow and README); first tagged run **Blocked** on the owner's signing secrets | `android.yml` release job, `README.md`, `docs/SIGNING.md` |
| Unit tests for tools, permissions, model fallback and the GitHub client; instrumented UI smoke test passing on PRs and main | Done | 177 unit tests; instrumented tests on API 26 and 36 for every PR (main run linked below) |

## Next step

All ten phases are merged. The project is complete apart from the owner's action above (signing
secrets, then a `v*` tag for the first signed release). For further work, start from `main`, keep one
PR per change from `ccr-ee862235-7b5f5g`, and keep this file and docs/DECISIONS.md current.
