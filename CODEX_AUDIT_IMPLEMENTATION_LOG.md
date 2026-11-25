# SOTAware Construct Implementation Log

## Sub-drawing tool rounds 1-4, 2026-11-20

Status: IMPLEMENTED LOCALLY, UNCOMMITTED on the task branch. No commit, push,
branch change, device install, signing, or audit-stage action is authorized; version.properties,
tools/, and update code are untouched.

Round 3 fixes: the naming popup arms from an observed crop-list snapshot; the open
sub-drawing paints its own strip as the last viewer layer so Rename and Back to page
take taps; badges draw at a fixed 34dp radius; every crop on a page takes the next
unused palette colour; the top-left arrow closes an open crop in both landscape and
portrait chrome.

Round 4 fixes (Dave tablet report): the strip sits below the floating page-number and
undo/redo controls and clips its label to one line, so nothing covers the viewer
controls, and the repeated bottom banner is removed; a cancelled new crop goes through
a new abandonSubDrawing command that drops its own creation record, so Undo/Redo
cannot resurrect it (named, marked-up, and foreign crops reject the command and keep
the undoable delete path); labels show the typed name plus the sheet code verbatim
(104 (E1), Mechanical Room North Wing (SHEET 1)) with no Room prefix; the rename
popup carries its own hint saying Cancel keeps the current name; the name field uses
ImeAction.Done to save, and its dialog content scrolls with imePadding so OK stays
reachable above the keyboard. Files: PdfPageRenderer.kt, MainActivity.kt,
SubDrawingModels.kt, SubDrawingBadge.kt, stage8/AnnotationReducer.kt, strings.xml,
SubDrawingTest.kt.

Commands: ./gradlew --console=plain :app:compileDebugKotlin; focused
:app:testDebugUnitTest --tests *SubDrawing* --tests *LegacySnapshotReadTest*; then
./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -
BUILD SUCCESSFUL in 2m 37s, 1121 JVM tests, zero failures/errors, six existing skips,
lint zero errors and no new warning categories (the retired banner and room-title
strings were removed with their call sites). Badge appearance, strip placement against
the floating controls, the keyboard Done action, and the naming/rename dialogs are
Compose UI covered only by compile, lint, and reducer/geometry tests - a tablet and
landscape-phone tap-through is not run.


## Confirmed-bug audit repairs, 2026-10-02

Status: IMPLEMENTED LOCALLY, UNCOMMITTED. No audit stage is advanced; no commit,
push, release, signing, account, or security configuration action is authorized.

Dave explicitly reopened the audit and subsequently authorized bounded fixes.
Six independent reviewers covered source identity/persistence, measurement,
Drive, lifecycle, concurrency/performance, and security. The original checkout
was clean at `36f7fc6e28800b00bde25c221c5a1d5883422387` on
`codex/stage-3-transactional-switching`. Source and tests were built in a separate
OS-temp copy; a recoverable archive was made before repository application.

Seven finding groups are repaired: corrupt-manifest retry could allocate a new
identity; stale-association empty restore could erase accepted slots (latent API
defect); whole-document OCR held the shared cache mutation lock; valid 2,048-photo
bundles exceeded the import marker limit; gutter taps and navigation pinches
could create PDF annotations; the mocked-ADB CI harness retained exit code 2
after PASS; and unresolved camera publication could replay after Undo.

Host gates: 1,076 JVM tests, 1,070 passes, six existing filesystem-capability skips, zero failures/errors; both debug APKs; lint zero errors and 111 warnings. Final account-free native coverage on the authorized disposable TB336FU/API 36: 51 passing invocations and one existing hard-link capability skip across the 52-invocation source-verified plan; 135 method passes and 1 skip, zero failures. All seven production SAF phases and both process-recovery phases passed. The full runner remains BLOCKED because the required hard-link method is skipped; live-provider coverage is separately omitted and unqualified; the ten remaining classes passed separately on the same APKs.

Original-red/candidate-green synthetic native probes confirm camera photo
resurrection and all four PDF gutter/pinch cases. Camera probes use the real
owner/private journal with a synthetic host and save fault; MainActivity history
wiring was independently reviewed. Physical OEM camera interaction and real
Drive operations were not repeated. The legacy positive renderer fixture now
uses fitted-page coordinates and preserves all assertions. Earlier fixture and
checkpoint-sharing failures are retained as failures, not relabeled green.

App APK SHA-256: `E72F03BAED72F730CA92CDFBBD4BA2B9F0F2C03F417996550BB3DBCE23F3E943`.
AndroidTest APK SHA-256: `05B214A6A93949E6A145B04914435CC9856E418E816B6419167AC2D98B25EDC2`.
Evidence: unique OS-temp `construct-audit-20261002-36f7fc6/evidence`, including
`final-host-gates3.log`, `final-native-fixture-build.log`, `native-final2-full`,
`native-final2-tail2`, original/green probes, XML/lint, source archive, and the
application hash manifest. Live destructive Drive tests were never run.

See [repair ledger](docs/audits/2026-10-02/REPAIRS.md). Existing release,
provider/account, crash-power-loss and other-device limits remain open.
Final tablet state: the hash-verified repaired debug app remains installed,
synthetic Construct data is cleared, and the test APK/fake handlers are removed.
Cold launch and a screenshot show its empty Projects screen. LocalDots package
metadata is unchanged and the exclusive device window is released.

## Document selection and camera final qualification, 2026-09-25

The final source candidate retains the two owner extractions described below.
Six AndroidTest harness files were adjusted to find the same exact synthetic
provider PDF, scroll to the exact project fixture, and use the compact
toolbar's enabled overflow actions and visible Back control. An independent
read-only review found no high-confidence weakening or production issue in
these test changes. The native runner parser passed 36/36 checks.

The final host command `gradlew.bat --no-daemon --stacktrace --console=plain
:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
:app:assembleDebugAndroidTest` passed: 1,066 JVM tests, 1,060 passes, six
existing skips, zero failures/errors; lint had zero errors and 122 warnings.
Both debug APKs built. App APK SHA-256 is
`5C8A48103F84E1EEDF93F89C353680EFD19A27E2CC6AB3016DB3B12D5760B521`;
AndroidTest APK SHA-256 is
`305DDBF33341D15E99A6D32C695C3D186129676B074279BBC6634BACE17332C5`.
The native runner's source-tree digest was
`95e7eecfa6f4e2217c278c7e94db2cbdbac566564cd99d71cebcc8c2388f18b5`.

On a fresh, explicitly disposable `Medium_Phone_API_36.1` emulator, the full
account-free native run completed 39 passing invocations before stopping at
the existing Android hard-link capability skip. The remaining ten planned
classes passed separately on the same APK/source candidate. Across the 50
planned invocations, 49 passed and one skipped: 129 test methods passed, one
skipped, zero failures. All seven production SAF phases and both process
recovery phases passed through the checked-in runner. Evidence is in unique
OS-temp directories named `construct-refactor-full-final2-*` and
`construct-refactor-tail-final-*`. The full runner's status remains BLOCKED:
it stops on the hard-link skip and explicitly omits the account-dependent
live-provider class. The task-owned emulator was stopped after qualification.

On the signed-in TB336FU (API 36), the final debug APK opened a uniquely
named synthetic repository PDF via DocumentsUI. An annotation photo pin
launched the actual device camera; shutter and confirmation returned a photo
to the pin. After force-stop/relaunch, the PDF reopened with `Photos (1)` and
`Photo 0`; the account still showed signed in. The tablet was returned to the
app selector. The synthetic source PDF and captured association remain on the
tablet so the verified document is not broken. No user document was used.

`verifySotawareReleaseSigning` was run and BLOCKED because all four external
upload-keystore settings were absent; the documented keystore was unavailable.
No signing identity was created or changed. Live Drive qualification was not
run on the populated tablet: the checked-in test can replace its backup root
and the disposable runner uninstalls app data. It needs an isolated account or
device plus a restoring harness. Pixel-specific and second-device transfer
checks were unavailable. These limits are not debug-test passes, and no audit
stage advanced.

## Document selection and camera session owner extraction, 2026-09-25

Starting from clean `955715d5da06c1bd301d4cd9cf6ee231c767d1a3`, moved
document-opening consequences into `stage3/DocumentSelectionWorkflow` and
camera capture/recovery orchestration into `CameraCaptureSessionOwner`.
`BlueprintApp` retains SAF and Activity Result launchers, current Compose
state, notices, and the recovery dialog. The selection owner now sequences
exact project-source verification, session switching, ready-session browser
restoration, recent/project writes, and fail-closed grant cleanup. The camera
owner sequences journal preparation, launch, identity and page admission,
reserved photo publication, reducer attachment, canonical save, commit,
recovery, and explicit discard. A composition-lifetime mutex remains shared
across camera-owner rebinds so a retiring capture cannot overlap a new owner.
`MainActivity.kt` fell from 4,686 to 4,073 lines. No stage advanced.

