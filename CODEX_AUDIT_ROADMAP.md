# SOTAware Construct: Independent Audit Verification and Remediation Plan

**Repository:** `dspevo-afk/sotaware-construct`  
**Audited branch:** `main`  
**Audited commit:** `e010bee287894abdcaf29b5e539f16269a94a9c5`  
**Audit date:** 2026-08-22

## Scope and verification limits

This review independently inspected the repository source, resources, Gradle configuration, scripts, tests, and repository contents. It compared the code against the 35 findings in the prior Codex audit.

The source review found no Codex finding that was fabricated or contradicted by the code. Several findings are broader than Codex described, and several important defects were omitted from the original audit.

The exact Gradle command results reported by Codex were not independently rerun in this environment because the runtime could not resolve GitHub or Gradle dependencies. The following were independently verified from source/configuration:

- the trivial test coverage;
- the source that triggers the lint indentation error;
- the absent release signing configuration;
- the invalid PowerShell helper syntax;
- the invalid `.vscode/tasks.json` structure;
- all substantive data-loss, sync, persistence, OCR, rendering, export, UI, and privacy findings.

## Executive verdict

The app does not need a rewrite. Its drawing and annotation functionality is salvageable. The dangerous area is the state/persistence/sync boundary:

- document identity is not stable;
- there are several independently assembled snapshots of the same document;
- sync writers can overlap;
- local data is saved late and non-atomically;
- remote data is applied incompletely and not persisted;
- async work is insufficiently tied to the active document and lifecycle.

Until Priority 0 is complete, Google Drive sync should not be treated as a reliable backup. A successful sync can currently mean that an incomplete or older snapshot won the race.

## Codex audit verification

No original finding was disproven. The following qualifications apply:

1. The large-PDF freeze/crash finding is a confirmed risk, not a deterministic crash for every file.
2. The embedded-text coordinate defect is structurally confirmed; the visible displacement depends on crop boxes and page rotation in a given PDF.
3. The Drive scope mismatch is confirmed; the exact failure depends on existing grants and whether the selected folder is app-created, user-selected, or in a shared drive.
4. An unsigned Gradle release is a release-readiness issue, not necessarily a defect if external signing is deliberately used.
5. The broad FileProvider configuration is defense-in-depth risk because the provider is correctly non-exported.

## Prioritized findings

### Priority 0: Data-loss and security blockers

#### 1. Document identity is broken locally and remotely

Codex #1 is confirmed. Drive backup folders are selected by PDF display name, so distinct files named `plan.pdf` collide. Local markup and thumbnail files use the 32-bit `Uri.hashCode()`, which can collide and is not a stable document identity. The app also does not detect when content at an existing URI changes.

**Impact:** annotations, shapes, measurements, scales, and photos can be associated with or overwrite the wrong drawing.

#### 2. Switching PDFs can discard unsaved work, and delayed loads can contaminate the next PDF

This was missed by Codex. `onPdfSelected` clears the ViewModel before saving the current document. Local persistence otherwise occurs mainly on Activity pause/stop, so changing drawings inside the app can lose recent edits. The selected PDF is also loaded twice, and the extra coroutine does not verify that its URI is still active before writing into the shared ViewModel.

**Impact:** work can disappear on an ordinary document switch, or annotations from PDF A can arrive late in PDF B.

#### 3. Sync payload builders disagree, and sync writers can race

Codex #2 is confirmed. Immediate, debounced, and manual sync omit page shapes, while the timer path includes them. In addition, photo sync, debounced sync, manual sync, and timer sync are not serialized. An older snapshot can finish after a newer snapshot and overwrite it.

**Impact:** a successful upload can silently remove shapes or roll back newer edits.

#### 4. Conflict detection is not a real conflict barrier

Codex #4 is confirmed and understated. One global `PREF_LAST_SYNC` is shared across accounts, backup folders, and documents. It is set from the device wall clock rather than a Drive revision/server cursor. Clock skew and unrelated document syncs can suppress or manufacture conflicts. Some conflict dialogs do not block all upload paths while the user is deciding.

**Impact:** newer remote work can be overwritten without a reliable warning.

#### 5. Remote restore is incomplete, non-atomic, non-durable, and unacknowledged

Codex #3 is confirmed. Shapes are not restored. Accepted remote state is only placed in memory, is not atomically saved locally, and does not advance a per-document remote cursor. Startup acceptance can leave sync blocked. The restore loop only overwrites pages contained in the remote snapshot, so local pages omitted remotely survive as stale ghost data.

