# SOTAware Construct Audit Working Roadmap

Canonical roadmap: `SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md` (documentation commit `adbee4eeb0c990226a7f9e887f719a2dbcd9105d`).

This file tracks implementation status only. The canonical document remains the source of truth for the complete remediation project.

| Stage | Status |
| --- | --- |
| Stage 0: Establish reliable gates | complete |
| Stage 1: Create one canonical document snapshot | complete |
| Stage 2: Replace local persistence safely | complete |
| Stage 3: Make document switching transactional | complete |
| Stage 4: Replace synchronization with one serialized coordinator | complete |
| Stage 5: Harden filenames, payloads, and photo transactions | complete — Coder implementation and gates complete; Luna Max Reviewer, bounded Foreman, and Terra Max Inspector passed |
| Stage 6: Make import/export current and self-contained | closed/passed — Android gate passed; Reviewer Halley PASS, Foreman PASS, and Terra Chandrasekhar PASS |
| Stage 7: Fix rendering and OCR | closed/passed — final certification, exact-SHA CI, fresh reviews, device qualification, and Terra inspection passed |
| Stage 8: Repair search, annotation actions, and responsive UI | closed/passed — final JVM, build/lint, and emulator qualification passed; bounded reviewer/inspector deferments recorded |
| Stage 9: Privacy, authentication, release, and cleanup | closed (internal-company scope) — lifecycle/auth code and local debug qualification passed; external OAuth/Drive and release qualification deferred pre-deployment |
| Stage 9A: Cross-stage correctness repair | Closed for internal development at published `69019f4`; exact-SHA CI passed. Native/live-provider limits remain explicit. |
| Stage 9B: Annotation consolidation and immutable photo synchronization | scoped recovery correction qualified for internal development; final matrix2/native-storage2 pass, with scoped independent adoption-delta review and explicit recovery limits; see latest STAGE9B_IMPLEMENTATION.md entry |
| Stage 10: Final qualification | pending |

## Stage 0 scope

Stage 0 is limited to reliable build/test/lint and developer gates, deterministic regression fixtures, legacy persistence characterization, and focused state-loss canaries. It does not implement the snapshot, identity, persistence, synchronization, OCR, rendering, import/export, or UI architecture assigned to later stages.

## Stage 0 status

- Baseline recorded on branch `codex/stage-0-gates` from starting commit `e010bee287894abdcaf29b5e539f16269a94a9c5`.
- Baseline: `assembleDebug` and `testDebugUnitTest` passed; only the template arithmetic test existed. Baseline `lintDebug` failed with 1 `SuspiciousIndentation` error at `MainActivity.kt:5509` and 77 warnings.
- Integrated: tooling repairs, CI, deterministic fixtures, local/Drive characterization tests, legacy descriptors/FQNs, sync-payload seam, and state-loss canaries are complete.
- Current gate evidence: debug assembly passes; all 17 JVM tests pass; lint reports 0 errors and 77 warnings; PowerShell and VS Code task validation pass; CI YAML parses and contains required gates. Final Gradle artifacts were cleaned and the tracked wrapper partial is restored to zero bytes.
- Device/instrumentation smoke test: unavailable in this environment and not represented as passed.
- Stage 0 completion decision: complete after final post-cleanup independent review; no Stage 1+ remediation has begun.

## Stage 1 status

- Completed on branch `codex/stage-1-canonical-snapshot` from verified Stage 0 commit `1218d50a593a72832c0577de7bcc3dd8fe5b514f`.
- Added typed/versioned `DocumentSnapshotV1`, one `snapshotFromState()` path, one `applySnapshotReplace()` path, deep-copy boundaries, true replacement semantics, and a thin legacy Drive adapter.
- Immediate, debounced, automatic, and manual sync routes all use the canonical snapshot adapter. Remote update application also uses canonical replacement.
- Final gates: `assembleDebug` passed; `testDebugUnitTest` passed with 28 tests, 0 failures, 0 errors, 0 skipped; `lintDebug` passed with 0 errors and 77 warnings.
- Stage 1 independent review completed; its materialize-before-mutate blocker was resolved and the complete final gate was rerun.
- Connected instrumentation sanity check subsequently passed after a data-preserving in-place APK replacement resolved the tablet's version-code downgrade blocker: `connectedDebugAndroidTest` ran 1 package-context test on `TB336FU` (Android 16) and completed successfully. This proves installation/instrumentation/package-context sanity only, not a functional app smoke test.
- This follow-up documentation commit is the Stage 1 handoff push; remote Actions status is recorded separately if available. Stage 2 is the next recommended assignment; it was not started.