The first main-source compile found two project export/import callers of the
removed local verification helper; a shared adapter was restored. The first
unit-test compile found an incorrect coroutines opt-in import; it was fixed.
The focused document-selection, session, camera-store, pin-identity, capacity,
and preparation JVM regressions then passed. The final host command
`gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed:
1,066 JVM tests, 1,060 passes, six existing skips, zero failures/errors;
lint had zero errors and 122 warnings. Both debug APKs built. App APK SHA-256
is `5C8A48103F84E1EEDF93F89C353680EFD19A27E2CC6AB3016DB3B12D5760B521`;
AndroidTest APK SHA-256 is
`287AA225C7FB9080AFFCAD79EA0CBA6B09A55B12A08D3B12E5C4CA6DF900CD4B`.
The native runner's source-tree digest was
`6ecde017e6055716e30fa1cc4f630d72966cadc4c84b49c4026bcc1da68e32d2`.

The native runner parser's 36 tests passed. Its account-free `audit` suite
passed all six selected methods with zero failures or skips on the
`Medium_Phone_API_36.1` emulator: `Stage10DocumentIsolationInstrumentedTest`,
`CameraRecoveryInstrumentedTest`, `CameraPreparationInstrumentedTest`, and
`CameraStagedRecoveryInstrumentedTest`. Evidence is in a unique task-owned
OS-temp directory. The emulator was stopped; the signed-in tablet was not
changed. An independent read-only review found one mutex-lifetime regression,
corrected before final gates, and no remaining high-confidence findings.
Live-provider, physical-camera-hardware, and release qualification were not
run for this refactor; their existing roadmap limits remain. No publication
was performed during this qualification run.

## Tablet app identity and sign-in correction, 2026-09-24

After the focused connected tests, the intended `com.sotaware.construct`
package was absent from the TB336FU while an older
`com.example.myapplication` build remained installed and foregrounded. Its
account chooser returned a canceled result after account selection. The
final qualified debug APK (`4965AD6F1528DFF2146FD7DBD29734B1C8A784F8B31750819E6E2C2CA3AE728C`)
was installed under `com.sotaware.construct`; choosing the tablet's existing
Google account then completed sign-in. The Drive settings screen showed
`Sign Out`, and the authorized identity restored after force-stop and relaunch.
No backup folder was created. The older package was disabled for user 0,
preserving its data while removing the confusing duplicate launcher; the
intended app was left at its selector. No source or roadmap stage changed.

## MainActivity owner extraction, 2026-09-24

Starting from pushed `5e9fd9018d56191a40c59c6dbc2f39fdb9c4b878`, moved
the retained `BlueprintViewModel`, PDF page browser and thumbnail loader,
PDF page renderer, and page PDF exporter into four same-package owners.
`MainActivity.kt` now contains the Activity, shared UI adapters, and
`BlueprintApp`; its line count fell from 8,518 to 4,686. The ViewModel class
and public UI/export signatures remain stable. The moved implementations,
session/page admission fences, bitmap leases, export source checks, and
cleanup paths were preserved; only cross-file helper visibility changed from
private to internal where needed. A bounded independent source comparison
found no actionable behavioral drift.

The final host gate `gradlew.bat --no-daemon --no-build-cache --stacktrace
--console=plain '-Pkotlin.incremental=false' :app:assembleDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed:
1,057 JVM tests, 1,051 passes, six existing skips, zero failures/errors;
lint reported zero errors and 121 warnings. Both debug APKs built. App APK
SHA-256 is `4965AD6F1528DFF2146FD7DBD29734B1C8A784F8B31750819E6E2C2CA3AE728C`;
AndroidTest APK SHA-256 is
`287AA225C7FB9080AFFCAD79EA0CBA6B09A55B12A08D3B12E5C4CA6DF900CD4B`.
On the authorized TB336FU (API 36), a
`:app:connectedDebugAndroidTest` selection ran 16 browser/navigation,
renderer, PDF export, and ViewModel history-lifecycle tests: all passed,
zero failures/errors/skips. The foreground app was returned to the selector.
Two attempts through the foreground app's existing-account chooser returned
a canceled provider result. Subsequent package-identity inspection found that
the foreground app was the older `com.example.myapplication` install; the
correction above supersedes the signed-out device handoff.
No roadmap stage was advanced; live-provider and release qualification were
not rerun. Publication is recorded in the branch's Git history.

## Audit-recommended seam extraction, 2026-09-23

Starting from pushed `0319560ba21d7420dc498d22fa654ae57971d975`, extracted
the remaining bounded architecture seams recommended by the September 22
audit. `SyncAdoptionStateMachine` now derives the remote-link/local-apply
phases from the existing schema-2 metadata and builds candidate, link,
acknowledgement, retry, legacy-reconciliation, and apply-complete transitions.
The serialized coordinator still owns gateway effects, document barriers,
journals, and durable publication. `DocumentBundleWorkflow` now owns bundle
export/import admission, source rechecks, photo capture and staging, apply,
rollback integration, and resource release through a tested host interface;
Compose retains the SAF launchers and UI notices. The separate PDF export
request owner is unchanged. `ProjectDriveAuthorizationOwner` now owns project
read-only consent attempts, generation and result fences, token-free saved
state, and retry/invalidation through an injectable project-only port. Backup
authorization remains separately scoped.

Focused Stage 4, Stage 6, and project-consent JVM regressions passed after
correcting initial compile errors in the new files. The final host command
`gradlew.bat --no-daemon --no-build-cache --stacktrace --console=plain
'-Pkotlin.incremental=false' :app:assembleDebug :app:testDebugUnitTest
:app:lintDebug :app:assembleDebugAndroidTest` passed: 1,057 tests, 1,051
passes, six existing skips, zero failures/errors; lint zero errors and 121
warnings. Both debug APKs built. App APK SHA-256 is
`CD96A2C9F844A23C8123F64EBF4EDF74AEC0B5FF863C640B9CC39B3D4F7277E8`;
AndroidTest APK SHA-256 is
`CB40093B9602E773D9A6BF81C967BC10BF12880E7B8D1D2DBD94A5021C096B4A`.

On the authorized TB336FU (API 36), the checked-in runner passed all seven
fresh-install production SAF phases, including bundle export/import, relaunch,
and retired-format rejection. The project Drive consent recreation class also
passed in a separate checked-in audit selection. The SAF source-tree digest
was `6ecde017e6055716e30fa1cc4f630d72966cadc4c84b49c4026bcc1da68e32d2`;
the task-owned OS-temp evidence is in `construct-refactor-00804084c426448ab50c0ba9f072a4af`
under `saf` and `project-consent`. Bounded independent reviews of sync,
project auth, and bundle workflow found no high-confidence defect. The test
APK was removed; the debug app was left on the selector, signed in to the
existing tablet account without a backup folder, with rotation settings
unchanged. Live-provider and release qualification were not rerun; the
September 23 live-provider limitation remains open. No roadmap stage was
advanced; publication is recorded in the branch's Git history.

## September 22 audit repairs, 2026-09-23

Repaired all seven findings in the [current-worktree audit](docs/audits/2026-09-22/README.md)
without advancing a roadmap stage. Adoption now retains a durable local-apply
intent and blocks upload until canonical/photo acceptance completes; legacy
ambiguous adoption metadata fails closed. Fingerprint reads bound nonprogress
and honor cancellation. SAF read grants are leased and conditionally released
after failed admissions. Repository saves enforce the manifest's ID, source
URI, and fingerprint under the manifest/document locks. Project Drive consent
correlation survives Activity recreation and rejects stale/process-restored
results. Successfully retired owners leave the composition maps. The page-code
cache has an aggregate budget and leased active entries. A native regression
also corrected non-content local PDF opening, and an intermittent recent-file
reopen now navigates to a ready existing session before unrelated storage work.

