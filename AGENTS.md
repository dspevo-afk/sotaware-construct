# AGENTS.md — SOTAware Construct

This file is the permanent operating manual for Codex and every agent working
in the SOTAware Construct repository. It supplements the user's prompt and
the repository's implementation and test contracts; it does not authorize
work that the user did not request.

SOTAware Construct is an Android/Kotlin application for viewing PDF-based
construction documents and managing document annotations, measurements,
photos, shapes, OCR/search state, local persistence, and Google Drive-related
state. The application uses Gradle, Kotlin, Jetpack Compose, Android lifecycle
components, PDFBox, ML Kit OCR, and a mixture of newer staged seams and older
runtime models. Treat the actual source and tests as authoritative when this
summary becomes stale.

## Operating principles

### Read before editing

Before substantive work, inspect enough of the repository to understand the
requested path. At minimum, read:

1. This `AGENTS.md`.
2. The relevant README, development, architecture, contribution, and task
   documentation, if present.
3. `SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md`, the canonical audit and
   remediation plan.
4. `CODEX_AUDIT_ROADMAP.md`, which records implementation status, and
   `CODEX_AUDIT_IMPLEMENTATION_LOG.md`, which records prior evidence and
   deferred work.
5. The relevant Gradle files, version catalog, CI workflow, scripts, and
   developer-tool configuration.
6. The directly involved production files, their call paths, related tests,
   fixtures, and verification helpers.
7. Recent commits and the current worktree when they materially affect scope,
   compatibility, or the requested stage.

If a named document does not exist, note that fact and continue with the
available evidence. Do not invent architecture, gates, or historical status.
When documentation and code disagree, investigate the discrepancy and state
which evidence controls the decision.

### Keep scope bounded

- Implement the user's requested task and its necessary tests or documentation.
- For remediation work, complete one eligible roadmap stage at a time unless
  the user explicitly authorizes a sequence.
- Do not start the next stage merely because the current stage is complete.
- Do not turn a focused repair into a broad refactor, framework migration, or
  `MainActivity.kt` decomposition unless the task truly requires it.
- Keep unrelated cleanup, dependency upgrades, naming changes, and UI polish
  out of a functional repair.
- Prefer the smallest clean integration point and preserve working behavior.

### Preserve valuable state

The application is undergoing staged remediation. Existing serialized data,
document identity, local files, Drive data, annotations, photos, scales,
shapes, migration paths, and compatibility names are potentially valuable
user state.

Do not casually delete, rename, invalidate, or overwrite:

- legacy Java-serialized classes, fields, package names, or fully-qualified
  names;
- legacy local markup and scale artifacts;
- local document snapshots, previous-good snapshots, manifests, or quarantine
  evidence;
- Drive files, folders, remote data, or synchronization metadata;
- user-selected PDFs, photos, annotations, measurements, shapes, or notes;
- characterization fixtures, regression tests, or historical audit evidence.

Deletion or destructive migration requires an explicit task requirement,
evidence that it is safe, and a recovery or compatibility plan where needed.
Preserve unrelated user changes and pre-existing untracked artifacts.

### Evidence over confidence

"This should work" is not validation. A claim of PASS must be backed by the
evidence required by the task's closed validation set. Prefer, in proportion
to risk:

- deterministic unit tests and targeted regression tests;
- debug assembly and lint;
- instrumentation or emulator/device evidence when the changed behavior is
  Android- or lifecycle-dependent;
- deterministic fixture checks and failure-injection tests;
- script, JSON, YAML, and Gradle configuration parsing;
- direct inspection of the relevant call path, final diff, and worktree.

Record exact commands and results. Distinguish an unavailable device,
dependency download, credential, or CI service from an application failure.
An environmental failure is not application success, and an instrumentation
test that checks only package context is not a functional UI smoke test.

### Honest blockers and focused completion

If a required gate fails, report the failure and its evidence. Do not weaken
acceptance criteria, remove or dilute a test, suppress a failure, reinterpret
the requested behavior, or declare success because the remaining failure
looks inconvenient or unrelated.

Before declaring a task complete:

- inspect the final diff and status;
- verify that only intended files changed or were added;
- run the closed validation set;
- preserve and record meaningful deferred findings;
- state what changed, what was verified, what was unavailable, and what
  remains out of scope.

Completion of one task never authorizes autonomous wandering into another.
When the requested scope is complete, stop.

## Repository map and architectural boundaries

Read the current source before relying on this map. These are the important
boundaries present in the repository at the time this manual was written:

