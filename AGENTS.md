# AGENTS.md — SOTAware Construct

Repository guidance, revised 2026-09-12. Current user instructions take precedence.

## Working approach

- **Subagents are optional.** You may implement, test, and review work yourself.
  Delegate only when useful; neither delegation nor independent subagent review
  is mandatory unless the user explicitly requires it for the current task.
  Historical role names and review procedures do not reinstate mandatory workers.
- If delegating, prefer Luna with MAX reasoning and normal speed. Give each leaf
  worker a compact assignment and exclusive file ownership; retain integration
  and publication responsibility. Do not run competing writers or Gradle matrices.
- Check the actual branch, HEAD, tracked edits, and untracked files before edits.
  Preserve unrelated work. Follow applicable nested instructions.
- Fix the requested root cause through existing owners and adapters. Avoid
  unrelated refactors, dependency upgrades, framework migrations, or stage changes.
  Complete authorized sequences without asking again at each routine step.
- Read `CODEX_AUDIT_ROADMAP.md` for current status, then only relevant sections of
  `SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md`,
  `CODEX_AUDIT_IMPLEMENTATION_LOG.md`, and applicable contracts. Consult callers,
  tests, Gradle configuration, and `.github/workflows/android.yml` as needed.
  Keep task status and evidence in the roadmap/log, not this file.

## Project invariants

Android/Kotlin application; main sources are under
`app/src/main/java/com/example/myapplication/`. User documents and their
associations are durable data.

- Use the canonical typed snapshot and capture/apply owners. Validate incoming
  state before mutation; full replacement clears absent pages/domains and
  preserves paths, measurements, notes, scale, photo pins, and PDF/image shapes.
- Preserve app-generated document ID, exact source association, fingerprint,
  revision, and session generation. Same-name PDFs remain independent.
- The local repository is authoritative. Preserve atomic publication,
  previous-good recovery, quarantine, journals, rollback, and per-document write
  serialization. Corrupt, missing accepted, mismatched, or unavailable state
  must never become a successful empty document.
- Current formats only: backward compatibility is not required. Reject retired
  inputs explicitly without altering their bytes or the current document.
  Recovery and retained photo ownership are correctness guarantees to preserve.
- Switching is transactional: freeze/flush outgoing state as required, keep
  provisional targets noneditable, and preserve the committed session on failure.
  Fence asynchronous results by document/source/session/page/query revision;
  propagate cancellation and cancel/join owned work when completion matters.
- All sync routes use the canonical state and serialized coordinator. Conflicts
  block affected writes. Validate every required component before replacement;
  do not advance cursors or report success after partial/stale/undurable results.
  Use scoped account/root/document identities and stable Drive IDs. Discovery
  and conflict reads must not create folders; preserve pagination and cancellation.
- Treat imports, bundles, and remote payloads as untrusted. Enforce schema,
  finite-number, count, byte, image, and path limits. Reject traversal, zip-slip,
  expansion bombs, and incomplete required assets. Stage, validate, then publish
  atomically; clean only owned partial output.
- `.sotaware` bundles must be self-contained and round-trip every supported
  domain through actual import/export. Preserve SAF permissions, cancellation,
  stream completion, durable apply, and complete replacement.
- Keep PDF/image/OCR/export work off the main thread with byte/pixel budgets.
  Close owned resources, avoid recycling in-use bitmaps, and never mark failed
  OCR complete. Rendering and hit-testing share crop/rotation/zoom geometry.
- Annotation actions use canonical history/dirty/save/sync paths. Preserve
  undo/redo, clear-page behavior, photo/image parity, selection isolation,
  dialog save/cancel, Back, and relevant portrait/landscape accessibility.

## Working speed (Dave, 2026-11-20)

- Do not pass `--no-daemon` to Gradle; reuse the warm Gradle daemon between runs.
- Batch independent work: when you need several files read, several searches, or several
  related edits, issue them together in one step (parallel tool calls) instead of one per step.
  Use the edit tool for code changes rather than ad-hoc find/replace scripts.