Final host command: `gradlew.bat --no-daemon --no-build-cache --stacktrace
--console=plain '-Pkotlin.incremental=false' :app:assembleDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed.
JUnit XML: 1,041 tests, 1,035 passes, six existing skips, zero failures/errors.
Lint: zero errors, 120 warnings. Both debug APKs build. Qualification scripts:
36 Python cases and seven mocked-ADB cases pass. Bounded independent sync,
persistence/cache, and project/consent reviews found no remaining
high-confidence issue after their reported corrections.

On the authorized disposable TB336FU, API 36, the actual app/SAF fresh-install
flow passed all seven phases, and process recovery passed. The checked-in full
runner stopped at the established hard-link capability skip; its 39 earlier
invocations passed, including three other methods in the skipped class. The
ten remaining local classes passed in a separate checked-in audit run. Total:
49 passing invocations, one capability skip; 129 passing methods, one skip,
zero local failures. The live-provider account class was omitted from that
local matrix. A subsequent authorized live attempt reached the first Drive
upload and verified the remote snapshot and photo assets, but the full class
failed at its second UI note step; the unchanged-photo second upload remains
unqualified. The eight exact disposable folders created during live attempts
were moved to Drive Trash and absent from a fresh active Drive search; older
matching folders were untouched. The tested debug app was reinstalled cleanly,
signed in to the existing tablet account, and left without a backup folder.
The full runner remains BLOCKED by the hard-link skip and incomplete live
provider qualification; no release claim is made. Rotation remained at its
original settings. See the [repair closeout](docs/audits/2026-09-22/REPAIRS.md)
for APK hashes, evidence identities, and diagnosed attempts. The initial dirty
worktree was preserved. No commit, push, email, signing change, or stage
advancement occurred.

## Current-worktree audit, 2026-09-22

Reviewed the existing dirty worktree across persistence, switching, sync,
projects/Drive, bundles, viewer/annotations, tests, and CI. The
[audit report](docs/audits/2026-09-22/README.md) records seven prioritized
findings and the recommended repair order. The leading P1 is a durable Drive
adoption cursor acknowledged before the separate local remote-acceptance apply;
a failed apply can be missed by later same-cursor checks and followed by upload.
The September 14 O1-O5 repairs remain closed. No production code changed.

Corrected host command: `gradlew.bat --no-daemon --stacktrace --console=plain
'-Pkotlin.incremental=false' :app:assembleDebug :app:testDebugUnitTest
:app:lintDebug :app:assembleDebugAndroidTest` passed (86 actionable tasks: one
executed, 85 up-to-date). The first PowerShell invocation passed the property
unquoted and stopped at task parsing before product tasks. Existing JUnit XML:
1,020 tests, 1,014 passes, six skips, zero failures/errors. Lint: zero errors,
118 warnings. The APK SHA-256 values match the September 14 repair closeout.
`python -B tools/test_native_qualification.py` passed 36 tests; the PowerShell
logcat harness passed seven mocked-ADB cases. No new device, live-provider,
signed-release, or fault-injection gate ran. No stage advancement, commit, push,
or email.

## Five audit findings repaired and locally qualified, 2026-09-14

Completed S13-02/04/05/06 and K01 on the preserved feature working tree.
Recalibration is atomic with dimension labels; photo appendices paginate;
photo display/export/hit-testing share order; project downloads have durable
receipt-based restart recovery; browser list ownership and the matching
Compose 1.7 maintenance fix address the reproduced deactivated-node crash.

Final host: 1,020 collected, 1,014 passed, six existing skips, zero failures/errors;
both APKs build; lint zero errors and 118 warnings. Final tablet: 126 passes,
one existing hard-link capability skip, zero failures; all 49 planned local
invocations attempted. All seven SAF workflows pass. Five extra retained-state
browser repetitions and both fresh-process project-recovery phases pass.
The full runner remains BLOCKED by its explicit capability skip and live-provider
omission, not by a failed local test. External release gates remain unqualified.

See [repair closeout](docs/audits/2026-09-14/REPAIRS.md) and
[machine-readable results](docs/audits/2026-09-14/repair-results.json).
No commit, push, merge, signing change or stage advancement. Tested APK installed;
synthetic target app data cleared, original rotation restored, cold launch passed.
Evidence: OS-temp `construct-five-repairs-f1zq91nr/resume-20260914`.

## Full working-tree audit and bounded repairs, 2026-09-14

Audited the existing feature worktree without discarding its pre-existing edits.
Repaired PDF physical page size, extreme measurement arithmetic, nonprogressing
project-stream reads, app-only logging, and stale native test selectors.
Fresh final host results: 1,008 collected, 1,002 passed, six platform skips, zero
failures/errors; both APKs built; lint zero errors and 118 warnings.
Final physical-tablet matrix: 46 of 48 invocations wholly passed; project-browser
K01 reproduced and one hard-link capability test remained skipped. All seven SAF
phases and actual process-restart recovery passed. Live-provider qualification was
explicitly omitted. This is not a green native matrix or release approval.
S13-02/04/05/06 and K01 remain open; no stage advanced.
See [full audit](docs/audits/2026-09-14/README.md) and
[results](docs/audits/2026-09-14/results.json). Work remains uncommitted; no push.
Evidence is retained in OS-temp `construct-full-audit-20260913-iy6thlt5`.

## Annotation tools, photo parity and project defaults, 2026-09-12

User follow-ups are implemented on `codex/stage-3-transactional-switching`, based
on `c265c963788261b23126fea8cc91e2812d739539` plus the preserved project-folder and
page-code/auth/fling edits below. No commit, push, or audit-stage advancement is
requested. Initial dirty/untracked files and the patch were copied to the
task-owned external `construct-tools-*` evidence directory before implementation.

- Page-code identification is in **Menu → Identify page codes**. The obstructed
  scan button and duplicate rail Undo/Redo controls are removed.
- Stationary long-press text selection uses the pointer deadline even without
  move events. OCR completion is fenced by gesture/session/page; mode changes,
  cancellation, and page-code selection retire pending text selection.
- The shared toolbar routes all annotation tools to open photos and hides the
  camera there. Photo calibration uses original oriented image dimensions.
  Photo-specific Clear preserves the attached image and PDF annotations.
- Polyline taps accumulate vertices; Finish commits the total source-space
  length through the reducer as one undoable measurement.
- Long-press settings replace the automatic tool popup. Defaults persist under
  stable local project identity, or exact document identity for a standalone PDF.
  Pen starts red and Highlighter yellow. Pan selection exposes Appearance for
  existing annotations; saves use expected-before reducers and preserve geometry.
- Note dragging uses relative movement so beginning a pinch away from the center
  does not relocate the anchor. Photo note pinch admission retains the selection.
- Snapshot schema 3 includes photo paths/measurements/scales, polyline vertices,
  and annotation appearance through capture/apply, validation, history, sync,
  and bundle/PDF export. Retired schema-2 input is rejected unchanged. Wrapper
  versions remain unchanged; bundle snapshot version uses the canonical constant.

See [tool behavior](docs/annotation-tools.md) and the
[current-format amendment](STAGE9B_CONTRACT.md). Preferences are local project
settings; annotation appearance is in the snapshot/bundle.

Validation: final-source host `matrix11` passes assembleDebug,
testDebugUnitTest, lintDebug, and assembleDebugAndroidTest with configured JBR 21,
two workers and Kotlin compilation in process. 1,001 tests collected: 995 pass,
six existing skips, zero failures/errors. Lint has zero errors and 118 warnings.
Focused qualification on the explicitly authorized disposable TB336FU, API 36,
passes all 17 landscape tests (`native8`) and four portrait tests (`portrait8`).
These exercise real full-app tool selection, saved settings/recreation, PDF and
photo appearance, note pinch anchor, polyline/history, OCR through clipboard,
all-page page-code selection/recreation, fling/cancellation, and control layout.
All seven actual SAF phases pass in `saf11`: cropped/rotated PDF export with
pixel oracles, picker recreation, picker cancellation, complete bundle export,
fresh-install complete replacement, force-stop/relaunch durability, and rejection
of retired input without mutation. The extended fixture includes photo paths,
polyline vertices, measurement appearance, photo scale, and an explicitly yellow
note checked by the existing rendering oracle. Total scoped tablet passes: 28.
The production APK is identical across native8, portrait8, and saf11; only the
SAF harness changed afterward, so unchanged viewer-test evidence is retained.

Earlier attempts are retained as failures: initial compile issues were fixed;
matrix1 exposed the outdated embedded bundle-schema constant (21 failures), then
matrix2 passed all JVM cases but failed one indentation lint error. Native3
passed eight existing viewer regressions; the two new tests failed at their
recreation fixture's browser-to-viewer transition after the photo-tool steps
passed. The fixture now explicitly reopens the drawing. Build4 exhausted native
memory while a desktop-control helper consumed approximately 15 GB; the user
restarted, restoring available memory. No helper was killed by this task.
Matrix5 had a PowerShell argument quoting error; matrix6 passed host tests/lint
but exposed a test-only import error. Matrix7 passed all host gates. Native7
passed the photo workflow; the PDF fixture tried selecting a path while Pen was
active. It now explicitly selects Pan. Photo Pen/Highlighter remain active
between strokes to match the PDF interaction; native8 verifies this behavior.
All diagnosed failures are corrected in the final passing host/native checks.
SAF8/9/10 exposed stale test navigation: the old empty-home heading/Open PDF
selector, a closed provider drawer, and a provider title mistaken for its root
row. The harness now uses Projects, scrolls real controls, opens DocumentsUI's
roots, and taps the visible synthetic provider row. Production SAF contracts,
permissions, assertions, replacement paths, and process boundaries are retained.
Matrices 9/10/11 pass after those test-only changes; saf11 completes all phases.
The pre-existing accumulated-project layout crash described below is outside
this change and is not reclassified as a passing broad native matrix.

Owner review covered snapshot/mapper and immutable-history changes, incoming
validation, photo/domain ownership, rendering and hit testing, settings scope,
and asynchronous selection fences. No independent worker review was requested.

Final production APK SHA-256:
`0dd4517052f399c8d19db9dbb024914239c807f6aa59326cd89e2178d7972b33`.
Final instrumentation APK SHA-256:
`9d996c6634648e68ea5920cdb12b44f1f5563157802b4cf4b0d65a89cb4e9347`.
The installed tablet APK hash matches. Authorized synthetic app data was cleared,
the task's instrumentation APK and temporary device dump removed, original
auto-rotation settings restored, and the final app cold-launched successfully.
The patch, file hashes, runner reports, and checkpoint remain in the task-owned
external evidence directory. Git remains uncommitted on the same branch/HEAD;
24 pre-existing files are byte-identical to this task's initial preservation copy.

## Page codes, backup authorization and viewer momentum, 2026-09-12

User-reported regressions repaired on `codex/stage-3-transactional-switching`,
based on `c265c963788261b23126fea8cc91e2812d739539` plus the existing uncommitted
project-folder feature. That feature's initial patch and files were preserved
before edits. No publication or audit-stage advancement is part of this task.

The viewer lacked both the page-code region action and release momentum. The
restored action selects a crop/rotation-normalized area, uses the existing
session-owned OCR pipeline across every page, and publishes the completed set
of labels in the page browser. Stale, canceled and incomplete scans do not
replace labels. A bounded atomic private cache is keyed by exact document/source
identity and fingerprint; annotation snapshots and bundle formats are unchanged.
See [page-code behavior](docs/page-code-identification.md).

The tablet reproduced a successful Google account choice followed by the app's
`ContainsUnexpectedDriveScope` rejection. Clearing the exact cached token once
and requesting a fresh `drive.file` grant made live backup sign-in succeed.
The request still opts out of inherited grants, and the session still rejects
broader scopes. No account grant is revoked, additional scope requested, or
token persisted. Consent completion and returning authentication use the same
bounded refresh; missing/broader grants have specific messages. This follows
Google's [token-cache guidance](https://developer.android.com/identity/authorization#clear-token-cache)
and the [scope opt-out request](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder).

PDF and full-screen photo pans now track release velocity and run a bounded,
cancellable decay. New touch, tool/page/source changes, resize and disposal stop
the previous motion. Annotation movement, text selection, multi-touch and
ACTION_CANCEL cannot start it. Native verification observes a rendered PDF
stripe after release and after interruption rather than reading internal offsets.

Final host matrix 3 PASS using the required command with both APK builds:
`gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`.
996 JVM cases: 990 passed, six existing skips, no failures/errors. Lint has zero
errors and 93 warnings, matching the pre-task project-feature baseline. Eleven
new JVM regressions cover full-page scanning, failure/cancellation/stale results,
bounded regions, and exact-token refresh without broad-scope acceptance.

The final combined tablet run records 26 passes, then a FAIL in
`ProjectBrowserInstrumentedTest.actualFolderPickerSubfoldersRecentReopenAndStartupPreserveDrawing`
with Compose's `measure is called on a deactivated node` crash. All four new
viewer/page-code tests pass, including actual app scanning, browser labels and
recreation; existing PDF/photo annotation, text selection, authentication, and
the other project cases pass. This is not a green combined native matrix.

The project class passes in isolation with fresh test data. Crucially, an
external reconstruction of the complete pre-task sources (HEAD plus all initial
dirty and untracked files) also reproduces the same crash with the accumulated
test data retained. That APK passes the same class with fresh data. The failure
therefore predates these fixes; its exact data/layout trigger remains unresolved.
An experimental project-list reset did not help and was fully reverted. The
project browser source remains byte-for-byte identical to the pre-task file.

An initial JVM fixture used Android's no-op RectF constructor and was corrected
to populate fields. The first native fling assertion exposed a zero-time
first-frame cancellation in the new decay; the production fix passes that
unchanged assertion. All failed runs remain separate evidence. Page-code jobs
use the existing coordinator owner and their UI is scoped to document screens.
No independent subagent review was requested; author review and diff checks were
performed. Publication, broad live-provider transfers and release qualification
were not run.

The owner explicitly authorized testing and disposable application data on the
connected TB336FU tablet, Android 16/API 36. No emulator or background service is
task-owned. The four new native tests also pass in locked portrait on the final
candidate; their landscape counterparts pass in the combined run. Tablet
rotation policy was restored. Application data was cleared as authorized after
synthetic qualification, the final APK was restored, and real Google backup
sign-in succeeded again. The installed APK SHA-256 matches the final host APK.
No real Drive files were created, downloaded, modified or deleted for testing.
The working tree remains uncommitted on the original branch; no push or email.
Evidence, final source/APK hashes, reports and initial checkpoint: OS-temp
`construct-viewer-auth-dafb00cb2fae4824a13c6c61c9a66659`.

## Project folders and Google Drive project downloads, 2026-09-12

User-requested feature on `codex/stage-3-transactional-switching`, based on clean
`c265c963788261b23126fea8cc91e2812d739539`. No stage advancement or publication.

The home screen now lists remembered local project folders and downloaded Drive
projects. Folder navigation preserves the hierarchy; five recent drawings per
project retain their names and parent-folder labels. Selection and save-bundle
actions resolve existing provider document IDs back to their exact saved source
associations and use the existing transactional document owners.

The separate Drive browser requests explicit `drive.readonly` consent, searches
for folders across Drive, remembers an account-scoped source folder, and downloads
PDF projects for offline use. Both Google authorization requests opt out of inherited
scopes so the backup owner's exact `drive.file` contract remains intact. Downloads
enforce pagination, count/depth/byte limits, ID-based paths, checksums, PDF parsing,
revision checks, complete staging, atomic publication, and cancellation cleanup.
Existing downloaded PDFs are not silently replaced. See
[project behavior and limits](docs/project-folders.md).

Final matrix 6 PASS, using the configured JDK/SDK and the required command:
`gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`.
985 JVM cases: 979 passes, six existing skips, zero failures/errors, including
23 new project regressions. Both APKs build. Lint has zero errors and 93 warnings
(four above the prior baseline: three home resources now unused and a storage
allocation advisory). Final source hashes match the qualified inputs.

Final native PASS: eight tests on the explicitly selected tablet, covering the
actual SAF folder/subfolder/recent/cold-owner/recreation flow, offline PDF opening
with original display names, exact source matching, both consent contracts,
existing undo/lifecycle behavior, and repeated same-name document isolation.
Command: `adb -s <authorized-tablet> shell am instrument -w -r -e class <project,history,isolation,consent selectors> com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner`.
Exact selectors, candidate input hashes, APK hashes and output are retained in
the external checkpoint and `native-final.log` (eight passes, no skips/failures).

Live read-only Google consent, Drive-wide folder search, selection of the requested
source folder, and child-folder listing were verified. That source selection is
retained on the tablet. No private project PDFs were downloaded for testing;
download correctness uses synthetic HTTP/gateway fixtures and native offline
opening. Portrait and landscape home layouts were visually inspected. Broader
release, live transfer and full fresh-install bundle qualification were not run.
The change was reviewed locally without an independent subagent review.

Development-only compile failures and an initial native fixture setup failure
are retained in the evidence directory. The native fixture originally attempted
an annotation before entering the viewer; it now exercises the actual Note UI,
including save and reopening. No production assertion was weakened. A deliberately
overbroad whitespace scan also found pre-existing whitespace in MainActivity;
the final check covers changed lines and every new file without unrelated cleanup.

Testing uses the explicitly authorized TB336FU tablet, Android 16/API 36. App
updates preserve its installation. Synthetic native tests restore the project
index, and the complete native run restores the original global recent-list bytes
and rotation policy. No real Drive files are created, modified or deleted.
No emulator, persistent helper service, commit, push, or email is task-owned.
Evidence/checkpoint: OS-temp `construct-projects-9222d2ef97bb441bae25f27d49d6ff4f`.

## September 12 audit remediation — scoped qualification

The current candidate extends `989e0454ececa702bb6d09b878d773fef90ee62a` and its
audited uncommitted camera/export/OCR work. All 302 audit input hashes matched
before edits; the initial index was empty. Scope and per-finding evidence are in
[the A01–A16/R01–R08 ledger](AUDIT_REMEDIATION_2026-09-12.md).

Final matrix 2 builds both debug APKs and freshly executes 962 JVM tests:
956 pass, six existing capability skips, no failures/errors. Lint has zero errors
and 89 warnings, matching the audit baseline. Native qualification accounts for
111 local identities: 110 pass, one historical Android hard-link capability skip;
all seven SAF phases and the process-restart pair pass. The Windows hard-link
counterpart passes. PowerShell logcat tests pass 7 cases and the native runner
passes 36 parser/safety cases. Integrated and targeted Luna reviews pass, with
one optional Toast package-predicate follow-up. The final skip-count-only parser
delta is root-reviewed and revalidated against retained native output.

The actual live-provider test passes in `native-live6`: real Google authorization,
SAF open, note edits and Sync Now, full-domain remote snapshot/photo readback, and
unchanged remote photo identity/descriptors/bytes after a second annotation edit.
The optional live conflict flag and broader multi-account matrix were not run.
The live harness now handles the actual immersive tutorial and Note options and
waits for visible Back destinations. `native-build14` builds both APKs and passes
lint; the production APK is unchanged. Relevant matrix 2/local native evidence is
reused after source/fixture hash comparison. Later harness/docs are root-reviewed.

The user requested optional delegation/review and shorter session context;
AGENTS.md is reduced from 483 lines/3,838 words to 108 lines/835 words, retaining
essential project protections. Exact synthetic Drive cleanup identities and final
commit/push/CI/notification outcomes are recorded in the external checkpoint.
Evidence/checkpoint: task-owned OS-temp directory
`construct-audit-remediation-047a91c4dab6411eae7e979f741f76d8`.

The current-format contract now specifies repository acceptance metadata schema 1
and restore-intent schema 2, including explicit non-destructive rejection of retired
intent schema 1. Historical release/account/Pixel/transfer gates remain open.

## Independent review accepted and publication authorized, 2026-09-11

The owner accepted the separate direct independent review and requested commit/push.
Repair-only candidate SHA-256: `9f6a5f419a95d15b61e991c8ccbf68ede2f170bbfb5c684b32ec70259cebf049`.
Review result: PASS, no scoped blocking findings. This is not a spawned Luna approval.
Fresh reviewer JVM evidence: 895 collected, 889 passed, six capability skips, zero
failures/errors; both APK assembly gates pass using unchanged up-to-date outputs.
Lint report: zero errors and 80 warnings, with unchanged analysis inputs reused.
The author's native/SAF evidence was inspected and reused, not independently rerun.
All 271 input hashes and both APK hashes matched again before publication.
The preserved housekeeping diff was inspected; archived roadmap/log bytes match HEAD.
Evidence: OS-temp `construct-lifecycle-independent-0gh_nhdk/independent-review.md`
and `construct-publication-audit-w0nl9aqw`. The earlier review/publication holds below
are historical. Broader Stage 10 deployment qualifications are still open.

## Activity-recreation handoff repair, 2026-09-11

Status: IMPLEMENTED LOCALLY; host/native/SAF validation PASS; independent review BLOCKED.
This supersedes the unresolved lifecycle diagnosis below, not the broader release gates.

The new Activity's coordinator could read absent/older disk state while its predecessor
was still flushing the retained ViewModel. Applying that result cleared the new note and
undo history. Two controlled real-repository omission probes reproduce the live-state
loss when handoff admission is absent; the corresponding permanent regressions pass.

A ViewModel-owned host handoff now joins predecessor save/cleanup before replacement
resolve/load/clear. Failed saves block admission and remain retryable; late old owners
cannot close a newer host. Callback rebinds receive a final flush even without ON_PAUSE.
Restored viewer navigation also waits for the existing verified document/page readiness
predicate. Native helpers wait for the actual decoded PDF gesture surface, not merely
the toolbar. Original assertion lines remain intact, with no arbitrary readiness sleeps.

Final fresh gates: debug and test APK builds PASS; JVM 895 collected, 889 passed,
six capability skips, zero failures/errors; lint zero errors/80 warnings. Fresh synthetic
API 36.1 full native suite: 82 collected, 75 passed, seven skips, zero failures. All five
sequenced SAF workflows then execute and pass without skips. The original recreation
case also passes eight additional invocations. The two remaining native omissions are
hard-link capability and opt-in live-provider cases. Eleven new JVM cases pass.

All 271 final source/build/test/fixture hashes and both APK hashes were verified after
native execution. Earlier failed native/readiness runs and red/green probe outputs are
retained separately; they are not relabeled as passing. The tablet and real Drive data
were untouched. No dependency, document format, signing or release configuration changed.

Normal Luna and the documented Reserve route both returned usage-limit errors before
inspection. Direct author review is complete, but no fresh independent PASS is claimed.
The repair remains uncommitted; the existing staged housekeeping is preserved. No main
merge, push, release or success email occurred during this repair. Publication is held
for independent review, with broader Stage 10 release qualifications still open.

Evidence: OS-temp `construct-lifecycle-repair-qjtbuol1`, especially `checkpoint.json`,
`repair-report.md`, `matrix2-junit`, `no-handoff-state-loss-junit`, and the final
`native-final/native-checkpoint.json`. The full command ledger and APK identities are
external evidence; historical implementation chronology remains in its existing archive.


## Branch integration and housekeeping, 2026-09-11

Status: IMPLEMENTED LOCALLY; PUBLICATION BLOCKED by fresh native lifecycle evidence.
This is internal development integration, not Stage 10 release qualification.

The reviewed Stage 10 candidate is preserved as `2e64182`. Historical main and
both documentation branches were reconciled using normal local merge commits.
Earlier Stage 0-2 branches were already ancestors. The current roadmap/format
policy is retained; every original commit remains reachable. Main and GitHub
were not advanced. No branch deletion, history rewrite, force push or email.

Housekeeping removes 24 captured logs/screenshots, two obsolete highlight-analysis
scripts and one stale commit-instructions note. Eleven generated cache/IDE files
are untracked while their local copies are preserved. Ignore rules prevent these
artifacts from returning. Complete prior roadmap/log chronology is archived
byte-for-byte; short root indexes and README links retain the active contracts.

All optional Android resource deletions were withdrawn. All 268 application,
resource, build, fixture and test inputs match the earlier reviewed candidate.
No runtime code, resource, dependency, identity, persistence or sync change remains.

## Final validation

Fresh host gates PASS: debug and test-APK assembly; 884 JVM cases, 878 passed,
six capability skips, zero failures/errors; lint zero errors and 80 warnings.
Seven version-check advisories absent offline are not claimed fixed dependencies.
Static archive, ignore-rule, documentation-link, YAML and XML checks pass.

Fresh-install native suite FAIL: 82 collected, 74 passed, seven skipped, one failure
in `HistoryLifecycleInstrumentedTest.activityRecreationPreservesTheSameDocumentUndoOwner`.
The same ViewModel survived, but the new note was absent and undo unavailable at
the assertion; history epoch changed from 2 to 3. Permanent loss, transient state
or a test-timing root cause has not been established. Publication remains held.

The rebuilt debug APK differs only in classes4.dex from the prior tablet artifact;
the disassembly delta is confined to generated Compose stability initializers.
No byte-identical APK or reused final native PASS is claimed. The test APK is unchanged.
Five SAF phases were NOT RUN in this task after the native failure.

Earlier exploratory resource-removal failures are preserved separately: a dialog
visibility assertion, an unchanged focused pass and a full rerun on reused durable
fixed-URI fixtures. That rerun was not clean-install evidence. No test was weakened.
The optional resource changes were restored before the final fresh-install run.

## In-app updater, 2026-11-20 (Claude, owner request)

The owner asked for an Update button so updates no longer need USB or wireless debugging.
Added `update/AppUpdater.kt` (latest.json check on resume, max every 10 min; SHA-256-verified
download; PackageInstaller session; one-time "Install unknown apps" prompt), the
`REQUEST_INSTALL_PACKAGES` permission and `UpdateInstallReceiver` in the manifest,
`onResume`/`onPause` hooks plus a Settings "Check for app update" row in `MainActivity.kt`,
optional `version.properties` versioning and `UPDATE_URL` in `app/build.gradle.kts`, and
`tools/publish_update.ps1`. Signing identity unchanged (debug key). See README "In-app updates".
Gates on the candidate: `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` PASS
(lint: no errors; two UseKtx style warnings in the new file). Native check: 1.0.2 installed by
cable on Pixel 8 (fresh) and tablet (upgrade from 1.0.0, data kept); app opens; the manual check
reached the server and reported up to date. Self-install of a newer SOTAware version not yet
exercised on device (the same flow was verified end to end in the Local Dots app).
Not committed: this sits on top of uncommitted 2026-10-02 work already in the tree.

## Sub-drawing badges as drawing-owned markers (round 5), 2026-11-20 (Claude, owner request)

Owner asked that a sub-drawing badge behave like a normal annotation: it belongs to its
drawing (grows/shrinks with page zoom), spawns at a size matched to the zoom the crop was
drawn at, is selectable and pinch-resizable with the Pan tool, keeps its own size across
close/reopen, stays readable at every size, shows only badges by default with a long-press
toggle to rectangles-only, and is black-and-white only.

- `SubDrawing.badgeRatio` (radius as a page-width fraction) stores the size; it travels
  through `SubDrawingSnapshotV1`, `DocumentSnapshotV1Mapper` capture/apply, `withDecodedDefaults`
  and `PayloadSecurity` (`badgeRatio` is an optional field bounded to MIN/MAX, absent stays the
  derived default, so schema 3 is unchanged and pre-size documents keep round-tripping).
- `SubDrawingGeometry`: `badgeRatioForZoom`, `defaultBadgeRatio` (scaled by the crop, capped by
  DEFAULT_BADGE_CAP), `badgeRatioOrDefault` clamps; `SubDrawingBadge` lost its fixed-screen
  `radiusPx` rule and now paints white fill, black outline/dividing line, black sheet number
  over black room name, plus a cyan ring when selected.
- `AnnotationReducer.resizeSubDrawingBadge(page, subId, ratio, kind)` is one undoable
  `SubDrawingEntry`; badge size counts in `subDrawingBytes`; out-of-range/non-finite radii are
  rejected and record nothing.
- `PdfPageRenderer`: stored-size radius x displayed page width paints every badge (scales with
  zoom); long-press with Pan selects the crop under the finger, shows the existing Open/Delete
  toolbar, and a pinch then resizes only that badge through the reducer on pointer-up; a plain
  pinch still zooms the page; tap on empty space/other item/opening a crop ends the selection;
  hit-testing uses the drawn radius (or the area in rectangle mode); one marker set paints at a
  time. `onCreateSubDrawing` now carries the release zoom so `MainActivity.createSubDrawing`
  stores the spawn size; export uses the stored radius.
- `MainActivity`/`ToolRail`: badges-only default (`sub_drawings_shown` false, written on first
  run), long-press on the Sub-drawing tool toggles badges/rectangles with a toast naming the
  current mode; rectangles keep their palette colours.
- Tests: `SubDrawingTest` (resize is one undoable step affecting only that badge, rejects
  unknown crops and unusable radii, clamps at the pinch limits, zoom/crop spawn-size math,
  stored size survives capture/apply); `SubDrawingSnapshotRoundTripTest` (size survives wire
  validation/decode/apply/re-capture, absent field opens with the capped default, malformed
  radii rejected). Badge painting, selection highlight, pinch feel, spawn size at a zoom and
  the tool-button long-press are Compose/canvas behaviour verified by compile, lint and unit
  tests; a tablet tap-through remains. Gate on the candidate: `./gradlew --stacktrace
  --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` - BUILD SUCCESSFUL in
  2m 45s, 1129 JVM tests, zero failures/errors, six existing skips, lint report 130 issues with
  zero errors (the earlier `SuspiciousIndentation` errors were a transient artifact of the
  in-progress edits and are gone from the candidate report). Published to the beta feed only via
  `tools/publish_beta.ps1`: sotaware-beta **beta-3** (versionCode 3); the official feed
  (`sotaware`, `version.properties`, `tools/publish_update.ps1`) was untouched. Not committed.
## Sub-drawing badge hold-to-select and legacy sizes (round 6), 2026-11-20 (Claude, owner report on beta-3)

Owner reported two beta-3 problems: holding a finger on a badge never highlighted it and lifting
opened the sub-drawing (so selection and pinch-resize were unreachable), and badges in drawings
made before round 5 were still much bigger than a new badge drawn at the same zoom.

- `PdfPageRenderer`: the badge under the finger is resolved when the gesture starts, and the
  existing timed-hold path (the long-press timeout elapsing with the finger still) now selects
  that crop - cyan-highlighted badge, cyan-stroked rectangle in rectangles mode, and the normal
  Open/Delete toolbar at the badge - so the next pinch resizes only that badge through
  `AnnotationReducer.resizeSubDrawingBadge`. The tap path is gated by `!longPressTriggered`, so a
  held finger can no longer open the sub-drawing, while a quick tap still opens it. Off a badge
  the timed hold keeps starting text selection exactly as before.
- `SubDrawingGeometry.DEFAULT_BADGE_CAP` is now `DEFAULT_BADGE_RATIO`, so a crop with no stored
  size (drawings written before badge sizes existed) derives a marker no bigger than a badge
  drawn today at the whole-sheet zoom, and smaller for small rooms. Stored sizes - including a
  badge the user pinched larger - are kept exactly.
- Tests (`SubDrawingTest`): `legacyBadgeSizes_openNoBiggerThanABadgeDrawnAtTheWholeSheetZoom`
  (derived sizes never exceed `badgeRatioForZoom(1)`, stored/pinched sizes honoured, unsized crop
  stores null) and `badgeHitArea_tracksTheStoredBadgeSize` (drawn radius = stored ratio x displayed
  page width, scales with zoom, legacy crop within the cap). Focused run: 50 tests, zero
  failures/errors. Gate on the candidate: `./gradlew --stacktrace --console=plain :app:assembleDebug
  :app:testDebugUnitTest :app:lintDebug` - BUILD SUCCESSFUL in 3m 11s, 1131 JVM tests, zero
  failures/errors, six existing skips, lint report 130 issues with zero errors. Published to the
  beta feed only via `tools/publish_beta.ps1`: sotaware-beta **beta-4** (versionCode 4); the
  official feed (`sotaware`, `version.properties`, `tools/publish_update.ps1`) untouched. Hold-to-
  select, the cyan highlight while holding, pinch-to-resize and legacy badge sizes on tablet remain
  Compose/canvas behaviour verified by compile/lint/unit tests only - owner tap-through pending.
  Not committed.

### Round 7 - sub-drawing code cleanup (2026-11-20, uncommitted)

Dave asked for a cleanup round: move the sub-drawing code out of the viewer's main composable, give
the viewer one clear-all-selections owner, share the page/sub-drawing reducer code, and drop dead
code - with no visible change. Nothing user-facing, no saved-format, field-name, version or feed
change; beta-4 stays published.

- New `SubDrawingPageMarkers` owns how a page of crops looks and what answers to a touch:
  `drawCropRectangles`, `drawBadges`, `drawCreationDraft` (as `DrawScope` extensions), the two
  pointer questions `badgeAt` (hold-to-select, measuring in the page's own width as before) and
  `hitItems` (tap, measuring in the displayed bitmap's width and height as before), plus the
  creation-preview rectangle. Painting and hit-testing read the same geometry by construction.
- New `ui/SubDrawingOverlay.kt` owns the open-crop strip and the name/rename dialogs over
  callbacks; the viewer keeps all crop state, and the two dialogs share one private name field.
- `PdfPageRenderer` gains `clearPageSelection()` (item, per-kind selections, their drafts, the
  selected badge) and `applyPageSelection(item)`; tapping empty space, tapping a crop, the overlap
  picker, holding a badge, deleting and a document/page switch all go through them. The picker's
  icon/label rules moved to `annotationIcon`/`annotationLabel`. Dead gesture state
  (`heldBadgeSub`, `badgeLongPressFired`) and an unused viewer helper are gone; the imports the
  moved UI left behind are gone. 3846 -> 3645 lines.
- `AnnotationReducer` routes every page-family add through `addPageAnnotation` and page-family
  replace/delete through `replacePageAnnotation`/`deletePageAnnotation`, so sub-drawings use the
  same session, page, id-uniqueness, capacity, unchanged-input and history rules as paths,
  measurements, notes, pins and shapes. `SubDrawingEntry`, nested edits, capacity checks and
  undo/redo semantics are unchanged. 1464 -> 1524 lines (the shared owners plus their docs).
- `SubDrawingModels`: `cropFactor` (unused) dropped for a private `cropCarriesScale`;
  `SubDrawingPalette.overlaps` (no callers) dropped; `center` moved from the palette to
  `SubDrawingGeometry` where the rest of the crop geometry lives; `assignIndex` no longer takes an
  unused new-crop argument and its redundant modulo; `DEFAULT_BADGE_CAP` deleted as a duplicate of
  `DEFAULT_BADGE_RATIO`; two comments corrected - the inherited page scale carries over unchanged,
  it is not multiplied by any crop factor. 298 -> 285 lines.
- Tests: `SubDrawingTest` and `SubDrawingSnapshotRoundTripTest` call the moved owners
  (`assignIndex(list)`, `SubDrawingGeometry.center`, `DEFAULT_BADGE_RATIO`) with the same
  expectations; two new regressions cover the marker hit area (saved badge size, rectangle mode vs
  badge-only mode) and the drag rectangle plus inherited calibration. No existing assertion was
  weakened.
- Gate on the candidate: `./gradlew --stacktrace --console=plain :app:assembleDebug
  :app:testDebugUnitTest :app:lintDebug` - BUILD SUCCESSFUL in 2m 39s, 1133 JVM tests, zero
  failures/errors, six existing skips, lint report 130 issues with zero errors.
- Emulator (`tools/emu.ps1`, disposable blueprint fixture): `open_blueprint.png`,
  `subdrawing_tool_selected.png` (badges black and white), `tool_longpress_rectangles.png` and
  `badges_with_new_name.png` (long-press toggles rectangles/badges), `new_crop_name_dialog.png` and
  `crop_named.png` (drag creates a crop, naming dialog, new crop takes an unused colour),
  `tap_badge_opens.png` + `picker_open_crop.png` (overlap picker lists the crop in its own colour
  and selects it), `open_crop_strip.png` and `renamed_strip.png` (strip "104b (SHEET 1)", Rename,
  Back to page), `back_to_page.png`, `hold_badge_selected.png` (cyan badge with Open/Delete, does
  not open), `tap_empty_deselect.png`, `undo_2.png`/`redo_1.png`/`undo_remove_crop.png`
  (undo/redo the name and the crop; the new crop was undone so the fixture is as found).
  Two-finger pinch-to-resize cannot be exercised on this emulator. Not committed, not published.



### Round 8 - Google Drive remembers the chosen source folder (2026-11-20, uncommitted, published beta-5)

Dave's tablet report: tap Google Drive, sign in, browse My Drive down to his projects folder, tap
"Use this folder" - and the next time he taps Google Drive he is back at My Drive and has to browse
all the way down again.

What was going wrong: `ProjectBrowserScreen` opened `DriveProjectPicker`, whose `accept()` set
`path = listOf(ProjectBreadcrumb("root", "My Drive"))` on every connection. The chosen folder *was*
persisted (`ProjectLibraryStore.selectDriveRoot` -> `project-library-v1.json`, one record per
account, and downloaded projects already matched by `account + source`), so nothing was lost - the
picker simply never used it, and the Projects screen never showed it.

Changes:
- `ProjectLibrary`: `DriveProjectRoot` gains an optional `driveName` (the sign-in address, display
  only, so two accounts on one tablet are told apart); `requireDriveFolderId` rejects blank,
  over-long and `"root"` ids; `chosenDriveRoot(accounts)` is the single owner of which folder this
  device opens - the current sign-in's choice first, the last saved choice when no account is signed
  in yet, null only when there is genuinely no saved folder. `selectDriveRoot` returns the stored
  root and keeps the eight-account budget it already had. Codec schema stays 1: `driveName` is
  optional on read (a beta-4 library restores with an empty label) and always written; a root with
  unknown keys is rejected.
- `DriveProjectPicker`: `accept()` opens the remembered folder (`path = [that folder]`,
  `choosingSource = false`) and My Drive only when the account has none. The listing pass treats a
  remembered folder that cannot be listed as "not reachable": it clears the stale choice for the
  session, moves to My Drive in source-picking mode and shows
  `DRIVE_SAVED_FOLDER_UNAVAILABLE` with the normal Retry action - not an error, not a blank list.
  "Change source folder" keeps the chosen folder on screen while picking a new one (Back walks out
  towards My Drive), and the download view names the chosen folder and its sign-in address. The
  picker's own `"root` crumb was replaced by `DRIVE_ROOT_FOLDER_ID` everywhere.