- `app/src/main/java/com/example/myapplication/MainActivity.kt` remains a
  large integration point for the Compose UI, document/session state, and
  legacy runtime models. Make narrow, tested changes around established seams;
  do not split it for aesthetics while persistence and lifecycle behavior are
  still being stabilized.
- `stage1/DocumentSnapshotV1.kt` and `stage1/DocumentSnapshotV1Mapper.kt`
  define the typed canonical snapshot boundary. The snapshot covers all
  persisted page domains, including paths, measurements, notes, photo pins,
  scale, page shapes, image notes, and image shapes. `snapshotFromState()` is
  the state-capture authority and `applySnapshotReplace()` is the replacement
  path; compatibility adapters may translate to or from legacy `PageData`,
  but must not create a competing capture authority.
- `stage2/DocumentIdentity.kt` owns app-generated document identity and source
  fingerprinting. Identity must not be derived from display names, URI hash
  codes, content hashes, or Drive folder names. Same-name PDFs must remain
  independent, and a changed or unavailable source must not silently load the
  old document as though it were the new source.
- `stage2/LocalDocumentRepository.kt` is the typed local snapshot authority.
  Its staged validation, atomic replacement, previous-good recovery,
  quarantine, typed failures, per-document serialization, and migration
  boundaries are safety behavior, not optional implementation detail.
- `stage2/LegacyPersistence.kt` and `stage2/LegacyMigration.kt` are explicit
  compatibility seams. Legacy artifacts remain available until migration is
  proven and the task authorizes a later policy.
- `stage3/DocumentSwitchCoordinator.kt`,
  `stage3/AndroidDocumentSessionCallbacks.kt`, and
  `stage3/DocumentSelectionIntegration.kt` own the transactional document
  switching boundary. A session token carries document identity, exact source,
  source fingerprint, and generation. Work that can outlive a screen or
  document must validate the current session/page/query token before applying
  results. Provisional targets are not ready or editable, and cancellation or
  failure must preserve the last committed outgoing session.
- `DriveSyncManager.kt` is the current Drive integration and compatibility
  surface. Do not assume that a serialized sync coordinator, remote generation
  cursor, conflict write barrier, typed payload validator, or complete remote
  replacement already exists merely because the roadmap requires one in a
  later stage.
- `OcrIndex.kt`, `PdfSearchEngine.kt`, and `PdfBitmapRenderer.kt` cover OCR,
  PDF text search, rendering, and bitmap work. They are sensitive to
  cancellation, document identity, page/query revisions, coordinate systems,
  resource lifetime, memory budgets, and thread selection.
- `ui/` contains Compose components and theme code. User-visible changes must
  preserve lifecycle correctness, narrow-phone usability, state semantics,
  accessibility, and annotation parity across PDF and image surfaces.

The roadmap is the controlling sequence for remediation. The current roadmap
records Stages 0–3 as complete and Stage 4 as the next pending stage; always
re-read `CODEX_AUDIT_ROADMAP.md` rather than treating that snapshot in this
manual as a substitute for current status.

## SOTAware remediation rules

The audit and remediation work is specifically about document identity,
canonical state, local persistence, switching, synchronization, import/export,
photos, OCR/search, rendering, annotation history, lifecycle, privacy, and
release readiness. Apply these rules whenever a task touches those areas.

### Canonical state and identity

- Capture complete document state through the canonical snapshot boundary.
- Replacement must be true replacement: absent pages and absent domains must
  not leave stale in-memory state behind.
- Materialize and validate incoming state before mutating live state.
- Keep document identity separate from display name, URI hash, and remote
  folder naming. Same-name PDFs are a mandatory regression case.
- Include source identity and, where required, source revision/fingerprint in
  caches, async work, persistence associations, and conflict decisions.
- Never apply a delayed result to a different document, page, or query merely
  because the numeric page index or URI string matches.

### Persistence and migration

- Treat the local repository as durable state, not as a cache.
- Preserve current and previous-good data until the new snapshot has been
  validated and durably replaced.
- Fail closed on uncertain replacement or association mismatch. Do not turn a
  corrupt or unavailable document into an empty document and report success.
- Keep per-document writes serialized and preserve cancellation semantics.
- Migration must retain legacy artifacts, claim associations safely, verify the
  new snapshot by read-back, and be repeatable without silently rebinding data.
- Scales, photos, image notes, image shapes, page shapes, and other domains are
  part of the document; a partial snapshot is not a successful save.

### Switching and lifecycle

