# Stage 9A correctness repair

Status: CLOSED/PASSED for the internal-company development scope.

Baseline: `675546dee2ec96e2e2c3de9cc8114d15b3327519`.
Qualified implementation: `2eabb5d1eb42b8396ab4765219ff45bfc59edcf7`. Local commit only; not pushed.

Scope: history/replacement ownership and photo retention; durable pending uploads;
calibration admission; recoverable camera results; real Drive root ID validation.
Internal-company scope remains unchanged. Stage 9B remote photo transport and Stage 10
final qualification are not started. Preserve strict validators, legacy serialization,
atomic durability, document/account/source fencing, user data, and current/previous-good
recovery. No broadened OAuth scopes or arbitrary cap increases.

## Acceptance and evidence

- Regressions are written and executed against baseline before production repairs.
- History must reject stale commands after accepted/imported replacement, retain valid
  history through identical persistence, and protect recoverable image bytes.
- Pending work above 8 MiB must survive real file-backed storage/restart with immutable
  bounded content outside small metadata; old records remain readable.
- Calibration rejection must change no model, history, dirty/save/sync effects; UI errors
  preserve the entered value and prior calibration.
- Camera result ownership must survive recreation and reject stale/duplicate/wrong-target
  results; real ActivityResult/host integration requires Android evidence.
- Drive roots use canonical account-specific IDs and reject unrelated/malformed metadata.
- Final gates: debug assembly, freshly executed full JVM suite, zero-error lint, relevant
  and full connected tests on an isolated emulator; independent Luna review.

Baseline prior audit: 480 JVM total, 477 executed, three existing Windows capability skips;
zero failures/errors. Incremental build PASS. Lint zero errors, 91 warnings. Exact-SHA CI
run `34186655231` PASS. Historical 33/33 connected results are not repair qualification.
This task booted a fresh disposable Android 16/API 36.1 emulator; baseline package-context
instrumentation passed 1/1. That establishes the harness only, not functional correctness.

## Next stages, not started

Stage 9B: versioned immutable remote photo assets, hashes, bounded/resumable transfers,
deduplication, backward-compatible reads/migration, zero unchanged-photo bytes on annotation
edits, all required assets before snapshot/cursor success, conservative deletion. Reuse the
9A retention/outbox foundation. Current 64 MiB Base64 JSON cannot carry 100 MiB photo sets.

Stage 10: integrated source/same-name isolation, rapid switching, process death, conflict
and interrupted transfer recovery, migration, large/scanned/rotated PDFs, high-resolution
photo memory, fresh-install bundle round trip, and backup/device-transfer exclusions.
Distinguish internal debug qualification from employee-distribution and public-release gates.

## External contracts checked

- https://developers.google.com/workspace/drive/api/guides/folder
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files
- https://developer.android.com/training/basics/intents/result

`root` is an input alias; parent entries are IDs. ActivityResult registration does not
persist the additional operation state necessary to interpret a result.

## Final disposition

The completed review and final post-repair gates are recorded in the closure below.
The implementation is committed locally, not pushed or represented as deployment-qualified.

## Baseline regression execution in this task

All failures below were obtained before the respective production repairs. Native tests
were run from a source-only archive of exact `675546d`, avoiding changing worker inputs.

| Regression | Baseline result | Evidence class |
| --- | --- | --- |
| Opaque Drive root ID | 22 tests, 3 intended new failures | Real manager with synthetic HTTP transport |
| Calibration and nonempty pending upload | 5 tests, 3 intended failures | Compiled production parser/reducer/file store |
| History and previous-good photo retention | 4 intended failures after repairing one fixture directory setup | Real mapper/coordinator/reducer/store with existing JVM filesystem seam |
| Invalid calibration dialog | 1 intended failure: dialog closes on rejected input | Actual Android gesture/dialog route |
| Viewer re-entry and Activity recreation history | 2 intended failures: latest edit not undone, recreated Undo disabled | Actual MainActivity and Compose controls |
| Pending camera after host recreation | Normal control passes; recreated-host result fails, 2 tests/1 failure | Actual TakePicture/FileProvider/ActivityResult with separate synthetic camera process |
| Large photo-free pending snapshot | 1 intended failure: valid snapshot exceeds 8 MiB metadata | Actual file-backed pending metadata store |