- `ProjectBrowserScreen`: the Projects screen lists the chosen folder - "Google Drive: New Bid
  Documents" with its sign-in address (or a hint when the label predates this round) - tapping it
  opens the picker there. It is read from the saved library, so it survives app close and reboot.
- `ProjectDownloads`/`GoogleDriveProjects`: the read-only gateway exposes `driveName` (defaulting to
  empty, so existing fakes are untouched) and carries the signed-in address into the saved record.
- Strings: `drive_chosen_source_folder`, `drive_chosen_source_account`,
  `drive_chosen_source_open_hint`.
- Tests: `ProjectLibraryTest` adds `chosenDriveRoot_reopensTheFolderThisAccountPickedLastTime`,
  `chosenDriveRoot_survivesAReopenedLibraryAndIsReplacedByTheNextChoice`,
  `chosenDriveRoot_prefersTheCurrentSignInAndKeepsEveryAccountApart`,
  `driveSourceKeepsItsLabelAcrossSaveAndRejectsThePickersOwnRootFolder` (including a beta-4-shaped
  library with no label, and decode rejecting `"folderId":"root"`), and
  `driveSourceStaysWithinTheStoredAccountBudget`. One existing expectation was extended, not
  weakened: `chosenDriveRoot` with no signed-in account now names the last saved folder (it
  previously returned null) because the Projects screen must still name the folder.
  `ProjectBrowserInstrumentedTest.driveSourceKeepsTheFolderThePickerOpensAndItsSignInLabel` checks
  the same rules on-device and restores the library it touched.