- Route document selection, process restoration, autosave, and background
  document work through the established session owner instead of creating a
  second load path.
- Flush a frozen, complete outgoing snapshot before destroying or replacing a
  session when the task requires it.
- Cancel and join outgoing work when completion matters. Rethrow
  `CancellationException`; do not convert cancellation into an ordinary
  failure or continue publishing after the document is closed.
- Do not expose a provisional target as ready, editable, or saveable.
- Roll back to the last committed session when target setup/load fails, and
  surface the failure honestly.
- Key or clear document-sensitive dialogs, selections, caches, highlights,
  measurements, photo overlays, and UI state at session/page boundaries.

### Synchronization and remote state

Until the roadmap's synchronization stage is implemented and gated:

- Do not describe Drive upload, download, conflict resolution, or restore as
  authoritative or complete without inspecting every route involved.
- Immediate, debounced, manual, periodic, import, and lifecycle routes must
  not capture different document representations or race older state over
  newer state.
- A required component failure makes the whole sync operation fail; partial
  upload or download must not advance a cursor or display success.
- Remote state must not replace authoritative local state until the payload is
  validated, every required component is available, local durability is
  confirmed, and in-memory replacement is complete.
- Conflict state must be a real write barrier for every upload route.
- Prefer stable Drive IDs, app properties, account/root/document-scoped
  metadata, remote revision cursors, pagination, and read-only checks. Do not
  create folders as a side effect of a read or conflict check.

### Payloads, files, and photos

- Treat Drive, import, and legacy JSON as untrusted input. Prefer typed DTOs,
  explicit schema versions, finite-number checks, enum validation, required
  fields, and bounded page/annotation/photo/file/image sizes.
- Generate internal photo IDs and fixed safe filenames. Check canonical path
  containment on every file operation; reject traversal and absolute-path
  attempts.
- Do not interpolate untrusted names into Drive queries. Use IDs or correctly
  escaped query values.
- Download or export into temporary locations, validate bytes/hash/decodability,
  and atomically publish only after validation. Clean up safely without
  destroying the last known-good file.
- Required photo or payload failures must invalidate the enclosing operation;
  do not advance synchronization metadata after a partial result.
- If a bundle format is introduced, protect against zip-slip, zip bombs,
  malformed manifests, and version drift.

### OCR, rendering, search, and memory

- Keep PDF I/O, bitmap decode/render, OCR, image transforms, and export off the
  main thread unless Android explicitly permits and the cost is demonstrably
  bounded.
- Enforce pixel and memory budgets; use sampled decoding and byte-aware cache
  policies rather than assuming an entry-count limit is safe.
- Close PDF, ML Kit, stream, descriptor, and bitmap resources at the correct
  lifecycle boundary. Do not recycle a bitmap before its dimensions or data
  are no longer needed.
- Reuse and close OCR resources appropriately, preserve coroutine
  cancellation, and never mark canceled or failed work complete.
- Test PDF coordinate conversion with media box, crop box, rotation, origin,
  and UI-normalization fixtures. The cropped/rotated and scanned resources in
  `app/src/test/resources/stage0/` are intentional regression assets.
- Search must handle the requested matching semantics, clear obsolete
  highlights, and reject stale query/page/document results before publication.

### Annotation actions and Compose UI

- Do not mutate nested annotation collections through a side channel that
  bypasses the canonical state/history/persistence/sync path.
- A user-visible annotation action must have an explicit state transition and,
  where the feature contract requires it, history, dirty-state, local-save,
  synchronization, and undo/redo coverage.
- Preserve scale and coordinate invariants for rotated notes and shapes; use
  the same dimensions and transforms for rendering and hit-testing.
- Keep expensive work out of composition. Use lifecycle-aware scopes and
  stable keys; avoid launching duplicate work because a recomposition occurred.
- Test narrow portrait and landscape layouts, accessibility semantics, system
  Back behavior, dialogs/overlays, and document switching when UI state is
  involved.

### Safety-critical regression set

The Stage 0–3 characterization and remediation tests are safety controls, not
test-suite decoration. Tests covering data loss, stale state, document
identity, same-name PDFs, Java serialization and fully-qualified names, sync
conflicts, migration, local recovery, and document switching must not be
weakened, deleted, skipped, or rewritten to fit a broken implementation.
Preserve fixtures for same-name PDFs, cropped/rotated PDFs, scanned PDFs,
large pages, high-resolution photos, malformed payloads, traversal/query
injection, non-finite values, and missing required fields.

