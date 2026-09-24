# SOTAware Construct: five-finding repair closeout

Date: September 14, 2026.
Branch: `codex/stage-3-transactional-switching`.
Unchanged Git HEAD: `c265c963788261b23126fea8cc91e2812d739539`.

## Disposition

**All five functional findings are repaired and locally qualified.** Final host and targeted tablet checks pass. The complete tablet runner remains formally **BLOCKED**, not an unrestricted green release gate, because it retains one device hard-link capability skip and deliberately omits the account-dependent live-provider class. There are no failed local tests in the final candidate.

The repairs apply to the real feature working tree, including the earlier audit fixes and unpublished project-folder, page-code, and annotation changes. Existing work was preserved. No commit, push, merge, branch deletion, signing change, or stage advancement was performed. This report supersedes the five open functional findings in the [September 14 audit](README.md); it does not overwrite its historical evidence or certify an external release.

## Repairs and acceptance evidence

| Finding | Repair | Validation |
| --- | --- | --- |
| O1 / S13-02: stale dimensions after recalibration | Scale and existing labels update atomically through canonical history, using actual PDF geometry or oriented photo aspect | JVM straight/polyline/photo, undo/redo and rejection tests; native measurement and full-state SAF round trips |
| O2 / S13-04: dense photo appendix fails export | Paginated appendix with positive cell dimensions, continuation headings and aggregate decode targets | Actual 1, 2, 7 and 128-photo exports in both letter orientations; every distinct photo label exactly once; paper size and missing-asset checks |
| O3 / S13-05: photo layer/selection order differs | One back-to-front scene for display/export; hit-testing traverses it in reverse | Cross-kind ordering and topmost-selection tests; real exported-note-over-path pixel regression |
| O4 / S13-06: interrupted project downloads strand data | Durable transaction receipts precede staging; startup/retry verifies, publishes or safely retires only owned output | Seven publication interruption boundaries; corruption, changed bytes, cancellation, torn first receipt, hard-link safety, idempotence and unrelated-file preservation |
| O5 / K01: deactivated-node project-browser crash | Location-owned lazy-list state, stable typed item slots, and the applicable Compose 1.7 maintenance fixes | Previously failing tablet workflow, combined matrix, and repeated retained-state tests below |

### Recalibration

`stage8/MeasurementCalibration.kt` calculates labels from each measurement's full geometry. `AnnotationReducer.ScaleEntry` stores detached before/after scale and dimension lists as one bounded history entry. Apply and reverse validate expected state before committing a single Compose snapshot transition. Photo recalibration updates the photo's scale and labels through the existing pin transaction. Normal dirty/save/sync effects are retained; missing geometry or unrepresentable results reject the entire change rather than leaving a partial recalibration.

### Photo export and composition

`stage6/PhotoAppendixLayout.kt` lays out bounded photo groups instead of fitting an entire pin into one page. Export preserves original physical page dimensions and every photo/reference. Decode targets are apportioned across the appendix; the sampled-memory regression is evidence for the tested fixtures, not a universal peak-memory guarantee. The native test extracts all 128 distinct photo labels exactly once and checks page count/dimensions in portrait and landscape.

`stage8/PhotoAnnotationScene.kt` supplies paths, measurements, notes and shapes in the same order to display and export. Selection walks that order backward. Drafts and selection decorations remain display-only. A new real PDF export regression places a red note over a thick green path and verifies that the red note remains visible in the rendered appendix. Shared-scene unit tests independently assert cross-kind stacking and topmost selection.

### Project-download recovery

`projects/ProjectDownloadJournal.kt` writes an atomic receipt before staging. After all PDFs, checksums, catalog and directory durability checks pass, it records the verified file set. Startup and download retry hold the downloader mutex while reconciling receipts. Verified-but-unregistered output can be published without duplicate downloads; interrupted unverified work is removed only under its exact transaction identity. Accepted content is verified before receipt retirement. Corrupt receipts, changed verified assets and uncertain library state fail closed without discarding accepted or unowned bytes.

Ordinary cancellation marks abandonment durably before deleting a verified-but-unaccepted tree, so a second interruption resumes cleanup rather than trying to verify a half-deleted project. A torn first temporary receipt can be retired only when neither a project directory nor a library entry exists. Receipt scratch files are unlinked and recreated exclusively, never truncated through an existing hard link. Existing unrecorded directories are deliberately preserved; no broad orphan-directory purge was performed. Project-library publication now also synchronizes its parent directory before successful acknowledgment.

The device recovery fixture constructs all seven interruption states, covering staging, PDF sync, catalog sync, verified receipt, rename, library registration and retirement. Its dedicated stage/force-stop/recover sequence requires a different process ID before verification. This is real fresh-process recovery of fault-injected boundary states, not a claim that the operating system was killed separately during each filesystem instruction.

### Project-browser crash

