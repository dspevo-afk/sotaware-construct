# SOTAware Construct: September 13, 2026 repository audit

## Disposition

Audit and developer-tooling cleanup, not release approval. Six application findings remain open: four reproduced against the committed application and two established by inspection of unpublished feature work. Two tooling findings are fixed. A previously documented project-browser native crash remains open separately.

Audited application: `c265c963788261b23126fea8cc91e2812d739539`.
Main before consolidation: `43102cc9dfcc9fb53cf44aff707ca6867aadd225`.

The original checkout has substantial uncommitted project-browser, page-code and annotation-tool changes. These were preserved and copied into an isolated snapshot for review/testing, not committed or included in main. The 370-file snapshot manifest has SHA-256 `ebcd7b910d845d511c6c02e6b80e19d5d354dc57330737aec3d85bba8e5ebe20`; original source hashes were checked again after the work.

All seven initial remote branch tips are ancestors of the audited committed application. Main can therefore advance by ordinary fast-forward, without cherry-picks or history rewriting. The active development branch must remain because the original checkout still has unpublished work. Historical branch deletion is limited to verified, incorporated, inactive refs. Full Git history and the initial branch inventory were backed up outside the repository.

## Scope and qualification boundary

The sweep examined instructions and ancestry; build/CI and scripts; calibration, annotation state and rendering; PDF/photo export; canonical storage/recovery and sync/photo ownership paths; and the unpublished project/download, page-code and markup additions. Existing host suites and focused newly written probes were executed. This is a broad risk-based review, not a proof that every path on every device is correct.

The housekeeping candidate leaves production Kotlin, dependencies, Android build configuration and signing identity unchanged. It does not implement the application repairs below. The passing ordinary suite and the failing new probes are reported separately, rather than pretending green CI proves the application is finished.

## Findings

| ID | Priority | Finding | Disposition |
| --- | --- | --- | --- |
| S13-01 | P1 | Large PDF exports change physical paper dimensions | Open; actual Android reproduction |
| S13-02 | P2 | Recalibration leaves existing measurement labels stale | Open; actual reducer regression |
| S13-03 | P2 | Admitted tiny scale makes measurement formatting throw | Open; validator/reducer/formatter reproduction |
| S13-04 | P2 | Accepted dense photo pins cannot export | Open; actual Android reproduction |
| S13-05 | P2 | Photo display/export/hit-test stacking differs | Open; unpublished snapshot source finding |
| S13-06 | P2 | Download staging has no process-death reconciliation | Open; unpublished snapshot source finding |
| S13-07 | P2 | App-only log tasks fall back to broader device logs | Fixed; actual-script tests pass |
| S13-08 | P3 | Nonportable and duplicate IDE task commands | Fixed; actual-script tests pass |
| K01 | P2 | Known accumulated-project native layout crash | Still open; prior evidence, not a new reproduction |

No P0 defect was confirmed. Previously repaired findings are not relabeled as new defects.

### S13-01: exported physical scale changes with bitmap downsampling

Committed source: `MainActivity.kt:8070-8077,8232-8244`, and `stage7/BitmapBudget.kt:73-78,152-170`. Application paths here are relative to `app/src/main/java/com/example/myapplication/`.

The exporter retains original PDF dimensions and obtains a bounded raster plan, but constructs `PdfDocument.PageInfo` from `bitmap.width` and `bitmap.height`. Once the raster exceeds the memory budget, downsampling also changes physical page dimensions. Photo appendix pages instead use the original size.

The native probe creates a 3456-by-2592-point source (48 by 36 inches). Its bounded raster is 3265 by 2449. Opening the exported PDF fails the width assertion: expected 3456, actual 3265. Thus a 48-inch sheet becomes approximately 45.35 inches wide, about 5.5% smaller. Android's official `PdfDocument.PageInfo.Builder` contract specifies dimensions in PostScript points (1/72 inch), not raster pixels.

Repair: retain original point-space dimensions in PageInfo and draw the bounded raster into the original page rectangle. Do not raise memory limits to mask the defect. Give physical page sizes and raster pixel sizes separate types.

Acceptance: exact page dimensions and annotation positions for letter/large-format, portrait/landscape, rotation/crop and appendix pages; memory limits and verified-source/cancellation behavior remain intact. Vector-preserving output is a later quality improvement, not a prerequisite for this fix.

### S13-02: scale changes do not update existing dimensions

Committed source: `stage8/AnnotationReducer.kt:272-279,553-560`; `AnnotationModels.kt:30-37`; rendering/export uses stored `Measurement.text`.

The reducer records a scale-only history entry. Existing labels remain calculated under the old scale. The JVM probe creates a 144-source-point measurement at 12 points per foot, labeled 12 feet. Changing the page to 24 points per foot leaves the text at 12 feet, where the new scale implies six feet.

Repair: derive dimension text from geometry/current calibration, or atomically recompute all affected labels in one undoable recalibration transaction. If historical per-measurement calibration is intended, explicitly store and show it; do not present mixed calibration under one apparent page scale.