**Impact:** remote state can be lost on process death, repeatedly reoffered, mixed with stale local state, or followed by a bad upload.

#### 6. Local persistence can corrupt the only copy and has no durable schema

Codex #6 is confirmed and broader than written. The app overwrites Java-serialized files directly, on UI/lifecycle paths, without an atomic replace or verified backup. Any read error becomes `emptyMap()`, making corruption look like a legitimate blank document. Java serialization has no explicit schema/version migration, and moving or changing serialized classes during refactoring can make legacy files unreadable. Scales are stored separately, so the document is not one transaction.

**Impact:** crash/power loss/refactoring can turn valid work into an apparently empty document.

#### 7. Untrusted photo names create path traversal, query injection, and possible app-private data exposure

Codex #7 and #21 are confirmed and more serious together. Imported/remote JSON controls photo filename strings. Those strings are used both as filesystem paths and inside unescaped Drive queries. A crafted name can traverse out of the intended photo directory, overwrite app-private files on download, or cause upload code to read and upload another app-private file while labeling it as a JPEG.

**Impact:** corruption or disclosure of app-private state after importing or syncing a crafted snapshot.

#### 8. Remote/import payloads are untyped, unbounded, and partial photo failures are called success

Codex #20 is confirmed and also affects downloads. Raw Gson maps use unchecked casts and enum parsing with no schema version, bounds, count limits, or size limits. JSON is loaded entirely into memory. Photo upload/download failures are swallowed while annotation sync still advances the cursor and reports success. Downloads write directly to final files and may leave partial files.

**Impact:** malformed/future data can abort restore; huge data can exhaust memory/storage; missing or corrupt photos can be falsely certified as synchronized.

#### 9. Sync lifecycle and session restoration are broken

Codex #5 is confirmed. Auto-sync owns an independent coroutine scope. Session restoration initializes the manager but its result is ignored by UI state. The setup effect is keyed only by PDF URI, so signing in or selecting a backup folder while a PDF is already open may not start sync.

**Impact:** background work can outlive the Activity, retain it, waste resources, or never begin when the UI says Drive is configured.

#### 10. Android Auto Backup has no exclusions and can restore incompatible sync state

Codex #9 is confirmed. Files and preferences containing markups, photo references, URI history, backup folder state, and the global sync cursor are eligible for OS backup/device transfer. Beyond privacy, restoring stale URIs or a stale/future sync cursor onto another device can poison conflict decisions.

**Impact:** sensitive construction data may be copied through another backup channel, and restored state can cause missed conflicts or stale uploads.

#### 11. Rendering, OCR, thumbnails, photo viewing, and PDF export can exhaust memory or block the UI

Codex #10 is confirmed and applies to more paths than listed. PDF pages and full-resolution photos are decoded/rendered on the main thread. OCR permits an 8192 by 8192 ARGB bitmap, approximately 256 MiB before OCR overhead. Thumbnail bitmaps are retained without a byte budget or recycling. Full-screen photo and export paths decode large images without sampling, and PDF export may hold source, output, and attachment bitmaps together.

**Impact:** freezes, ANRs, garbage-collection storms, and out-of-memory crashes on large drawings or phone photos.

#### 12. Sensitive text and high-volume diagnostics are logged and committed

Codex #8 and #33 are confirmed. OCR samples, search terms, matched text, per-shape drawing diagnostics, screenshots, blueprint logs, and a physical device serial are present or emitted without debug-only/redaction controls.

**Impact:** privacy leakage, oversized logs, performance degradation, and repository exposure.

### Priority 1: Major correctness and reliability defects

#### 13. Async search, OCR, and load results are not bound to a document/query revision

Codex #12 is confirmed and the same flaw exists in local document loading. Search effects are insufficiently keyed, cached OCR is keyed only by page index in UI state, and delayed work can mutate the current ViewModel after the active URI changes. Search results are merged without clearing pages that no longer match, leaving stale yellow highlights.

#### 14. OCR pre-cache is quadratic, cancellation-hostile, and leaks recognizers

Codex #15 and #16 are confirmed. Each page can reparse the complete PDF; a new ML Kit recognizer is created per page and not closed; generic exception handling swallows cancellation; failed pages can still mark the document fully cached; and code reads bitmap dimensions after recycle.

#### 15. Embedded-text coordinate mapping is incomplete

