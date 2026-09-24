# SOTAware Construct: full-codebase audit, September 14, 2026

## Disposition

**Later repair closeout:** the five open functional findings below are repaired and locally qualified; see [the closeout and remaining qualification limits](REPAIRS.md). The original audit observations and failures are retained here as history.

**Small repairs implemented locally. Audit is not release approval.** The final physical-tablet run reproduces the project-browser layout crash. Four other application correctness/recovery findings remain open. No P0 defect was confirmed, but the open findings and incomplete release qualification prevent a clean bill of health.

Audited branch: `codex/stage-3-transactional-switching`.
Unchanged HEAD: `c265c963788261b23126fea8cc91e2812d739539`.
The target is the actual working tree, including pre-existing unpublished project-folder, page-code, and annotation-tool features. It is not merely the committed application or the older attached reports. All pre-existing edits were backed up and preserved. No commit, push, merge, branch deletion, signing change, or stage advancement was performed.

## Scope and method

The baseline inventory contained 370 source-controlled or nonignored files, including 97 production Kotlin files. The final Kotlin tree contains 51,707 lines. The entire inventory was searched; manual deep review concentrated on canonical state, repository publication/recovery, transactional switching, sync finalization and ownership, photo retention, bundle admission/rollback, export geometry, annotation numeric/ordering contracts, project downloads, OCR/session lifetime, authentication boundaries, and development scripts. Build configuration, manifest/backup/FileProvider XML, existing tests and prior audit evidence were examined.

Kotlin source paths below are relative to `app/src/main/java/com/example/myapplication/`.

This was a risk-based source audit plus actual host and native execution, not a claim that every line or possible execution path was exhaustively verified. Tests used synthetic fixtures on the authorized TB336FU tablet, API 36. Real Drive files and account data were not used for mutation tests. The app's pre-test private data was backed up before package resets; native qualification intentionally resets only the target application and uses the fixture test package.

## Repairs made

| ID | Priority | Repair | Evidence |
| --- | --- | --- | --- |
| F1 / S13-01 | P1 | Preserve physical PDF page dimensions when the raster is downsampled | Reproduced before repair on tablet; native regression passes after repair |
| F2 / S13-03 | P2 | Guard extreme calibration arithmetic before display and measurement mutation | Five new JVM regressions; native extreme-scale recovery-message test passes |
| F3 | P3 | Bound repeated zero-byte download reads | Two new JVM regressions for nonprogress and transient zero reads |
| F4 / S13-07 | P2 | Restore fail-closed app-only logging in this feature branch | Seven actual-script mocked-ADB cases pass |
| F5 | Test infrastructure | Repair obsolete tool selectors and unstable fixture-provider selection | Original assertions preserved; final native results below |

### F1: PDF physical size

`MainActivity.kt:8405-8410` now creates the output page from source point-space dimensions and stretches the bounded raster into that original rectangle. Memory limits are unchanged. The pre-fix tablet probe expected width 3456 points but observed 3265. The fix passes a regression covering 3456×2592, 2592×3456, and 792×612-point pages. Final production SAF cropped/rotated PDF export also passes. Android's PageInfo contract defines dimensions in PostScript points, so using raster pixel dimensions was a physical-scale error, not just lower image quality.

### F2: numeric overflow and imported extreme scales

`stage8/ViewerInteraction.kt` adds a nullable source-distance formatter using Double arithmetic and an explicit whole-inch range boundary. PDF scale display, new measurements, endpoint resizing, and the shared polyline/photo builder use it. Invalid display shows a recalibration message; invalid creation is rejected; invalid resizing retains the previous draft instead of persisting misleading geometry/text. The legacy formatter no longer silently saturates oversized integer results. Existing admitted snapshot bytes are not rewritten, and the persisted schema is unchanged. This does **not** fix stale labels after ordinary recalibration, which remains O1.

### F3: nonprogressing project streams

`projects/ProjectDownloads.kt:133-156` previously continued indefinitely when a stream repeatedly returned zero bytes. It now uses the existing Stage 5 no-progress limit, resetting the counter when bytes arrive. Tests verify bounded failure, stream closure, no library publication, cleanup of owned staging, and acceptance after a short run of zero reads. This is defensive handling of a broken/nonconforming stream, not evidence that ordinary Google Drive streams behave this way. It does not solve process-death recovery, O4.

### F4: feature-branch logging regression

The current feature branch still had the older fallback that read the broader device log when app PID resolution failed. The already-reviewed fail-closed script and its tests were brought across from existing commit `892e7bf`, without merging branches. Missing, malformed, multiple, overflowing, or failed PID resolution now collects no logs. This is a carry-forward repair of S13-07, not a newly discovered vulnerability. No real device logs were used by the mocked script tests.

### F5: test navigation matched to current UI

The SAF helper preferred a coordinate tap while the provider drawer was moving. It now prefers the actual accessible root-row action and retains a visible-node coordinate fallback. The corrected production SAF sequence passes all seven phases. Two calibration tests still clicked the removed "Close tool options" control; those obsolete clicks were removed, not their calibration, history, or mutation assertions. The photo admission test still looked for visible "Note" text instead of the new accessible icon rail; its selector was updated. An additional native test verifies that an admitted extreme scale displays the recovery message without crashing or creating a measurement.