Acceptance: PDF, photo and polyline cases, recalibration, undo/redo, save/reopen and export; no partial updates or bypass of dirty/save/sync effects.

### S13-03: admitted numeric values exceed the formatter's contract

Committed source: `stage8/CalibrationInput.kt`, `stage5/PayloadSecurity.kt` and `stage8/ViewerInteraction.kt:17-22`. Unpublished plain-PDF creation also directly divides and formats in `MainActivity.kt:6703-6705`.

Positive finite scale does not imply finite measurement length. A current-format snapshot with `Float.MIN_VALUE` passes validation, and an initialized page accepts that scale through the reducer. Formatting `144f / pointsPerFoot` then throws `IllegalArgumentException: Length must be finite and nonnegative`.

This reproduces a validator-to-formatter exception, not a separately observed full native UI crash. Ordinary calibration UI creation already guards some invalid values; imported/admitted scale and downstream calculations remain inconsistent. The polyline path's better finite-result check does not protect every plain-measurement call.

Repair: share a numeric contract across import, mutation, calculation and display. Use adequate precision, bound resulting lengths, and return an explicit invalid result before formatting. Merely replacing the minimum with an arbitrary small positive constant is insufficient without accounting for maximum supported geometry.

Acceptance: extreme finite scales/dimensions, imports, direct and polyline measurement, resize/recalibration; invalid values must neither crash nor partially mutate history.

### S13-04: one-page photo layout rejects accepted dense pins

Committed source: `MainActivity.kt:8272-8277,8303-8315`.

All photos for a pin are placed on one appendix page. With the accepted 128-photo maximum, two columns require 64 rows. On a landscape letter source the available height per row minus spacing becomes negative; decoding/layout rejects it and the entire export fails.

The actual Android probe uses a valid 792-by-612-point source and 128 retained synthetic JPEG references. Export returns false. Existing changed-source/verified-source controls pass in the same run.

Repair: paginate a pin's photos at a minimum readable image height; continue headings across pages and preserve every photo/annotation. Lowering admission limits alone would strand already accepted documents.

Acceptance: 1, 2, intermediate and 128 photos, both orientations, complete coverage and appendix count, bounded memory and cancellation without successful publication of incomplete output.

### S13-05: annotation composition order diverges

Unpublished snapshot: `MainActivity.kt:7860-7866,7924-7947,7951 onward,8509-8525`.

The photo viewer draws paths, measurements, notes and then shapes. Export draws notes before paths and measurements. Paths can obscure notes in output while appearing underneath them in the viewer. Hit-testing also checks paths before measurements although measurements are drawn above paths.

This is a source-level ordering mismatch; no new overlap screenshot test was executed here. Shared low-level drawing functions do not establish shared scene order.

Repair: construct one ordered annotation scene and share it between display and export; hit-test in reverse visual order. Acceptance requires deliberate cross-kind overlaps and topmost selection, with selection decorations excluded from exports.

### S13-06: interrupted downloads leave unowned storage

Unpublished snapshot: `projects/ProjectDownloads.kt:124-196`, project library and startup paths.

Downloads stage into `.incoming-*`, verify content, rename into a final directory and register in the library. The current invocation's finally block handles ordinary failure/cancellation. Abrupt process death bypasses finally, and startup/retry does not reconcile earlier incoming or renamed-but-unregistered directories. Repeated interrupted downloads can accumulate large private files until storage is exhausted.

This is a missing recovery path established by inspection, not an executed process-kill experiment or evidence of losing an already accepted project.

Repair: journal publication and reconcile exact owned staging on startup. Distinguish active, verified-but-unregistered and library-owned content. Never delete arbitrary unlisted files. Test death after writes, fsync/verification, rename, index publication and retirement; restart must produce an intact accepted project or bounded cleanup of its uncommitted staging, without accumulating retry copies.

### Fixed tooling: S13-07 and S13-08

Unix app-only logging formerly fell back to unfiltered adb logcat; PowerShell read the broader stream and filtered package text, not process identity. Both now fail closed on missing, malformed or failed PID resolution. Valid logging is process-scoped. Tests execute the actual scripts against synthetic ADB responses, without touching any device.

The new portable Bash helper replaces duplicated grep-P app-ID extraction and provides launch, stop, logcat and file capture through correct OS overrides. Seven PowerShell cases and fourteen Bash-helper cases pass. The Bash helper was exercised with Git Bash on Windows; physical macOS execution is not claimed.

Two redundant composite IDE tasks were removed. Unreferenced broad-log scripts `scripts/clear_logcat.ps1`, `scripts/cycle_logcat.ps1` and `scripts/get_logcat.ps1` were removed. The generated two-plus-two JVM example was removed; the meaningful installed-app package identity test remains. Shell LF endings and CI execution of the portable tests are included. Historical records, recovery contracts and useful test fixtures remain intact.

### K01: prior native browser crash remains unresolved

