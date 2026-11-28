# Testing

## Automated tests

| Where | What | Runs |
|---|---|---|
| `:agent` unit tests | every tool, permission matrix per tool and mode, executor (parallel reads, ordering, approvals), agent loop with a scripted model, GitHub/Actions tools against an in-memory GitHub, build-fix guard and a full fail → fix → rebuild scenario, web tools, compaction rules, slash commands, frontmatter, sub-agents, MCP adapters | every push |
| `:gemini` unit tests | SSE parser, chunk folding that keeps thought signatures, model selection and fallback, backoff with a virtual clock, client through MockWebServer | every push |
| `:github` unit tests | device flow, pagination, ETag, rate limits, repo/PR/issue/release/Actions/secrets requests, artifact redirect without the token, sealed box (libsodium vector), log parser, workflow template | every push |
| `:git` unit tests | JGit operations on temporary repositories, diffs, conflict parsing and resolution, merges | every push |
| `:mcp` unit tests | 2026-07-28 and 2025-11-25 servers through MockWebServer (headers, `_meta`, sessions, SSE, paging, errors) | every push |
| `:core:data` unit tests | secret store, settings, Room DAOs | every push |
| `:app` unit tests | navigation back stack, chat items, diff model, build pipeline helpers, collaboration logic, sessions/compaction/rewind helpers | every push |
| Instrumented (emulator API 26 and 36) | `AppShellSmokeTest` (onboarding → chat with a scripted Gemini → tool card → slash commands → every tab → licences, PRs, issues, releases, secrets, sessions, settings, MCP), `CodeEditorTest` (Sora edit and save, source-control tabs), `ConflictResolverTest` (resolve a real conflict and commit the merge), `GitOnDeviceTest` (JGit on the device), `DatabaseMigrationTest` (Room 1 → latest) | pull requests and `main` |
| Release launch check | the minified (R8) `qa` build is installed and launched on the API 36 emulator; the job fails on a crash | pull requests and `main` |

Coverage: `./gradlew jacocoTestReport` for the JVM modules, then `python3 scripts/coverage_summary.py`.
CI prints the table in the job summary and uploads the HTML reports as `coverage-report`.

Run everything locally:

```sh
export GCODE_GITHUB_CLIENT_ID=<any 8+ character id>
./gradlew lint test assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest   # needs a device or emulator
```

## Manual checklist

Use a debug APK from CI on a real phone. Tick each item.

### Onboarding and Gemini
- [ ] First launch shows onboarding; a wrong key is rejected; a valid key is accepted.
- [ ] The model picker lists the models of your key; the default is `gemini-4-hydrogen` or the
      fallback reason is shown.
- [ ] Changing the thinking level and "show thoughts" changes the next answer.
- [ ] A long answer streams; Stop ends it and keeps the partial text.

### Agent and permissions
- [ ] In **Ask** mode an edit shows the diff sheet; Reject, Edit and Accept behave as labelled.
- [ ] **Plan** mode only reads and ends with a plan to approve.
- [ ] **Full auto** asks for opt-in with the warning; a force push or a merge into the default
      branch still asks for confirmation.
- [ ] Asking the agent to read `.env` triggers the protected-file prompt.
- [ ] The todo list updates while the agent works.
- [ ] A long conversation shows "compacted" after Compact (menu), and the agent still knows the goal.
- [ ] Long-press a message → Rewind restores the files and/or the conversation; remote actions are
      listed as not undone.
- [ ] `/init` writes `GEMINI.md`; a command in `.gcode/commands/` appears in the suggestions.
- [ ] Attach a screenshot and ask about it; use the microphone button to dictate.

### GitHub
- [ ] Device-flow sign-in works; a PAT works as fallback.
- [ ] Clone a repository with progress; open, edit, commit and push from **Code**.
- [ ] Create a branch, merge it with a conflict, resolve with Ours / Theirs / Ask AI, commit.
- [ ] Open, comment on and merge a pull request (confirmation into the default branch).
- [ ] Create an issue, comment, close as not planned, reopen.
- [ ] Create a release and attach an APK from the build history.

### Builds
- [ ] **Set up builds** on an Android repository commits the workflow and a run starts.
- [ ] **Build** follows the run live; a failing step is highlighted in the logs.
- [ ] Download the artifact and install it (confirmation and the Android installer).
- [ ] Leave the app during a build: a notification arrives when it finishes and opens the run.
- [ ] Create the stable debug key; the next build updates the installed app.
- [ ] Ask the agent to "build and fix until it passes" on a repository with a compile error.

### UI
- [ ] Light and dark themes; dynamic colour on Android 12+.
- [ ] Arabic: the layout mirrors, code and logs stay left-to-right.
- [ ] Tablet or unfolded foldable: chat on the left, the other screens on the right.
- [ ] Largest font size: no clipped buttons in chat, builds and settings.
- [ ] TalkBack reads the navigation items, the send/stop/attach/voice buttons and tool cards.
