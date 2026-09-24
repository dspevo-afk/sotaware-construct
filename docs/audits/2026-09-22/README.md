# SOTAware Construct: current-worktree code audit

Date: September 22, 2026 (America/New_York). Branch:
`codex/stage-3-transactional-switching`. HEAD:
`c265c963788261b23126fea8cc91e2812d739539`.

## Disposition

This risk-based audit reviewed the current working tree, including its existing
uncommitted project, annotation, and September 14 repair work. It found one
high-priority sync transaction gap, two active bounded defects, one latent
repository invariant gap, and three lower-priority lifecycle/cache issues. The
September 14 O1-O5 findings are closed by that audit's repair closeout and are
not reopened here. No production code was changed by this audit. No release or
stage advancement is claimed.

The subsequent [September 23 repair closeout](REPAIRS.md) records the fixes
and their host/tablet qualification. The findings below describe the original
audited state.

Source inspection covered the snapshot and repository owners, switching,
sync/auth/Drive adapters, photo ownership, bundle admission, project browsing
and downloads, viewer/OCR/search/annotation paths, Android configuration, CI,
and relevant tests. This is a risk-based review, not proof of every possible
execution path. The P1 scenario below is source-confirmed; no new live-provider
or fault-injected device reproduction was run.

Paths below are relative to the repository root.

## Findings and repair recommendations

### A1 — P1: adoption acknowledges a remote cursor before local apply

`app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt:3850-3869`
durably sets `acceptedCursor` and clears `pendingAdoption` after the remote
resource is linked. The **Link and download** UI then separately queues remote
acceptance at `MainActivity.kt:4827-4861`. If download, validation, local
publication, cancellation, or process death prevents that second operation
from completing, its rollback starts from the already-adopted metadata
(`SyncCoordinator.kt:2883-2888`). A later remote check sees the same cursor and
returns `RemoteUnchanged` (`SyncCoordinator.kt:2690-2698`), while the UI has
cleared the adoption dialog. There is no normal UI call to retry remote
acceptance without an update/conflict prompt. A subsequent upload can use the
adopted cursor as its baseline; existing
`RemoteResultIdentityContinuityTest.kt:475-501` demonstrates upload after
adoption replacing the remote snapshot.

Preserve the remote relocation and reference durably, but record a separate
pending-local-apply intent and block conflicting upload while it remains.
Only clear that intent after complete canonical/photo application and accepted
metadata publication. On restart or retry, finish the pending apply or present
the same actionable choice. Inject failure between adoption, download, apply,
and metadata finalization, including a fresh process.

### A2 — P2: source fingerprinting can run without progress or cancellation

`app/src/main/java/com/example/myapplication/stage2/DocumentIdentity.kt:65-76`
continues forever on repeated zero-byte reads from a provider stream. It also
does not check coroutine cancellation while hashing a normally progressing
large stream. `stage3/AndroidDocumentSessionCallbacks.kt:205-235` uses this
path before durable saves; target resolution and import/export paths also call
it. A stalled provider can therefore keep a switch/save or teardown join from
finishing. The earlier project-download stream fix does not cover this path.

Use a bounded no-progress count, check cancellation within the loop, and make
the owned stream close when cancellation wins. Test repeated zero reads,
transient zeros, a slow stream, and cancellation during save/switch.

### A3 — P2: failed SAF admissions retain new read grants

`app/src/main/java/com/example/myapplication/projects/ProjectFiles.kt:30-39`
persists a tree grant before verifying the provider root, and
`projects/ProjectBrowserScreen.kt:59-70` catches later validation or library
publication failures without releasing it. `MainActivity.kt:2989-3013`
similarly persists a selected PDF grant before a switch that can fail or be
superseded. There is no production `releasePersistableUriPermission` path.
Repeated failures can accumulate grants for sources that the app did not
accept, retaining unnecessary access.

Track whether the grant was newly acquired. On failure or cancellation,
release it only after proving no accepted project, recent entry, active
session, or document association still needs it. Preserve pre-existing grants.
Exercise provider rejection, library write failure, canceled/superseded PDF
switches, and an already-shared grant on device.

### A4 — P2 invariant gap: repository save trusts caller-supplied association

The public `LocalDocumentRepository.save(DocumentId, snapshot,
sourceFingerprint)` overload (`stage2/LocalDocumentRepository.kt:464-473`)
passes the supplied fingerprint to `writeSnapshotLocked` without checking that
the document ID, source URI, and fingerprint equal the manifest association
(`:1122-1184`). A mismatched write can return `Saved`, but the next load
returns `SourceChanged` for that current slot before previous-good recovery
(`:1390-1401`). The current production caller re-resolves the fingerprint
before calling the association overload
(`stage3/AndroidDocumentSessionCallbacks.kt:209-235`); this is a latent
repository API hazard, not a confirmed current production misuse.