Codex #11 is confirmed at code level. The mapper does not consistently normalize crop-box origin, media-box origin, rotation, and PDF bottom-left versus UI top-left coordinates through one tested transform.

#### 16. Phrase search and uncached text selection are functionally broken

Codex #13 and #14 are confirmed. A whole multi-word query is compared against individual OCR word boxes, and the fallback text-selection call uses a blank search that exits immediately.

#### 17. Export/import is stale, incomplete, and can lie about success

Codex #17 and #18 are confirmed. Save export reads the last disk snapshot rather than the current ViewModel, stores photo filenames rather than photo bytes, discards scale, and can show success after a null output stream. Import writes disk state but does not atomically apply it to the active ViewModel and does not preserve scale.

#### 18. Photo lifecycle management is incomplete

Codex #19 is confirmed. Removing a pin does not remove local photos, temporary camera files, or remote copies. There are no reference counts, tombstones, retention rules, or garbage collection. A null camera input stream can still result in a filename reference being added even though no image was copied.

#### 19. Image annotation edits bypass the canonical undo/save/sync path, and shape hit-testing is stale

Missed by Codex. Photo-image notes and shapes mutate nested plain lists directly and do not consistently create history actions or trigger immediate/debounced persistence and sync. Page-shape rendering uses ratio dimensions, but hit-testing can use legacy width/height and ignores rotation, so resized/rotated shapes can draw in one place while selecting in another.

#### 20. Cache identity and eviction are incomplete

Codex partly captured this in #10 and #12. UI OCR selection is keyed only by page number, OCR cache does not detect changed content at the same URI, and thumbnail bitmaps are retained without an LRU byte budget or explicit release.

#### 21. Drive authorization, browsing, pagination, and side effects are inconsistent

Codex #22 is confirmed. Sign-in requests broad scopes while API credentials request only `DRIVE_FILE`. A custom arbitrary-folder/shared-drive browser does not align cleanly with per-file authorization. Folder/drive listings are capped without pagination. Read/check methods call folder-creation code, so checking for updates can mutate Drive.

#### 22. “Recent files” are not recent

Codex #23 is confirmed. An unordered `StringSet` is alphabetically sorted by filename, so UI ordering and OCR pre-cache selection are not chronological.

#### 23. Viewer controls overflow common phone widths

Codex #30 is confirmed. The top bar and bottom tool rail contain more fixed 48 dp actions than fit a common 360 dp portrait width.

#### 24. Undo/redo observability and unfinished controls are inconsistent

Codex #28, #29, and #31 are confirmed. History stacks are plain mutable lists inside snapshot maps. Clear-page is passed to the tool rail but never invoked. The tool-options sheet has no call site and an unused dismiss path.

#### 25. Localization and accessibility are incomplete

Codex #34 is confirmed. Nearly all visible text is hard-coded; active tool state relies heavily on color; some images have null content descriptions; and state semantics are sparse.

### Priority 2: Build, test, and release blockers

#### 26. Lint currently fails

Codex #24 is confirmed from source. The exact `1 error / 77 warnings` count was not rerun here, but the suspicious indentation that produces the reported error is present.

#### 27. Behavioral tests are effectively absent, and there is no CI gate

Codex #27 is confirmed. The only unit test verifies arithmetic and the instrumentation test verifies the package name. No workflow guards build, lint, serialization, persistence, sync, export, OCR, or UI behavior on every commit.

#### 28. Developer automation is broken in two places

Codex #26 is confirmed. `tools/android_env.ps1` contains invalid PowerShell syntax. Additionally, `.vscode/tasks.json` contains two concatenated top-level JSON objects and repeats the invalid PowerShell condition, so the tasks file itself is invalid JSON.

#### 29. Release identity and packaging are unfinished

Codex #25 is confirmed. There is no release signing configuration, the application ID remains `com.example.myapplication`, and versioning remains at the initial value. The application ID must be deliberately chosen before distribution; changing it later produces a different app identity and can strand existing app-private data.

#### 30. Google sign-in uses a deprecated path

Missed by Codex as a separate maintenance item. `GoogleSignInClient` is deprecated. Correctness and data migration should be fixed before an auth rewrite, but the eventual release should use current Google identity/authorization APIs with one coherent scope model.

### Priority 3: Cleanup and structural debt

#### 31. Repository hygiene is poor

