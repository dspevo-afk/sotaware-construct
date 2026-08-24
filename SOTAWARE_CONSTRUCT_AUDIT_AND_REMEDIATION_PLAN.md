# SOTAware Construct Independent Audit & Remediation Plan

> **Canonical remediation roadmap.** This document records the independently verified audit findings and the staged remediation plan for the SOTAware Construct Android app. Codex and any sub-agents working on remediation should treat the staged plan and completion gates below as the controlling roadmap unless a later repository commit explicitly updates this file.

> Audit baseline: `main` at commit `e010bee287894abdcaf29b5e539f16269a94a9c5`.

# Verdict

Yeah, Codex was **not** crying wolf. I independently reviewed `main` at commit `e010bee287894abdcaf29b5e539f16269a94a9c5`, compared the source against all 35 findings in the submitted audit, and found **no original finding contradicted by the code**.

More importantly, several findings were understated, and Codex missed a few defects that belong near the very top of the list:

- Switching PDFs can discard unsaved work.
- Delayed loading from one PDF can contaminate another.
- Multiple sync paths can run concurrently, allowing an older snapshot to overwrite a newer one.
- Search and OCR results can land after the document or query has changed.
- Image-note and image-shape edits bypass the normal undo, save, and sync machinery.
- `.vscode/tasks.json` is not merely messy. It contains two concatenated top-level JSON documents and is invalid.

The app **does not need a rewrite**. The drawing and annotation engine is salvageable. The dangerous part is the state/persistence/synchronization plumbing, where there are several competing versions of “the document” and no single authoritative snapshot.

Until Priority 0 is fixed, I would **not treat Google Drive sync as a trustworthy backup**. Right now, “Sync successful” is occasionally more of a motivational poster than a factual statement.

## Verification limitation

I could not independently rerun the Gradle commands because this runtime could not resolve the required remote dependencies. Therefore, the exact reported outcomes such as “1 lint error and 77 warnings” and the prior passing build remain results from Codex’s environment.

I did independently verify:

- the source causing the lint failure;
- the effectively nonexistent behavioral tests;
- the missing release signing configuration;
- the invalid PowerShell;
- the invalid VS Code task configuration;
- every substantive data-loss, sync, persistence, OCR, rendering, export, security, privacy, and UI finding.

## Severity calibration

A few Codex labels need nuance:

1. Large-PDF freezing or crashing is a **confirmed structural risk**, not something every PDF will trigger.
2. Embedded-text highlight displacement is structurally real, but visible severity depends on crop boxes and rotation in a particular PDF.
3. Drive scope failures depend partly on what access was previously granted and whether the folder is app-created, user-selected, or on a shared drive.
4. An unsigned Gradle artifact is a release-readiness problem, though external signing could theoretically be deliberate.
5. The broad FileProvider paths are defense-in-depth risk, not an immediate remote exploit, because the provider is correctly non-exported.

# Ranked findings

## Priority 0: Data-loss, synchronization, and security blockers

### 1. Document identity is broken locally and remotely

Drive backup folders are identified by the PDF display name, so two unrelated files named `plan.pdf` share the same remote identity. Locally, markup and thumbnail files use `Uri.hashCode()`, a 32-bit hash that is neither stable document identity nor collision-proof. The app also does not recognize that the contents behind an existing URI may have changed.

**Impact:** annotations, scales, photos, measurements, and shapes can be loaded into or overwrite the wrong drawing.

### 2. Switching PDFs can discard unsaved work and mix documents

`onPdfSelected` clears the ViewModel before saving the currently open PDF. Local persistence otherwise happens mainly when the Activity pauses or stops, so recent edits can disappear during an ordinary in-app document switch. The target PDF is then loaded twice, including from a coroutine that does not verify the PDF is still active before applying data.

**Impact:** PDF A can lose work or finish loading late into PDF B.

### 3. Sync payloads disagree, and sync writers can race

Immediate, debounced, and manual uploads omit page shapes. The timer-based path includes them. In addition, immediate photo sync, debounced sync, manual sync, and periodic sync are not serialized through one coordinator. An older snapshot can complete last and overwrite newer work.

**Impact:** a technically successful upload can delete shapes or roll back edits.

### 4. Conflict detection is not a real write barrier

One global `PREF_LAST_SYNC` is shared by every account, backup root, and PDF. It records the device’s current clock rather than a Drive revision or accepted server timestamp. Some conflict dialogs merely appear without blocking every upload route.

