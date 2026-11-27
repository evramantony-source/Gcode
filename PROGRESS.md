# GCode progress

This file lets a new session resume without other context. It is updated after every phase.

- Repository: `evramantony-source/Gcode`. Work branch: `ccr-ee862235-7b5f5g` (the only branch this
  agent may push to). Each phase is one PR from that branch into `main`; after a merge the branch
  is reset to `main` (fast-forward).
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
| 3 GitHub sign-in, repos, JGit | In review | [#3](https://github.com/evramantony-source/Gcode/pull/3) | — |
| 4 Workspace tools, agent loop, permissions | Not started | | |
| 5 Editor, explorer, source control UI | Not started | | |
| 6 Remote build pipeline | Not started | | |
| 7 Agent GitHub/Actions tools, build-fix loop | Not started | | |
| 8 PRs, issues, releases, branches, conflicts | Not started | | |
| 9 Compaction, sessions, checkpoints, MCP, commands, sub-agents | Not started | | |
| 10 Release signing, polish, README, tests | Not started | | |

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

## Known limitations

- Agent tools arrive in Phase 4; Builds and PR/issue/release screens are still placeholders until Phases 6 and 8.
- JGit 5.13 has no shallow clone; "only the default branch" is offered instead.
- The release APK has not been launched on a device yet (planned in Phase 10).

## Blockers on the repository owner

- None. (Signed releases additionally need the four signing secrets from docs/SIGNING.md.)

## Next step

Wait for Phase 3 CI (now also instrumented tests on API 26 and 36), merge, reset the branch to
`main`, then start Phase 4: `WorkspaceFs` (internal + SAF), file/git tools, tool registry, parallel
execution, permission policy, diff sheet, checkpoints, AgentService.