## Stage 2 status

- Completed on `codex/stage-2-local-persistence` after the final independent review and all required gates.
- Focused production commit: `f2e74270fd8e908df608e1e341bfa8aae3c2daab`.
- The implementation uses app-generated UUID associations, a safe manifest, SHA-256 change detection, typed `LocalDocumentRepository` snapshots, atomic staging with previous-good recovery, quarantine, process-wide per-document serialization, and read-back-verified legacy migration with legacy artifacts preserved.
- Final gate: an interrupted write recovers the previous complete snapshot, and corruption never silently becomes a blank document. Stage 3 switching orchestration and all later-stage work remain pending.

## Stage 5 closure status

- Stage 5 is complete for the uncommitted candidate at baseline `ac9f4e3`: filenames, bounded/typed payloads, Drive identity and query handling, validated transfers, and photo transactions passed the final independent review chain.
- The latest Luna Max Reviewer returned PASS, the bounded Foreman review returned PASS, and the fresh Terra Max Inspector (`gpt-5.6-terra`, max reasoning) returned PASS with no blocker.
- Preserved green evidence: focused Stage 4/5 JVM 166 tests, Stage 0–4 JVM 183 tests, full JVM 255 tests, `assembleDebug` PASS, `lintDebug` PASS, and `git diff --check` PASS, with the expected qualified Windows symlink capability skip.
- At the time of this Stage 5 closure entry, no Stage 6 work had started; the current Stage 6 candidate status is recorded below.

## Stage 6 candidate status

- The uncommitted candidate based on `ea0f31f7fb6a580dfc116bf39acf04a1e66e2759` passed the Stage 6 Android functional gate on authorized `HNY0DSR8` (`TB336FU`, Android 16/API 36), including the final ZIP data-descriptor rejection repair. It provides a versioned, self-contained `.sotaware` manifest/snapshot/photo bundle covering every canonical domain. Stage 6 is closed/passed for this candidate after Reviewer Halley PASS, Foreman PASS, and Terra Chandrasekhar PASS.
- The pre-certification Stage 7 record on baseline `f9a532fc2b5f19b226c042b80af88f4d5ddf34cf` is preserved in `CODEX_AUDIT_IMPLEMENTATION_LOG.md`; the final Stage 7 certification closure below supersedes its pending status.

## Stage 7 certification closure

- Baseline: `f9a532fc2b5f19b226c042b80af88f4d5ddf34cf`. Certification commit `bb8fd34076796acd9102c138403e0fe5a887bc45` and bounded-cache repair `abfa0c7e871784abf6aa1d0a9da93c3954569ef2` are pushed on `codex/stage-3-transactional-switching`.
- Exact-SHA GitHub Actions passed: run `33778114577` for `bb8fd340` and run `33782847316` for `abfa0c7`, each terminal `success`.
- Final local evidence on `abfa0c7`: focused Stage 5/7 JVM tests passed 165 tests with 0 failures/errors and 2 skips; full JVM tests passed 390 tests with 0 failures/errors and 3 skips; `assembleDebug` and `assembleDebugAndroidTest` passed; `lintDebug` passed with 0 errors and 75 existing warnings.
- Authorized device `HNY0DSR8` (`TB336FU`, Android 16/API 36) passed the targeted Stage 7 connected suite (6 tests) and full connected suite (7 tests), both with 0 failures/errors/skips.
- Fresh Luna reviews and the final fresh Terra Inspector passed. The repair bounds cached OCR payloads per page and in aggregate, prevents unbounded pre-cache staging, preserves rollback across flushed pages, and serializes cross-namespace cache transactions.
- Deferred compatibility follow-up: synchronous compatibility-only `OcrIndex.close()` does not clear cache prefixes; production paths use `closeAndJoin()`. Consider documentation/deprecation during a later cleanup stage. Stage 8 is next; Stages 8–10 remain out of scope.

