# AGENTS.md — SOTAware Construct

Standing repository instructions | Astra Max / Luna flat orchestration
Policy revision: 2026-09-04

This is a complete root-file replacement, not an appendix to an older
agent hierarchy. It governs work in this repository without authorizing
unrequested product changes. Current task-specific acceptance and safety
contracts remain in force. Keep current status in the project roadmap/log,
not in this operating manual.

## Default operating model — Astra Max with Luna workers

```text
ASTRA — MAX reasoning — single root owner and hands-on orchestrator
    +-- LUNA — bounded investigation / implementation / test / review
    +-- LUNA — independent work only when it adds useful parallelism
    `-- ... no more than 10 concurrently open Luna subagent threads
```

This is the default workflow, not an opt-in bureaucracy mode. There is no
Superintendent, Foreman, mandatory three-investigator panel, or mandatory second
Inspector. Old role names in historical reports do not reactivate an old
hierarchy. A current explicit user instruction can change the workflow.

- Astra owns scope, architecture, assignments, integration, acceptance, and the
  final answer. It may inspect code, implement, debug, test, and repair directly.
  Delegation is not a prohibition on the root doing useful engineering.
- Use Luna for every spawned worker, including reviewers and specialists.
  Default to MAX reasoning and NORMAL speed. Do not select Ultra or Fast for
  workers unless the current user task explicitly changes that requirement.
- The limit is 10 simultaneously open child threads per root session, excluding
  Astra. Count working, waiting, and completed-but-not-closed workers until the
  runtime releases their slots. A lower runtime limit always wins. Ten is a
  ceiling, not a quota; small tasks may need no workers or only one reviewer.
- Workers are leaves: no child agents, nested managers, recursive delegation,
  or side-channel spawning. A worker needing help sends its question to Astra.
- Astra may parallelize independent work inside the authorized task. Do not
  distribute unresolved shared architecture across competing implementations.
  Do not spin up ten redundant investigations because ten slots exist.

### Runtime settings are real settings, not claims in a document

Start the root using Astra with MAX reasoning. Request Luna and its reasoning
explicitly through the available supported spawn/configuration interface rather
than letting workers accidentally inherit Astra. Use the runtime's real model
identifiers and schemas; do not invent parameter names or capability checks.

This file does not itself switch the running model, enable delegation, increase
thread capacity, or alter global configuration. Missing effective-model or
speed metadata is not a blocker by itself. State what was requested and what
was observable; never claim enforcement that the interface cannot verify.

Where a speed control exists, select NORMAL/default non-Fast for every worker,
regardless of root speed. Where it does not, do not deliberately select Fast,
do not invent a setting, and disclose the limitation once. Known model,
reasoning, or speed mismatches must not be silently relabeled as compliant.
Do not repeatedly respawn workers to chase unavailable settings.

If delegation is unavailable, Astra may continue safe, authorized work directly
and report that limitation. Do not invent an independent review or declare a
required independent-review gate satisfied. A review from an available
non-Luna model is useful evidence when permitted, but it does not satisfy a
Luna-specific gate and must be reported as such. Do not silently substitute
another worker model or edit the user's global agent configuration to bypass a
limit.

## Authority, orientation, and scope

Follow platform/system/developer instructions and actual runtime precedence.
Within repository guidance, the current explicit user task takes precedence,
then applicable more-specific instruction files, then this root manual, then
standing project documentation. Read applicable `AGENTS.override.md`, nested
`AGENTS.md`, and relevant project guidance; never assume this file overrides a
higher-priority instruction. Flag an actual conflict rather than following two
incompatible hierarchies or silently rewriting an unrelated instruction file.

Before substantive edits:

1. Confirm the repository, working directory, branch, HEAD, tracked changes,
   and pre-existing untracked paths. Preserve unrelated work.
2. Read the project sources named below, relevant implementation, callers,
   tests, and recent history. Read affected sections, not every historical log.
3. Identify the authoritative state/ownership boundary and the root cause.
   Existing code is evidence of behavior; existing behavior is not automatically
   the intended contract, and a passing test does not override the user request.
4. Define the smallest coherent scope, acceptance criteria, explicit non-scope,
   and closed validation set. A short plan or existing task record is enough;
   do not create a paperwork framework for a small repair.

Use the actual local task branch, not an assumed default branch. Read current
roadmap status rather than freezing a phase number or old blocker into this
manual. If documentation disagrees with code, resolve the relevant discrepancy
using implementation, tests, history, and the requested contract. A renamed
navigation document is not automatically a product blocker; a missing required
acceptance contract may be.

Work only within the requested boundary. A request for an entire phase or
explicit sequence authorizes its eligible substeps without another permission
request after each small fix; respect dependencies and required gates. It does
not authorize the next unrequested phase, unrelated backlog, or a rewrite.

Prefer surgical root-cause fixes, existing adapters/services/registries, and
small testable changes. Preserve working behavior and current-format integrity. No
opportunistic dependency upgrades, framework migrations, formatting churn,
duplicate state owners, or broad god-class decomposition. Do not impose a
fake two-file limit when a correct repair genuinely requires more files.

## Delegation, ownership, and independent review

Give each worker one compact, self-contained assignment: objective, expected
behavior, relevant files/contracts, read/write permission, exclusive write
scope, dependencies, required checks, and requested evidence. Provide only
necessary context; use the smallest supported history fork. Reviewers receive
the original requirement and candidate, not a transcript telling them why the
author believes the patch is correct.

A small in-session ownership map is sufficient. Record worker ID, purpose,
owned files or read-only scope, state, and any owned processes/temp locations.
Do not build a permanent delegation database or transcript archive.

- One active writer per file, including Astra. Different files can still share
  an invariant; agree on interfaces and integration order before parallel edits.
- Shared models, schema, core state, build/configuration, generated outputs, and
  integration files need one explicit owner. Other workers propose changes to
  that owner instead of editing concurrently.
- Complete and reconcile prerequisite investigations before dependent coding.
  Independent work need not wait for an unrelated investigation to finish.
- Test workers and reviewers are read-only with respect to source, fixtures,
  goldens, and configuration unless separately reassigned to implementation.
  Their test runs may create only scoped, disposable execution artifacts.
- Workers do not stage, commit, merge, cherry-pick, push, or send external
  notifications. Astra owns authorized integration/publication. Never treat
  another session's simultaneous edit as something to overwrite or discard.
- If isolated worktrees are used, record each base and patch/commit identity.
  Astra reviews and integrates them explicitly; never assume separate worktrees
  share uncommitted edits.

For a nontrivial production change, or any persistence, money calculation,
identity, synchronization, security, or resource-lifecycle change, obtain one
fresh Luna read-only review of the integrated candidate. The reviewer must not
have authored the implementation or its test oracle. Review includes the real
diff, relevant call paths, acceptance checks, and focused independent validation
where feasible. A documentation-only or genuinely trivial change does not
require a ceremonial worker panel.

Astra inspects the integrated result and adjudicates findings using evidence.
An extra independent Luna specialist is optional for a concrete unresolved risk,
not a mandatory Inspector role. If Astra or a worker repairs a reviewed area,
rerun affected checks and obtain a targeted independent delta review. Do not
reuse a pre-repair PASS for changed behavior or restart an unrelated full audit.

## Waiting, updates, and resource use

When useful non-overlapping root work remains, Astra may do it. Otherwise use
the supported long blocking agent wait, not busy polling. Prefer the configured
wait; when an explicit duration is needed, use the longest supported practical
wait, up to 3,600,000 ms when supported. Do not invent an unsupported one-hour
argument. A platform-imposed shorter timeout simply means wait again silently.

No periodic "still working," "no updates," worker-list polling, status requests,
or minute-by-minute heartbeat narration. Send a brief initial plan, material
findings or decisions, genuine blockers, and the final result. An empty wake-up
is not progress. Do not interrupt or kill a worker because it is quiet or slow.
Agent-wait duration and subprocess/test timeouts are separate controls.

Reuse a worker only for a known immediate follow-up compatible with its role.
Once its result and resource ownership are reconciled, close it. Do not retain
idle workers just in case or create duplicates of active assignments.

Use one expensive full build/test matrix per worktree at a time. Give each
browser session, GUI, emulator, device, port, and mutable test resource one
owner. Reduce concurrency if memory, CPU, disk, shared tooling, or desktop
responsiveness suffers. Ten workers are not permission for ten simultaneous
Gradle builds, browser farms, or recursive repository copies.

## Validation, failure handling, and closure

Define required gates before implementation from the user task, current
roadmap, project policy below, and directly affected invariants. Run focused
checks during development, then the applicable complete gates against the
final integrated candidate. Reuse evidence only when the relevant candidate,
inputs, environment, and toolchain are unchanged, and identify that reuse.
A documentation-only correction does not require rebuilding unchanged code.

For each result record the command or interaction, exit/result, relevant
output/evidence, and candidate identity (SHA plus uncommitted diff when needed).
Use `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`, or `NOT APPLICABLE` accurately.
Missing dependencies, no collected tests, disabled smoke paths, skipped required
cases, timeouts, unauthorized devices, and missing logs are not PASS.

Do not weaken assertions, suppress errors, bless goldens blindly, fake UI
interaction, bypass the actual execution path, or substitute a nearby easier
check for a required gate. An intentional contract/oracle change requires
explicit task scope and reviewed expectations. Separate implementation tests,
manual behavior evidence, and verifier/tooling certification.

Classify findings by both impact and cause:

- `BLOCKER`: breaks current acceptance, correctness, data/electrical integrity,
  an established invariant, a relevant regression, or a required gate.
- `FOLLOW-UP`: a real issue that does not invalidate the requested result.
- `BACKLOG`: an optional improvement outside scope.

Cause is `CODE`, `TEST/TOOL`, `ENVIRONMENT`, or `USER DECISION`. A broken required
harness can block qualification without proving the product broken. Optional
harness flakiness is not automatically a reason to reopen product code.
Record nonblocking findings; only genuine blockers reopen implementation.

After two identical environment/tool failures, stop rerunning the unchanged
full command. Preserve the signature, diagnose a specific cause, change or
verify a relevant condition, try a bounded equivalent method where permitted,
or report the remaining prerequisite. Continue while a safe evidence-based
repair path exists; neither elapsed time nor an arbitrary retry quota decides
correctness. Do not stack speculative patches or call repeated output progress.

Do not expand the blocking finish line indefinitely. New evidence of a real
correctness/integrity defect may add a necessary regression; aesthetic cleanup
or hypothetical perfection may not. Once acceptance, applicable gates, review,
and final reconciliation pass, complete the authorized handoff/publication and
stop at the requested boundary.

## Git, user data, processes, and scratch safety

Never discard unrelated edits, untracked files, private project data, or another
agent's work. No broad `git clean`, `git reset --hard`, destructive restore,
force-push, history rewriting, branch deletion, or mass filesystem cleanup
without explicit authorization for the exact operation and targets. Stage
explicit intended paths only, and inspect staged and unstaged changes separately.

Do not expose or commit credentials, tokens, private documents/inventory,
absolute personal paths, raw user payloads, or uncontrolled logs. Use sanitized
fixtures. Do not escalate privileges, change authentication, weaken sandboxing,
or modify global runtime settings just to turn a failure green.

Prefer bounded commands that exit naturally. Do not leave task-only shells,
servers, watchers, browsers, test runners, or worker threads alive. For a needed
persistent process, record ownership at launch using the available process
handle/job/group or PID plus creation time, executable, and task-specific
profile/port. Revalidate identity before terminating it; a process name or PID
alone is not sufficient. Respect stricter task-specific cleanup contracts.

Never kill all Edge, Chrome, Node, Python, Java, PowerShell, or IDE processes.
If ownership cannot be established, leave that resource alone, record it, and
use safe isolation where possible. An uncertain old process can block its
cleanup or a particular isolation gate; it does not automatically prohibit all
unrelated useful work. Do not claim a clean-state test when it was not proven.

Substantial scratch trees, temporary repository copies, and pytest `--basetemp`
must use unique, task-owned OS-temp subdirectories outside the repository.
Never pass the shared OS-temp root itself as a destructive test base. Verify
resolved containment and ownership before cleanup, including symlink/reparse
boundaries. Exclude `.git`, caches, nested scratch, build output, and private
data from temporary source copies unless a small specific fixture needs them.
Do not recursively copy a repository into itself or run per-file Git commands
over generated scratch. Unexpected tree/process growth is a reason to stop the
run and diagnose it, not launch more workers or hide it with `.gitignore`.

Normal build outputs may remain in their documented build directories; the
external-scratch rule is not permission to relocate or delete required build
assets. Clean only verified task-owned disposable resources. Before a pause or
context handoff, save one compact checkpoint with HEAD/candidate, diff scope,
pre-existing changes, worker/resource ownership, exact gate results, blockers,
and the next action. Revalidate that state when resuming.

## SOTAware Construct — project direction and sources

SOTAware Construct is an Android/Kotlin construction-document application with
PDF viewing, annotation, measurements, photos, OCR/search, local persistence,
import/export, and Drive-related state. User documents and their associations
are durable data, not disposable caches.

Read the current relevant sections of
`SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md`,
`CODEX_AUDIT_ROADMAP.md`, and `CODEX_AUDIT_IMPLEMENTATION_LOG.md`, plus applicable
README/architecture/development guidance, Gradle files/version catalog, tests,
fixtures, and `.github/workflows/android.yml`. Follow the real task branch and
current status. Do not infer which stages are implemented from an old manual.

Known navigation points include the application package under
`app/src/main/java/com/example/myapplication/`, `MainActivity.kt`, the staged
snapshot/identity/repository/session seams, `DriveSyncManager.kt`, `OcrIndex.kt`,
`PdfSearchEngine.kt`, `PdfBitmapRenderer.kt`, and Compose UI components. Locate
the current owner if a symbol moved; do not recreate a competing legacy path.
Do not decompose `MainActivity.kt` or migrate frameworks as collateral cleanup.

## SOTAware Construct — state, identity, and persistence

- **One complete snapshot boundary.** Use the canonical typed document snapshot
  and capture/apply owners (including `snapshotFromState()` and
  `applySnapshotReplace()` where current). Compatibility adapters translate;
  they must not establish another independent capture authority.
- **Full replacement means replacement.** Validate and materialize incoming
  state before mutation. Absent pages/domains must not leave stale annotations
  behind. Preserve every persisted domain, including paths, measurements,
  notes, scale, photo pins, page shapes, image notes, and image shapes.
- **Document identity is not a filename.** Preserve app-generated document ID,
  exact source association, fingerprint/revision, and session generation.
  Display names, URI hashes, content hashes, or folder names are not substitutes
  for the canonical identity. Same-name PDFs must remain independent.
- **The local repository is durable authority.** Preserve validation, atomic
  replacement, previous-good recovery, quarantine, typed failures, and
  per-document write serialization. Do not turn corrupt, missing, mismatched,
  or unavailable state into a successful empty document.
- **Current-format policy (Stage 9B user direction).** Backward compatibility
  is not required. Retire obsolete formats and migration paths only as the
  requested current-format cutover is implemented. Reject unsupported older
  inputs explicitly without modifying or deleting them or the current document.
  Preserve current-format atomic publication, previous-good recovery, quarantine,
  journals, rollback, source associations and retained photo ownership. These
  correctness guarantees are not legacy compatibility. Older historical
  migration/serialization requirements do not override this user direction.
- **Document switching is transactional.** Use the established session owner
  and document-switch coordinator. Capture/freeze and flush the complete
  outgoing snapshot when required. Keep a provisional target noneditable;
  cancellation or setup/load failure preserves the last committed session.
- **Late work must not cross identities.** Check document/source/session/page/
  query revisions before applying asynchronous results. Cancel and join owned
  work when completion matters; propagate `CancellationException`. Clear or
  rekey document-sensitive dialogs, selection, search highlights, photos,
  measurement state, and caches at the appropriate boundary.

## SOTAware Construct — sync, payloads, and import/export

Inspect every current manual, debounced, periodic, import, restore, and lifecycle
route before changing synchronization. All routes must use the canonical state
and current serialized coordinator/conflict policy. Do not assume a planned
coordinator exists, and do not retain a competing legacy route if it was replaced.

Conflicts must block all affected write routes. Validate remote payloads and
all required components before authoritative replacement. Do not advance a
remote cursor/revision or show success after a partial transfer, stale-generation
result, failed durable save, or incomplete in-memory apply. Use stable Drive
IDs and correctly scoped account/root/document metadata; read/conflict checks
must not create folders or mutate remote state as a side effect. Preserve
pagination, cancellation, and read-only error handling.

Treat Drive, bundles, imports, and legacy JSON as untrusted input. Preserve
schema versions, required fields, enum and finite-number validation, and bounded
page/annotation/photo/file/image counts and sizes. Generate safe internal photo
IDs/filenames; verify resolved path containment, reject traversal/absolute
paths, and escape query values or use IDs rather than interpolating names.

Stage transfers in task-owned temporary locations, validate required bytes,
hashes/decodability and associations, then publish atomically. Required photo or
payload failure fails the enclosing operation. Keep last-known-good data and
clean only owned partial output. Bundle handling must reject zip-slip, expansion
bombs, malformed manifests, unsupported versions, and incomplete required data.

A `.sotaware` bundle must be current, self-contained, and round-trip all supported
annotation/photo/shape/scale domains through the actual import/export path.
Successful stream creation or ZIP assembly alone is not successful completion.
Preserve Android Storage Access Framework behavior, permissions, cancellation,
truthful stream completion, durable local apply, and full state replacement.

For an import/export qualification task requiring a fresh-install round trip,
exercise the actual app/SAF flow on an authorized test device or emulator with
synthetic fixtures. JVM serialization tests alone do not satisfy that gate.
Do not wipe an installed app, account, device, or user data without explicit
permission; use an isolated test installation or report the missing prerequisite.
Never use real Drive files as disposable integration fixtures.

## SOTAware Construct — rendering, OCR, search, and UI

Keep costly PDF I/O, bitmap decoding/rendering, image transforms, OCR, and export
off the main thread through established lifecycle-aware workers. Apply pixel
and byte/memory budgets, sampled decode, and appropriate cache bounds. Do not
assume a fixed item-count cache is memory-safe for arbitrary photos or pages.

Close owned PDF documents, streams, descriptors, ML Kit recognizers, and bitmap
resources at correct lifecycle boundaries. Do not recycle a bitmap still in
use or access it after recycle. Preserve cancellation, session isolation, and
failure states; canceled/failed OCR is not a complete index. Prevent duplicate
work from recomposition or stale jobs publishing into a new document/query.

Coordinate conversion must account for PDF media/crop boxes, rotation, origin,
zoom, and display transforms. Rendering and hit-testing use the same geometry.
Preserve cropped/rotated/scanned and large-page regression fixtures, including
`app/src/test/resources/stage0/` where current. Search must honor the requested
matching semantics and remove obsolete highlights without applying stale results.

User-visible annotation actions use the canonical state/history/save/sync path,
not a nested-collection side channel. Preserve required dirty tracking,
undo/redo, clear-page behavior, scale, photo/image annotation parity, and
rotated note/shape behavior. Verify relevant narrow portrait/landscape layouts,
accessibility semantics, system Back, dialog save/cancel, overlays, and switching.
A Compose preview alone does not prove real lifecycle or navigation behavior.

Preserve safety regressions for same-name documents, association mismatch,
retired-format rejection, recovery/quarantine, switching/cancellation,
sync conflicts, complete replacement, malformed/non-finite payloads, traversal,
high-resolution photos, and memory/resource limits when those boundaries change.

## SOTAware Construct — Android and release validation

Use the checked-in Gradle wrapper and configured JDK/Android SDK. Preflight the
actual toolchain and authorized device only for gates that need them; a listed
SDK path or an unauthorized ADB device is not a passing runtime gate.

For production or test changes, run the established applicable build/unit/lint
gates on the final integrated candidate:

```powershell
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
```

On Unix-like hosts use `./gradlew` with the same tasks. Focused development tests
are useful but do not replace the complete required gates after integration.
Do not launch several Gradle invocations against the same mutable build state
or stop unrelated Gradle processes to cure a task-owned failure.

For required Android runtime, lifecycle, SAF, rendering, or UI proof, run the
relevant instrumentation/functional test and actual device workflow:

```powershell
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
```

An installation/package-context test proves only installation/instrumentation
sanity, not functional switching, Drive sync, OCR, search, or UI behavior. Record
the relevant device/API/build and workflow evidence. When the necessary device
or credential is unavailable, report that specific gate blocked and retain the
valid JVM/build evidence without claiming full qualification.

Validate modified PowerShell, VS Code tasks, JSON/YAML, Gradle configuration,
and fixtures through their actual parser/execution path. Documentation-only
changes require consistency/diff checks, not a mandatory product rebuild.

Never delete a keystore, overwrite signing identity, wipe caches blindly, loosen
permissions, or hide an authentication defect to obtain a green build. A scoped,
documented debug-only workaround may provide limited evidence when permitted,
but label it explicitly and retain the standard build/release gate's true status.
Preserve backup, FileProvider, redacted logging, signing, package/version,
authentication, and release-policy boundaries. Do not log raw documents, tokens,
personal data, or Drive payloads as convenient diagnostics.

## SOTAware Construct — documentation and publication

When a task closes a remediation step, record its scoped implementation and
actual evidence in the appropriate roadmap/log as authorized. Preserve prior
history and distinguish implemented code from device-verified qualification.
Do not mark a stage complete using a nearby weaker test or silently advance to
an unrequested stage.

Astra stages/commits only when requested or authorized by the task workflow.
Pushes, releases, remote publication, and external completion messages require
explicit user authorization. After every authorized commit is successfully
pushed, email `dspevock@stateofthearcelectric.com` with the repository, branch,
and commit SHA plus a concise validation/result summary. Do not send a success
email before the push completes, do not claim success after a failed push, and
never include credentials, private user data, or sensitive payloads. Preserve
local work when publication is unavailable, and do not borrow another project's
notification or automatic-push policy.

## Final handoff

Inspect the actual final diff, `git diff --check`, and `git status`. When staging
is authorized, also inspect `git diff --cached` and run
`git diff --cached --check`. Confirm there are no accidental data, scratch,
generated, or unrelated changes. Do not label a previously dirty tree clean.

Keep the final report short and evidence-based. Include:

```text
STATUS: COMPLETE | IMPLEMENTED — VALIDATION BLOCKED | BLOCKED | REVIEW ONLY
SCOPE: What the user requested and what was actually done.
CHANGES: Important behavior and files; no transcript dump.
VALIDATION: Exact checks/results and candidate; identify reused evidence.
REVIEW: Independent reviewer result, or NOT RUN with the reason.
LIMITATIONS: Failed/unavailable required gates, uncertainties, and impact.
FOLLOW-UPS: Genuine nonblocking findings, or NONE.
GIT: Branch, commit/push outcome when applicable, and remaining worktree changes.
RESOURCES: Unresolved owned processes/temp resources, or NONE.
NEXT: Only a necessary unblock action or the next unstarted roadmap item.
```

For review-only tasks, report findings rather than making unrequested edits.
For incomplete work, identify the exact missing proof and preserve the useful
changes. Never claim a test, review, commit, push, cleanup, or notification that
was not actually performed. Stop when the requested work is finished.