Native fixture corrections (a Compose member import, a pure-Java separate-process camera,
and stopped-host recreation rather than forcing RESUMED) were distinguished from product
failures. Camera bytes are compared to the checked-in fixture and read back from the
canonical repository. A synthetic camera is not a real sensor/driver qualification.

The baseline failures above were repaired and the integrated results below supersede them.
Baseline failures remain evidence of the regression oracles, not failures of the final candidate.

## Pre-R1 integrated implementation and validation, 2026-09-08

The root integrated all five primary repair workstreams, including the shared history/photo
retention boundary. Calibration and Drive root fixtures retain their original substantive
oracles. Filesystem tests use an explicit Windows test adapter only where the native provider
lacks SecureDirectoryStream; the production Android path keeps its secure descriptor-relative
implementation. Outbox hardlink/staging and parent-replacement regressions were added after
independent security findings rather than weakening containment checks.

| Area | Implemented behavior | Evidence |
| --- | --- | --- |
| History and lifecycle | Retained ViewModel history, replacement epoch, same-source rebind, tracked lifecycle flush before teardown, bounded destructive-action admission, transaction-scoped rollback history | HistoryRetentionRegressionTest, HistoryRollbackBoundaryTest, HistoryBudgetRegressionTest, DocumentLifecycleFlushOwnerTest; actual viewer re-entry and Activity recreation |
| Photo recovery | Explicit complete live/current/previous-good/history/in-flight/camera retention authority; unknown authority never permits destructive collection | Exact-byte Undo/Redo and previous-good recovery tests; native camera and outbox checks |
| Pending uploads | Immutable bounded snapshot and photo payload outside small metadata, exact sizes/hashes/scope, inline legacy reads and recovery identity compatibility, secure CREATE_NEW staging | Real file-store large photo and photo-free snapshot tests, corruption/interruption/hardlink/path replacement cases, Android native provider |
| Calibration | Complete bounded parsing and finite scale admission before mutation/effects, retained dialog/input on error | Pure parser/reducer tests and real gesture/dialog test |
| Camera | Durable operation before launch, app-owned trampoline, exact source/session/pin admission, canonical source preflight, idempotent publication and flush before acknowledgement | File-store tests, real ActivityResult with synthetic camera, real process death with emulator Camera2 |
| Drive root | Resolve the account root ID and validate create/reuse responses against its actual ID, retaining generation/scope/marker/type fences | Manager and instrumentation fixtures use opaque IDs; no live provider transfer qualification claimed |