## Stage 8 closure — uncommitted candidate (2026-09-04)

- **Status: CLOSED/PASSED.** This closure records the uncommitted candidate at
  baseline/`HEAD` `456cdaf839162dac38edecbb5e5467aae16cb0f5`; candidate SHA is
  explicitly `UNCOMMITTED` (no commit or push was made).
- Bounded implementation repaired phrase-search publication and stale-result
  clearing, reducer/history parity and stale annotation preconditions for PDF,
  image, and photo-pin domains, OCR cache-miss long-press selection/Copy
  admission, wide-landscape control layout, safe drawing insets, and remaining
  touched user-visible resources. Production-route tests cover viewer search,
  renderer OCR selection, reducer effect consumption, control bounds, and
  photo/gallery Back and gesture lifecycle.
- Final evidence: Stage 8 focused JVM regressions plus the exact adjacent
  selectors `DocumentSnapshotV1RoundTripTest`, `SyncRouteEquivalenceTest`,
  `DocumentSelectionIntegrationTest`, `DocumentSwitchCoordinatorTest`,
  `SyncCoordinatorTest`, `Stage3RemoteAcceptanceIntegrationTest`,
  `DrivePaginationTest`, `SyncMetadataStoreTest`,
  `PhotoContentTransactionTest`, `BitmapBudgetPolicyTest`,
  `ByteAwareResourceLruCacheTest`, `OcrIndexCacheTest`, `OcrSessionTest`,
  `PdfCoordinateMapperTest`, and `Stage7WorkerResourceBoundaryTest`; full JVM
  `430` tests with `0` failures/errors and `3` skips; `assembleDebug`
  PASS; `lintDebug` PASS; and full `connectedDebugAndroidTest` PASS with
  `22/22`, `0` failures/errors/skips, on the newly booted task-local
  disposable Medium_Phone_API_36 Android 16 emulator `emulator-5562` with
  `ANDROID_SERIAL` set. No physical device `HNY0DSR8` was used.
- Final Luna Reviewer disposition: `MINOR-DEFERABLE`. Independent Sol
  Inspector disposition: `MINOR-DEFERABLE`. Deferred owners/scope are the
  Stage 8 test cleanup for explicit `ActivityScenario` close handling,
  coordinator-launcher injection coverage, and untouched `HudOverlay`
  hard-coded strings; these are a future bounded Stage 8 follow-up or Stage 9
  only if later shown appropriate, and are not Stage 9 work here.
- Restart recovery was completed with process launch restored; after final
  tests, the disposable emulator `emulator-5562` was shut down and only its
  verified task-local data directory
  `%TEMP%\sotaware-stage8-postrestart-emulator-<run-id>`
  was removed. No user data, physical device, or unrelated artifact was
  deleted. The emulator
  used an SDK-local path rather than PATH discovery, with Gradle 9.1, JDK 21,
  and compile SDK 36. Broad pre-existing Android/Gradle/JDK homes, outputs,
  caches, and other dirty artifacts were preserved. `AGENTS.md` staged
  trailing whitespace at line 649 remains untouched. Normal
  `git diff --check` for the uncommitted candidate passed; cached diff-check
  status is not claimed because the unrelated pre-existing staged change in
  `AGENTS.md` remains. Stage 9 was eligible next at that closure; its later
  continuation is recorded below.

## Stage 9 continuation status

This section records the broader external/release qualification status before
the owner's 2026-09-07 internal-company scope decision. Its historical open
disposition is superseded by the scoped closure recorded at the end of this
file; the evidence and limitations remain applicable to future deployment.

- Continued the paused Credential Manager / AuthorizationClient migration on
  `codex/stage-3-transactional-switching`, HEAD
  `456cdaf839162dac38edecbb5e5467aae16cb0f5`, preserving the prior dirty tree
  and staged `AGENTS.md`. No reset, cleanup, staging, commit, or push.
- Repaired authentication epochs, account/subject/root isolation, live Compose
  authorization state, exact-token 401 invalidation, and owned root-operation
  cancellation/draining. The established Stage 4–6 coordinator, gateway,
  snapshot, persistence, and conflict owners remain in place.