- Gate on the candidate: `./gradlew --stacktrace --console=plain :app:assembleDebug
  :app:testDebugUnitTest :app:lintDebug` - BUILD SUCCESSFUL in 2m 17s, 1138 JVM tests, zero
  failures/errors, six existing skips, lint report 130 issues with zero errors.
- Emulator (`tools/emu.ps1`, no account sign-in): `projects_no_drive_choice.png` /
  `projects_after_fix.png` - the Projects screen is unchanged when nothing is chosen;
  `drive_picker_no_choice.png` + `projects_after_back.png` - Google Drive opens and backs out as
  before; with a synthetic saved folder written into the app's own library file,
  `chosen_drive_row_visible.png` shows "Google Drive: New Bid Documents / dave@example.test",
  `chosen_drive_row_opens_picker.png` shows tapping it opens the Drive picker, and
  `projects_after_cleanup.png` confirms the synthetic record was removed and the screen is as
  found. Real Drive browsing, the unreachable-folder message and re-sign-in need Dave's own account
  and are checked on his tablet.
- Side effects to note: `assembleDebugAndroidTest` did not compile before this round - a pre-existing
  `when (item)` in `Stage8PdfPageRendererInstrumentedTest.deletePageItemCallback` had no
  `PageItem.SubDrawingItem` branch; one branch was added so the instrumented sources build. Running
  `:app:connectedDebugAndroidTest` reached a physical Samsung tablet (TB336FU) that is connected to
  this machine, where four pre-existing `ProjectBrowserInstrumentedTest` cases fail with "No compose
  hierarchies found in the app"; no app data was changed there. Connected runs are left to an
  explicitly authorized disposable target.