If a change causes a safety-critical test to fail, stop the affected stage,
trace the failure to the actual state transition, and repair or escalate it.
Do not advance to a later roadmap stage until the required gate is honestly
closed.

## Roadmap stages and gates

The canonical plan defines the detailed work. This compact map prevents an
agent from silently jumping ahead:

| Stage | Intent | Required proof before closing |
| --- | --- | --- |
| 0 | Establish reliable build, test, lint, tooling, fixtures, and characterization gates. | Deterministic fixtures, legacy/domain characterization, focused canaries, and build/test/lint/tooling evidence. |
| 1 | Establish one typed canonical document snapshot and adapters. | Complete round trips, true replacement, deep-copy boundaries, and all sync route equivalence. |
| 2 | Replace local persistence safely. | UUID/source association, fingerprints, atomic replacement, recovery/quarantine, serialization, migration read-back, and legacy preservation. |
| 3 | Make document switching transactional. | Frozen outgoing state, generation/session isolation, cancellation/rollback, one load path, autosave serialization, and stale-result rejection. |
| 4 | Replace synchronization with one serialized coordinator. | Same-name isolation, conflict blocking, lifecycle cancellation, complete remote replacement, remote cursor discipline, and stale-generation prevention. |
| 5 | Harden filenames, payloads, and photo transactions. | Traversal/query-injection rejection, typed bounded payloads, validated temporary transfers, and no false success after partial transfer. |
| 6 | Make import/export current and self-contained. | Fresh-install round trip for every annotation, photo, shape, and scale, with durable apply and truthful stream completion. |
| 7 | Fix rendering and OCR. | Bounded memory, prompt cancellation, resource cleanup, and coordinate golden tests for large/scanned/rotated/cropped PDFs. |
| 8 | Repair search, annotation actions, and responsive UI. | Phrase search, reducer/history parity, rotated-shape behavior, narrow layouts, accessibility, Back, clear-page, and undo/redo tests. |
| 9 | Complete privacy, authentication, release, and cleanup. | Redacted diagnostics, intentional backup/FileProvider policy, release identity/signing/versioning, current auth, clean repository, and green CI. |
| 10 | Final qualification. | The full plan's device, migration, conflict, transfer, rendering, memory, backup, release, and clean-tree evidence. |

Do not claim a stage gate from a nearby or weaker check. In particular, a
package-context instrumentation test proves installation/instrumentation
sanity only; it does not prove functional PDF switching, sync, OCR, or UI
behavior.

## Standard validation and handoff

### Define a closed validation set first

Before editing, write down the acceptance criteria and the smallest checks
that prove them:

- direct tests for the changed path;
- adjacent regression tests protecting identity, state, persistence, lifecycle,
  or UI boundaries;
- the repository build/test/lint gates required by the change;
- device/emulator evidence when the behavior cannot be proven on the JVM;
- configuration/script/fixture checks when those files change;
- final `git diff --check`, status, and accidental-file inspection.

Exploratory checks are welcome, but do not keep moving the blocking finish line
after the defined set passes unless new evidence demonstrates a real blocker.
Classify material findings as `BLOCKER`, `FOLLOW-UP`, or `BACKLOG` and preserve
the latter two in the handoff or implementation log.

A validation gate named by the user, roadmap, or task brief is part of the
acceptance criteria, not an optional postscript.

### Repository gates