- The active Drive root flow uses a marked app-created folder under
  `drive.file`. The unreachable general folder browser is retained for a later
  explicit Picker decision; no broader Drive scope or collateral cleanup.
- Current debug evidence: compile PASS; focused Stage 9 JVM 41/41 PASS; full
  JVM 471 total tests (468 executed), 0 failures/errors, 3 Windows symlink
  capability skips;
  `assembleDebug` PASS; `lintDebug` PASS with 0 errors and 87 warnings.
  The earlier complete connected suite passed 30/30 with no
  failures/errors/skips on the authorized TB336FU tablet (Android 16/API 36)
  before the latest root-metadata/restore-marker follow-ups. Those follow-ups
  pass the latest debug/JVM/lint gates. A real internal Workspace account was
  used on the current debug install for sign-in, Drive grant, restart,
  sign-out, and chooser cancellation checks; this does not replace the
  synthetic external-account, release, or full-transfer matrix. The prior
  independent Luna review passed with three nonblocking follow-ups; a fresh
  review of the latest candidate was unavailable because the runtime usage
  limit was reached. At that time Stage 9 remained open under the broader
  external/release gate; that historical disposition is superseded below.
- `STAGE9_AUTH_RELEASE_SETUP.md` supplies exact external setup instructions,
  public debug certificate fingerprints, and the one-time user-owned keystore
  procedure. The supplied Web OAuth client ID is configured through the
  untracked user Gradle properties and is present in the current debug build.
  The release-signing prerequisite check still fails as expected while its
  four external inputs are absent. External synthetic-account consent,
  account switching/revocation, Drive upload/download, signed release
  install/authentication, repository cleanup, and CI qualification remain
  future pre-deployment follow-ups. Stage 10 has not started.

## Stage 9 qualification update before internal-scope decision — 2026-09-07

- The owner supplied a non-secret Web OAuth client ID through the user-level
  Gradle properties file outside the repository. The current debug artifact
  therefore carries a non-empty `BuildConfig.GOOGLE_WEB_CLIENT_ID`; the ID is
  intentionally omitted from this roadmap and the evidence log.
- On the authorized TB336FU tablet (Android 16/API 36), the user completed
  internal Workspace sign-in and Drive grant in the real `com.sotaware.construct`
  debug app. Force-stop/relaunch restored the signed-in/root state; sign-out
  cleared the local session and root; a subsequent explicit chooser cancellation
  left the app signed out. These are real UI observations, without recording
  account identifiers or tokens.
- The post-follow-up closure gates passed serially: `assembleDebug`, the full
  Stage 9 focused JVM result of 41 tests with 0 failures/errors/skips, the full
  `testDebugUnitTest` result of 471 total tests (468 executed) in 40 suites
  with 0 failures/errors and 3 existing Windows symlink capability skips, and
  `lintDebug` with 0
  errors and 87 warnings. The previous 30/30 connected suite remains valid
  evidence for its earlier candidate, and the current candidate now also has a
  fresh 30/30 connected result with 0 failures/errors/skips after the synthetic
  root metadata repair.
- `verifySotawareReleaseSigning` remains an expected FAIL because the four
  human-owned release signing inputs are absent. The synthetic external-account
  matrix, process-recreation during consent, Drive transfer, and signed-release
  checks remained open under the broader release gate at that time. The
  separate read-only review initially found the stale fixture blocker; its
  targeted delta review is `PASS WITH FOLLOW-UPS`, with D13 process recreation
  and the external matrix recorded as future pre-deployment follow-ups. The
  current source-input manifest is recorded in the implementation log.

## Stage 9 D13 authority repair update before internal-scope closure — 2026-09-07

- Independent lifecycle review found that the authorization-result tracker
  survived Activity recreation while its `remember`-owned `DriveSyncManager`
  did not. A post-recreation numeric generation collision could therefore have
  associated an old account's grant with a replacement manager. The manager is
  now retained by the same `BlueprintViewModel` as the tracker, and each pending
  result is additionally bound to the exact manager owner, generation, and
  `GoogleIdentity` before provider completion can run.