Codex #33 is confirmed. Generated Gradle directories, logs, screenshots, temporary artifacts, and an IDE deployment file with a physical device serial are tracked. Ignore rules do not cover these categories.

#### 32. Dead code and resources remain

Codex #32 is substantially confirmed: dead OCR extraction code, an unused tool sheet, unused cache/function paths, unused layouts/assets, and unused Drive helpers/scopes remain. Delete only after behavioral coverage proves they are not hidden compatibility paths.

#### 33. FileProvider exposes broader roots than required

Codex #35 is confirmed as defense-in-depth. The provider is non-exported, which limits exposure, but the configured roots should be narrowed to dedicated camera/export directories.

#### 34. `MainActivity.kt` is a risk multiplier, not the first repair target

The roughly 5,500-line Activity combines composition, ViewModel/domain models, local persistence, export/import, photo management, OCR orchestration, rendering, and sync coordination. Separate image-annotation and page-annotation pipelines have already drifted. This should be decomposed after a canonical snapshot/repository seam exists, not by performing a big-bang file split first.

## Step-by-step Codex implementation roadmap

### Execution rules for every stage

- One stage per Codex run and one logically focused commit.
- Codex must not push automatically.
- Do not commit when a required gate fails; leave the working tree intact and report the failure.
- Update `CODEX_AUDIT_IMPLEMENTATION_LOG.md` with changed files, behavior, migrations, tests run, exact results, and unresolved risks.
- Preserve the current serialized legacy classes and fully qualified names until legacy migration is implemented and tested.
- Do not perform a broad `MainActivity.kt` split before Stages 1 through 4 establish stable seams.
- No destructive cleanup or deletion of legacy local/Drive data during migration. Rename/quarantine only after verified conversion.

### Stage 0: Establish a trustworthy baseline

1. Create a dedicated remediation branch from the audited commit.
2. Add `CODEX_AUDIT_ROADMAP.md` and `CODEX_AUDIT_IMPLEMENTATION_LOG.md`.
3. Fix only the PowerShell parser error, invalid `.vscode/tasks.json`, and the lint-blocking indentation error.
4. Add CI for `assembleDebug`, `testDebugUnitTest`, and `lintDebug`.
5. Add deterministic fixtures:
   - two different PDFs both named `plan.pdf`;
   - a rotated/cropped embedded-text PDF;
   - a scanned PDF;
   - a large blueprint and a high-resolution phone photo;
   - a multi-page annotation snapshot containing paths, measurements, notes, page shapes, scales, photo pins, image notes, and image shapes;
   - malformed/malicious JSON cases.
6. Add characterization tests proving current serialization fields and current behavior before refactoring.

**Gate:** debug build, unit tests, lint with zero errors, current-device smoke, clean tree.

### Stage 1: Introduce one canonical document snapshot

1. Add a typed, versioned `DocumentSnapshotV1` DTO containing every persisted domain:
   - document ID and metadata;
   - pages;
   - paths;
   - measurements;
   - notes;
   - page shapes;
   - scale;
   - photo pins;
   - photo file metadata;
   - image notes;
   - image shapes;
   - schema version and snapshot revision.
2. Implement exactly one `snapshotFromState()` and one `applySnapshotReplace()` path.
3. Replace hand-built `PageData` unions in immediate, debounced, manual, and timer sync with the canonical snapshot builder.
4. Add round-trip tests and explicit shape/scale/photo canaries.
5. Define remote replacement semantics: pages absent from the accepted remote snapshot must be removed, not preserved as ghosts.

**Gate:** every state domain survives serialize/deserialize/apply; all sync triggers produce byte-equivalent logical snapshots.

### Stage 2: Replace local persistence safely

1. Introduce a stable app-generated `DocumentId` UUID. Do not use display name or `Uri.hashCode()` as identity.
2. Persist a document manifest mapping the selected URI/provider metadata to `DocumentId`; record a content fingerprint only to detect changed source content and prompt, not as the sole identity.
3. Create a `LocalDocumentRepository` using typed, schema-versioned snapshots and an atomic file replacement on `Dispatchers.IO`, protected by a per-document mutex.
4. Include scale in the same transaction as annotation state.
5. Add recovery behavior:
   - keep the previous good snapshot;
   - quarantine corrupt files;
   - show a recoverable error instead of returning an empty document.
6. Implement a one-time legacy migrator from `markups_<uri.hashCode>.bin` and scale preferences.
7. Verify migrated data by reading the new snapshot back before marking legacy files migrated.
8. Preserve legacy files as `.legacy` or `.migrated` until final qualification.