- Published to the beta feed only via `tools/publish_beta.ps1`: sotaware-beta **beta-5**
  (versionCode 5), which carries the round-7 sub-drawing cleanup as well. The official feed
  (`sotaware`, `version.properties`, `tools/publish_update.ps1`) is untouched. Not committed.



### Round 9 - Sub-drawing drag previews its rectangle on every page (2026-11-20, uncommitted, published beta-6)

Dave's tablet report (beta-4, reproduced on the emulator in beta-4 and in the round-7 cleanup build):
with the Sub-drawing tool on sheet 3 of blueprint.pdf - a page with no sub-drawings - pressing and
dragging showed only the purple "Drag a rectangle over the area to keep as a sub-drawing" banner, no
box. Lifting the finger still opened "Name this sub-drawing", so the area was captured; he just never
saw it. After one crop existed on the page, the box followed the finger, and sheet 1 (which already
has crops) always showed it - which read as "it randomly started working".

Cause: in `PdfPageRenderer`, the live creation preview is a state (`subDrawingDraft`) set by the
gesture loop through `SubDrawingPageMarkers.dragRect` at press and on every move, and painted by
`SubDrawingPageMarkers.drawCreationDraft`. That paint call sat *inside* the block that paints the
page's existing crops, which is gated by `!isSubDrawingView && currentSubDrawings.isNotEmpty()`. On a
page with no sub-drawings the block never ran, so the preview was never painted - while the gesture
loop kept recording the drag, which is why release still published. Nothing in the drag rule or the
marker owner was wrong; the viewer drew it under the wrong condition.