- A new app-owned, non-exported authorization trampoline preserves an immutable
  random operation ID across its recreation. Unit regressions reject stale
  operation, owner, generation, identity, and process-collision combinations;
  a debug-only Activity test proves the manager owner survives configuration
  recreation. Failed account switches and matching 401 revocations also clear
  the old restore marker. The Drive upload adapter now rejects an account-scope
  mismatch before constructing any HTTP request.
- Latest serial local gates pass: Stage 9 JVM 49/49; full JVM 480 total tests
  (477 executed), 0 failures/errors and 3 existing Windows symlink capability
  skips; `assembleDebug`; `assembleDebugAndroidTest`; `lintDebug` with 0 errors
  and 91 warnings; and the complete connected suite 33/33 on TB336FU / Android
  16 / API 36. The fresh review's blocker is closed; targeted delta disposition
  is `PASS WITH FOLLOW-UPS`.
- This repair fails closed after true process death but does not qualify D13's
  real delayed-consent same-account/different-account workflow. D2/D8 real
  two-account behavior, denial/revocation/network cases, complete Drive
  transfer, the signed-release R1–R6 matrix (and R7 if Play signing applies),
  repository publication, and CI remained open under the broader release gate.
  They are future pre-deployment follow-ups for the internal-company decision;
  Stage 10 has not started.

## Stage 9 internal-company closure — 2026-09-07

- **Status: CLOSED for the internal-company development scope.** The owner
  decided that SOTAware Construct will remain internal and will not be publicly
  released in the current planning horizon. The closure boundary is the
  `com.sotaware.construct` Android-signed debug APK, the checked-in
  privacy/authentication safeguards, and the real internal Workspace
  sign-in/Drive-grant/restart/sign-out evidence.
- The current local technical evidence is Stage 9 JVM 49/49, full JVM 480
  tests (477 executed, 3 existing Windows symlink capability skips), debug and
  Android-test assembly PASS, lint PASS with 0 errors, and complete connected
  device coverage 33/33 on TB336FU / Android 16 / API 36. Final integrated Luna
  review is `PASS` after two cancellation tests added explicit outer-job joins
  to remove a scheduling-only assertion race; production ownership and request
  oracles are unchanged. The repaired D13 code race remains covered by
  synthetic tests and Activity recreation instrumentation.
- The internal Workspace UI path proves sign-in, Drive grant, force-stop/
  relaunch restoration, local sign-out, and chooser cancellation on the
  authorized debug install. It does not prove external synthetic-account
  consent, live two-account switching, revocation, provider/network failure,
  complete Drive upload/download, or delayed-provider process recreation.
- Public launch, external OAuth audience/verification, public
  privacy/homepage/terms pages, Play App Signing/R7, a signed public release,
  and external release publication are not applicable to this closure. No
  release certificate, Play certificate, public OAuth verification, clean
  repository, CI, or public-release pass is claimed.
- Live-provider D2/D8/D9/D11/D13 and provider/network cases are retained as
  pre-deployment qualification follow-ups. A stable company signing key and
  release-certificate OAuth registration are deferred until durable employee
  APK/private distribution and updates are planned; they are not current
  closure blockers. The canonical broader release gate remains available for
  that future qualification and is not silently marked passed here.
- Stage 10 remains pending for the broader final qualification. No public or
  private employee distribution is implied by this scoped Stage 9 closure.

## Stage 9A cross-stage repair, 2026-09-08

- **CLOSED/PASSED for internal-company development.** Qualified implementation:
  `2eabb5d1eb42b8396ab4765219ff45bfc59edcf7` on `codex/stage-3-transactional-switching`.
  This is local-only; no push or candidate CI pass is claimed.
- Implemented history/replacement/rollback ownership, same-source lifecycle durability,
  complete photo-retention authority, immutable snapshot/photo pending outbox, strict
  calibration admission, durable camera result ownership, and actual Drive root IDs.
- The fresh integrated Luna review found one failed-switch history blocker. Six new tests
  recorded five pre-repair failures and one successful-switch isolation control. The repair
  passed all six plus adjacent tests; the independent targeted delta review returned PASS.