Final root checkout commands (one Gradle matrix at a time, JDK from Android Studio jbr):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --stacktrace --console=plain --max-workers=2 --init-script <evidence>\fresh-tests.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
$env:ANDROID_SERIAL='emulator-5574'
.\gradlew.bat --no-daemon --stacktrace --console=plain --max-workers=2 :app:connectedDebugAndroidTest
```

The test init script sets Test.outputs.upToDateWhen { false }; unit tests were actually
executed. Assembly was incremental, not represented as a clean rebuild. Final source and
build-input hashes were frozen before execution and verified unchanged afterward.

- Debug assembly: PASS.
- Full JVM: 535 total, 531 executed, zero failures/errors, four capability skips. Three are
  the existing Windows symlink-privilege cases. The added skip is the secure-provider test
  on a JVM filesystem without SecureDirectoryStream; the production primitive is exercised
  by the Android tests. The separate Windows hardlink regression executed successfully.
- Lint: zero errors, 91 warnings, unchanged warning count from the audited baseline.
- Full connected suite: 44 total, 43 executed, zero failures/errors, one skip on the owned
  Android 16/API 36 Medium_Phone_API_36.1 emulator. The skipped test requires creating a
  hardlink, which this Android sandbox denies; symlink/parent-swap checks executed.
- Additional cold-process camera gate: PASS. The exact app process was terminated while
  Android Camera2 remained active; there was no app process when the shutter/review opened.
  Completing capture restarted the app with a different PID. The correct source/document,
  page 0 and original pin contained exactly one 63,666-byte JPEG. The operation journal was
  cleaned only after durable attachment. A second cold launch preserved the single reference
  and SHA-256 `cacf0d6937e5880d73aadd95ca7c075de19b7ace8fd5cea90584a956da48acff`.
  This uses the emulator virtual camera, not a physical camera sensor/driver.
- Additional external-camera cancellation gate: PASS. A second capture on the same pin
  was cancelled with Back. Only its new journal/capture were cleaned; the canonical
  snapshot and the previous single JPEG/hash remained unchanged.

Raw evidence is task-local, outside the repository:
`%TEMP%\construct-stage9a-f1s7i2ht\final-evidence`.
It contains input hashes, exact console output, 52 JVM XML suites, connected XML, lint XML,
and the cold-process journal/snapshot/proof. No real document, token, account, or private
photo was used in that evidence. The physical tablet and its installed application/data
were not modified. Repository history and pre-existing qualification artifacts were preserved.

## Stage 9B implementation order and closed acceptance contract

1. Preserve existing inline Drive payload reads. Define the next explicit manifest version
   with document/source identity and immutable photo descriptors. Keep the current coordinator,
   accepted-cursor, conflict and account/root/document ownership boundaries.
2. Reuse the Stage 9A durable outbox and reachability rules. Build a bounded transfer adapter
   that sends immutable, hash-addressed assets separately, with resumable transfer where the
   provider supports it. Enforce both per-file and aggregate limits before publication.
3. Publish every required asset before the versioned snapshot/manifest becomes authoritative.
   A failed or cancelled required asset must not advance the cursor or display sync success.
   Concurrent updates still require the existing generation lease and remote precondition.
4. Download into contained staging, validate descriptors, counts, bytes, hashes and decoding,
   then apply the complete canonical document through the established transaction boundary.
   Retain the previous complete document on any failure; do not add a competing state owner.
5. Deduplicate unchanged assets. An annotation-only edit must upload zero unchanged-photo
   bytes. Old formats migrate only after read-back-verified complete replacement; do not delete
   old recovery material as a side effect of reading or discovering a remote document.
6. Qualify restart, interrupted transfer, missing/corrupt asset, revision conflict, account/root
   switch, source replacement and conservative deletion with deterministic transport tests,
   then an explicitly authorized live-provider fixture round trip. Measure transfer byte counts
   and bounded memory for the supported large-document/photo envelope on Android.

Stage 9B is NOT implemented or declared qualified here. The 64 MiB whole-JSON remote transport
still cannot support the entire locally accepted 100 MiB photo envelope. Raising caps or
silently discarding photos is not the planned solution.

## Stage 10 qualification matrix and remaining scope

| Gate | Execution boundary | Evidence owner |
| --- | --- | --- |
| Same-name/source revision and A/B switching | JVM coordinator plus actual Android document selection | Stage 2/3 repository/session tests and MainActivity integration |
| Undo, persistence, photo recovery and interrupted commits | JVM fault injection plus fresh process Android checks | Stage 9A history, lifecycle, outbox and camera owners |
| Drive conflicts and complete transfers | Deterministic HTTP tests plus authorized live fixture accounts | Stage 4 coordinator/gateway and Stage 9B adapter |
| Legacy migration and bundle fresh-install round trip | JVM fixtures plus disposable Android installation/SAF | Stage 2 migration and Stage 6 bundle service |
| Scanned/cropped/rotated/large PDFs and photo memory | Budget tests and Android rendering/OCR/device profiling | Stage 7 worker/cache/resource owners |
| Backup/device-transfer exclusions | Android backup/restore qualification | Stage 9 privacy configuration |
| Internal authentication and delayed provider results | Synthetic lifecycle tests plus authorized internal account cases | Stage 9 identity/authorization owner |
| Durable employee APK distribution | Stable company signing key, certificate OAuth setup and install/update matrix | Future distribution scope; not this internal debug closure |

Live two-account Google switching, delayed live-provider consent, revocation/network failures,
complete live Drive transfer, employee/private signing and public release remain separately
unqualified. No public launch, broader OAuth scope, Play workflow, or dependency upgrade was
introduced. Secondary reverse-alphabetic recents and tracked historical log/screenshot/cache
hygiene remain deferred: this repair did not invent old timestamps or delete unclassified
historical evidence. No automatic main-branch merge, branch deletion, or history rewrite.

## Independent review R1 and bounded repair

The fresh independent Luna integrated review returned FAIL with one reachable blocker:
transactional A-to-B switching cleared A's Undo/Redo before B became authoritative, then
restored only A's snapshot when B failed. A new six-case regression using the real Android
session callback adapter and switch coordinator reproduced five failures and a passing
successful-switch isolation control before the repair.

The repair introduces a detached, owner-bound outgoing switch checkpoint carried with the
frozen snapshot through provisional loads, supersession, cancellation, setup and apply
failures. Rollback validates owner/document/source/fingerprint, restores the canonical state
and exact reducer/legacy history, and keeps old-generation UI closures stale. Successful
switches do not inherit outgoing history. Only the Stage 3 coordinator and Android callback
adapter required production changes.

Post-repair focused validation: 42 tests PASS with zero failures/errors, including all six
new switch regressions, the existing 29 coordinator tests and seven related history tests.
The targeted independent delta review passed and new full integrated gates passed. Prior
535-JVM/44-native results above describe the pre-R1 candidate; the final 541-JVM/44-native
results below qualify this changed code. The cold-process camera/cancellation evidence is retained
as explicitly reused evidence for the unchanged camera implementation and call path.

## Final Stage 9A closure

**CLOSED/PASSED** for internal-company development on implementation commit
`2eabb5d1eb42b8396ab4765219ff45bfc59edcf7`. A following documentation-only commit records this closure; it does not
change the validated source. Neither commit was pushed, and no new candidate CI result is
claimed. The prior exact-SHA CI result belongs to the original baseline only.

The fresh independent integrated Luna review identified R1. Five new failure-path tests
reproduced it before repair; the sixth successful-switch control passed. All six passed
after repair, together with 36 adjacent tests. The same independent reviewer then reviewed
the narrow correction and returned PASS, explicitly finding no remaining substantive blocker
and no invalidation of previously reviewed invariants. The reviewer performed static read-only
inspection; all build/device execution was performed by the root, not attributed to the reviewer.

| Final post-review gate | Result |
| --- | --- |
| Debug assembly | PASS; incremental build, not claimed clean |
| Fresh full JVM | 541 total; 537 executed; zero failures/errors; four documented capability skips |
| Lint | Zero errors; 91 warnings |
| Full Android emulator suite | 44 total; 43 executed; zero failures/errors; one documented hardlink capability skip |
| Independent integrated review plus R1 delta | PASS after the reported blocker was repaired |
| Working source versus qualified input manifest | Exact match before/after local implementation commit |

The complete source/build input manifest has SHA-256
`047a50c3558d0a685d181e1a9ec9e22eb3f135f58cadb3aa34df247b30cc9e4b`. Final post-review evidence is in
`%TEMP%\construct-stage9a-f1s7i2ht\final-evidence\post-review-delta`.
The prior native process-death Camera2 and cancellation proofs are explicitly reused for the
unchanged camera code; the final complete native suite reran its camera/history/outbox cases.
No physical camera hardware, real account transfer, or employee distribution is implied.

The owned emulator was shut down and verified absent. Retired task REPLs/workers were closed.
Only the verified task-local source copies, archive and emulator directory were removed;
raw evidence and input/review manifests were retained. The populated physical tablet,
unrelated projects/processes, prior repository qualification artifacts and user data were
preserved. Git reported an existing unreachable-object housekeeping warning during normal
commit; no manual prune, broad cleanup, history rewrite or branch deletion was performed.

Stage 9B remains next, with the closed transfer/migration acceptance contract above. Stage 10
remains pending. Secondary recents ordering and unclassified historical artifact hygiene stay
explicitly deferred rather than widening this correctness repair.