- Every step costs about 6 seconds before any tool runs, so a step that only reads one file is the most
  expensive way to look around. Before you look, decide everything you need for the next decision and get it
  in ONE step. Two ways:
  - Several tool calls in one reply. Example: instead of read A, then read B, then grep C over three steps,
    send all three in the same reply: read `PdfPageRenderer.kt` offset=1590 limit=90, read `SubDrawingModels.kt`,
    grep `selectedPhotoPin`.
  - One `.\tools\look.ps1` call, which prints many files, line ranges and searches at once:
    `.\tools\look.ps1 PdfPageRenderer.kt:1590-1680 SubDrawingModels.kt "grep:selectedPhotoPin" "grep:fun resize@stage8"`
    (a bare file name is found under app/src; `File.kt:120` = 80 lines from 120; run it with no arguments for help).
  The same goes for edits: when you already know several changes, make them in one reply.

## Checking screens on the emulator (Dave, 2026-11-20)
Unit tests can't see the screen: earlier rounds passed every test while buttons were drawn under the drawing,
bars covered other controls and a popup never appeared. So for any change you can see or touch, look at it yourself
on the Android emulator before you finish, using `.\tools\emu.ps1` (run it with no arguments for the command list):
1. After the gate passes: `.\tools\emu.ps1 install` then `.\tools\emu.ps1 launch` (start it first with
   `.\tools\emu.ps1 start` if it says the emulator is not running).
2. Go to the screen you changed with `ui` (lists on-screen text with positions), `tap`, `hold`, `swipe`, `type`, `key`.
   Every action saves `build\emu\last.png`; open it with the read tool to see the result. `shot NAME` keeps a copy.
3. Check what the user would check: it appears, it's readable, nothing covers it or is covered by it, and tapping it
   does what it should (tap it, then look again). Test files: Downloads/fresh.pdf and blueprint.pdf.
4. In your final reply, list what you checked on the emulator and the screenshot names; say plainly what you could not
   check (two-finger pinch is not possible on this emulator).
Rules: emulator only (the script can't reach phones or tablets); never sign in to any account - if a Google sign-in or
Play screen appears, press `key back`; don't clear app data or delete the test PDFs; don't change emulator settings
beyond what the script does.

## Validation and evidence

Define applicable gates before implementation. Run focused regressions, then
required gates on the final candidate using the configured JDK/SDK:

```powershell
.\gradlew.bat --stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Use `./gradlew` on Unix. Add `:app:assembleDebugAndroidTest` and relevant native
workflows when runtime behavior changes. Use `:app:connectedDebugAndroidTest`
or the checked-in runner in `docs/native-qualification.md` on an explicitly
authorized disposable target. Required fresh-install bundle qualification must
exercise the actual app/SAF flow; JVM serialization or install sanity is insufficient.
Use synthetic local/live-provider fixtures, never arbitrary user documents.

Validate modified scripts/configuration through their actual parsers. Documentation
edits need consistency/diff checks, not a product rebuild. Record commands, results,
candidate SHA/diff, and device/API where relevant. Separate passes, skips, failures,
blocked and unrun gates; reuse evidence only for unchanged relevant inputs.
Do not weaken assertions, bypass execution paths, or hide failures. Diagnose repeated
environment failures before retrying; optional cleanup does not extend the finish line.

## Safety and publication

- Do not discard work, rewrite history, force-push, wipe installations/accounts,
  change signing identity, loosen permissions, or perform broad cleanup without
  explicit applicable authorization. Preserve backup/FileProvider/auth boundaries.
- Never expose or commit credentials, private documents, personal absolute paths,
  or raw payload logs. Keep substantial scratch and bulky evidence in unique
  task-owned OS-temp directories outside the repository.
- Record ownership of persistent processes/devices/temp paths. Verify identity
  and resolved containment before cleanup; never kill processes by name alone.
  Stop task-only resources when finished. Save a compact checkpoint before handoff.
- Commit/push/release only when requested or authorized by the task. Stage explicit
  intended paths; inspect staged and unstaged diffs, `git diff --check`,
  `git diff --cached --check`, and `git status`. Verify any authorized push.
- After each successful authorized push, email `dspevock@stateofthearcelectric.com`
  with repository, branch, commit SHA, and a concise validation summary.
- Update the roadmap/log with actual scoped results without advancing unrequested
  stages. Keep the final report concise: outcome, changes, validation/limitations,
  review if performed, Git state, and any unresolved resources or necessary next step.