- Final post-review root gates: debug assembly PASS; fresh JVM 541 total / 537 executed /
  zero failures/errors / four capability skips; lint zero errors / 91 warnings; full Android
  emulator 44 total / 43 executed / zero failures/errors / one hardlink-capability skip.
- Earlier real process-death Camera2 capture and cancellation proofs are retained as scoped
  evidence for unchanged camera code. The final native suite reran camera/history/outbox tests.
  The physical tablet and its populated install were not used or changed.
- Source hashes, exact commands, review dispositions, platform limits, cleanup, and the
  concrete Stage 9B/10 contract are in `STAGE9A_REPAIR_NOTES.md`.
- Stage 9B separate immutable remote-photo transport is next and remains unimplemented.
  Stage 10 remains pending. Live external provider transfer, signing and employee/public
  distribution are separate future gates, not part of this internal debug qualification.

## Stage 9B scope authorization

The current user authorized implementation, normal commit/push and email of the expanded
9B contract. Backward compatibility is not required. Retired formats must fail explicitly
without modifying their bytes or the current document. Current-format recovery, atomic
saves, undo/photo reachability and session/conflict fences remain required.
The five baseline admission failures and two controls are recorded in STAGE9B_IMPLEMENTATION.md.
No 9B qualification pass or Stage 10 completion is implied by the Stage 9A baseline.

## Stage 9B solo continuation checkpoint, 2026-09-10

Stage 9B is implemented in the preserved uncommitted worktree but is not closed or published.
The latest source remains based on `69019f4440e9704e12e59332b845144dce1788f1`.
Fresh matrix45: debug APK, Android test APK, and lint pass; 685 JVM cases with 679 executed,
six capability skips, and zero failures/errors. Lint reports zero errors and 87 warnings.
The current-production emulator full suite and all five actual SAF phases passed; subsequent
code changes affect only the opt-in live-provider harness, not those production/native paths.
Fresh-target live Drive qualification passed actual upload/readback, unchanged-photo identity
and bytes, annotation-only update, and Keep Local conflict preservation. In-place upgrade
recovery of previously failed pending data also passed without clearing that data first.

Closure remains BLOCKED on final physical-tablet full/SAF requalification, whose fresh-install
operation was rejected before execution, and a fresh independent review of the Drive protocol
and live-harness delta. The latest continuation launched no subagents. Prior independent PASS
and older tablet results are retained only for their actual candidate and scope.
See `STAGE9B_IMPLEMENTATION.md` for the gate/evidence ledger and exact limitations.
No commit, push, release, or success email has occurred. Stage 10 is not started.

## Stage 9B safe tablet qualification checkpoint, 2026-09-10

The physical-tablet qualification blocker above is resolved through a separate disposable
QA installation, not by retrying the blocked original-app wipe. Production source is
hash-identical; only application/test identities and fixture-provider labels differ.
Final tablet47: full suite 66 executed passes, seven explicitly recorded skips, zero failures;
all five actual SAF phases then execute separately and pass without skips. Original-package
boundary47 checks pass 11 cases, with one Android hard-link capability skip. Windows JVM47
executes and passes the corresponding hard-linked staging regression.

The supported 100 MiB physical memory envelope passes: incremental Java 78,557,784 bytes,
native 35,180,752 bytes, maximum read 65,536 bytes. Two native test helpers now drain queued
preference writes before cleanup; the fixed rerun creates no new test preference residue.
All 17 pre-existing original-app durable files remain unchanged. Four earlier task-only
preferences are retained after a narrowly scoped runtime cleanup rejection.

Final matrix47 passes 685 JVM cases (679 executed, six capability skips), both APK builds,
and lint with zero errors and 87 warnings; lint reused its unchanged-input report.
Production APK/live harness remain unchanged from passing live44. Emulator full40/SAF40
are reused for unchanged paths, with all four changed native fixture cases passing again.

The remaining closure blocker is a fresh independent review of the integrated 14-file
provider/harness/test-cleanup delta. No agents were launched and no independent PASS is
invented. No commit, push, release, or success email. Stage 10 remains unstarted.
See the latest STAGE9B_IMPLEMENTATION.md ledger and external qualification47 checkpoint.

## Final delta review and adoption repairs, 2026-09-10