**Gate:** fault-injected interrupted writes recover the previous snapshot; legacy fixtures migrate without loss; corruption never silently becomes blank data.

### Stage 3: Make document switching and async work transactional

1. On document switch:
   - cancel/join document-scoped jobs;
   - flush the current dirty snapshot;
   - clear document-scoped UI state/caches;
   - load the target snapshot once;
   - apply it only if its document token is still current.
2. Remove the duplicate load path in `onPdfSelected`.
3. Key search, OCR selection, thumbnails, rendering, and callbacks by `DocumentId + sourceRevision + pageIndex` and, where applicable, query revision.
4. Discard any result whose token no longer matches active state.
5. Add a debounced local autosave after every user mutation plus a non-cancellable final flush for an explicit document switch.

**Gate:** rapid A/B/A switching cannot lose or cross-contaminate state; process-kill/relaunch recovers the latest completed edit transaction.

### Stage 4: Replace Drive sync with one serialized coordinator

1. Extract a `DriveGateway` interface and an in-memory fake.
2. Add a lifecycle/ViewModel-scoped `SyncCoordinator` with a per-document `Mutex` and monotonic local generation.
3. Route immediate, debounced, manual, and periodic requests into the same queue. Older generations must never overwrite newer generations.
4. Persist Drive folder/file IDs and use an app property containing `DocumentId`; never identify a document by name.
5. Store sync metadata per Google account + backup root + `DocumentId`.
6. Use remote file/revision metadata as the cursor, not device wall clock.
7. Implement a real state machine: `Idle`, `Dirty`, `Uploading`, `Conflict`, `ApplyingRemote`, `Error`.
8. While `Conflict` is active, block every upload path.
9. Accepting remote must:
   - download and validate the complete snapshot and photos;
   - atomically persist locally;
   - replace in-memory state;
   - record the accepted remote cursor;
   - clear the conflict;
   - restart normal synchronization.
10. Keeping local must upload the canonical current snapshot only after the conflict decision is recorded.
11. Stop creating Drive folders during read/check operations.
12. Add pagination for all folder/shared-drive listings.

**Gate:** fake-Drive tests prove same-name isolation, no old-generation overwrite, per-document cursors, conflict write blocking, complete remote replacement, and lifecycle cancellation.

### Stage 5: Harden filenames, payloads, and photo transactions

1. Generate internal photo IDs and fixed safe filenames; remote JSON must never choose filesystem paths.
2. Store photos under `files/documents/<documentId>/photos/`.
3. Validate canonical containment on every read/write even for generated names.
4. Stop interpolating untrusted values into Drive queries. Prefer saved file IDs/app properties; otherwise use one tested Drive query escaping function.
5. Replace raw Gson maps with typed DTO parsing, explicit schema migrations, enum fallback handling, and validation.
6. Enforce maximum JSON size, page count, annotation count, photo count, photo size, dimensions, and finite numeric values.
7. Download to a temporary file, validate size/hash/bitmap decodability, then atomically rename.
8. Treat any required photo failure as a failed sync; do not advance the cursor.
9. Define deletion/retention behavior with tombstones or a safe garbage-collection pass. Never delete remote legacy backups during migration.

**Gate:** traversal/query-injection fixtures are rejected; partial download/upload cannot report success; missing/corrupt photos are surfaced.

### Stage 6: Make import/export current and self-contained

1. Export the current canonical snapshot, never a stale disk reread.
2. Create one self-contained `.sotaware` bundle containing a manifest/snapshot and referenced photos.
3. Include scale, page shapes, image notes, and image shapes.
4. Validate the destination stream and only show success after close/flush completes.
5. Import through the same typed validator and atomic local repository.
6. Apply imported state to the active ViewModel only after persistence succeeds.
7. Add zip-slip/zip-bomb protections if a ZIP container is used.

**Gate:** export to a second test device/emulator reproduces all annotations, photos, and scales; canceled/null streams never show success.

### Stage 7: Fix rendering and OCR resource handling