Changes:
- `PdfPageRenderer`: the creation preview is drawn in its own block, outside the crop-marker block,
  gated only by `!isSubDrawingView`. It therefore paints on a page with no sub-drawings, on the first
  drag after opening a page, and in both badge and rectangle mode, from the first movement until the
  finger lifts. Crop/badge painting, sizes and colours stay in `SubDrawingPageMarkers`; nothing about
  what is published changed, so saved data, hit-testing and the naming dialog are untouched.
- Tests: `SubDrawingTest.markerDragRect_givesTheCanvasABoxFromTheFirstMovementOfEveryDrag` - any
  movement that leaves a real width and height yields the preview box in every drag direction; no
  movement, leaving the sheet, and a perfectly straight vertical or horizontal drag yield nothing,
  matching `SubDrawingGeometry.MIN_CROP_RATIO` (a crop needs a width as well as a height, so the
  preview shows exactly the box the release can keep). Writing this test found that the degenerate
  axis-aligned drag has no preview by design; it is documented in the test rather than changed.
- Gate on the candidate: `./gradlew --stacktrace --console=plain :app:assembleDebug
  :app:testDebugUnitTest :app:lintDebug` - BUILD SUCCESSFUL in 1m 48s, 120 suites, 1139 JVM tests,
  zero failures/errors, six existing skips.