Use the checked-in Gradle wrapper and the repository's configured JDK/SDK.
The current CI workflow is `.github/workflows/android.yml` and defines these
core gates:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
```

On Unix-like environments use `./gradlew` with the same tasks. For production
or test changes, run the applicable assemble, unit-test, and lint gates. Run
focused tests first when useful, then run the full relevant suite before
completion. Run `connectedDebugAndroidTest` when the task needs Android/device
proof and a device is available; if no authorized device exists, report it as
unavailable or blocked, never as passed.

When developer tooling changes, validate the actual parser/execution path for
the affected PowerShell, VS Code task, YAML, JSON, or Gradle file. When the
task is documentation-only, a product rebuild is not automatically required,
but the document, links/references, diff, and worktree must still be checked.

Do not hide warnings by suppressing them without a task-specific reason. Do
not delete caches, build outputs, wrapper files, logs, or fixtures merely to
make a gate easier to run; first determine whether they are tracked,
pre-existing, or required evidence.

### Failure and retry discipline

For a reproducible failure in the closed validation set:

1. Preserve the worktree and capture the exact command and output.
2. Determine whether the cause is application code, test code, tooling, or
   environment.
3. Try materially different diagnostic or repair approaches when safe; do not
   count identical retries as progress.
4. Fix the defect within scope and rerun the narrowest affected checks plus
   required regressions.
5. If the required gate still cannot pass, stop and report the blocker rather
   than changing acceptance criteria or claiming a partial pass.

Do not use a minimum retry count to justify continuing after the root cause is
understood, and do not use environmental uncertainty to excuse a product
failure.

### Git and publication

- Inspect `git status`, the final diff, and whitespace before handing off.
- Stage only intended files when staging is requested or required.
- Keep one logically coherent task or remediation stage per commit when a
  commit is authorized or expected.
- Never push, publish, or send external completion messages unless the user
  explicitly authorizes that action.
- Delegated agents never push or publish. The owning primary agent or
  Superintendent handles any explicitly authorized publication after all
  gates pass.
- Do not use destructive Git or filesystem commands to erase unrelated work.

The completion report must name files changed, behavior implemented, tests and
exact validation commands, results and unavailable gates, reviewer findings,
deferred work, migration/compatibility considerations, and final worktree
state. Do not collapse "mostly passed" into PASS.

## Normal mode

Normal mode is the default. Agents may be used pragmatically when they
materially improve investigation, implementation, testing, or independent
review. Delegation is proportional to task complexity; spawning agents is not
itself progress.

The primary agent remains responsible for the user's assignment, scope,
acceptance criteria, integration, evidence, final review, and handoff. A
delegated agent receives a bounded assignment and returns findings or a
candidate; it does not silently expand the task or start another stage.

Use read-only parallel investigation for independent questions when helpful.
Avoid overlapping write ownership. Multiple agents may edit concurrently only
when the files, interfaces, and integration order are genuinely independent
and explicitly assigned. Reviewers must inspect the actual implementation and
diff rather than accepting another agent's summary.

Normal mode does not require the Superintendent/Foreman hierarchy below. It
still requires read-before-edit, evidence-based validation, preservation of
state, honest blockers, focused scope, and a hard stop after completion.

# AGENT BUREAUCRACY MODE

This mode is **OPT-IN ONLY**. It activates only when the user's prompt contains
the exact phrase:

`AGENT BUREAUCRACY MODE`

The phrase must appear exactly as written. Do not activate this mode because a
task is large, agents are available, a previous task used it, or this document
describes it. A mention of the phrase in repository documentation does not
activate it; activation comes from the current user's prompt.

When active, normal repository safety and validation rules remain in force and
the following hierarchy is mandatory.

## LEAN CONTEXT BUREAUCRACY PROTOCOL

- Repository source, current diff/status, tests, CI/build evidence, roadmap,
  and governing docs are authoritative. Never forward full transcripts;
  search history only for a specific unresolved fact.
- Every fresh role receives a handoff packet of about 1,500 words or less
  containing only: TASK, BASELINE SHA, CANDIDATE SHA, OBJECTIVE,
  ACCEPTANCE/INVARIANTS, FILES/SUBSYSTEMS, OPEN BLOCKERS, one-line RESOLVED
  BLOCKERS when relevant, TEST/BUILD/CI EVIDENCE, DEFERRED MINORS, and NEXT
  ACTION. Omit chronology, transcript dumps, and giant logs. Reports cite
  concise file:line/symbol, command and result, Gradle task and result, CI
  run/job, or device result.
- A Foreman owns one coherent roadmap task. Rotate context intentionally only
  after a major task or two substantial repair loops, never for inactivity and
  never mid-edit. The outgoing Foreman writes the compact packet; a fresh
  Foreman receives only that packet plus governing docs.
- Use two independent read-only Investigators by default. Add a third only
  for high-risk persistence/migration, import/export integrity,
  security/permissions, destructive updates, concurrency/synchronization or
  background services, multi-component lifecycle/state restoration,
  build/release, three or more independent subsystems, or disagreement
  between the first two. Keep Investigator reports preferably at 800 words or
  less.
- Exactly one Coder owns one coherent implementation. Give it reconciled
  findings only, target 1,200 words or less, reuse it for narrow repair loops,
  and never respawn it because of perceived slowness.
- Every implementation receives a fresh independent Luna Max Reviewer. The
  first review is task-level; a repair review is delta/invariant/regression-
  focused; repeat a full review only for architecture, a new subsystem,
  systemic misunderstanding, three repair loops, or invalidated evidence.
  Target Reviewer packets at 1,200 words or less.
- After Reviewer PASS, the Foreman review is bounded to diff/status,
  acceptance, tests/build, the Reviewer packet, and release/CI evidence; it
  must not duplicate the whole audit.
- Do not invoke the final Inspector until implementation, Reviewer PASS,
  Foreman PASS, required unit/instrumentation/static/build checks, terminal-
  green CI evidence, and required APK/AAB/release evidence are complete. Do
  not spend Inspector credits diagnosing ordinary red Gradle or CI.
- The final Inspector is Terra Max by default (gpt-5.6-terra, max reasoning),
  fresh and read-only, and receives only acceptance criteria, candidate SHA,
  relevant diff, compact handoff, Reviewer PASS, Foreman PASS, completed
  evidence, and deferred minors. After an Inspector blocker, use the same
  Coder, a fresh delta Reviewer, the Foreman, and targeted Terra reinspection;
  do not full-audit again unless the architecture, a new subsystem, or the
  evidence is invalidated.
- Sol is escalation-only: use it only for material Terra uncertainty,
  Reviewer/Foreman/Terra disagreement, contradictory device/CI/runtime
  evidence, ambiguous high-impact persistence/security/destructive-data risk,
  or explicit user request; allow at most one Sol escalation per phase unless
  expressly authorized.
- Preserve all Android safety checks: migration/persistence compatibility,
  lifecycle/state restoration, permissions/security, explicitly required
  device/emulator validation, release/signing/build validation, regression-
  first repairs, and independent review. Save context by removing duplicated
  handoffs and repeated audits, never by removing validation.

## Bureaucracy hierarchy

```text
SUPERINTENDENT (Luna Max Reasoning)
└── FOREMAN (Luna Max Reasoning)
    ├── INVESTIGATOR 1 (Luna Max Reasoning)
    ├── INVESTIGATOR 2 (Luna Max Reasoning)
    ├── INVESTIGATOR 3 (Luna Max Reasoning; high-risk or disagreement only)
    ├── CODER (Luna Max Reasoning)
    ├── REVIEWER (Luna Max Reasoning)
    └── INSPECTOR (Terra Max)