## Open findings requiring larger work

| ID | Priority | Current disposition |
| --- | --- | --- |
| O1 / S13-02 | P2 | Ordinary recalibration leaves existing measurement labels stale; confirmed in current source |
| O2 / S13-04 | P2 | Accepted dense photo pins can fail PDF export; current source still contains the previously reproduced layout defect |
| O3 / S13-05 | P2 | Photo drawing/export/selection order disagrees; confirmed in current source |
| O4 / S13-06 | P2 | Interrupted project downloads lack startup reconciliation; confirmed missing recovery path |
| O5 / K01 | P2 | Project-browser layout crash reproduced in this final physical-tablet run |

### O5: project-browser crash, freshly reproduced

The final candidate fails `ProjectBrowserInstrumentedTest.actualFolderPickerSubfoldersRecentReopenAndStartupPreserveDrawing` with `IllegalArgumentException: measure is called on a deactivated node`. The invocation ends with instrumentation completion code 0, so it is failed/unfinished, not a pass merely because other methods reported success. The same class passed in the earlier baseline run. This matches previously recorded K01, but the exact root cause is still unproven. The production project-browser files were not changed by this audit.

Investigate `projects/ProjectBrowserScreen.kt`'s lazy-list lifecycle and navigation/refresh transitions together with the fixture's retained/reopened state. Preserve a deterministic reproduction before changing keys or dependencies. Required acceptance: repeated local-folder selection, nested navigation, drawing open/back, recent-file reopen, recreation and orientation changes with accumulated state, followed by the complete native matrix. Deleting the problematic state or catching the framework exception is not a repair. No lasting drawing-data loss was established by this failure.

### O1: stale measurement labels after recalibration

`stage8/AnnotationReducer.kt:276-286,572-601` changes page or image scale without recomputing existing `Measurement.text`. Rendering/export use that stored text. A dimension originally calculated from 144 source points at 12 points/foot remains labeled 12 feet after the page changes to 24 points/foot, although that geometry now represents six feet. Earlier audit probes demonstrated this; the relevant reducer behavior remains unchanged in the current candidate.

Repair through one explicit numeric/calibration owner. Supply the correct PDF or photo geometry and update scale plus affected labels as one bounded, undoable transaction, or derive labels from canonical geometry and calibration. Cover straight and polyline dimensions, PDF and photos, undo/redo, save/reopen, export and invalid-result rejection. Do not silently mix historical and current scale conventions.

### O2: accepted photo counts exceed the appendix layout

`MainActivity.kt:8444-8450` places all images of a pin on one page. A valid 128-photo pin requires 64 rows in the two-column layout. On a 792×612-point page, the calculated maximum image height is negative, causing decoding/layout failure. This audit rechecked the current source and arithmetic; it did not rerun the older dedicated 128-photo native probe. The prior audit contains that executed reproduction.

Paginate photos at a positive, readable minimum cell size; include continuation headings and every photo/annotation exactly once. Keep dimensions and aggregate memory bounded, including the PDF writer's retained resources. Test 1, 2, intermediate and 128 photos in both orientations, cancellation, missing assets and destination failure. Lowering admission limits is not sufficient for already accepted documents.

### O3: photo annotation layer order differs

`MainActivity.kt:7871-7872,7930-7990,8515-8535` retains inconsistent composition. The viewer paints paths, measurements, notes, then shapes; export paints notes before paths and measurements. Photo selection checks paths before measurements even though measurements are painted above paths. Deliberate overlaps can therefore export differently or select the lower object.

Use one explicit ordered scene shared by display/export, and hit-test it in reverse. Keep selection/draft decorations separate from persisted/exported content. Add cross-kind overlap and topmost-selection regressions rather than relying only on separate single-annotation tests. This finding is source-confirmed; no new pixel-overlap test was executed in this pass.

### O4: project download publication is not process-recoverable

`projects/ProjectDownloads.kt:124-200` stages under an owned `.incoming-<uuid>` directory, renames into a final directory, then registers in the project library. Ordinary exceptions and cancellation run its cleanup, but process death bypasses that block. Startup has no journal/reconciliation path for interrupted staging or renamed-but-unregistered projects. Repeated interrupted downloads can strand substantial private storage. This is a recovery-path gap, not observed deletion of an accepted project.

Add a durable publication journal with exact ownership, active-operation fencing and bounded restart reconciliation. Preserve accepted/uncertain content; clean only proven uncommitted output. Inject process death after writes, verification, rename, library publication and journal retirement. Restart must recover a complete accepted project or safely reclaim only its abandoned staging.

## Architecture priorities