Disposition: PASS after two bounded sync-integrity repairs. The user explicitly authorized
fixes and self-review for this final delta; no independent worker or Inspector PASS is invented.
All 14 checkpoint scope hashes and the complete 320-file starting manifest matched final47.
Only DriveGateway.kt changed in production during this review. The new
DriveAdoptionAmbiguousAssetTest.kt adds 14 deterministic real-adapter regression cases.

- P1: A committed immutable-asset ownership PUT could lose its reply, fail during response
  cleanup, or return malformed metadata. The asset was then absent from rollback bookkeeping,
  leaving its owner changed while the manifest reverted. Ambiguous outcomes now require a
  scoped readback of the exact asset, parent, immutable-content evidence and complete property
  map. The observed ETag is retained with the original properties for conditional rollback.
- P1: A successful asset reply was trusted without checking its returned ownership map.
  The expected complete map is now checked; an inconsistent reply cannot authorize adoption.
  HTTP 412 remains terminal, cancellation propagates, and the PUT is never blindly replayed.

Failure evidence: adoption-ambiguity-red reproduced three failures on unchanged production;
ack-validation-red reproduced two additional acknowledgement failures during self-review.
The final-reviewed-matrix executes 699 JVM cases: 693 passes, six explicit platform-capability
skips, zero failures/errors. All 14 new cases execute and pass, including external owner,
content and parent changes, before-commit failure, committed/uncommitted server failure,
and an external edit between verified readback and conditional rollback. Both APK assembly
tasks pass (the unchanged Android test APK assembly is up-to-date). Lint analysis executes;
its report task is up-to-date with zero errors and 87 warnings. The report is explicitly reused.

The new production APK differs from final47; the Android test APK is byte-identical. Prior
tablet/emulator/SAF/memory/live results are retained for unchanged paths, not relabeled as
execution on this repaired APK. Changed adoption fault paths are covered by deterministic
JVM HTTP fixtures, not a newly claimed live-provider or native fault-injection run. No tablet,
real Drive resources, original documents, or previously rejected cleanup targets were touched.

External evidence: final-delta-review-20260910-105135, especially final-reviewed-matrix,
final-validation.json, review-source-final.json, review-repair.diff and apks-final.
The final delta review requirement is satisfied under the user's explicit self-review authority.
No remaining blocking finding in this reviewed scope. Publication closeout remains pending;
no commit, push, release, or success email was performed in this review. Stage 10 is unstarted.

## Stage 9B post-publication correction, 2026-09-10

Correction qualification is CLOSED/PASS. The three findings on `0d2156b` and independent
review followups are repaired: bounded pool reclamation/shared-manifest freshness and durable,
exactly scoped adoption recovery with complete canonical/content/parent validation and
selected-or-verified-compensation cursor/ETag fences. No blind replay or external-edit overwrite.

Final matrix5: 749 JVM cases, 743 executed passes, six explicit platform-capability skips,
zero failures/errors; both APK assemblies PASS; lint zero errors/87 warnings (report explicitly
reused after affected analyses). Final native5: 76 XML cases, 69 executed passes, seven skips,
zero failures/errors. All five actual SAF phases then pass separately without skips on the
same frozen APKs. Supported 100 MiB photo memory/read budgets pass. Independent narrow
review3 finds no remaining blocker; its source-only scope and runtime limitations are recorded.

Fifty new JVM cases and three native cases protect these boundaries. Retained failing runs,
the synthetic-camera input diagnosis, source/APK hashes and detailed scope are in
STAGE9B_IMPLEMENTATION.md and external evidence `construct-9b-review-repairs-l9q2lzxk`.
Publication of this qualified candidate is authorized; Git/GitHub and the external checkpoint
record the resulting SHA, push, exact-SHA CI and required notification once created.
Stage 10 remains pending/unstarted. Release qualification and distribution are not implied.

## Stage 9B scoped recovery correction, 2026-09-10

The two reviewed lockouts on `cef6f77` are repaired: exact no-mutation intent retirement for
first-PUT 412, safe explicit reselection of verified original state after ambiguous failures,
and recovery from a complete interrupted photo-pool index without losing retention or bytes.
Twenty-four new JVM and two native cases protect the changes. Final matrix2 has 773 JVM
cases (767 passes, six capability skips), both APKs pass, and lint has zero errors/87 warnings.
Native-storage2 has 14 cases (13 passes, one capability skip); memory limits pass unchanged.