The unpublished `CODEX_AUDIT_IMPLEMENTATION_LOG.md:130-143` records 26 native passes followed by `ProjectBrowserInstrumentedTest.actualFolderPickerSubfoldersRecentReopenAndStartupPreserveDrawing` failing with Compose's `measure is called on a deactivated node` error. It reports reproduction with accumulated data in earlier reconstructed sources, despite isolated fresh-data success. The exact trigger remains unresolved.

This task did not reproduce that crash again or use the physical tablet. The fresh host suite cannot waive a native layout failure. Keep feature work separate until retained/accumulated-state navigation, recreation and orientation qualification passes without deleting the problematic state to obtain a green result.

## Architectural priorities

1. **Export and surface composition:** extract verified source capture, physical geometry, raster rendering, photo pagination and destination publication. Share ordered annotation scenes across display/export/hit-test. Fix S13-01/04 before expanding export features.
2. **Measurement contract:** geometry, calibration, numeric results and formatting need one policy for PDF/photo/polyline. Eliminate stale redundant labels or explicitly model captured calibration. Fix S13-02/03 before adding more measurement modes.
3. **MainActivity by workflow:** the committed production tree has 82 Kotlin files and 49,861 lines; MainActivity alone has 8,457 (unpublished: 8,626). Extract a thin composition root, document/viewer state, viewer gestures, project browser, camera coordinator and export coordinator. Preserve tokens, retained owners and cancellation admission. Moving everything to Helpers.kt is not a boundary.
4. **Explicit persistence/sync phases:** SyncCoordinator is 4,192 lines; PhotoAssetStore 4,146; LocalDocumentRepository 2,451; DocumentBundleService 2,387; DriveImmutableAssetTransfer 2,200. Thin orchestrators should express admission, verified staging, durable publication, compensation and retirement. Preserve accepted-state markers, leases and journals, using fault-injection/restart tests for each extraction.
5. **Project lifecycle:** bring new downloads up to the existing durability standard. Define removal/archive, refresh/redownload and storage management before the bounded library fills. These product-policy gaps are recommendations, not extra proven bugs.
6. **Test missing contracts:** add physical paper-size, extreme numeric-result, recalibration, overlapping annotation, accepted-limit photo and accumulated-state tests. Promote the supplied probes to ordinary regression suites when repairing their findings; do not ignore them or weaken assertions merely to restore green.
7. **Reproducibility without churn:** Gradle's distribution checksum is already configured. Dependency verification/locking and immutable reviewed CI action pins are worthwhile. No complete transitive vulnerability or history-wide secret audit was performed. Filename inventory found no tracked signing keys, which does not prove no secret ever existed. Cosmetic stage-package renaming and module multiplication are lower priority than real boundaries; retired-format rejection is intentional, and supported-format recovery must remain.

The existing canonical snapshot, transactional switch, generation checks, verified source capture, bounded history and immutable photo ownership are valuable guarantees. Refactor around them, not through them with a bonfire.

## Executed validation

| Check | Actual result |
| --- | --- |
| Committed baseline host suite | 962 collected; 956 passed; six existing skips; no failures/errors |
| Baseline APKs/lint | Both APKs built; zero lint errors, 88 warnings |
| Final housekeeping host suite | 961 collected; 955 passed; six existing skips; no failures/errors |
| Final APKs/lint | Both APKs built; zero lint errors, 88 warnings |
| Unpublished exact snapshot | 1,001 collected; 995 passed; six skips; both APKs built; zero lint errors, 117 warnings |
| JVM negative probes | Three executed: two expected defect failures, one supporting budget check passed |
| Native audit on a new disposable API 36.1 emulator | Five executed: two expected defect failures, three existing controls passed |
| Portable launcher tests | 14 passed |
| PowerShell launcher tests | Seven passed |
| Native qualification-runner tests | 36 passed |
| Configuration | JSON and exact modified workflow YAML parsed; actual scripts executed; diff checks clean |

The one-test reduction removes only the generated arithmetic example. Negative probe failures remain open findings and are not counted as successful qualification. Instrumentation failure statuses and its final summary were inspected instead of treating adb's exit code as the verdict.

Required host command was the checked-in wrapper with `--no-daemon --console=plain --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`, using the configured Android Studio JBR and SDK. The original physical tablet, existing AVDs, real accounts and documents were untouched. The task-owned emulator was stopped. Initial probe-harness errors were corrected before the final results and not counted as application defects.

Historical CI run `34711643344` passed for exact baseline c265c96. The publication commit must be reported with its own observed CI status, not assumed green from that historical run. Signed release/install/upgrade, wider live-provider/conflict and physical-device coverage, and the complete unpublished native feature matrix remain unqualified. No stage is advanced.

## Handoff

First repair S13-01 through S13-04 on consolidated main. Then repair S13-05/06 and K01 in the preserved feature work, reconcile with main and qualify the combined candidate. Follow with workflow-sized refactors under the same invariants.

See [negative probe setup](repro/README.md) and [compact results](results.json). Corrected, actually executed Kotlin probes are retained outside normal application test sources, visibly documenting unresolved findings rather than breaking the unrelated housekeeping gate. Full logs, source hashes and the Git bundle are preserved in task-owned external evidence; do not commit private paths or raw payload logs.