Extract workflow boundaries after the correctness repairs, not a wholesale rewrite. `MainActivity.kt` has 8,632 lines; `SyncCoordinator.kt` 4,192; `PhotoAssetStore.kt` 4,146; `LocalDocumentRepository.kt` 2,451; `DocumentBundleService.kt` 2,387; and `DriveImmutableAssetTransfer.kt` 2,200. The highest-value separations are PDF export/physical geometry, measurement/calibration, project lifecycle, and shared annotation scenes. Preserve the existing token fences, durable acceptance markers, recovery journals, photo leases and bounded history during every extraction. Renaming stage packages or moving everything into a generic helpers file is lower priority.

## Executed validation

| Gate | Observed result |
| --- | --- |
| Final JVM suite | 1,008 collected: 1,002 passed, six existing skips, zero failures/errors |
| Debug application and instrumentation APKs | Both built successfully |
| Final lint | Zero errors; 118 warnings remain |
| Native qualification-runner safety/parser tests | 36 passed |
| App-only logcat actual-script tests | Seven passed; mocked ADB only |
| JSON/XML and Git whitespace checks | Applicable VS Code JSON and Android XML parsed; `git diff --check` passed |
| Physical-tablet native matrix | FAIL: 46 of 48 invocations fully passed; project-browser invocation failed; one invocation retained a hard-link capability skip |
| Production SAF sequence | Seven phases passed: PDF export, picker recreation, picker cancellation, bundle export, bundle import, process relaunch, retired-format rejection |
| Interrupted-publication recovery | Stage and actual process-restart phases passed |
| PDF-size regression | Failed before repair on tablet; passed after repair across three physical sizes |
| Extreme-scale native regression | Passed: recovery message displayed without crash or unwanted measurement |

The 123 planned test identities produced 121 PASS, one ERROR and one SKIP terminal statuses. This is not 121 fully qualified tests: the browser invocation ended with completion code 0, and the security invocation contained a capability skip. There are 115 tests in wholly passing invocations. All planned invocations were attempted; the separately declared live-provider class was omitted. The repaired photo-note admission test passes. The browser failure and the capability skip remain visible in the final summary.

Host commands were run from the existing repository using its configured Android Studio JDK and Android SDK:

```powershell
.\gradlew.bat --no-daemon --no-build-cache --stacktrace --console=plain -Pkotlin.incremental=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat --no-daemon --console=plain -Pkotlin.incremental=false :app:assembleDebugAndroidTest :app:lintDebug
python -B tools/test_native_qualification.py
powershell -NoProfile -File tools/test_run_logcat_app.ps1
```

The initial ordinary host invocation was mostly up-to-date, so it was not counted as fresh execution. A later incremental compile failed with widespread unresolved references to existing top-level Kotlin declarations. A non-incremental rebuild passed without a source workaround for those errors. The precise cache/compiler cause was not established; no permanent Gradle setting or dependency upgrade was made. Subsequent final unit tests and lint executed successfully. Earlier failures remain in the evidence rather than being relabeled green.

The six host skips concern platform-specific symlink, provider-alias or descriptor-relative filesystem behavior. The physical device separately skips the existing hard-link capability case. Account-dependent live-provider qualification was deliberately omitted by the checked-in runner and remains blocked. Successful local fixtures do not qualify real multi-account conflicts, OAuth deployment, signed release installation/upgrade, Pixel-specific behavior, cloud restore or device transfer. No complete transitive vulnerability scan or history-wide secret scan was performed.

## Evidence and handoff

Task evidence is retained in the task-owned OS-temp directory named `construct-full-audit-20260913-iy6thlt5`. It includes the baseline source copy and hashes, original worktree patch, pre-test tablet app-data archive, checkpoint, red PDF-size reproduction, host logs/JUnit reports, per-command native logs, parsed native checkpoints, final APK identities, and an audit-only patch against the preserved working-tree baseline. Substantial logs and private absolute paths are not committed to the repository.

Final application APK SHA-256: `ea3a7ce9c9a50c258060664e65eabf5c9e68edf2c148b749c78b3fea90787d86`.
Final instrumentation APK SHA-256: `a286f78074a365cab2f11fb623cfeac963631212eae865d890775457f217f5c3`.
Audit-only patch SHA-256: `f1c0b8f0aa208fb02f53a0380d5e42e153b55074ff6482c23963d9aa86bafae2`.

The final audited debug APK is installed on the physical tablet and its installed SHA-256 matches the tested artifact. Rotation settings match the original values. A final combined cleanup request was blocked by platform safety checks and did not execute; the target/test-fixture installations and any remaining synthetic test state were left in place. The pre-test backup contained no document paths. No build/native worker is left running; the shared ADB server is not stopped.

The practical repair order is: stabilize the project-browser crash, make recalibration and labels one coherent transaction, paginate dense photo appendices, unify photo annotation ordering, then implement process-safe project-download recovery. Requalify the combined candidate before treating the unpublished feature work as finished. The current audit fixes should be preserved while those larger repairs are made.

## External API contracts checked

Android Developers: `PdfDocument.PageInfo.Builder`, physical page dimensions are PostScript points (1/72 inch), not raster pixels. Oracle Java SE: `InputStream.read(byte[], int, int)`, a nonempty-buffer read normally returns positive progress or EOF; repeated zero results are defensive fault handling. These references support the API interpretation, not the repository-specific test outcomes.