The same independent reserve session resumed after stream failures and found no concrete
blocker in its final adoption delta. Scope/identity limits, unchanged-pool inspection, exact
commands and evidence are in STAGE9B_IMPLEMENTATION.md and construct-recovery-fix-4m652gy6.
A crash before the first-PUT rejection is durably retired remains conservatively ambiguous;
this is a recorded liveness boundary, not permission for blind replay. Stage 10 remains pending.

## Stage 9B CI host qualification follow-up, 2026-09-10

The first recovery publication (944d005, run 34552578317) passed CI assembly but hit host heap
OOM in an existing large-snapshot test. The follow-up changes only the host Test worker
budget to 1 GiB/one fork; app limits and assertions remain fixed. Full local requalification
passes all 773 cases apart from six capability skips, with lint zero errors/87 warnings and
byte-identical APKs retaining the prior native evidence. The failed run, replacement exact-SHA
CI and notification remain separately recorded; see the latest STAGE9B_IMPLEMENTATION.md
entry and external checkpoint. No new Stage 10 scope is opened.

## Stage 9B public photo-release correction, 2026-09-10

The reviewed public-handle release defect is implemented locally with pre/post-publication
retry protection and independently retryable anchor cleanup. Focused 50/50 and full JVM
795 cases (789 passes, six existing capability skips) pass with both APKs and zero lint
errors. The original four-case failure reproducer and five existing native pool lifecycle
cases on the rebuilt APK in the synthetic API 36 AVD pass. Independent review remains
blocked by the requested Luna route usage limit; no Stage 9B/10 closure or publication is
claimed. See STAGE9B_IMPLEMENTATION.md for exact evidence and scope.

## Stage 9B capture/freeze rollback correction, 2026-09-11

The reviewed post-publication photo-deletion defect is repaired locally without changing
the prior public-release correction. Rollback consults authoritative metadata, preserves
committed/uncertain bytes, and keeps staged recovery viable. New regressions pass 28/28;
focused 78/78 and full 823-case matrix (817 passes, six existing skips) pass, with both
APKs, zero lint errors/87 warnings and five existing native lifecycle cases. Original
capture and release probes also pass. Required independent Luna review remains blocked
by usage limits, so no whole-stage closure or publication is claimed. Stage 10 remains
pending. Exact evidence and limitations are in the latest STAGE9B_IMPLEMENTATION.md entry.

## Scoped photo-recovery publication authorization, 2026-09-11

The user waived the fresh Luna review for the combined release/capture recovery fixes
and authorized commit/push. Review status is WAIVED BY USER, not an independent PASS;
standing review policy and Stage 10 scope are unchanged. The five executable hashes
match the locally validated 823-case candidate, so prior build/test/native evidence is
reused. See the latest STAGE9B_IMPLEMENTATION.md entry and the publication outcomes in
construct-photo-publish-k5gd2fa0/checkpoint.json. This supersedes the preceding scoped
publication hold, not the limitations on full Stage 9B or Stage 10 qualification.

## Stage 9B deferred-release caller-lifetime correction, 2026-09-11

The dropped-handle leak reviewed on d36b497 is repaired locally. A root-scoped,
process-owned retry ticket survives caller/store disposal and drains before the
next admission or collection, including empty-photo admission. Published release
phases and anchor cleanup resume without another ownership decrement; persistent
or ambiguous failures retain their ticket and block that root, not other roots.
Thirty corrected-oracle cases fail on the base and pass after repair. Final
focused 113/113; full JVM 858 cases (852 passes, six existing capability skips),
both APK gates, freshly regenerated lint (zero errors/87 warnings), and five
existing native pool lifecycle cases pass. New injection remains JVM-only.
Direct self-review completed; no new independent Luna review or whole-stage
closure is claimed. Changes remain uncommitted/unpushed; Stage 10 is unstarted.
See STAGE9B_IMPLEMENTATION.md and construct-deferred-release-i6lvf1qf/checkpoint.json.