Source-only list changes still reproduced the original crash in the earlier candidate. The final candidate additionally moves the Compose BOM from `2024.09.00` to `2025.02.00`, bringing the runtime/foundation/UI family from 1.7.0 to 1.7.8. AndroidX Foundation's official 1.7.4 notes document removal of `ReusableContentHost` at lazy-item roots as a potential cause of the same deactivated-node error. This is a targeted maintenance-line correction for the observed crash, not a general dependency upgrade. It is paired with location-owned scroll/layout state and explicit item keys/content types.

The original failing workflow remains in the suite. Additional invocations retain application drawing data, alternate portrait and landscape, and repeat folder selection, nested navigation, drawing edits, recent-file reopening, startup and Activity recreation. Passing repeated executions supports closure of the observed defect; it is not a proof that every Compose path on every device is infallible.

## Final qualification

| Check | Final observed result |
| --- | --- |
| JVM suite | 1,020 collected: 1,014 passed, six existing platform skips, zero failures/errors |
| Debug and instrumentation APKs | Both built successfully |
| Lint | Zero errors; 118 warnings remain |
| Full physical-tablet local matrix | 127 collected: 126 passed, one existing hard-link capability skip, zero failures; all 49 planned invocations attempted |
| Actual SAF workflows | All seven passed |
| Extra retained-state browser workflow | Five of five passed, alternating portrait/landscape without clearing drawing data between repetitions |
| Project-download fresh-process recovery | Stage and restart phases both passed; new process ID verified |
| Native-runner parser/safety tests | 36 passed |
| Mocked app-only logging tests | Seven passed |
| Source preservation and patch checks | Expected hashes match; repair-only patch reverse-check and Git whitespace checks pass |

The final matrix has 48 wholly passing invocations and one invocation with its existing capability skip. The separately declared live-provider omission is not counted as a pass. Seven additional native invocations pass: five retained-data browser repetitions and the two-phase project-download restart check. Earlier failure evidence remains preserved. The final app and test source/build/resource inputs match the frozen artifacts throughout qualification.

The final host command was:

```powershell
.\gradlew.bat --no-daemon --no-build-cache --stacktrace --console=plain -Pkotlin.incremental=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python -B tools/test_native_qualification.py
powershell -NoProfile -File tools/test_run_logcat_app.ps1
```

The earlier integrated tablet run reached every planned local invocation. Its one assertion failure was an older reducer fixture attempting recalibration of existing dimensions without source geometry. The fixture now supplies its synthetic page dimensions and explicitly checks the resulting label; no history, clear, restore or redo assertion was removed. Earlier source-only browser failures and the initial blocked stale-source test selection are retained in the evidence rather than relabeled as passes.

The native-runner self-test also still expected two measurement-hint tests and 16 smoke tests, predating the added extreme-scale regression. Those explicit expectations and the documentation now match three hint tests and 17 smoke tests. The runner implementation itself was not changed. Its complete 36-case regression suite passes. These test-count/documentation edits occurred after the final application/instrumentation artifacts were frozen; all runtime, native-test, resource and Gradle inputs still match the frozen candidate.

## Limits and publication

The six JVM platform skips and the tablet hard-link capability skip remain explicit. Account-dependent live-provider testing was deliberately omitted. Signing, signed-release installation/upgrade, broader multi-account conflict behavior, other device models, cloud restore and device transfer are not certified by this repair. No test omission was counted as a pass, and no real Drive data was modified by qualification.

Pre-existing unrecorded download directories are not automatically deleted: a missing ownership receipt does not grant permission to erase arbitrary files. The new journal protects new transactions and their resumptions; accepted or uncertain data stays protected. The application still has the architectural debt described in the original audit, including the large activity and coordinator classes. These repairs extract narrow contracts, not a wholesale architecture rewrite.

The exact tested debug APK remains installed on the TB336FU tablet; its on-device SHA-256 was verified against the tested artifact. After qualification, synthetic target-application data was cleared and the app was cold-launched successfully. Original auto-rotation settings were restored. The existing instrumentation fixture package remains installed. Real Drive files, account settings outside this application, other applications and unrelated computer processes were untouched.

## Evidence and final identities

Detailed evidence, baseline copies, checkpoints, source hashes, command logs, parsed instrumentation outcomes, final JUnit/lint reports and APKs remain in OS-temp `construct-five-repairs-f1zq91nr`, principally `resume-20260914`. The repair-only patch is relative to the preserved pre-repair feature worktree, not directly to HEAD; applying it to an unrelated checkout is not supported.

Application APK SHA-256: `fdea8c4a5b42424389934a7f0e352c5709089f4ebe6ea53204cf3c51379a5745`.
Instrumentation APK SHA-256: `2c47f0a8bb407c2f5dd1cd6b0da122bd9fb339cdd6e77b388f6076b9c61f9291`.
Repair-only patch SHA-256: `cf206bb43a399c49a44495b36295f2dddb2cc883499d6b69d2a46a8c0bb00b5a`.

The Git index remains untouched and the repairs are uncommitted. The previous feature work and all earlier audit repairs remain present. No notification email was sent because no push occurred.

External reference: Android Developers, Jetpack Compose Foundation release notes, version 1.7.4 (October 16, 2024), documents the matching deactivated-node correction; version 1.7.8 is the selected maintenance release. See https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.7.4 .