- Emulator (`tools/emu.ps1`, blueprint.pdf via Open PDF > Downloads), mid-drag captured by starting a
  slow `adb shell input swipe` in the background and screenshotting while it is still moving:
  `middrag_sheet3_first_drag.png` - dashed blue box on sheet 3, which has no sub-drawings, with only
  the banner before it; `middrag_sheet1_drag.png` - box mid-drag in badge mode;
  `middrag_sheet1_rectangle_mode.png` - box mid-drag after the Sub-drawing tool's long-press switched
  to rectangles; `sheet1_badges_restored.png` - marker mode toggled back. The release dialog appeared
  as before (`middrag_sheet3_release_dialog.png`, `sheet1_release_dialog.png`) and every test drag was
  cancelled there, so no sub-drawing was written to the test PDFs and sheets 2-4 stay empty
  (`sheet1_after_cancel_no_marks.png`). Pinch zoom while dragging is not possible on this emulator.
- Published to the beta feed only via `tools/publish_beta.ps1`: sotaware-beta **beta-6**
  (versionCode 6). The official feed (`sotaware`, `version.properties`, `tools/publish_update.ps1`)
  is untouched. Not committed.


## Qualification and history

Signed release install/upgrade, broad live-provider/account qualification,
Pixel-specific smoke and actual restore/device transfer remain OPEN. The tablet,
real Drive data, pre-existing emulator and detached worktrees were untouched.
See [current qualification](STAGE10_QUALIFICATION.md) and [roadmap](CODEX_AUDIT_ROADMAP.md).

The complete [implementation chronology](docs/archive/CODEX_AUDIT_IMPLEMENTATION_LOG_2026-09-11.md)
and [roadmap history](docs/archive/CODEX_AUDIT_ROADMAP_2026-09-11.md) remain available.
Current evidence is in OS-temp `construct-housekeeping-dovcfsc7`, including the
checkpoint, original branch bundle, candidate patch and `native-restored-fresh.log`.
Investigate the lifecycle failure before finishing the prepared integration.


## UX change plan, phases 2-5 (2026-11-25)

- Scope: Dave's approved UX plan (`MyApplication-backups/ux-change-plan.md`), phases 2-5, on
  `codex/stage-3-transactional-switching` over HEAD 36f7fc6 and the existing uncommitted work. Backups
  before starting: `MyApplication-backups/pre-ux-plan-20261125-1346.zip` and a stash object. Not committed.
- New files: `ui/SelectionToolbar.kt`, `ui/ViewerUiPrefs.kt`, `ui/SettingsSwitchRow.kt`,
  `ui/ThemeChooser.kt`, `ui/ViewerSearchBar.kt`, `stage8/MarkupMove.kt`; rewritten
  `stage8/PageCodeRegionSelector.kt` (adds `PageCodeBoxEdit`), `ui/theme/Color.kt`, `ui/theme/Theme.kt`.
- Data: sub-drawing `badgeX`/`badgeY` (optional, both-or-neither, 0..1, finite) through snapshot, mapper,
  payload security, reducer validation and export; page-code cache entries gain `source` (`auto`/`manual`,
  absent reads as manual); `PageCodeIndex.autoScan`/`codeInCorner` reuse the existing owner, OCR budgets,
  cancellation and session fencing.
- Fixes found while checking: search highlight coordinates (pixels into the 0..1 transform); Settings
  column did not scroll (glove row and update card unreachable); floating controls unreadable over a
  white sheet in Light; selected rail icon invisible in High contrast; page-code help card covered the
  top controls; auto page-code notice cut off by the toast's two-line limit.
- Tests: new `Phase5UxTest` (8), badge round-trip/undo tests, `MarkupMoveTest`. Instrumented tests updated
  for the new flows (click instead of long-press, Colour label, single Delete, search submits from the bar,
  page-code box confirm). `ViewerNavigationRegressionTest` had not compiled since `PageCodeIndex.scan`
  started returning `ScanResult` (earlier uncommitted work); fixed with `.codes`.
- Gate: `.\gradlew.bat --stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  :app:assembleDebugAndroidTest` - BUILD SUCCESSFUL, 1181 JVM tests, zero failures/errors, six existing
  skips, lint clean. Instrumented tests not run (no authorized disposable device run in this task).
- Emulator (emulator-5554, blueprint.pdf and fresh.pdf only), screenshots in `build/emu/`: p23_* (selection,
  move, delete/undo, badge select/move/open, note auto-select, Back deselect), p4_* (info box off, Pen panel
  and live colour, Sub-drawing re-tap and menu toggle, no arrows inside a sub-drawing, auto-hide and wake),
  p5_* (themes in Settings and viewer, search bar 1 of 1 / 2 of 4 with next moving sheets, visible highlight,
  page-code box draw/resize/move, automatic page-code notice on fresh.pdf, dark toolbar in Light, snackbar
  Undo, High contrast toolbar). Not checkable: two-finger pinch (page-code pan/zoom, badge pinch), reliable
  double-tap timing, OCR text selection (no OCR words on the fixture), typing is hampered by the Gboard stylus
  overlay. Emulator settings left as found: Slate, glove off, info box off, auto-hide off.

## Search highlight baseline fix (2026-11-25, uncommitted, HEAD 36f7fc6)
- Cause: `pdfBoxDisplayedTextPositionOrNull` (AndroidOcrSessionResourceFactory.kt) treated
  `TextPosition.getY()` as the glyph's top edge. In pdfbox-android 2.0.27.0 the constructor sets
  `y = pageHeight - getYLowerLeftRot(rotation)` (text matrix baseline translate), i.e. the baseline.
  Displayed rotation 0 now spans `[y - height, y]` and 180 spans `[y, y + height]`; 90/270 unchanged.
  The function and its result class became `internal` (@VisibleForTesting) for the test.
- New `stage7/TextPositionBaselineTest` (3 cases, real `TextPosition` objects): upright fails on the old code
  (top 100 instead of 83), upside-down, and sideways unchanged. `PdfCoordinateMapperTest` still passes.
  OCR results are in memory only, so no cache needed invalidating.
- Gate: `.\gradlew.bat --stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
  - BUILD SUCCESSFUL, 1184 JVM tests, zero failures/errors, six existing skips, lint 0 errors (no new
  warnings in the changed file). Instrumented tests not run.
- Emulator (blueprint.pdf sheet 1, search "MECHANICAL", 1 of 1): highlight sits on the word -
  `build/emu/search-mechanical-fixed.png`. Not checked: OCR (scanned) text and text selection.