Enforce the manifest association in the repository immediately before staging
under a consistent lock order. Narrow or remove the raw overload if only tests
need it. Add a negative test proving a mismatched ID/URI/fingerprint leaves
current, previous, and accepted-state bytes unchanged.

### A5 — P3: Drive consent state is lost on browser recreation

`projects/ProjectBrowserScreen.kt:50` keeps the picker-visible flag in ordinary
`remember` state. `projects/DriveProjectPicker.kt:48-49` similarly keeps the
operation ID and identity in ordinary `remember` state, and its result callback
discards results without a matching operation (`:79-88`). Activity recreation
while an external consent resolution is open can therefore unmount the picker
or drop its result, requiring the user to reconnect. This is a retry/usability
issue, not a demonstrated document-loss path.

Retain the exact pending operation and account generation in an appropriate
recreation-safe owner, or recover to an explicit reconnect state. Keep tokens
out of saved UI state. Test recreation while consent is pending and a delayed
result from an older operation.

### A6 — P3: successful coordinator retirements remain strongly reachable

Account/root changes are keys for `documentCallbacks` in
`MainActivity.kt:1240-1249`, creating new document and sync owners. The
remembered `coordinatorsByOwner`, `syncCoordinatorsByOwner`, and
`compositionDocumentHosts` collections add each owner at `:1384`, `:1817`,
and `:2943` but never remove successfully retired owners. Retired
`SyncCoordinator` records can retain snapshots and photo-asset descriptors
(`stage4/SyncCoordinator.kt:417,462-499`), and a retired host still holds its
retirement closure (`stage3/DocumentHostHandoff.kt:23-55`). Repeated auth/root
rebinding grows retained memory until the entire composition ends.

Prune all three collections only after the old owner's durable flush and
`closeAndJoin` succeed. Keep failed retirements reachable for the existing
retry contract. Test repeated rebinds and retained owner/record counts.

### A7 — P3: page-code cache lacks an aggregate budget

`stage8/PageCodeControls.kt:21-26,54-68` writes a file per exact source key and
caps each file at 2 MB, but has no aggregate count/size/age eviction. Scanning
many distinct or revised PDFs can accumulate private cache files until Android
reclaims cache storage. This is disposable data, not annotation corruption.
Add a bounded aggregate cache policy with safe eviction of inactive keys.

## Architecture and qualification priorities

`MainActivity.kt` is about 8.4k lines, including a roughly 4k-line
`BlueprintApp` composable (`:1029-4994`) that binds document sessions, sync,
auth, browsing, and export. `SyncCoordinator.kt` and `PhotoAssetStore.kt` are
about 4k lines each. The immediate extraction should follow A1: make remote
link versus local apply explicit in one durable state machine, then move
export and project-auth lifecycle ownership behind narrow tested interfaces.
Preserve transaction barriers, token fences, journals, rollback, and photo
leases; file size alone is not a correctness failure.

`.github/workflows/android.yml` runs host gates and the native `smoke` suite,
while `tools/run_native_qualification.py:632-666` restricts smoke to a small
source-derived class set. Full SAF, restart recovery, and account-dependent
provider scenarios are therefore not routine CI gates. Before employee APK
distribution, run the full local fixture/SAF matrix on an owned disposable
target and a separately authorized synthetic live-provider matrix. The
`GoogleDriveProjects.kt:60-69,129-135` media-read path also needs a redirect
fixture because it disables redirects; a live-provider failure was not
confirmed. Signed release install/upgrade, Pixel behavior, cloud restore,
and device transfer remain the already documented qualification limits.

## Validation and state

- Corrected host command: `gradlew.bat --no-daemon --stacktrace --console=plain '-Pkotlin.incremental=false' :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed. Gradle reported 86 actionable tasks, one executed and 85 up-to-date. The first invocation had an unquoted PowerShell property and stopped at task parsing; it ran no product tasks.
- Existing JUnit XML reports: 1,020 tests, 1,014 passes, six platform skips, zero failures/errors. Lint: zero errors, 118 warnings.
- `python -B tools/test_native_qualification.py`: 36 passed. `powershell -NoProfile -File tools/test_run_logcat_app.ps1`: seven mocked-ADB cases passed. `git diff --check`: exit zero.
- Application and instrumentation APK SHA-256 values match the September 14 repair closeout exactly: `fdea8c4a5b42424389934a7f0e352c5709089f4ebe6ea53204cf3c51379a5745` and `2c47f0a8bb407c2f5dd1cd6b0da122bd9fb339cdd6e77b388f6076b9c61f9291`. That closeout records 126 tablet passes, one hard-link capability skip, and seven passing SAF workflows for these artifacts. No native test was rerun in this audit; the live-provider omission and release limits remain open.

The pre-existing worktree edits remain uncommitted. This audit adds only
documentation; it does not change app, test, build, signing, account, or device
state. No commit, push, or email occurred.