**Impact:** another device’s newer work can be overwritten while the user is still deciding what to do.

### 5. Remote restore is incomplete, non-atomic, and not durably acknowledged

Accepted Drive data does not restore page shapes, is applied only to memory, is not atomically persisted, and does not advance a document-specific remote cursor. Pages absent from the remote snapshot are not removed, leaving stale local “ghost” pages mixed into the accepted remote state. Startup acceptance can also leave synchronization blocked.

**Impact:** restored data can vanish on process death, be repeatedly reoffered, or be merged with stale local content.

### 6. Local persistence can corrupt the only copy

The app directly overwrites Java-serialized files using `FileOutputStream`. The operation is non-atomic, synchronous, and not protected by a per-document mutex. On read failure, the loader logs the exception and returns an empty map, making corruption look exactly like “there were never any annotations.” Scales are stored separately, so the two pieces can disagree.

Android provides atomic-file mechanisms specifically to preserve the previous complete file when a write fails. ([developer.android.com](https://developer.android.com/reference/androidx/core/util/AtomicFile.html))

### 7. Untrusted photo names create path traversal and query injection

Imported or remote JSON controls photo filenames. Those names are used in `File(context.filesDir, fileName)` without canonical containment checks and are interpolated into Drive query strings without escaping. A malicious filename such as `../some_private_file` could escape the intended photo directory, overwrite app-private data during download, or cause other app-private files to be uploaded to Drive.

Drive’s query language requires escaping special characters. More importantly, stable file IDs and app properties should replace repeated name queries wherever possible. ([developers.google.com](https://developers.google.com/workspace/drive/api/guides/search-files))

### 8. Remote and import payloads are untyped, unbounded, and partially successful

Deserialization uses raw Gson maps, unchecked casts, direct enum conversion, and almost no validation. There are no limits for JSON size, page count, annotations, photo count, photo dimensions, or numeric values. Individual photo failures are swallowed while the annotation upload still advances the sync cursor and returns success. Downloads write directly to final files, so an interrupted transfer can leave a corrupt photo that appears legitimate.

### 9. Automatic sync is detached from the real lifecycle

`DriveSyncManager` creates its own independent coroutine scope. Session restoration initializes the Drive service but its return value is ignored, leaving Compose’s `isSignedIn` state stale. The synchronization setup effect is keyed only to `pdfUri`, so signing in or selecting a folder while a PDF is already open may never start automatic sync.

Android’s lifecycle-aware coroutine APIs exist so work is canceled or suspended when the owning screen is gone. ([developer.android.com](https://developer.android.com/topic/libraries/architecture/views/coroutines-views))

### 10. Android Auto Backup has no meaningful exclusions

Backup is enabled, while both backup rule files contain only template comments. Markup files, photos, preferences, URI history, document mappings, and synchronization cursors can therefore participate in device backup or transfer unless explicitly excluded. Restoring a stale sync cursor onto a different device could be especially ugly.

Android Auto Backup includes app files and shared preferences by default unless rules exclude them. ([developer.android.com](https://developer.android.com/identity/data/backup?utm_source=chatgpt.com))

### 11. Rendering and export can freeze or exhaust memory

PDF pages are opened, rendered, and allocated on the main thread at source resolution. Thumbnail generation performs PDF I/O during composition. OCR allows an 8192 × 8192 ARGB bitmap, approximately 256 MiB before secondary copies. Full-screen photos decode at full resolution in composition, and PDF export can simultaneously retain the blueprint bitmap plus multiple photo bitmaps. Several are not reliably recycled.

Android explicitly warns against blocking disk and expensive work on the main thread. ([developer.android.com](https://developer.android.com/topic/performance/threads))

### 12. Sensitive text and high-volume diagnostics are logged and committed

OCR samples, search terms, matched text, note positions, shape geometry, and export details are logged unconditionally in several paths. The repository contains large captured log files and blueprint screenshots, including multi-megabyte artifacts.

For a construction-document app, blueprint text in Logcat is not cute. It is customer data wearing a name tag.

## Priority 1: Serious correctness and usability defects

### 13. Async results are not tied to a document or query revision

Search is keyed mainly by a trigger counter. OCR selection caches are keyed by page index. Load and search jobs do not consistently capture and validate the current document identity, source revision, page, and query revision before applying results. Existing highlights are merged rather than cleared when the search changes.

### 14. OCR pre-caching is quadratic, cancellation-hostile, and leaks recognizers

Each page reparses the entire PDF, creating approximately O(N²) document work. A new ML Kit recognizer is created per page and not closed. The bitmap is recycled before later code reads its dimensions. Generic exception handling can swallow coroutine cancellation and continue processing after the document has been closed. Failed or canceled pages can still result in the entire document being marked cached.

### 15. Embedded-text coordinate conversion is incomplete

The conversion mixes PDFBox text coordinates with media-box normalization without consistently accounting for crop-box offsets, rotated coordinate systems, and top-left versus bottom-left origins. Codex’s highlight-displacement finding is legitimate.

### 16. Phrase search and uncached text selection are broken

Search compares the complete query against individual word-level OCR boxes, so phrases such as “fire exit” cannot normally match adjacent words. Text selection attempts to load OCR by performing a blank search, while blank searches return immediately.

### 17. Export and import are stale, incomplete, and can lie

Save-file export rereads the last disk snapshot instead of exporting current in-memory state. It omits scale and does not embed photos. A nullable output stream can produce no output while the app still displays success. Import writes a disk file but does not consistently update the active ViewModel or scales.

### 18. Photo lifecycle management is incomplete

Deleting a photo pin removes only its reference, not its local image files, temporary camera file, Drive copies, image notes, or image shapes. Camera input can fail while a filename is still added. Failed remote photo operations do not invalidate the surrounding synchronization.

### 19. Image annotation edits bypass canonical history, persistence, and sync

Image notes and image shapes mutate nested mutable collections directly. Many edits, moves, rotations, resizes, and deletions create no `HistoryAction`, do not mark the document dirty, and do not trigger local save or Drive sync. Shape hit-testing also uses stale legacy width and height fields in paths where rendering uses ratio-based dimensions, and it does not consistently transform points through inverse rotation.

### 20. Cache identity and eviction are incomplete

Thumbnail and OCR caches do not consistently include document identity plus source revision. Bitmap caches are not byte-budgeted and may retain large bitmaps across document changes. A small entry-count cache is not enough when one entry might be a tiny page and another might be a blueprint-sized memory rhinoceros.

Android’s `LruCache` can be sized by bytes rather than merely counting entries. ([developer.android.com](https://developer.android.com/reference/android/util/LruCache))

### 21. Drive authorization, browsing, pagination, and read behavior are inconsistent

Sign-in requests `DRIVE_FILE`, `DRIVE_APPDATA`, and full `DRIVE`, but the API credential is created only with `DRIVE_FILE`. Folder listings request only the first page. Conflict checks call `createPdfFolder`, so a read/check operation can mutate the user’s Drive by creating folders. Shared-drive folder handling is also inconsistent.

The full Drive scope is broad and restricted, while `drive.file` is intended for files the application creates or the user explicitly selects. ([developers.google.com](https://developers.google.com/workspace/drive/api/guides/api-specific-auth))

### 22. “Recent files” are sorted alphabetically

The app stores recent URIs in an unordered `StringSet` and sorts by filename in reverse alphabetical order. This also means OCR pre-caching chooses alphabetically convenient files rather than recently used files.

### 23. Viewer controls overflow ordinary phone widths

The portrait top bar contains a navigation button plus seven action buttons. The bottom tool rail contains all eight tool modes in a fixed, non-scrollable row. At 48 dp each, the controls exceed a common 360 dp viewport before padding is counted.

### 24. Undo, clear-page, and tool-option behavior is inconsistent

Undo and redo stacks use plain mutable lists stored inside Compose state maps, so enabled state may not recompose reliably. `onClearPage` is passed into `ToolRail` but never called. `ToolOptionsSheet` has no call site, and its `onDismiss` callback is unused.

### 25. Localization and accessibility are incomplete

`strings.xml` contains only the app name. Nearly all interface text is hard-coded. Tool selection is conveyed primarily through color without complete selected-state semantics, and several decorative icons use null descriptions without a surrounding accessible state.

## Priority 2: Build, test, and release blockers

### 26. Lint currently fails

The suspicious indentation near the embedded-text result path is real and provides a source-level explanation for the reported lint failure. The broader warning count still needs to be regenerated in the actual Android build environment.

### 27. Behavioral test coverage is essentially zero, and there is no CI

The unit test proves that `2 + 2 == 4`. The instrumentation test proves the package name. There are no tests for persistence, migration, Drive conflict handling, same-name PDFs, OCR, rendering, export, import, process death, or UI editing flows. The repository contains no GitHub Actions workflow.

### 28. Developer automation is broken in two separate places

`android_env.ps1` contains invalid PowerShell conditional syntax. Separately, `.vscode/tasks.json` contains two complete JSON objects concatenated together, making the entire file invalid JSON. Codex caught the first and missed the second.

### 29. Release identity and packaging are unfinished

The application still uses `com.example.myapplication`, version code `1`, version name `1.0`, and has no release signing configuration. The release artifact is therefore unsigned unless an external signing process is intentionally used.

The application ID should be chosen before public distribution because changing it later creates a different installed application rather than a normal update. ([developer.android.com](https://developer.android.com/studio/publish/preparing?authuser=19&hl=en&utm_source=chatgpt.com))

### 30. Google sign-in uses a deprecated integration path

The app uses the legacy `GoogleSignIn` APIs. Google now directs Android applications toward Credential Manager and the newer Google Identity authorization approach. This should be migrated only after synchronization correctness is stable, not while the backup system is already undergoing surgery. ([developer.android.com](https://developer.android.com/identity/legacy/gsi))

## Priority 3: Cleanup and maintainability

### 31. Repository hygiene is poor

The repository includes large logs, screenshots, temporary Gradle directories, generated files, and an IDE deployment file containing a physical device serial. `.gitignore` does not cover most of these artifacts.

### 32. Dead code and resources remain

`ToolOptionsSheet` has no call site. Other unused OCR methods, caches, XML layouts, drawables, splash assets, scopes, and Drive helper functions are present. They should be removed only after tests prove there is no hidden dependency.

### 33. FileProvider exposes broader roots than necessary

The provider exposes all of `filesDir` and `cacheDir`. Since the provider is non-exported and grants are temporary, this is lower risk than the path traversal problem, but dedicated camera and export subdirectories would be safer.

Android FileProvider supports narrowly scoped path entries rather than exposing whole application roots. ([developer.android.com](https://developer.android.com/reference/androidx/core/content/FileProvider))

### 34. `MainActivity.kt` is a risk multiplier

`MainActivity.kt` is roughly 344 KB and combines data models, ViewModel behavior, navigation, Compose screens, gestures, rendering, OCR fallback, local persistence, import/export, and Drive orchestration. That makes changes harder to isolate and test. However, splitting it first would simply distribute the existing bugs across prettier folders. Establish stable snapshot, repository, and sync seams first.

# Step-by-step Codex implementation plan

Codex should handle **one stage per task and one logically focused commit**. Do not let it disappear into the cave for six hours and return with “refactored everything.”

## Stage 0: Establish reliable gates

1. Create a remediation branch from `e010bee…`.
2. Add `CODEX_AUDIT_ROADMAP.md`.
3. Add `CODEX_AUDIT_IMPLEMENTATION_LOG.md`.
4. Fix only:
   - the PowerShell parser error;
   - the invalid `.vscode/tasks.json`;
   - the lint-blocking indentation.
5. Add CI for:
   - `assembleDebug`;
   - `testDebugUnitTest`;
   - `lintDebug`.
6. Add deterministic fixtures:
   - two different PDFs both named `plan.pdf`;
   - a rotated and cropped embedded-text PDF;
   - a scanned PDF;
   - a large blueprint;
   - a high-resolution phone photo;
   - a fully populated annotation snapshot;
   - malformed and malicious JSON.
7. Add characterization tests for every currently serialized field.

**Gate:** debug build passes, tests pass, lint has zero errors, device smoke passes, working tree is clean.

## Stage 1: Create one canonical document snapshot

1. Add a typed, versioned `DocumentSnapshotV1`.
2. Include every persisted domain:
   - document identity;
   - pages;
   - paths;
   - measurements;
   - notes;
   - page shapes;
   - scale;
   - photo pins;
   - photo metadata;
   - image notes;
   - image shapes;
   - schema version;
   - snapshot revision.
3. Implement exactly one `snapshotFromState()`.
4. Implement exactly one `applySnapshotReplace()`.
5. Make immediate, debounced, manual, and periodic synchronization use that same snapshot.
6. Define replacement semantics so pages absent from an accepted snapshot are deleted rather than left as ghosts.
7. Add round-trip canaries for shapes, scale, photos, image notes, and image shapes.

**Gate:** every sync path produces logically identical snapshot content.

## Stage 2: Replace local persistence safely

1. Create an app-generated `DocumentId` UUID.
2. Maintain a manifest mapping source URI/provider metadata to `DocumentId`.
3. Use a content fingerprint only to detect changed source contents, not as the sole identity.
4. Introduce `LocalDocumentRepository`.
5. Store typed snapshots with atomic replacement on `Dispatchers.IO`.
6. Use a per-document mutex.
7. Store scale in the same transaction.
8. Keep the previous good snapshot.
9. Quarantine corrupt snapshots and display a recoverable error.
10. Migrate legacy `markups_<hash>.bin` files and separate scale preferences.
11. Read the migrated snapshot back and verify every field before marking migration complete.
12. Preserve legacy files until final qualification.

**Gate:** an interrupted write always recovers the previous complete snapshot, and corruption never silently becomes a blank document.

## Stage 3: Make document switching transactional

1. Cancel and join all document-scoped jobs.
2. Flush the current dirty snapshot.
3. Clear document-scoped UI state and caches.
4. Load the target document exactly once.
5. Apply the result only when its document token remains current.
6. Key rendering, thumbnails, OCR, search, and selection by:
   - `DocumentId`;
   - source revision;
   - page index;
   - query revision where applicable.
7. Discard stale results.
8. Add debounced local autosave after every mutation.
9. Use a final non-cancellable flush for explicit document switching.

**Gate:** rapid A/B/A switching cannot lose or cross-contaminate work.

## Stage 4: Replace synchronization with one serialized coordinator

1. Extract a `DriveGateway` interface and in-memory fake.
2. Add a lifecycle or ViewModel-scoped `SyncCoordinator`.
3. Use a per-document mutex and monotonically increasing local generation.
4. Route every sync request through one queue.
5. Prevent an older generation from overwriting a newer generation.
6. Store Drive folder and file IDs.
7. Tag remote files with an app property containing `DocumentId`.
8. Store synchronization metadata per:
   - Google account;
   - selected backup root;
   - `DocumentId`.
9. Use Drive revision or modified metadata as the cursor, not the device clock.
10. Add explicit states:
    - `Idle`;
    - `Dirty`;
    - `Uploading`;
    - `Conflict`;
    - `ApplyingRemote`;
    - `Error`.
11. Block every upload while in `Conflict`.
12. On remote acceptance:
    - download and validate everything;
    - atomically persist locally;
    - replace memory;
    - record the accepted remote cursor;
    - clear conflict state;
    - restart sync.
13. Stop creating folders during read/check operations.
14. Add Drive pagination.

**Gate:** fake-Drive tests prove same-name isolation, conflict blocking, lifecycle cancellation, complete remote replacement, and prevention of stale-generation overwrite.

## Stage 5: Harden filenames, payloads, and photo transactions

1. Generate internal photo IDs and safe fixed filenames.
2. Store photos under `files/documents/<documentId>/photos/`.
3. Validate canonical containment on every file operation.
4. Stop interpolating untrusted names into Drive queries.
5. Replace raw Gson maps with typed DTOs and explicit migrations.
6. Validate enums, finite numbers, ranges, and required fields.
7. Set limits for:
   - JSON size;
   - pages;
   - annotations;
   - photos;
   - file size;
   - image dimensions.
8. Download into a temporary file.
9. Validate size, hash, and bitmap decodability.
10. Atomically rename only after validation.
11. Treat required-photo failures as complete sync failures.
12. Add safe deletion tombstones or garbage collection.

**Gate:** traversal and query-injection fixtures are rejected, and partial transfers can never report success.

## Stage 6: Make import/export current and self-contained

1. Export the current canonical snapshot, not a stale disk read.
2. Create a `.sotaware` bundle containing:
   - versioned manifest;
   - snapshot;
   - referenced photos.
3. Include scale, page shapes, image notes, and image shapes.
4. Report success only after the destination stream closes successfully.
5. Import through the same typed validator and atomic repository.
6. Apply imported state to memory only after durable persistence.
7. Add zip-slip and zip-bomb protection if ZIP is used.

**Gate:** importing onto a fresh installation reproduces every annotation, photo, shape, and scale.

## Stage 7: Fix rendering and OCR

1. Move PDF I/O, bitmap decoding, EXIF transforms, rendering, and export off the main thread.
2. Establish a maximum pixel and memory budget.
3. Render for the viewport rather than uncapped source resolution.
4. Decode photos using sampling.
5. Use a byte-sized LRU cache.
6. Release cache resources when evicted or when a document closes.
7. Open a PDF once per OCR session.
8. Reuse and close one ML Kit recognizer.
9. Rethrow `CancellationException`.
10. Never mark canceled or failed OCR as complete.
11. Capture bitmap dimensions before recycle.
12. Create a tested `PdfCoordinateMapper` covering media box, crop box, rotation, and UI normalization.
13. Enable StrictMode during development tests.

**Gate:** large fixtures remain below the agreed bitmap budget, OCR cancels promptly, and coordinate golden tests pass.

## Stage 8: Repair search, annotation actions, and responsive UI

1. Implement phrase search across adjacent OCR boxes and lines.
2. Add `getOrBuildPageOcr()` for selection.
3. Clear obsolete highlights whenever the query or document changes.
4. Route every page and image annotation mutation through one ViewModel reducer.
5. Every reducer action must:
   - create history;
   - mark dirty;
   - save locally;
   - request synchronization.
6. Fix shape hit-testing using ratio dimensions and inverse rotation.
7. Ensure Back closes the full-screen photo overlay before leaving the viewer.
8. Replace overflowing phone bars with primary actions plus overflow or scrolling.
9. Wire clear-page with confirmation and undo, or remove it.
10. Wire or remove `ToolOptionsSheet`.
11. Move UI text to resources.
12. Add selected-state and accessibility semantics.

**Gate:** UI tests cover narrow portrait and landscape, undo/redo, clear-page, PDF/image annotation parity, rotated shapes, phrase search, and Back behavior.

## Stage 9: Privacy, authentication, release, and cleanup

1. Gate diagnostics behind debug builds.
2. Redact search terms, OCR text, document names, and annotation content.
3. Remove committed logs, screenshots, generated directories, and device serials.
4. Expand `.gitignore`.
5. Disable Auto Backup or define explicit exclusions.
6. Narrow FileProvider paths.
7. Choose the final application ID before distribution.
8. Configure release signing through environment or Gradle properties without committing secrets.
9. Add intentional versioning.
10. Migrate the deprecated sign-in flow after sync correctness is stable.
11. Remove dead code only after coverage proves it unused.
12. Then incrementally extract `MainActivity.kt` around the tested seams:
    - ViewModel and actions;
    - local repository;
    - synchronization;
    - OCR/search;
    - rendering;
    - screens.

**Gate:** signed release artifact, explicit backup policy, current authentication path, clean repository, and all CI checks green.

## Stage 10: Final qualification

Codex must run and record:

- debug build;
- unit tests;
- lint with zero errors;
- connected instrumentation tests;
- Pixel device smoke;
- signed release install and upgrade;
- legacy local migration;
- same-name PDF isolation;
- rapid document switching;
- process death recovery;
- local/remote conflict decisions;
- interrupted JSON and photo transfers;
- large, scanned, rotated, and cropped PDFs;
- high-resolution photo memory tests;
- export/import on a fresh install;
- backup and device-transfer behavior;
- clean working tree;
- completed implementation log.

# Rules Codex should follow during remediation

1. **One stage per Codex run and one focused commit.**
2. **Do not push automatically.**
3. **Do not commit when a required gate fails.**
4. Update `CODEX_AUDIT_IMPLEMENTATION_LOG.md` after every stage with exact commands and results.
5. Preserve legacy serialized classes and fully qualified names until migration is implemented and proven.
6. Do not delete legacy local files or Drive folders during migration.
7. Do not begin by broadly splitting `MainActivity.kt`.
8. Do not claim a sync, export, import, or migration succeeded when any required component failed.
9. Stop immediately if a test shows:
   - same-name PDFs share identity;
   - stale sync generations overwrite newer ones;
   - conflict state allows upload;
   - remote state reaches the UI before durable persistence;
   - malformed filenames escape the photo directory;
   - scale, shapes, photos, or image annotations disappear;
   - build, lint, or tests fail.

The correct first assignment for Codex is **Stage 0 only**. No persistence rewrite, Drive rewrite, auth migration, or grand architectural opera until the test gates and fixtures exist.