1. Move all PDF open/render, bitmap decode, EXIF transform, local file I/O, and export rendering off the main thread.
2. Render to a viewport/pixel budget rather than uncapped source dimensions.
3. Decode photos with sampling appropriate to the displayed/exported size.
4. Replace raw bitmap maps with a byte-sized LRU cache and release resources on eviction/document close.
5. Open each PDF once per OCR pre-cache session.
6. Reuse one ML Kit recognizer per session and close it.
7. Rethrow `CancellationException`; do not mark failed/canceled documents fully cached.
8. Capture bitmap dimensions before recycle and use `try/finally` for every renderer/page/bitmap resource.
9. Introduce one tested `PdfCoordinateMapper` for crop box, media box, rotation, and normalized UI coordinates.
10. Add StrictMode and memory-budget tests for large fixtures.

**Gate:** no main-thread disk/PDF violations in major flows; OCR cancels promptly; large fixtures stay under the defined bitmap budget; coordinate golden tests pass.

### Stage 8: Repair search, annotation actions, and responsive UI

1. Implement phrase search by grouping adjacent OCR boxes/lines and mapping the phrase back to rectangles.
2. Add a direct `getOrBuildPageOcr()` API for text selection; never use a blank search as a loader.
3. Clear obsolete search highlights on each query/document revision.
4. Route every page and photo-image mutation through one ViewModel action/reducer that creates history, marks dirty, saves, and requests sync.
5. Use ratio-based dimensions and inverse rotation for shape hit-testing/resizing.
6. Make the full-screen photo overlay truly root-level and let Back close it before leaving the viewer.
7. Replace overflowing bars with a responsive primary-action set plus overflow/scroll behavior.
8. Wire clear-page with confirmation and undo, or remove the dead API.
9. Wire or remove `ToolOptionsSheet`.
10. Move strings to resources and add selected-state semantics/content descriptions.

**Gate:** UI tests cover narrow portrait/landscape layouts, undo/redo, clear-page, page/photo annotation parity, rotated/resized shape selection, phrase search, and Back behavior.

### Stage 9: Privacy, authentication, release, and repository cleanup

1. Gate diagnostics behind debug builds and redact document text/search terms.
2. Remove committed logs, screenshots, generated directories, and deployment-target serial; update `.gitignore`.
3. Decide whether Android Auto Backup is disabled or explicitly scoped. Exclude sync cursors, URI history, Drive/session state, temporary files, and remote-derived caches at minimum.
4. Narrow FileProvider paths to dedicated camera/export directories.
5. Choose the final application ID before distribution and document the effect on existing sideloaded data.
6. Add release signing through environment/Gradle properties without committing secrets; add intentional versioning.
7. Migrate deprecated Google sign-in only after sync correctness is stable. Use one coherent authentication/Drive authorization design and the narrowest scope that supports required backup-folder behavior.
8. Remove dead code/resources only after coverage proves they are unused.
9. Extract `MainActivity.kt` incrementally around tested seams: ViewModel/actions, local repository, sync coordinator, OCR/search, rendering, and UI screens.

**Gate:** signed release artifact, release smoke test, no sensitive repo artifacts, explicit backup rules, current auth path, and all CI gates green.

### Stage 10: Final qualification

Run and record all of the following without admin-only assumptions:

- `assembleDebug`;
- `testDebugUnitTest`;
- `lintDebug` with zero errors and no unjustified new warnings;
- connected instrumentation tests on at least one emulator and the target Pixel device;
- signed release build/install/upgrade test;
- legacy local migration;
- same-name PDF isolation;
- process death and A/B document switching;
- remote/local conflict decisions;
- network interruption during JSON and photo upload/download;
- large PDF, scanned PDF, rotated/cropped PDF, and high-resolution photo memory tests;
- export/import round trip on a fresh install;
- Android backup/restore behavior according to the chosen policy;
- clean working tree and final implementation log.

## Required stop conditions for Codex

Codex must stop the current stage, avoid committing, and report evidence when any of the following occurs:

- a legacy migration fixture loses a field or photo;
- a same-name PDF test shares a Drive identity;
- an older sync generation can overwrite a newer generation;
- a conflict state permits upload;
- a remote apply is visible before it is durably persisted;
- a malformed path escapes the document photo directory;
- any export/import round trip loses scale, shapes, image annotations, or photos;
- lint/build/tests fail;
- a memory fixture exceeds the agreed bitmap budget or crashes;
- a cleanup step cannot prove a file/resource is unused.

## Recommended first Codex assignment

Implement **Stage 0 only**. Do not combine it with persistence, Drive, auth, or architecture changes. The objective is to create reliable gates and fixtures before touching the data model that currently holds the user's work.