```

If the named model or configuration is unavailable, preserve the intended
reasoning hierarchy with the closest supported configuration and state that
substitution in the task record. Do not silently omit a role.

### Superintendent

There is exactly one Superintendent for the overall user assignment. The
Superintendent:

- understands the complete assignment and identifies bounded major tasks;
- establishes order, dependencies, and the closed validation set;
- spawns exactly one Foreman for the currently active major task;
- receives and evaluates the Foreman's completion packet against the user's
  actual acceptance criteria;
- owns top-level reporting and any explicitly authorized commit/push action;
- starts a later major task only when the original prompt explicitly authorized
  the sequence and the current task is properly closed.

The Superintendent is primarily an orchestrator. Once the Foreman owns a
major task, the Superintendent waits. It must not duplicate the investigation,
edit production code, review the same files in parallel, perform speculative
work, invent busywork, continuously poll, or quietly take over the Foreman's
assignment.

### Foreman

Exactly one Foreman owns one active major task. The Foreman:

- inspects enough context to define the investigation and acceptance criteria;
- divides the problem into useful independent investigative angles;
- spawns two independent Investigators by default and adds a third only for
  the high-risk conditions or first-two disagreement listed above;
- reconciles evidence and disagreements rather than concatenating reports;
- writes a precise implementation brief for the Coder;
- waits for the Coder, then spawns an independent Reviewer;
- waits for the Reviewer and delegates only focused blocker repairs;
- performs its own final read-only review after Reviewer PASS;
- spawns the final Inspector, reconciles the Inspector result, and returns the
  structured completion packet.

The Foreman does not edit production code in this mode. It delegates repairs
instead of quietly fixing them.

### Investigators

Investigators are read-only. They must not edit production implementation.
Use deliberately different angles such as:

1. Primary implementation path, architecture, state flow, and likely root
   cause.
2. Tests, edge cases, lifecycle, persistence, migration, and failure modes.
3. Independent challenge of assumptions, adjacent paths, races, security,
   compatibility, and hidden coupling.

Each Investigator returns findings, evidence, files/symbols, suspected root
cause, risks, implementation considerations, and uncertainties, preferably
within 800 words. They do not spawn their own departments.

### Coder

The Coder is the only ordinary subordinate role authorized to edit production
code while this mode is active. Exactly one Coder owns one coherent
implementation and receives only the Foreman's reconciled
brief containing:

- objective, root cause, affected files/symbols, and scope;
- invariants and behavior to preserve;
- implementation expectations and known traps;
- required tests, fixtures, and validation commands.

The Coder verifies the brief against the actual source, implements only the
bounded change, adds or updates necessary tests, runs targeted validation,
inspects its own diff, and reports exact changes, failures, and uncertainty.
Keep the brief and report at 1,200 words or less where practical, reuse the
same Coder for narrow repair loops, and never respawn it because of perceived
slowness. The Coder does not declare final acceptance, push, publish, or
recursively create write-capable agents. If the brief is wrong, the Coder
reports the contradictory evidence instead of implementing nonsense.

Documentation or test files that are the explicit deliverable may be edited by
the specifically assigned Coder. This exception does not give supervisors
production edit authority, and it does not authorize unrelated documentation
or cleanup.

### Reviewer

Every implementation receives a fresh independent Luna Max Reviewer. The
Reviewer is read-only and inspects the original task, the
Foreman's brief, actual changed files and diff, surrounding production code,
tests, and validation evidence. It must not rely on the Coder's summary.

The Reviewer looks for incorrect assumptions, incomplete behavior, regressions,
stale async results, lifecycle mistakes, persistence/data loss, identity and
migration problems, synchronization races, security issues, missing or
weakened tests, accidental scope creep, false-positive validation, and code
that passes tests while violating the intended contract.

Classify each finding as:

- **BLOCKER** — the current major task must not close until it is repaired.
- **MINOR / DEFERRED** — a real issue that does not invalidate the current
  acceptance contract and is recorded for a suitable later task.

Only a genuine blocker returns the work to the Coder. The Reviewer never edits
production code. A first review is task-level; a repair review is
delta/invariant/regression-focused. Repeat a full review only for architecture,
a new subsystem, systemic misunderstanding, three repair loops, or invalidated
evidence. Keep the Reviewer packet at 1,200 words or less where practical. A
Reviewer PASS means the candidate is ready for the Foreman's independent final
review; it is not a substitute for that review.

### Foreman final review

After Reviewer PASS with no unresolved blockers, the Foreman performs a fresh
bounded read-only review of diff/status, acceptance, tests/build, the Reviewer
packet, and release/CI evidence. It verifies that the implementation solves
the assigned problem, Investigator concerns were reconciled, Reviewer findings
were resolved, required gates passed, scope stayed controlled, and no obvious
cross-system regression was introduced; it does not duplicate the whole audit.

If the Foreman finds a blocker, it prepares a focused repair brief, delegates
to a Coder, sends the repair through independent Reviewer validation again,
and repeats its own read-only review. The Foreman never repairs production code
itself.

### Inspector

The final Inspector is **Terra Max** and is read-only. It receives the original
task, acceptance criteria, reconciled findings, implementation summary, final
diff, tests, validation results, and known deferred findings. It performs a
fresh audit intended to falsify completion.

The Inspector specifically checks for hidden regressions, state corruption,
persistence and migration failures, race conditions, incorrect test
assumptions, inadequate validation, security implications, accidental scope,
and future remediation debt. Its findings are `BLOCKER` or `MINOR / DEFERRED`.

If the Inspector reports a blocker, the Foreman creates a repair brief, a Coder
repairs it, the Reviewer independently checks the repair, the Foreman reviews
read-only again, and the Inspector inspects again. Do not bypass this chain
because the problem was found late. The Inspector never edits production code
and its verdict is not optional.

## Mandatory bureaucracy workflow and waiting

The required order for each major task is:

1. Superintendent defines the active major task and delegates one Foreman.
2. Foreman plans and defines independent investigation angles.
3. Two Investigators work in parallel and return read-only reports; add a
   third only under the documented high-risk or disagreement conditions.
4. Foreman reconciles the reports and writes the Coder brief.
5. Coder implements and validates.
6. Reviewer independently reviews the actual candidate and diff.
7. Foreman performs its read-only final review.
8. Inspector performs the final Terra Max read-only audit.
9. Foreman returns the completion packet.
10. Superintendent closes the major task against the original prompt.

Implementation and review are sequential. Do not start the Coder before
Investigator reconciliation, the Reviewer before the Coder finishes, or the
Inspector before Reviewer and Foreman review pass.

Waiting is a required behavior, not an optimization:

- While the Foreman owns the active major task, the Superintendent waits.
- While Investigators work, the Foreman and Superintendent wait.
- While the Coder works, the Foreman and Superintendent wait.
- While the Reviewer works, the Foreman and Superintendent wait.
- While the Inspector works, the Foreman and Superintendent wait.

Waiting supervisors must not duplicate investigations, edit code, repeatedly
reread the same files, fabricate secondary work, spawn replacement agents,
run polling loops, or pressure an agent because a build or review takes time.
Use the product's passive wait/status mechanism. Silence and elapsed time are
not evidence of failure. Wake on a meaningful result, blocker, escalation, or
completion packet.

## Bureaucracy edit ownership and escalation

Production implementation may be edited only by the Coder while this mode is
active. Superintendent, Foreman, Investigators, Reviewer, and Inspector are
read-only with respect to production code. Nobody gets to "just fix one line."

If a Reviewer or Inspector finds a blocker, report it and delegate the repair.
Do not weaken tests or acceptance criteria. Keep correction loops bounded and
focused. If the Foreman cannot resolve a blocker within scope, escalate the
exact evidence and decision needed to the Superintendent. If safe completion
is impossible, leave the worktree understandable and report the blocker.

Do not allow Investigators, Coders, Reviewers, or Inspectors to recursively
create miniature departments. The only hierarchy is Superintendent → Foreman
→ the explicitly assigned roles.

## Completion packet and closure

The Foreman's completion packet must contain:

- major task name and objective;
- root cause and reconciled investigation summary;
- files changed and implementation summary;
- tests added or changed;
- exact validation commands and results, including failed or unavailable
  commands;
- Reviewer verdict and findings;
- Foreman verdict;
- Inspector verdict;
- blocker status and minor/deferred findings with ownership;
- migration, compatibility, identity, and data-preservation considerations;
- worktree status and accidental-file check;
- recommended commit scope/message, if a commit is authorized or expected.

The Superintendent closes only after verifying requested behavior, required
gates, no unresolved blocker, Inspector approval, preserved deferred findings,
and coherent scope. It then performs only top-level actions explicitly
authorized by the user. In particular, do not push merely because the
workflow completed.

## Anti-patterns prohibited in every mode

The following are prohibited:

- editing first and understanding later;
- accepting an agent's summary without inspecting the actual diff and path;
- coding and reviewing the same implementation with the same agent;
- weakening, removing, skipping, or deleting tests to obtain PASS;
- changing acceptance criteria after a failure;
- calling an environmental failure application success;
- broad unrelated refactors or silent future-stage work;
- deleting legacy data before migration is proven;
- modifying persistence, identity, synchronization, or migration without
  regression coverage;
- treating a partial upload/import/export/migration as success;
- allowing stale async work to mutate a different document or query;
- pushing without explicit authorization;
- silently starting subsequent tasks;
- supervisor busywork or agent polling loops;
- uncontrolled recursive delegation;
- multiple agents editing overlapping production files concurrently;
- claiming success without required evidence;
- hiding or forgetting deferred findings;
- treating the Terra Max Inspector verdict as optional;
- using destructive filesystem or Git commands against unclear targets.

## Normal mode versus AGENT BUREAUCRACY MODE

**NORMAL MODE** is the default:

- use the standard repository workflow;
- delegate pragmatically and proportionally;
- use independent read-only review where it adds confidence;
- keep ownership and scope explicit without a mandatory hierarchy.

**AGENT BUREAUCRACY MODE** activates only through the exact phrase in this
file's activation rule:

- mandatory Superintendent → Foreman ownership;
- two independent read-only Investigators by default, with a third only under
  the documented high-risk or disagreement conditions;
- one Coder implementation owner;
- one independent read-only Reviewer;
- Foreman read-only final review;
- Terra Max read-only Inspector final audit;
- strict production edit ownership;
- strict waiting and no-polling behavior;
- blocker repair loops through Coder → Reviewer → Foreman → Inspector;
- structured completion packet and Superintendent closure.

The two modes must not be blended casually. If the exact activation phrase is
absent, do not impose the bureaucracy hierarchy. If it is present, do not
skip its roles or waiting rules because the task looks simple.

## Document quality and maintenance

Keep this manual authoritative, concrete, repository-specific, and free of
motivational filler. Update it only when a durable operating rule or stable
architectural boundary changes. Do not use it as a substitute for recording
stage-specific evidence in `CODEX_AUDIT_IMPLEMENTATION_LOG.md` or current
status in `CODEX_AUDIT_ROADMAP.md`.

When this manual, the roadmap, the implementation log, and the source appear
to disagree, inspect the relevant commits and tests, resolve the discrepancy
explicitly, and preserve compatibility until the new behavior is proven.
