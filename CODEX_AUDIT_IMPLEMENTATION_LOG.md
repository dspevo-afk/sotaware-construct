# SOTAware Construct Stage 0 Implementation Log

## Scope and starting state

- Canonical remediation file: `SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md`
- Canonical documentation commit used: `adbee4eeb0c990226a7f9e887f719a2dbcd9105d`
- Canonical source branch: `origin/docs/audit-remediation-plan`
- Starting branch: `main`
- Starting commit: `e010bee287894abdcaf29b5e539f16269a94a9c5`
- Working branch: `codex/stage-0-gates`
- Documentation commit incorporated as local commit: `593511c` (`docs: add Android audit remediation plan`)
- Audit baseline named by the canonical plan: `e010bee287894abdcaf29b5e539f16269a94a9c5`

## Repository preparation

- `git fetch origin` was required to obtain `origin/docs/audit-remediation-plan`.
- The canonical document was read in full before remediation edits.
- Branch ancestry was checked before cherry-picking; the documentation commit was not already an ancestor and was cherry-picked without conflict.

## Sub-agent assignments

- Sub-agent A: independently verify Gradle/JDK/build/test/lint baseline and own the new Android GitHub Actions workflow under `.github/workflows/`.
- Sub-agent B: verify and minimally repair `tools/android_env.ps1` and `.vscode/tasks.json`; own only those two files.
- Sub-agent C: analysis-only inventory of current persistence, serialized domains, class/package identities, and characterization requirements.
- Sub-agent D: own only `app/src/test/java/com/example/myapplication/stage0/` and `app/src/test/resources/stage0/`; add deterministic fixtures, characterization tests, and state-loss canaries without changing production architecture.
- Sub-agent E: deferred until the integrated Stage 0 diff exists; independently review scope, tests, fixtures, CI, lint, compatibility, and hidden failures.

## Baseline commands and exact results

Environment used for Gradle verification: JDK `21.0.8` from Android Studio JBR, Gradle wrapper `9.1.0`, repository-local `GRADLE_USER_HOME=.gradle-user-home`, repository-local `ANDROID_USER_HOME=.android`.

Commands:

```text
java -version
```

Result: OpenJDK `21.0.8`.

```text
.\gradlew.bat --version
```

Result: Gradle wrapper `9.1.0`; first sandbox attempt could not download the distribution, so the download was completed with approved network escalation.

```text
.\gradlew.bat --no-daemon --console plain assembleDebug
```

Result: `BUILD SUCCESSFUL` (baseline rerun exit code `0`; 38 actionable tasks, all up-to-date).

```text
.\gradlew.bat --no-daemon --console plain testDebugUnitTest
```

Result: `BUILD SUCCESSFUL` (exit code `0`; 27 actionable tasks, all up-to-date). Baseline coverage was the template `addition_isCorrect` test.

```text
.\gradlew.bat --no-daemon --console plain lintDebug
```

Result: `BUILD FAILED` (exit code `1`): lint found `1 error, 77 warnings`. The error is `SuspiciousIndentation` at `app/src/main/java/com/example/myapplication/MainActivity.kt:5509`, where `if (_converted != null)` is indented as though it continues the prior statement at line 5508.

Other baseline observations: no GitHub Actions workflow was present; `tools/android_env.ps1` and `.vscode/tasks.json` required direct validation; existing behavioral tests were only the template unit and package-name instrumentation tests.

## Integrated Stage 0 changes

Intentional files currently changed or added:

- `CODEX_AUDIT_ROADMAP.md`
- `CODEX_AUDIT_IMPLEMENTATION_LOG.md`
- `.github/workflows/android.yml`
- `.vscode/tasks.json`
- `tools/android_env.ps1`
- `app/src/main/java/com/example/myapplication/MainActivity.kt`
- `app/src/test/java/com/example/myapplication/stage0/HighResolutionPhonePhotoFixture.kt`
- `app/src/test/java/com/example/myapplication/stage0/LegacyJsonCharacterizationTest.kt`
- `app/src/test/java/com/example/myapplication/stage0/LegacyStateCharacterizationTest.kt`
- `app/src/test/java/com/example/myapplication/stage0/LocalPersistenceCharacterizationTest.kt`
- `app/src/test/java/com/example/myapplication/stage0/LegacyStateFixture.kt`
- `app/src/test/java/com/example/myapplication/stage0/Stage0FixtureInventoryTest.kt`
- `app/src/test/java/com/example/myapplication/stage0/SyncPayloadCharacterizationTest.kt`
- all deterministic resources under `app/src/test/resources/stage0/`

Production changes are limited to the exact lint-blocking indentation repair at `MainActivity.kt:5509`, the final newline at EOF, and a small `buildPageDataForSync()` seam used by the four existing sync payload construction paths. That seam preserves the existing `PageData` shape and scale fields and is explicitly not a versioned snapshot, repository, coordinator, or other Stage 1+ architecture. No persistence replacement, document identity, synchronization coordinator, conflict redesign, OCR, rendering, import/export, authentication, or UI architecture was changed.

### Sub-agent findings used

- **A — build/Gradle/CI:** confirmed wrapper 9.1.0, AGP 9.0.0, Kotlin 2.0.21, SDK 36, Java compatibility 11, and required gate availability. Added `.github/workflows/android.yml` using Temurin 17, Android SDK 36/build-tools 36.0.0, the checked-in wrapper, basic Gradle caching, and separate assemble/test/lint steps. Local post-fix lint reproduced 0 errors and 77 warnings. No device test or remote Actions run was available.
- **B — developer tooling:** confirmed the PowerShell conditional/parser defect and concatenated/independently invalid VS Code task documents. Parenthesized the `Test-Path` expression and merged the two task sections into one 9-task configuration with platform variants, preserving useful build/install/launch/logcat/stop/full-run tasks. Parser and execution checks passed.
- **C — persistence characterization:** identified local `HashMap<Int, PageMarkups>` Java serialization in `markups_<pdfUri.hashCode()>.bin`, separate scale preferences, photo files/references, recent-file metadata, app/Drive preferences, Drive `PageData` Gson state, exact serialized FQNs, computed legacy serialVersionUID values, and compatibility hazards. The inventory was used to scope fixtures/tests; no production persistence code was changed.
- **D — fixtures/tests:** added deterministic same-name PDFs, cropped/rotated embedded-text PDF, scanned PDF, four-page approximately 36 KB vector blueprint PDF, deterministic 4032x3024 JPEG generator, fully populated legacy state, malformed/malicious payloads, JSON characterization, Java serialization characterization, and focused state-loss canaries. The primary agent added a PDFBox `PDDocument.load` fixture check, actual JPEG decoding, FQN/serial-descriptor assertions, local scale preference characterization, and FQN assertions for `PageScale`/`ShapeType`.

### Fixture and test coverage

The fixture manifest is `app/src/test/resources/stage0/fixture_manifest.json`. It covers:

- two different resources both named `plan.pdf` with unmistakably different markers;
- selectable text plus non-zero crop-box offset and `/Rotate 90`;
- an image-only scanned PDF with no text object;
- a four-page vector blueprint fixture;
- a deterministic 4032x3024 JPEG phone-photo generator;
- every currently persisted annotation domain: paths, measurements, notes, page shapes, scale, photo pins, photo IDs/filenames, image notes, and image shapes;
- malformed JSON, traversal/absolute-path-like names, Drive-query special characters, invalid enums, non-finite/unreasonable numbers, missing fields, and excessive counts/sizes.

Characterization/canary tests are named with `characterization_` and cover Java serialization field retention, exact legacy class names, Drive JSON field names/round-trips, scale-only pages, same-name fixture differentiation, actual PDFBox document loading, and preservation of page shapes, scale, photo metadata/filenames, image notes, and image shapes.

### Integrated commands and exact results

All Gradle commands below were run serially with the repository-local Gradle and Android user directories and approved network access only for the initial wrapper distribution/dependency setup:

```text
.\gradlew.bat --no-daemon --console plain assembleDebug
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 38 actionable tasks (4 executed, 34 up-to-date).

```text
.\gradlew.bat --no-daemon --console plain testDebugUnitTest
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 27 actionable tasks (2 executed, 25 up-to-date). XML reports contain 17 tests total, 0 failures, 0 errors, 0 skipped. This consists of 1 existing example test, 3 legacy Drive-JSON tests, 3 Java-serialization/FQN tests, 2 local-scale tests, 7 fixture inventory tests, and 1 sync-payload seam test.

```text
.\gradlew.bat --no-daemon --console plain lintDebug
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 30 actionable tasks (8 executed, 22 up-to-date), 0 errors and 77 warnings. Remaining warning IDs/counts are: `UseKtx` 20, `GradleDependency` 10, `UseTomlInstead` 10, `UnusedResources` 8, `IconDuplicates` 5, `IconLauncherShape` 5, `ObsoleteSdkInt` 5, `TrustAllX509TrustManager` 5, `NewerVersionAvailable` 4, `AndroidGradlePluginVersion` 1, `DefaultLocale` 1, `IconXmlAndPng` 1, `RedundantLabel` 1, and `VectorRaster` 1. These are outside the Stage 0 lint-blocking fix and were not suppressed.

Focused validation also passed:

```text
.\gradlew.bat --no-daemon --console plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*"
```

Result: `BUILD SUCCESSFUL`; all 16 Stage 0 tests passed.

```powershell
[System.Management.Automation.Language.Parser]::ParseFile(...tools/android_env.ps1...)
Get-Content -Raw .vscode/tasks.json | ConvertFrom-Json
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/android_env.ps1
```

Results: PowerShell parse passed with 0 errors; VS Code task JSON parsed with 9 tasks; setup script returned `com.example.myapplication` and exit code `0`. A direct execution without `-ExecutionPolicy Bypass` was blocked by the host policy, so the intended task invocation was used and passed.

The workflow was parsed with the bundled SnakeYAML parser and passed root-structure validation (`name` and `jobs` present). The parser represents the YAML 1.1 `on` key as boolean `true`; this is a parser-schema representation, not a workflow syntax error. No local `actionlint`/`yamllint` or remote GitHub Actions execution was available.

The deterministic PDF resources were opened with the app PDFBox library through `PDDocument.load`. A first host-JVM attempt to invoke `PDFTextStripper` was rejected because the Android-packaged glyph resource is not present on the plain JVM test classpath; the test was narrowed to parser loading and raw fixture assertions, leaving the app/runtime text-extraction path unchanged.

No connected Android device or instrumentation smoke test was available. This is reported as unavailable, not passed.

### Independent reviewer

Sub-agent E was invoked after integration with the canonical roadmap, Stage 0 assignment, complete diff, test/lint evidence, CI changes, and persistence inventory. The independent review found the following:

1. **Generated artifacts and tracked wrapper partial — resolved.** The reviewer found approximately 1.7 GB of generated Gradle/Kotlin/Android artifacts and a tracked empty wrapper partial marked deleted. After the final gates, Gradle was stopped; only the explicit generated cache, session, wrapper-distribution, and debug-keystore targets created during this run were removed; the pre-existing Android cache directory and tracked wrapper lock/partial artifacts were preserved/restored. The wrapper partial is zero bytes and the working-tree status shows no generated artifacts.

2. **Tracking documentation — resolved.** The roadmap and implementation log now contain the integrated file list, agent assignments/findings, exact commands/results, fixture/test inventory, warning enumeration, environment limitations, and reviewer dispositions.

3. **Local scale persistence — resolved for Stage 0 characterization.** `LocalPersistenceCharacterizationTest` exercises the real `saveScaleForPdf()`/`loadScalesForPdf()` path with separate documents, multiple pages including scale-only page coverage through the sync seam, and an explicit URI-prefix collision artifact. The existing prefix-based loader defect is documented and deferred to the canonical later persistence stage; Stage 0 does not assert that dangerous behavior is correct or implement the persistence rewrite.

4. **Legacy compatibility — resolved for the required Stage 0 seam.** The tests now protect populated current-runtime Java round trips, exact legacy FQNs including `Point`, `PageScale`, `RecentFile`, `HistoryAction`, and representative nested history classes, plus the compiler-derived serialVersionUID descriptors reported by the persistence inventory. A historical binary stream is not fabricated; migration-stage qualification must add one from a real legacy artifact.

5. **Sync-route canary — resolved with the smallest seam.** `buildPageDataForSync()` is used by immediate, debounced, periodic, and manual existing payload paths and includes all existing `PageData` domains, including page shapes and scale-only pages. `SyncPayloadCharacterizationTest` verifies the seam. This does not introduce `DocumentSnapshotV1`, a coordinator, identity, or any Stage 1+ sync architecture.

6. **High-resolution photo — resolved.** The test now decodes the deterministic JPEG with `ImageIO` and asserts `4032 x 3024`, in addition to byte determinism and JPEG markers.

7. **Developer-tool invocation — resolved for the available environment.** Setup messages are now verbose-only so inline task callers receive only the application ID. The exact launch-task PowerShell subexpression was exercised with missing `JAVA_HOME`/`adb` in the child environment and returned only `TASK_ID=com.example.myapplication`; the exact build command is covered by the final build gate. Install/launch against a device remain unavailable because `adb devices` cannot connect in this environment.

8. **Embedded text — accepted limitation, not a Stage 0 blocker.** Raw fixture assertions prove embedded text tokens, crop-box offset, font resource, and rotation; `PDDocument.load` proves parser loading. Host-JVM `PDFTextStripper` is not run because the Android PDFBox glyph resource is not present in the unit-test classpath. OCR/text extraction qualification remains in the later rendering/OCR stage.

9. **Test count discrepancy — resolved.** The final log distinguishes 16 Stage 0 tests from 17 total JVM tests including the template test.

## Final cleanup and independent disposition

The final independent review initially reported the regenerated wrapper/cache artifacts as the only blockers. After cleanup, the reviewer re-checked the tree and found only one documentation correction: the roadmap’s stale pending completion label, which was corrected before staging. The remaining historical-stream, durable-preferences, OCR-quality, embedded-text-extractor, and remote-CI limitations were accepted as documented Stage 0 limitations and deferred to the applicable later stages. No Stage 1+ scope creep was found. No push was performed during the initial implementation pass; the follow-up connected-device qualification and handoff push are recorded below.

## Known remaining failures and deferred issues

- The 77 non-blocking lint warnings are enumerated above; they remain for later cleanup and were not suppressed.
- All Priority 0 through Priority 3 application remediation remains deferred. Stage 0 must not implement `DocumentSnapshotV1`, UUID identity, persistence replacement, sync redesign, auth migration, OCR/rendering redesign, import/export redesign, reducer architecture, responsive UI redesign, or broad `MainActivity.kt` decomposition.

The Stage 0 entries above are retained as historical evidence from that completed stage. The Stage 1 record below describes the subsequent work and current status.

## Stage 1 implementation log — canonical document snapshot

### Scope and starting state

- Assignment: Stage 1 only — create one canonical document snapshot.
- Starting branch: `codex/stage-0-gates`.
- Starting commit: `1218d50a593a72832c0577de7bcc3dd8fe5b514f` (`test: establish Stage 0 audit gates`).
- Working branch: `codex/stage-1-canonical-snapshot`.
- Branch creation: the working branch was created directly from the verified Stage 0 commit after `git fetch origin`; no Stage 0 history was rewritten and no push was performed.
- Working tree was clean before Stage 1 edits.
- At task start, Stage 0 remained complete; Stage 1 was the current roadmap stage; Stages 2–10 were pending.

### Qualification debt checked at Stage 1 start

- `adb devices` was retried with the host permissions required by adb. The daemon started and reported an empty `List of devices attached`; no Android device or instrumentation smoke test was available. This remains qualification debt and is not represented as a passed test.
- The Stage 0 GitHub Actions run was available and inspected: [Android Stage 0 gates run #1](https://github.com/dspevo-afk/sotaware-construct/actions/runs/32598054402) for commit `1218d50` on `codex/stage-0-gates` reported `Status Success`.
- No Stage 1 GitHub Actions run exists because the Stage 1 branch was not pushed. Remote CI execution for Stage 1 therefore remains unverified.

### Sub-agent assignments and findings

- Sub-agent A — state-domain reviewer. Inventory confirmed the six persisted ViewModel domains, all nested photo annotation fields, the union-of-map-keys page-existence rule, provisional URI/display-name identity, and caller-supplied revision semantics. It identified history, redo, caches, highlights, and search terms as runtime-only state.
- Sub-agent B — canonical snapshot model and round-trip tests. Owned the isolated Stage 1 model, mapper, and round-trip tests. The model uses typed V1 DTOs, a snapshot-specific shape enum, defensive read-only collections, and fresh legacy objects at the application boundary.
- Sub-agent C — replacement-semantics reviewer. Required clear-and-repopulate replacement, explicit empty domains, absent-page removal, scale deletion on `null`, nested photo replacement, and clearing ancillary page-indexed state. Its findings were incorporated into `applySnapshotReplace()` and the tests.
- Sub-agent D — sync-path integration auditor. Confirmed the four existing outbound routes and recommended retaining `Map<Int, PageData>` only behind a thin adapter. It also identified the two inbound remote-apply blocks as stale merge paths; both now use the canonical replacement function.
- Sub-agent E — independent Stage 1 reviewer. Found three blockers/observations: materialization occurred after clearing state, generated Gradle/Kotlin artifacts were present, and documentation was stale. The first was fixed with pre-mutation materialization plus a regression test; generated artifacts were removed and the tracked wrapper placeholder restored; this Stage 1 section and the roadmap update resolve the documentation finding. The reviewer accepted the provisional identity/revision boundary, the legacy Drive adapter, and the indirect route-equivalence test as documented Stage 1 choices. No Stage 2+ scope creep or new lint issues were found.

### Persisted state inventory

The canonical snapshot represents exactly the current logical document state held by `BlueprintViewModel`:

- `pagePaths`: drawing paths and every point coordinate, color, stroke width, and highlighter flag;
- `pageMeasurements`: both endpoints and measurement text;
- `pageNotes`: position, text, font size, bold flag, and rotation;
- `pageShapes`: legacy dimensions, ratio dimensions, position, rotation, type, color, stroke, fill, and ID;
- `pageScales`: nullable per-page `pixelsPerFoot`;
- `pagePhotoPins`: pin position and ID, photo filename list, and filename-keyed nested image notes and image shapes. Image notes retain position, text, font metadata, rotation, ratio, and ID. Image shapes retain every page/image shape field.

Page existence is the union of all six page-level map key sets. This preserves scale-only, shape-only, photo-only, notes-only, measurement-only, and path-only pages. History/redo actions, thumbnails, highlights, search terms, OCR state, selected-page/UI state, and export wrappers are runtime state and are not snapshot domains.

### Snapshot model and semantics

New model files:

- `app/src/main/java/com/example/myapplication/stage1/DocumentSnapshotV1.kt`
- `app/src/main/java/com/example/myapplication/stage1/DocumentSnapshotV1Mapper.kt`

`DocumentSnapshotV1` contains:

- `schemaVersion`, fixed at `1`;
- `snapshotRevision`, a non-negative caller-supplied `Long`;
- `DocumentSourceIdentityV1`, containing current `sourceUri`, optional display name, and provider metadata;
- a page map of typed `PageSnapshotV1` values;
- typed DTOs for points, paths, measurements, notes, page scale, page shapes, photo pins, image notes, and image shapes.

Stage 1 identity semantics are intentionally provisional: the open source URI, display name, and provider metadata identify the current source context. No app-generated UUID, source manifest, content fingerprint, or final cross-device identity system was introduced; those belong to Stage 2. The model leaves a clean place for that later identity extension.

Stage 1 revision semantics are deterministic and deliberately limited: ordinary live captures use `snapshotRevision = 0`; callers may supply another non-negative logical revision for tests or an owning layer. Stage 1 does not allocate, increment, compare, persist, or use the value as a Drive cursor or synchronization generation. Serialized sync generations and remote conflict behavior remain Stage 4 work.

### Canonical capture, replacement, and mappings

- `snapshotFromState()` is defined once in `DocumentSnapshotV1Mapper.kt`. It reads the union of the six ViewModel maps and deep-copies every scalar, point, nested map, filename list, image note, image shape, and page shape into snapshot-owned unmodifiable collections.
- `applySnapshotReplace()` is defined once in the same mapper. It first validates and fully materializes every incoming page into fresh legacy objects, before clearing any live state. It then clears all six persisted maps plus history, redo, thumbnail, highlight, and search maps, and repopulates only the incoming page set. Empty domains receive empty list entries; `scale = null` removes old scale state; absent pages disappear.
- `snapshotToLegacyPageData(snapshot)` is the explicit temporary compatibility adapter used for the existing Drive API. It constructs fresh legacy objects, including nested photo data, and does not read ViewModel state.
- `snapshotFromLegacyPageData(...)` is the corresponding inbound compatibility mapper for the existing legacy Drive download result. It maps external payload data into the canonical typed model; it is not a live-state capture path.
- `buildPageDataForSync(vm, source)` remains only as a thin Stage 0 compatibility wrapper: `snapshotFromState(vm, source)` → `snapshotToLegacyPageData(snapshot)`. The previous domain-by-domain state reconstruction was removed.

Snapshot application therefore has two independent deep-copy boundaries: live ViewModel state → immutable snapshot DTOs, and snapshot DTOs → fresh mutable applied ViewModel state. A reviewer-required regression test corrupts an externally mutable snapshot page map and verifies rejection leaves the prior live state unchanged.

### Sync-path integration

The four existing outbound paths all call the same `buildPageDataForSync()` adapter:

- immediate photo sync in `triggerImmediateSync()`;
- debounced sync in `LaunchedEffect(syncTrigger)`;
- periodic/automatic upload through `DriveSyncManager.startAutoSync()`'s `getPageData` callback;
- manual `Sync Now` upload.

Each supplies the current URI/display-name source identity. The existing `DriveSyncManager` API, Drive coordination, queueing, conflict behavior, folder identity, lifecycle scope, and remote revision handling were not redesigned. The two existing remote-update dialogs now convert the legacy map to a `DocumentSnapshotV1` and use `applySnapshotReplace()`, eliminating their prior merge behavior and page-shape omission.

### Tests added and preserved

New tests:

- `app/src/test/java/com/example/myapplication/stage1/DocumentSnapshotV1RoundTripTest.kt` — fully populated round trip with explicit field assertions, scale-only, shape-only, photo-only, empty document, multiple pages, ghost-page removal, empty-domain replacement, nested filename/image-note/image-shape replacement, source/snapshot/applied-state isolation, compatibility adapter coverage, and materialize-before-mutate failure protection.
- `app/src/test/java/com/example/myapplication/stage1/SyncRouteEquivalenceTest.kt` — route-labelled immediate, debounced, automatic, and manual captures through the shared adapter, with logical snapshot equality and explicit shapes, photo filenames, and scale assertions.

The existing `app/src/test/java/com/example/myapplication/stage0/SyncPayloadCharacterizationTest.kt` now invokes the thin adapter while retaining its assertions for paths, measurements, notes, photos, page shapes, scale, and scale-only pages. All other Stage 0 tests remain present.

### Exact verification results

Focused command after the reviewer fix:

```text
.\gradlew.bat --no-daemon --console plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*"
```

Result: `BUILD SUCCESSFUL`; 27 tests, 0 failures, 0 errors, 0 skipped (16 Stage 0 tests and 11 Stage 1 tests).

Final required gates after resolving reviewer findings:

```text
.\gradlew.bat --no-daemon --console plain assembleDebug
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 38 actionable tasks, 4 executed and 34 up-to-date.

```text
.\gradlew.bat --no-daemon --console plain testDebugUnitTest
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 28 JVM tests, 0 failures, 0 errors, 0 skipped; 27 actionable tasks, 1 executed and 26 up-to-date. The 28 tests are 1 template test, 16 Stage 0 tests, and 11 Stage 1 tests.

```text
.\gradlew.bat --no-daemon --console plain lintDebug
```

Result: `BUILD SUCCESSFUL`, exit code `0`; 0 lint errors and 77 warnings. Warning IDs/counts are unchanged from Stage 0: `UseKtx` 20, `GradleDependency` 10, `UseTomlInstead` 10, `UnusedResources` 8, `IconDuplicates` 5, `IconLauncherShape` 5, `ObsoleteSdkInt` 5, `TrustAllX509TrustManager` 5, `NewerVersionAvailable` 4, `AndroidGradlePluginVersion` 1, `DefaultLocale` 1, `IconXmlAndPng` 1, `RedundantLabel` 1, and `VectorRaster` 1.

Generated Gradle, Kotlin, Android, and app build outputs created for verification were removed after the final gates. The pre-existing tracked zero-byte Gradle wrapper partial was restored. Final status contains only intentional Stage 1 files and documentation changes.

### Follow-up connected-device qualification

- The tablet was subsequently connected and authorized as `HNY0DSR8` (`TB336FU`, Android 16). The first retry of `connectedDebugAndroidTest` reached device installation but ran zero tests because the tablet already had version code `3` and the current debug APK had version code `1`; Android returned `INSTALL_FAILED_VERSION_DOWNGRADE`.
- To preserve the existing app data, the current debug APK was replaced in place with `adb install -r -d app\\build\\outputs\\apk\\debug\\app-debug.apk`. No uninstall or data wipe was performed.
- The exact connected test command was then rerun:

```text
.\\gradlew.bat --no-daemon --console plain connectedDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`; 1 instrumentation test started and finished on `TB336FU - 16`, with no reported failures. No application defect was exposed by the device run, so no source-code bug fix was added in this follow-up.

### Deferred Stage 2+ work and remaining limitations

- No app-generated UUID `DocumentId`, source manifest, fingerprint identity, local repository, atomic persistence, mutex, migration, quarantine, or legacy-data deletion was implemented.
- No transactional PDF switching, document job cancellation, autosave redesign, serialized `SyncCoordinator`, sync generations, Drive file-ID architecture, conflict state machine, remote cursor, or lifecycle sync redesign was implemented.
- No payload hardening, photo transaction redesign, bundle format, import/export redesign, OCR/rendering rewrite, reducer, responsive UI, auth migration, release work, or broad `MainActivity.kt` decomposition was implemented.
- The temporary Drive adapter still emits the existing unversioned `Map<Int, PageData>` wire shape; the canonical V1 envelope is the authoritative in-memory document representation, and versioned persistence/wire migration belongs to later stages.
- Legacy local load and export paths still use their existing legacy state formats; they remain explicitly deferred to the applicable later stages.
- The Stage 0 qualification smoke was unavailable at Stage 0 task start; the subsequent Stage 1 connected instrumentation sanity check passed as recorded above. The sole connected test asserts `com.example.myapplication == appContext.packageName`, so it proves installation/instrumentation/package-context sanity only and is not a functional app smoke test. The Stage 1 handoff push follows this documentation update; remote CI status is not part of the local device-test result.

## Stage 2 status — replace local persistence safely

### Scope and starting point

- The Stage 2 task started from `codex/stage-1-canonical-snapshot` at `62f38e699aba4368a9d4ab356285f897a4260d7f` (`docs: record connected device qualification`). The focused implementation branch is `codex/stage-2-local-persistence`.
- Stage 1 was treated as accepted. No Stage 1 snapshot or sync architecture was redesigned; Stage 2 only replaced local persistence and corrected the connected-test wording above.
- The canonical Stage 2 gate is satisfied by the repository tests and failure boundaries below: an interrupted write preserves the previous complete snapshot, and corruption never silently becomes a blank document.

### Sub-agent assignments and review

- Kant (legacy investigator) inventoried `markups_<uri.hashCode()>.bin`, Java serialization compatibility, `scales` preference keys, active callers, and the exact legacy domains.
- Bohr and Locke reviewed the DocumentId/manifest and repository boundaries.
- Harvey designed destructive/failure-injection coverage.
- Wegener and Poincare performed independent review passes. Their blockers were fixed: orphaned staging detection, mismatched-current recovery, explicit `CommitUncertain`, recovered-manifest allocation refusal, fingerprint binding/unavailability, source-association validation, process-wide mutexes, and transactional migration claims.
- Parfit's review identified the final P1: a non-atomic `Files.move` fallback. It was removed; unsupported atomic replacement now fails closed. Heisenberg's final independent review after that fix was CLEAR: no Stage 2 data-loss blocker remained.

### Legacy persistence inventory

- Normal legacy markup state was stored as Java-serialized `PageMarkups` maps in `filesDir/markups_<pdfUri.hashCode()>.bin`. The old loader returned an empty map on any read failure, which was the dangerous one-bad-write-to-blank-drawing path.
- Page scales were stored separately in `SharedPreferences("scales")` under the exact key prefix `${uri}_<page>`, with the old prefix filter susceptible to collisions.
- Legacy `PageMarkups` fields and nested photo annotation classes/FQNs remain unchanged. `AndroidLegacyPersistenceSource` is the only production migration reader and preserves both the binary artifact and scale preference data.
- Active MainActivity load/save, lifecycle persistence, import, and export now resolve an association and use `LocalDocumentRepository`. The old markup loaders are gone from active code. The old scale helpers remain only as explicit Stage 0 characterization/migration input seams.

### Identity, manifest, and fingerprint design

- `DocumentId` is a strongly typed, app-generated canonical UUID. It is persisted in `document-manifest.json` and is never derived from a filename, URI hash, content hash, Drive identity, or display name. Exact URI matching plus UUIDs keep same-name PDFs independent.
- The manifest stores schema version, UUID, exact source URI, display name, provider metadata, optional SHA-256 fingerprint, migration verification, legacy-artifact claim state, and legacy artifact name. It uses current/previous/staging files with staged validation and replacement.
- SHA-256 plus byte count is computed only to detect changed content behind an existing URI. Existing associations fail explicitly when the source cannot be fingerprinted. An unfingerprinted existing snapshot cannot be silently rebound when a fingerprint later becomes available; the result is `FingerprintNotBound` until an explicit later policy exists. A changed fingerprint returns `SourceChanged` and never loads the old snapshot as the new source.
- A syntactically valid but empty/incomplete manifest is rejected after initialization. A valid previous manifest can recover a corrupt current manifest, but recovery refuses to allocate an unverified new UUID for a source absent from the recovered mapping.

### LocalDocumentRepository architecture

- `app/src/main/java/com/example/myapplication/stage2/DocumentIdentity.kt` owns `DocumentId`, `SourceFingerprint`, injected source readers, and content-provider fingerprinting on `Dispatchers.IO`.
- `app/src/main/java/com/example/myapplication/stage2/LocalDocumentRepository.kt` is the sole typed local snapshot authority. It stores a versioned `SnapshotEnvelopeJson` per UUID under `filesDir/local_documents/documents/<uuid>/`, with `snapshot.json`, `snapshot.previous.json`, and a quarantine directory.
- Every save serializes the canonical `DocumentSnapshotV1`, writes and fsyncs a unique staging file, validates it by reading it back, preserves the valid current as previous, and replaces current with `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. If atomic replacement is unavailable, it fails closed instead of using a non-atomic fallback. A failure after replacement is surfaced as `CommitUncertain` and attempts to restore the previous complete snapshot.
- Corrupt or association-mismatched current data is quarantined. A valid previous snapshot is promoted and returned with `recoveredFromPrevious = true`; otherwise the repository returns typed `CorruptSnapshot`, `AssociationMismatch`, `SourceChanged`, `CommitUncertain`, or IO errors. Orphaned staging files are quarantined and cannot become `NotFound`.
- Manifest and document mutexes are process-wide and keyed by canonical root plus DocumentId. Resolution/migration use manifest-then-document lock order; ordinary snapshot IO uses document locking. Scale is only inside the canonical snapshot transaction.

### Migration procedure

- `migrateLegacy()` first checks the manifest, reads the legacy markup and exact scale-prefix data on the injected repository IO dispatcher, and maps the union of markup/scale page keys through `snapshotFromLegacyPageData()`.
- For a binary artifact, migration atomically claims the legacy hash in the manifest. A claimed artifact cannot be attached to a second UUID, including when the first migration failed before completion. The transaction then checks for an existing current snapshot, preserves any newer/different current state, or writes the expected canonical snapshot.
- Migration reads the new snapshot back and compares the complete canonical snapshot and fingerprint before setting `migrationVerified`. Claim, save, readback, and completion marking share the manifest/document lock, so normal saves cannot interleave. Repeated migration returns `AlreadyVerified` without rewriting. Legacy binary and scale data are never deleted.

### Tests added

- `DocumentIdentityTest` (5): UUID generation/parsing, stable SHA-256 fingerprinting, changed bytes, and unavailable source behavior.
- `LocalDocumentRepositoryTest` (23): UUID/manifest round trips, same-name and same-URI identity, changed/unavailable fingerprints, strict manifest recovery/reset protection, all canonical domains including scale-only/shape-only/photo-only/empty pages, interrupted/truncated writes, post-replace uncertainty, corrupt/mismatched current and previous recovery, orphan staging, typed no-recovery errors, source-association checks, concurrent same-document writes, and independent documents.
- `LegacyMigrationTest` (8): fully populated field-by-field migration, scale-only migration, stale-current protection, readback verification, failures before/after replacement, idempotency, successful collision rejection, and interrupted-claim collision rejection.
- `LegacyPersistenceSourceTest` (1): Java-serialized legacy compatibility, exact scale-key delimiting, and artifact preservation.
- Existing Stage 0 and Stage 1 tests were preserved. The full JVM suite is 65 tests: 17 Stage 0, 11 Stage 1, 37 Stage 2, plus the template test.

### Exact verification results

Final commands after the last reviewer fix:

```text
.\gradlew.bat --no-daemon --console plain testDebugUnitTest
BUILD SUCCESSFUL; 65 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --console plain assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --console plain lintDebug
BUILD SUCCESSFUL; 0 errors and 77 pre-existing warnings.

.\gradlew.bat --no-daemon --console plain connectedDebugAndroidTest
BUILD SUCCESSFUL on TB336FU (Android 16); 1 package-context instrumentation test passed.
```

The connected suite only asserts `com.example.myapplication == appContext.packageName`; it proves installation/instrumentation/package-context sanity, not a functional app smoke test. No uninstall or app-data wipe was performed.

### Reviewer disposition and boundaries

- The final atomic-replacement blocker was fixed by removing the unsafe ordinary-move fallback and returning a typed failure when atomic replacement is unavailable. The final independent review was CLEAR with no remaining Stage 2 issue.
- The remaining `clearSession()`/duplicate asynchronous load behavior during PDF switching is Stage 3 orchestration scope. It does not reintroduce silent repository corruption-as-empty behavior; repository failures remain typed and are surfaced by MainActivity.
- Deferred: transactional switching/cancellation/autosave orchestration, Drive SyncCoordinator and remote generations, payload/photo hardening, import/export redesign, OCR/rendering/search/UI work, auth/release cleanup, and all other Stage 3+ work.

### Final qualification update

- Final branch: `codex/stage-2-local-persistence`.
- Focused production commit: `f2e74270fd8e908df608e1e341bfa8aae3c2daab` (`feat: replace local persistence safely`).
- A small qualification-documentation commit follows the production commit. `git status --short --untracked-files=no` is clean after the production commit; the unrelated pre-existing `outputs/electrical_catalog_2026-08-22` artifact remains untracked by design and was neither deleted nor committed.

## Stage 3 status — make document switching transactional

### Scope and starting point

- Stage 3 started from the exact verified Stage 2 tip `ece4fb6bd4a6100e689e60666e923b107d04ed63` (`docs: qualify Stage 2 local persistence`).
- The focused branch is `codex/stage-3-transactional-switching`. No Stage 4+ redesign was included.
- Stage 2 production invariants remain authoritative: app-generated UUID associations, source fingerprints, atomic repository replacement, previous-good recovery, quarantine, `CommitUncertain`, process-wide locking, legacy migration claims, and canonical `DocumentSnapshotV1` replacement semantics.

### Sub-agent assignments and findings

- Boyle (A) inventoried MainActivity's duplicate PDF loads, lifecycle and autosave paths, OCR/search/selection/rendering jobs, thumbnail caches, Drive callbacks, and document-sensitive UI state.
- Dalton (B) designed the smallest coordinator boundary: full session tokens, monotonically increasing generations, frozen canonical snapshots, serialized switch transactions, explicit rollback, and a save mutex.
- Euler (C) built deterministic coordinator tests with controllable gates, delayed target loads, frozen-snapshot assertions, cancellation, recovery, and failure injection; the final Stage 3 coordinator suite has 21 tests.
- Meitner (D) independently reviewed cache and async ownership. The review found document-dialog leakage, query/page/OCR/thumbnail cancellation holes, and blocking-cache cancellation gaps; all were fixed and covered by guards or lifecycle resets.
- Cicero's integration review found four P1 classes around provisional-target flushing/editability, cancellation/throw rollback, outgoing exception restoration, and Drive cancellation/join/atomic capture; all were fixed and regression-tested.
- Volta performed the final independent review after the fixes. Disposition: **CLEAR**, with no remaining P0, P1, or P2 transactional-switching defects.

### Transaction architecture

- `app/src/main/java/com/example/myapplication/stage3/DocumentSwitchCoordinator.kt` is the single switch owner. `DocumentSessionToken` includes `DocumentId`, exact source URI, source fingerprint, and a monotonically increasing generation; `sourceCacheKey` deliberately excludes generation for safe cache reuse, while work tokens include session, page, and query revision where applicable.
- The coordinator serializes switches, resolves targets without mutating live state, cancels and joins outgoing document work, captures one immutable outgoing `DocumentSnapshotV1`, performs the final repository save in a narrowly scoped `NonCancellable` section, invalidates the old token, clears document state, establishes the target, and loads it exactly once.
- Target application is accepted only for the current, non-invalidated token. Provisional target loads are not exposed as editable/ready UI and cannot be flushed as blank state. Cancellation, target load failure, and setup exceptions restore the last committed outgoing session and frozen snapshot with a new generation.
- `DocumentAutosaveController` debounces edits, serializes saves, captures only the current token, flushes immutable snapshots, and reports failures. Explicit switch flushes share the same save serialization boundary.
- `AndroidDocumentSessionCallbacks.kt` adapts identity resolution, fingerprinting, migration, repository load/save, and UI establishment/clear callbacks without creating a second load path. `MainActivity.kt` now routes picker selection and process restoration through the coordinator; the former direct load plus `LaunchedEffect(pdfUri)` duplicate was removed.
- MainActivity gates browser editing and document background work on an applied ready token. Document-specific dialogs, including Drive update dialogs, store and validate their originating full token; dismissal, Keep Local, teardown, and replacement revoke pending update applies.

### Async and cache inventory

- Coordinator-owned document jobs cover target loading, OCR pre-cache, Drive startup/auto-sync work, and delegated page/search/render/selection work. Cancellation is joined where completion matters, and stale callbacks fail session validation before touching live state.
- OCR cache and fully-cached-document keys are namespaced by verified source identity; PDF search accepts the same cache namespace and checks current work per page. Thumbnail memory/disk keys include source identity and page rather than page index alone.
- Renderer bitmap, scale, offset, selection, dialog, photo, shape, measurement, note, and full-screen image state is reset/keyed at session/page boundaries. Delayed text-selection OCR checks both session and page tokens; document search checks the live query revision; camera, thumbnail, OCR, and ML Kit/PDFBox paths recheck cancellation before publishing or caching.
- Drive changes are limited to Stage 3 ownership seams: cancellation is rethrown, auto-sync can be stopped and joined, and page data is captured atomically through the coordinator's frozen current snapshot. Drive generations, queues, remote cursors, conflict state, and account/root redesign remain Stage 4 work.
- Legacy migration and repository public IO now preserve coroutine cancellation instead of converting cancellation into a typed ordinary failure; no Stage 2 atomicity or recovery behavior was weakened.

### Tests added

- `DocumentSwitchCoordinatorTest` contains 21 deterministic tests covering normal A→B switching, canonical all-domain round trips, rapid A→B→A generation separation, delayed stale target completion, frozen delayed saves, outgoing-save failure, target failure/recovery/empty states, same-document no-op, page/query/generation stale work, autosave coalescing and switch flush, source revision changes, concurrent requests, coordinator-owned job cancellation, lifecycle flush, provisional-target protection, setup failure rollback, and cancellation restoration.
- The test fake records per-document saves, gates target loads and saves, injects clear/setup failures, preserves durable snapshots, and verifies that a provisional target cannot overwrite its durable state. No timing-dependent sleeps are used for the transaction assertions.

### Exact verification results

Final post-review commands, after the Volta CLEAR disposition and the final dialog-request revocation fix:

```text
.\gradlew.bat --no-daemon --console plain :app:testDebugUnitTest --tests "com.example.myapplication.stage3.*"
BUILD SUCCESSFUL; 21 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --console plain testDebugUnitTest
BUILD SUCCESSFUL; 86 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --console plain assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --console plain lintDebug
BUILD SUCCESSFUL; 0 errors and 77 warnings. The warning count is unchanged from the Stage 2 baseline.

.\gradlew.bat --no-daemon --console plain connectedDebugAndroidTest
BUILD SUCCESSFUL on `TB336FU` (Android 16); 1 package-context instrumentation test passed.

git diff --check
PASS; no whitespace errors.
```

The connected suite still contains only the existing package-context assertion. It proves installation, instrumentation, and package-context sanity, not functional PDF switching. A meaningful A/B/A device test is blocked by the current app architecture's dependence on the Android document picker, real content-provider URIs/fingerprints, and the absence of a deterministic fixture-injection seam. The deterministic coordinator suite is the functional Stage 3 proof below the UI; no device limitation is mislabeled as a functional pass.

### Reviewer disposition and boundaries

- Final independent reviewer Volta is **CLEAR**: no remaining P0, P1, or P2 transactional-switching defects. The review specifically verified provisional-session protection, setup/load/cancellation rollback, autosave serialization, cache identity, stale session/page/query rejection, one authoritative load path, and Drive-dialog revocation.
- Stage 3 is complete because rapid A/B/A switching preserves frozen outgoing work, target identity, generation isolation, and stale-result rejection under deterministic completion reordering.
- Deferred by design: Stage 4 Drive synchronization coordinator and remote generations; Stage 5 payload/photo security; Stage 6 import/export; Stage 7 OCR/rendering performance redesign; Stage 8 reducer/history/search/annotation/UI redesign; and Stage 9 privacy/auth/release cleanup.

### Final qualification update

- Branch: `codex/stage-3-transactional-switching`.
- Production commit: `6e73a4d4ed043453bf33cf4802ab45f9130c89b7` (`feat: make document switching transactional`).
- A separate qualification-documentation commit follows this production commit. After that commit, the tracked working tree is clean; the unrelated pre-existing `outputs/` tree remains untracked by design and was preserved, not staged or deleted.
- The accepted Phase 3 production and qualification commits were pushed to `origin/codex/stage-3-transactional-switching`; this focused `AlreadyActive` navigation correction is maintained as a separate follow-up and does not begin Stage 4.

## Stage 3 integration follow-up — AlreadyActive navigation

- Defect corrected at the `MainActivity`/`BlueprintApp` boundary: selecting the already-active ready PDF from `Screen.SELECTOR` now restores its existing PDF URI and navigates to `Screen.BROWSER` only when the returned token is still current and both the active and ready UI tokens match.
- The coordinator's same-target `SwitchResult.AlreadyActive` no-op is unchanged. The correction does not reload the repository, create a generation, clear or reapply the ViewModel, repeat migration, or start background work.
- `DocumentSelectionIntegrationTest` adds two above-coordinator regressions: ready A selection reuses the exact session and generation with one load/apply/background start and an intact snapshot; a gated provisional A selection remains in the selector and not-ready until the original load completes.

### Follow-up verification

```text
.\gradlew.bat --no-daemon --console plain :app:testDebugUnitTest --tests "com.example.myapplication.stage3.*"
BUILD SUCCESSFUL; 23 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --console plain testDebugUnitTest
BUILD SUCCESSFUL; 88 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --console plain assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --console plain lintDebug
BUILD SUCCESSFUL; 0 errors and 77 warnings. The warning count is unchanged from the Stage 3 baseline.

.\gradlew.bat --no-daemon --console plain connectedDebugAndroidTest
BLOCKED before test execution; `HNY0DSR8` (`TB336FU`) reported `unauthorized`, so Gradle found no online devices and ran 0 instrumentation tests.

git diff --check
PASS; no whitespace errors.
```

- The connected suite could not be used for a functional A/B/A smoke because the device remained unauthorized after one `adb reconnect` attempt. The existing package-context instrumentation test is not represented as a functional switching test.
- Independent post-fix reviewer Plato: **CLEAR** for P0/P1/P2; no files were modified and no concrete defect was found. The review was static; the local unit/build/lint gates above provide execution coverage.
- Follow-up production commit: `1db253e` (`fix: restore navigation for active document selection`). The accepted production commit `6e73a4d4ed043453bf33cf4802ab45f9130c89b7` remains intact; no Stage 4 work was started.

## Stage 5 Coder handoff — filenames, payloads, and photo transactions

### Scope and starting state

- Stage 5 was implemented from `ac9f4e358b2cabb832be5e7e3f8de988d0ea9f02` (`feat: implement Stage 4 serialized synchronization`). Stage 4 serialized coordination, cursor/generation barriers, canonical replacement, local durability, and rollback ordering remain authoritative; no second queue or later Stage 6/7 architecture was introduced.
- The roadmap table is corrected here: Stage 4 is complete; Stage 5 is the active stage pending the final independent Reviewer/Inspector workflow; Stage 6+ remain pending.
- The bureaucracy role configuration substitution recorded for this run is `gpt-5.6-luna, max reasoning, priority tier`, as directed when the named role configuration was unavailable.

### Implemented boundaries and files

- Added `app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt`: named JSON/metadata/page/annotation/path/photo/string/base64/image limits; bounded UTF-8 input/output; strict base64; typed schema-version checks; Drive literal escaping; finite-number, enum, required-field, canonical snapshot, exact photo-key, SHA-256, MIME/signature, decodability, dimension, and pixel validation; typed photo descriptors.
- Added `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`: generated UUID photo IDs with fixed safe `.jpg` publication names, canonical `files/documents/<DocumentId>/photos/` roots, component-boundary containment, symlink rejection including dangling links, explicit validated per-document migration/claim for safe legacy basenames with originals preserved, bounded/fsynced temporary camera/media staging, and atomic-only publication.
- Added `app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt`: typed schema-0 adapter retaining current legacy field names and direct-measurement compatibility while rejecting missing domains, malformed values, unknown enums, non-finite numbers, unsafe names, and oversized structures. Serialization is also streamed through the JSON ceiling.
- Updated `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt`: version-2 photo descriptors, strict v0 compatibility, bounded media/payload reads and writes, required envelope checks, exact photo bytes/descriptors, strict base64, and the shared correctly escaped ID query literal. Stable IDs, app properties, pagination, conditional updates, and read-only lookup remain unchanged.
- Updated `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`: real image validation before staging, contained fsynced temps/backups, atomic-only moves, previous-good rollback, sticky rollback failures, and contained cleanup.
- Updated `app/src/main/java/com/example/myapplication/stage4/SyncMetadataStore.kt`: bounded metadata JSON, typed pending snapshot/photo validation, strict base64, filename/exact-photo validation, and metadata string/property limits while preserving atomic replacement and previous metadata behavior.
- Updated `app/src/main/java/com/example/myapplication/DriveSyncManager.kt`: typed legacy JSON routing, bounded legacy Drive reads/media and photo publication, strict required-photo failure behavior, safe photo paths, and escaped query literals. Existing display-name-only public sync entrypoints still fail closed.
- Updated `app/src/main/java/com/example/myapplication/MainActivity.kt`: bounded import reading before binding/durable apply, document-scoped photo reads for bridge/gallery/fullscreen/PDF export, and document-scoped validated camera staging. A null/partial camera stream cannot add a reference.
- Updated Stage 4 photo tests/helpers in `app/src/test/java/com/example/myapplication/stage4/` to use the committed valid 4032x3024 JPEG for incoming media; arbitrary bytes remain only as previous-good rollback fixtures. Added `app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt`, `Stage5PhotoAssetStoreTest.kt`, and `Stage5MetadataBoundaryTest.kt`.

### Coder test coverage

- The Stage 5 suite exercises the committed `malformed.json`, `missing_required_fields.json`, `malicious_payloads.json`, `malicious_non_finite_payloads.json`, and valid high-resolution photo fixture; schema missing/unsupported/future cases; typed required fields; unknown enums; non-finite/negative/out-of-range values; page/annotation/path/string/JSON/photo/metadata/base64 limits; generated names; traversal/absolute/UNC/drive/dot/NUL/sibling-prefix paths; symlink rejection where the host permits symlink creation; exact photo key/descriptor sets; query quote/backslash injection; valid, corrupt/truncated/non-image/oversized-dimension media; matching/wrong SHA-256; temporary publication; atomic-move failure; last-known-good preservation; cleanup containment; partial camera input; and typed pending metadata failure.
- No ZIP/bundle path was introduced in Stage 5. ZIP-slip, zip-bomb, malformed-manifest, and bundle-version defenses are **N/A for this stage** and remain Stage 6 work.
- Legacy Java serialization class names, fields, `serialVersionUID`s, artifacts, and migration paths were not renamed or deleted. Existing safe legacy photo basenames remain readable through an explicit compatibility fallback; new writes use generated document-scoped names.

### Pre-Hubble Coder candidate validation (superseded by repair evidence below)

All Gradle commands used the checked-in wrapper with task-local `GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home-5` and approved dependency access for the environment.

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL; 19 Stage 5 tests, 0 failures, 0 errors; 1 symlink test skipped because the host could not create symbolic links.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 58 Stage 4 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; Stage 0–4 regression selection passed with 146 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 166 tests total, 0 failures, 0 errors, 1 environment-dependent symlink test skipped in the final full report.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors; existing warning output remains and was not suppressed.

adb devices -l
PASS; authorized device `HNY0DSR8` (`TB336FU`, Android 16) was listed.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD SUCCESSFUL; 1 instrumentation/package-context test ran on `TB336FU` and completed with 0 failures. This is installation/instrumentation/package-context evidence only, not functional UI, photo, or synchronization end-to-end proof.
```

`git diff --check` passed after the final implementation/documentation edits; Git emitted no whitespace errors. Final status and changed-file inspection were also completed. No commit or push was performed by the Coder. Final Reviewer and Sol Ultra Inspector findings are not yet available; this section is Coder evidence, not a final Stage 5 PASS.

## Stage 4 status — serialized synchronization

### Scope and implementation

- Stage 4 was completed in the existing candidate worktree on 2026-08-24. Stages 0–3 were preserved; Stage 5 was not started.
- `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt` adds the typed Drive boundary, deterministic fake Drive, stable folder/file IDs, `DocumentId` and source-fingerprint app properties, authoritative remote cursors, ETag/If-Match conditional Google updates, explicit same-source adoption, read-only paginated lookup, complete canonical payload validation, and complete photo-byte sidecars. Lookup does not create folders or files.
- `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt` is the lifecycle-scoped serialized owner. Immediate, debounced, manual, periodic, photo, import, and lifecycle upload routes enter one per-scope FIFO worker; a per-document mutex, monotonic generation, binding/readiness fences, and the remote mutation lease reject stale work. `Idle`, `Dirty`, `Uploading`, `Conflict`, `ApplyingRemote`, and `Error` are explicit states, and persisted conflict metadata blocks every upload route.
- Remote acceptance validates the downloaded complete snapshot and photo bytes, stages photo replacement, performs the Stage 3 durable local save before memory replacement, commits the authoritative cursor only after replacement, and clears conflict only at that metadata commit. Acceptance rollback restores durable/live/metadata authorities; incomplete photo/canonical/metadata rollback is typed `RECOVERY` with causal evidence.
- `app/src/main/java/com/example/myapplication/stage4/SyncMetadataStore.kt` persists account/root/DocumentId-scoped cursors, references, adoption state, and complete pending snapshot/photo uploads through atomic replacement. `PhotoContentTransaction.kt` stages complete photo bytes and retains sticky rollback failures.
- `app/src/main/java/com/example/myapplication/stage3/DocumentTransactionBarrier.kt`, `DocumentSwitchCoordinator.kt`, `AndroidDocumentSessionCallbacks.kt`, and `MainActivity.kt` connect Stage 4 to the Stage 3 ready-session, lifecycle, local durability, import, photo, and stale-result boundaries. Local lifecycle flush and local import remain valid without a Drive binding; provisional cleared targets cannot be marked dirty or synchronized.
- `DriveSyncManager.kt` retains compatibility methods but their display-name-only sync entry points fail closed; the old independent auto-sync timer is not restarted. Untracked `AGENTS.md` and `outputs/` were preserved and were not staged.

### Inspector, repair, and review evidence

- The single requested fresh Sol/ultra Inspector (Feynman, thread `01a0335f-5702-7d72-b979-3c289ca8e87b`) returned **BLOCKER**. Its findings were not dismissed: non-conditional Drive updates, non-viable cross-device identity/adoption, provisional Stage 3 synchronization, Drive-gated local durability, JSON-only photo upload, Drive-gated local import, non-meaningful dirty state, and the red targeted fixture gate were repaired through the existing Coder only.
- The same Coder (Halley, thread `01a03194-b5ea-76b2-881c-bc362b2c094e`) is completed, not stuck in an environment wait. Its final repair additionally made photo rollback failure sticky, preserved the forward publish failure as suppressed evidence, promoted incomplete rollback to `RECOVERY`, and added the deterministic publish/rollback regression.
- The final independent Reviewer (Copernicus, thread `01a0330c-448a-76e3-93a1-83d8ff1b02dd`) returned **PASS** with no remaining Stage 4 correctness blocker. It retained one **MINOR / DEFERRED** finding: offline edits are durably saved but the dirty marker is in memory until a Drive scope exists; automatic offline-to-online replay is not implemented. That is recorded as deferred because the active Stage 4 contract requires local durability without Drive, not automatic later replay.
- Foreman read-only review after the final Reviewer PASS verified the actual diff and call paths for identity, generation/readiness fencing, queue ownership, conflict barriers, conditional updates, complete snapshots/photos, atomic local acceptance ordering, pagination/read purity, same-name isolation, and Stage 3 lifecycle integration. No additional Stage 4 blocker was found.

### Exact final verification

All commands below used repository-scoped `GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home` and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-home`.

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 58 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage3.*"
BUILD SUCCESSFUL; 24 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 147 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 77 warnings, 0 errors.

git diff --check
PASS; exit code 0. Git emitted only normal LF-to-CRLF working-copy notices for existing modified tracked files.

adb devices -l
UNAVAILABLE/BLOCKED before device listing: adb failed with `Cannot mkdir '\.android': Permission denied`.
```

- The connected Android gate was not claimed as passing. No authorized device result was available, so `connectedDebugAndroidTest` remains unavailable rather than being relabeled as a Stage 4 pass.
- No production files, roadmap, staging area, or commit were changed by the Coder or Reviewer. The final focused Stage 4 commit is intentionally left to the Foreman after this evidence update.

## Stage 5 Reviewer Hubble blocker repair — Coder evidence

### Repair scope and behavior

- Blocker 1: `PayloadSecurity.validateDrivePayloadTree()` now parses the bounded Drive object and strictly checks the v0/v2 envelope, scope fields, canonical snapshot tree, required page/domain/nested primitives, enums, finite/range/count/string limits, and exact descriptor shape before `DriveGateway` materializes `DrivePayload` or `DocumentSnapshotV1`. `FileSyncMetadataStore` now performs the same bounded raw-tree check for its metadata envelope, `decodeValidatedSnapshotJson()` is the only pending snapshot decoder, and pending snapshot serialization is bounded before it is embedded in metadata. The new regression removes a nested `fontSize` and a required page domain that Gson would otherwise default/omit and verifies rejection before acceptance.
- Blocker 2: the legacy import path in `MainActivity` now fails on a null stream, bounded/typed V0 decode failure, canonical snapshot failure, or any missing/invalid required photo before `importCurrentSnapshot()`. `DocumentPhotoAssetStore.migrateLegacyPhotos()` validates and stages the complete referenced set before publishing any member; the multi-photo regression proves a missing second photo publishes neither photo. JSON-only imports without photo references and direct-measurement V0 input remain supported.
- Blocker 3: `PhotoDecodeProbe` is injectable. Android uses `BitmapFactory` bounds plus full decode, applying image limits before full allocation; JVM tests use reflective `ImageReader`/ImageIO compressed-data decoding, dimension checks, and container-completion checks so valid-looking corrupt/truncated data is rejected. The high-resolution 4032x3024 JPEG, corrupt/truncated/non-image, and oversized-dimension paths remain covered.
- Blocker 4: global legacy photo files are no longer an operational fallback. Every active read/write is rooted at `files/documents/<DocumentId>/photos`; safe legacy basenames are only used by an explicit bounded, decoded, hash-checked, atomic per-document migration that preserves the original and then reads the document copy. Gallery, fullscreen, PDF export, camera, import, and sync bridge paths consume validated document-scoped bytes. The two-document same-basename regression verifies isolation.
- Blocker 5: `PhotoPathResolver` rechecks containment/symlink state immediately before and after secure `NOFOLLOW_LINKS` opens/creates/moves and uses `CREATE_NEW` plus atomic-only moves/deletes. Transaction defaults now route through that resolver. The host cannot create a symbolic link (`FileSystemException: A required privilege is not held by the client`), so the symlink test is explicitly skipped; production checks remain enabled and are not conditionally skipped.
- Blocker 6: `StagedPhotoContentTransaction.commit()` marks the authoritative commit point only after the coordinator's durable snapshot/apply and metadata ordering, then performs best-effort cleanup. Backup deletion failures are swallowed with the backup retained, so they cannot trigger an impossible rollback or false transaction failure. The injected cleanup-failure regression verifies the new bytes, old backup bytes, and no temporary file state.
- Stage 4 serialized ordering, generation/cursor/conflict barriers, stable-ID/escaped Drive queries, legacy Java serialization identifiers/artifacts, and the accepted deferred tombstone/GC, dead-private-legacy-helper, and non-atomic legacy-export items are unchanged. No ZIP/bundle path was introduced; ZIP-slip, zip-bomb, malformed-manifest, and bundle-version defenses are **N/A for Stage 5** and remain Stage 6 scope.

### Exact repair validation

The following final commands were run after the Hubble repairs with the checked-in wrapper, task-local `GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home-5`, and no commit or push:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage5.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 82 Stage 5/Stage 4 tests, 0 failures, 0 errors, 1 skipped symlink test.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 146 Stage 0–4 selected tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 171 total tests, 0 failures, 0 errors, 1 symlink test skipped for the host privilege limitation above.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings; warnings were not suppressed.

$env:ANDROID_USER_HOME="C:\Users\david\Desktop\MyApplication\.android-review-home-5"; adb devices -l
BLOCKED inside the sandbox before enumeration: this adb build attempted `\\.android` and returned `Cannot mkdir '\\.android': Permission denied`.

adb devices -l (approved host-environment retry)
PASS; authorized HNY0DSR8 / TB336FU, Android 16.

$env:GRADLE_USER_HOME="C:\Users\david\Desktop\MyApplication\.gradle-review-home-5"; $env:ANDROID_USER_HOME="C:\Users\david\Desktop\MyApplication\.android-review-home-5"; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD SUCCESSFUL; 1 package-context instrumentation test ran on TB336FU with 0 failures. This is installation/instrumentation/package-context evidence only, not functional UI/photo/synchronization proof.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors (only normal LF-to-CRLF notices for modified tracked files).
```

The first in-sandbox `connectedDebugAndroidTest` attempt was also blocked before test execution by the same adb initialization error; the approved host-environment retry completed one package-context instrumentation test. Gradle still printed its non-fatal metrics warning for unwritable `C:\.android`, while all requested tasks completed successfully. An intermediate focused run exposed an ImageReader reflection-signature error; it was repaired before the final green run above. The final independent Reviewer and Sol Ultra Inspector have not yet rerun after this Coder repair, so this section is repair evidence and not a final Stage 5 PASS. The available bureaucracy model substitution remains `gpt-5.6-luna, max reasoning, priority tier`; the Sol Ultra role remains reserved for the required final inspection.

## Stage 5 Hubble follow-up — Coder repair of the two remaining blockers

This section supersedes the earlier Stage 5 path-operation description for the current uncommitted candidate. It records only the two repairs requested after Hubble's fresh review; no Stage 6/7 work, commit, or push was performed.

### Blocker A: raw schema-0 legacy tree validation

- `app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt` now recursively validates the complete bounded `JsonObject`/`JsonArray` legacy V0 tree before any per-page `LegacyPageDto` is materialized by Gson.
- The raw pass rejects non-canonical page keys (including leading-zero keys such as `01`) and duplicate integer identities, unsupported fields, missing/null required primitives, malformed arrays/objects, oversized page/domain/annotation/point/photo/map lists, oversized strings, unsafe photo names, non-finite/out-of-range numbers, invalid booleans/integers, unknown shape enums, both known measurement variants unless their exact required fields are present, and image-note/image-shape keys outside the pin's declared photo set. It bounds unique photo references across the document and preserves the nullable `scale` field and direct-measurement V0 form used by existing data.
- `Stage5PayloadSecurityTest.legacyRawTreeBoundary_rejectsNestedOversizeMissingFieldsAndNonCanonicalPageKeysBeforeDtoMaterialization` covers a valid-shaped `MAX_PATH_POINTS + 1` nested array, a missing nested point primitive, and `1`/`01` page-key collapse. The existing fully populated V0 fixture and direct-measurement compatibility test remain green.

### Blocker B: descriptor-relative photo operations

- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt` now routes every production photo existence/type/read/create/move/delete operation through a held `SecureDirectoryStream` opened by descriptor-relative, component-by-component `NOFOLLOW_LINKS` descent from the filesystem root. Photo operations accept only one direct child name, reject symlinked targets, use secure `newByteChannel`/`CREATE_NEW`, and use same-directory secure moves without replacement; an unavailable/non-forceable secure provider fails closed. There is no production path-based fallback.
- `DocumentPhotoAssetStore` continues to derive only `files/documents/<DocumentId>/photos`, and ephemeral MainActivity import/gallery/fullscreen/export/camera/sync-bridge stores now close their descriptor after each operation. Legacy global files remain only validated migration sources; originals are preserved and document-root copies are used thereafter. `PhotoContentTransaction` closes the resolver after successful authoritative commit or rollback while retaining rollback evidence when cleanup fails.
- `app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt` is an explicitly injected JVM-only test seam; it is not selected by production. `Stage5PhotoAssetStoreTest.parentReplacementInjection_failsClosedWithoutRedirectingOutsideDocumentRoot` deterministically marks a parent replacement and verifies the secure seam rejects the operation without modifying the outside sentinel. Existing symlink coverage remains honest: the Windows host cannot create a symbolic link because link privilege is unavailable, so that one test is skipped rather than treated as a pass.

### Current repair evidence

Exact commands, all using the checked-in wrapper and task-local `GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home-5` and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-home-5`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest
BUILD SUCCESSFUL; targeted payload/photo tests passed.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage4.DrivePaginationTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL; selected Stage 4/5 classes passed.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 146 Stage 0–4 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 173 tests, 0 failures, 0 errors, 1 skipped symbolic-link test due host privilege limitation.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; exit code 0.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 warnings; no warnings were suppressed.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors. Git emitted only normal LF-to-CRLF working-copy notices.

adb devices -l
BLOCKED in the sandbox: adb returned Cannot mkdir '\\.android': Permission denied.

adb devices -l (approved host-environment retry)
PASS; authorized HNY0DSR8 / TB336FU, Android 16.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD SUCCESSFUL in the approved host environment; 1 package-context instrumentation test ran on TB336FU with 0 failures. This is not functional UI/photo/synchronization end-to-end evidence.
```

The full JVM result files report Stage 4 = 58 tests and Stage 5 = 26 tests (1 symlink skip). No ZIP/bundle path was introduced, so ZIP-slip, zip-bomb, malformed-manifest, and bundle-version defenses remain **N/A for Stage 5** and deferred to Stage 6. The final independent Reviewer and Sol Ultra Inspector have not rerun after this Coder repair; this is not a final Stage 5 PASS. The model substitution remains `gpt-5.6-luna, max reasoning, priority tier`. No commit or push was performed.

### Final post-duplicate-key rerun

After adding the streaming duplicate-root-key check, the following were rerun against the same uncommitted worktree:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 173 tests, 0 failures, 0 errors, 1 skipped symbolic-link test.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 warnings.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors.

adb devices -l (approved host environment)
PASS; HNY0DSR8 / TB336FU, Android 16, authorized.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest (approved host environment)
BUILD SUCCESSFUL; 1 package-context test on TB336FU, 0 failures.
```

### Final focused repair rerun

After the post-duplicate-key source check, the complete focused Stage 5 plus
direct Stage 4 regression selection was rerun:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage4.DrivePaginationTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL in 40s; selected Stage 4/5 repair and regression classes passed.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL in 41s; 173 tests, 0 failures, 0 errors, 1 skipped symbolic-link test.
```

The current worktree remains uncommitted; Reviewer and Sol Ultra Inspector
reruns are still required before any Stage 5 PASS claim.

## Stage 5 Hubble follow-up — aggregate photo bound and V0 nullable scale

This section records the two additional Coder repairs requested by Hubble. No
Stage 6/7 work, commit, or push was performed.

### Aggregate photo-size repair

- `PhotoAssetStore` now performs a complete-set legacy preflight: it enumerates
  the exact canonical references, resolves active document-root files first and
  validated legacy sources only as an explicit fallback, obtains each size
  through the secure descriptor-relative resolver, rejects individual and
  cumulative `MAX_TOTAL_PHOTO_BYTES` violations before reading, bounded-reads
  every source, rejects size changes, and validates the complete image/hash set
  before staging anything.
- Legacy migration stages the validated complete set through the existing
  `StagedPhotoContentTransaction` and publishes it as one rollback-capable
  transaction. `withMigratedLegacyPhotos` keeps that transaction open through
  the caller's durable/apply callback and rolls it back for stale or failed
  import apply. MainActivity import now validates and consumes the returned
  migrated byte set instead of discarding it. Upload capture and
  `readReferencedPhotos` use the same cumulative preflight.
- `PhotoPathOperations.size` is descriptor-relative in production and injected
  size reporting is test-only. The aggregate regression uses five individually
  acceptable injected-size photos, proves rejection before any document-root
  publication, and verifies legacy sources remain intact.

### Historical schema-0 scale compatibility

- `LegacyPageDataCodec` now treats `scale` as explicitly optional: absent and
  JSON-null both preserve typed `scale = null`; all required domains and nested
  fields remain strict. The fully populated fixture with every scale key
  removed round-trips successfully, alongside the direct-measurement V0 test.

### Exact repair evidence

All commands used the checked-in wrapper with task-local
`GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home-5` and
`ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-home-5`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL; 83 selected tests, 0 failures/errors, 1 symlink skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 146 tests, 0 failures/errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 175 tests, 0 failures/errors, 1 symlink skip; Stage 4 = 58 and Stage 5 = 28.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 warnings. Existing lint warnings remain unsuppressed.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors.

adb devices -l (sandbox)
BLOCKED before enumeration by Cannot mkdir '\\.android': Permission denied.

adb devices -l (approved host)
PASS; authorized HNY0DSR8 / TB336FU, Android 16.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest (approved host)
BUILD SUCCESSFUL; 1 package-context instrumentation test, 0 failures. This is not functional UI/photo/sync end-to-end evidence.
```

The host also emitted the known non-fatal `C:\.android` metrics warning and
the Kotlin daemon fell back to worker compilation because the sandbox denied
the user Kotlin-daemon marker directory. No ZIP/bundle path was introduced;
bundle defenses remain N/A for Stage 5. Reviewer and Sol Ultra Inspector
reruns are still required before a final Stage 5 PASS claim. The available
bureaucracy substitution remains `gpt-5.6-luna, max reasoning, priority tier`.

### Final post-rename verification

After the final source clarification renamed the independent set to
`uniquePhotoNames`, the focused and build gates were rerun with the same
task-local Gradle/Android homes:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL in 1m 11s; 84 selected tests, 0 failures/errors, 1 symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL in 39s; 146 tests, 0 failures/errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL in 44s; 176 tests, 0 failures/errors, 1 symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 29s.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL in 54s.
```

## Stage 5 Hubble follow-up — raw V0 cumulative photo-reference bound

This repair addresses the remaining raw legacy-payload blocker only. No Stage
6/7 work, commit, or push was performed.

- `LegacyPageDataCodec.validateLegacyRootTree()` now carries a separate mutable
  cumulative reference counter through every raw page and photo-pin tree. Each
  `imageFileNames` array contributes its full cardinality, including repeated
  occurrences of the same safe filename on different pins or pages, and the
  counter rejects `Stage5Limits.MAX_TOTAL_PHOTOS + 1` before the per-page Gson
  DTO materialization loop can run.
- The existing unique-name set remains separate for the independent unique-name
  bound and exact per-pin duplicate policy; it is no longer used to satisfy the
  aggregate reference limit. Valid repeated references within the declared
  cumulative limit remain accepted. Omitted or JSON-null V0 `scale`, raw nested
  validation, direct-measurement compatibility, document-scoped media
  migration, aggregate photo preflight/rollback, and SecureDirectoryStream
  fail-closed behavior are unchanged.
- `Stage5PayloadSecurityTest` now builds a valid-shaped V0 tree with one safe
  photo name repeated across four full pin pages plus a fifth page, proves the
  over-limit raw rejection message, and proves two repeated references remain
  decodable within the limit.

Exact validation evidence for this repair, using the checked-in wrapper and
task-local `GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-home-5`
and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-home-5`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest.legacyRawTreeBoundary_countsRepeatedPhotoReferencesBeforeDtoMaterialization
BUILD SUCCESSFUL; the new regression passed.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL; 84 selected tests, 0 failures/errors, 1 symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 146 tests, 0 failures/errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 176 tests, 0 failures/errors, 1 symlink-test skip; Stage 4 = 58 and Stage 5 = 29.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors (Git emitted only normal LF-to-CRLF notices).

adb devices -l (current sandbox)
BLOCKED before enumeration by Cannot mkdir '\\.android': Permission denied; no connectedDebugAndroidTest claim is made for this repair.
```

The Gradle runs also emitted the known non-fatal `C:\.android` metrics warning
and Kotlin-daemon marker denial, then used the worker fallback. No ZIP/bundle
path was introduced; bundle defenses remain N/A for Stage 5. The worktree is
still uncommitted, and Reviewer/Sol Ultra Inspector approval is outside this
Coder repair. The available bureaucracy substitution remains
`gpt-5.6-luna, max reasoning, priority tier`.

## Stage 5 Coder repair evidence — payload, metadata, and photo transaction blockers (2026-08-24)

This is Coder evidence for the replacement Stage 5 candidate only. It is not a
Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure. No Stage 6+
work, staging, commit, push, or publication was performed.

The bounded repair areas and files are:

- `app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt`:
  finite JSON-depth and zero-read budgets; aggregate nested annotation
  accounting across canonical, Drive, pending, and legacy V0 trees; common
  JPEG/PNG/WebP container-completeness validation; bounded metadata photo
  aggregate/image validation; Windows device-name/control-character filename
  rejection; and the existing duplicate, schema, enum, finite-number, base64,
  photo-reference, descriptor, and query-literal controls retained.
- `app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt`:
  complete outbound typed V0 validation, including nested domains, aggregate
  annotation/reference counts, numeric/text/ID/photo-name bounds, nullable
  scale, and the existing direct-measurement/field-name compatibility.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`:
  forced bounded staging, safe generated-photo GC/capture cleanup, legacy
  resolver lifetime closure, invalid-target quarantine, two-phase photo
  journal/reopen recovery, safe generated-name deletion, and required-photo
  capacity/integrity checks.
- `app/src/main/java/com/example/myapplication/stage4/SyncMetadataStore.kt`:
  deep mutation-safe graph freezing, complete typed write validation, exact
  metadata group validation, bounded photo correspondence/bytes/image checks,
  and strict raw-plus-typed read-back before reporting a committed write.
- `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`
  and `app/src/main/java/com/example/myapplication/MainActivity.kt`:
  durable photo transaction markers, fail-closed interrupted replacement,
  camera count/session checks, and cleanup of capture/publication artifacts on
  stale, failed, or unapplied operations.
- Regression coverage in
  `app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt`,
  `Stage5MetadataBoundaryTest.kt`, `Stage5PhotoAssetStoreTest.kt`,
  `TestPhotoPathOperations.kt`, and
  `app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt`.

Validation was run with the checked-in wrapper and task-local
`GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-final`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest
BUILD SUCCESSFUL; 56 selected tests, 0 failures/errors, 1 Windows symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 149 Stage 0–4 regression tests, 0 failures/errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 198 unit tests, 0 failures/errors, 1 Windows symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings; no warning suppression added.
```

The connected attempt was also made:

```text
adb devices -l
BLOCKED in the direct shell by adb: cannot create its root-level .android
directory (Permission denied); no device enumeration claim is made from this
direct command.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD SUCCESSFUL; Gradle discovered TB336FU - 16 and ran 1 test.
```

The sole Android test is the repository's package-context assertion
(`ExampleInstrumentedTest`); it proves installation/instrumentation context
only, not functional PDF switching, camera, photo, sync, or UI behavior. The
JVM symlink test remains skipped because Windows denied symlink creation
(`A required privilege is not held by the client`).

Compatibility and migration considerations: legacy JSON field names and
runtime FQNs/serialized artifacts remain unchanged; V0 nullable-scale and
direct-measurement inputs remain accepted; legacy originals are never deleted;
generated-photo GC never removes legacy names, references, backups, or
outside-root files; and Stage 4 session/synchronization boundaries were not
redesigned. ZIP/bundle/import-export redesign, OCR/rendering/UI performance,
authentication/privacy/release cleanup, and private legacy display-name Drive
helper revival remain out of scope. The roadmap remains active pending the
independent Reviewer, Foreman review, and final Sol Ultra Inspector sequence.

## Stage 5 Coder repair evidence — cross-store recovery and active generated-photo GC (2026-08-24)

This entry records Coder evidence for the two Lagrange blocker repairs. It is
not a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure. No
Stage 6+ work, staging, commit, push, or publication was performed.

The bounded repairs are:

- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`:
  added a durable, bounded canonical-intent record beside the prepared photo
  journal. It binds the document ID, source URI, and validated canonical
  snapshot digest for the previous and intended authorities. Reopen now
  finalizes only exact intended/intended state, rolls back only exact
  previous/previous state before commit evidence, and retains evidence while
  surfacing typed `RECOVERY` for mixed, missing, corrupt, or unrelated state.
  The resolver remains descriptor-relative and atomic-only; legacy file-only
  journals retain their compatibility recovery behavior.
- `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`
  and `stage4/SyncCoordinator.kt`: record the cross-store intent before any
  photo publication, reconcile at remote-acceptance entry with current durable
  and live canonical snapshots, and commit the photo journal only after the
  canonical durable/apply boundary. The local legacy import path uses the same
  intent protocol around its callback.
- `app/src/main/java/com/example/myapplication/MainActivity.kt` and
  `stage5/PhotoAssetStore.kt`: route bounded document-scoped generated-photo
  GC through the active Stage 4 photo admission/capture paths and import
  reconciliation. Camera failure/stale-session cleanup retains the captured
  document identity and attempts publication/temp cleanup even after UI
  session state is cleared. GC protects referenced names, legacy names,
  backups, temporary captures, and outside-root paths; cleanup errors remain
  fail-closed evidence.
- Regression coverage in
  `app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt`,
  `stage4/SyncCoordinatorTest.kt`, and
  `app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt`
  proves old/old rollback, new/new finalization after canonical durability,
  mixed-authority ambiguity, intent ordering before publication/apply, legacy
  import intent lifetime, and active photo admission removing an orphan while
  retaining referenced and legacy files.

Post-repair validation used the checked-in Gradle wrapper with task-local
`GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-final`
and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-5`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.* --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 153 Stage 0–4 tests in the final report, 0 failures/errors.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 204 tests, 0 failures, 0 errors, 1 Windows symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings; no warning suppression added.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors (only normal LF-to-CRLF notices).
```

Connected evidence is unavailable for this repair. `adb devices -l` exited
before enumeration because `adb.exe` could not create `\\.android`; the final
`:app:connectedDebugAndroidTest` attempt likewise failed to create the ADB
bridge for that environment reason. No instrumentation functional-app claim
is made. The Gradle runs also retain the known non-fatal `C:\.android` metrics
warning and Kotlin-daemon marker denial, using the worker fallback.

Compatibility/deferred considerations: legacy photo originals remain
preserved, legacy JSON field names/FQNs and V0 nullable-scale/direct-
measurement compatibility remain unchanged, and atomic descriptor-relative
photo operations remain in force. ZIP/bundle redesign, OCR/rendering/UI
performance, authentication/privacy/release cleanup, and private legacy Drive
helper revival remain out of scope. The roadmap remains active pending the
independent Reviewer, Foreman, and final Sol Ultra Inspector sequence.

## Stage 5 Coder repair evidence — admission authorities, publication reservations, and post-commit GC (2026-08-24)

This entry records the Coder response to Lagrange's second re-review. It is not
a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure. No Stage 6+
work, staging, commit, push, or publication was performed.

The three bounded integration repairs are:

- `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt`,
  `app/src/main/java/com/example/myapplication/MainActivity.kt`, and
  `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`:
  upload admission now captures a distinct durable snapshot before photo
  admission and passes durable/live authorities through explicit bridge seams.
  The production bridge no longer implements the old live/live photo methods;
  compatibility defaults are read-only or fail closed, and a missing durable
  snapshot cannot be replaced by live state. Legacy migration receives the
  actual previous durable snapshot for its canonical intent record. Admission
  GC protects the union of durable and live references until the accepted
  snapshot is authoritative.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt` and
  `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`:
  generated publication and document-scoped GC share a process-wide per-root
  critical section. A generated name is reserved before its atomic publication
  and remains protected until attachment or cleanup; journal target names and
  explicit in-flight names are also protected. Reservations are bounded and
  expire after the finite publication window, while all file access remains
  descriptor-relative and generated-name-only.
- `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt` and
  `app/src/main/java/com/example/myapplication/MainActivity.kt`: remote
  acceptance invokes bounded GC only after canonical durable/apply, metadata,
  and photo commit are authoritative; local import invokes the same pass only
  after an `Applied` result. Cleanup failure is surfaced as typed recovery
  evidence and does not roll back or delete accepted/reference files. Failed
  import/apply paths do not run the post-acceptance pass.

New or strengthened regression evidence is in
`app/src/test/java/com/example/myapplication/stage4/SyncCoordinatorTest.kt`,
`app/src/test/java/com/example/myapplication/stage4/Stage3RemoteAcceptanceIntegrationTest.kt`,
and `app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt`:
distinct durable/live admission and fail-closed durable capture; mixed-set GC
protection; deterministic publication-reservation interleaving; old generated
photo removal after active remote acceptance; and retention when migration
callback/apply fails. Existing cross-store old/old, new/new, and ambiguous
reopen tests remain green.

Validation used the checked-in wrapper with task-local
`GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-final`
and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-5`:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage5.* --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage4.SyncMetadataStoreTest --tests com.example.myapplication.stage4.SyncCoordinatorTest --tests com.example.myapplication.stage4.Stage3RemoteAcceptanceIntegrationTest
BUILD SUCCESSFUL; 118 selected tests, 0 failures/errors, 1 Windows symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage0.* --tests com.example.myapplication.stage1.* --tests com.example.myapplication.stage2.* --tests com.example.myapplication.stage3.* --tests com.example.myapplication.stage4.*
BUILD SUCCESSFUL; 156 Stage 0–4 tests, 0 failures/errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 210 tests, 0 failures/errors, 1 Windows symlink-test skip.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings; no warning suppression added.

git -c safe.directory="C:/Users/david/Desktop/MyApplication" diff --check
PASS; no whitespace errors (only normal LF-to-CRLF notices).
```

An initial combined focused run exposed a pre-existing Stage 3 barrier fixture
that had no durable snapshot; the new fail-closed contract correctly stopped
before the injected save signal. The fixture now seeds its explicit durable
state, and the isolated and recombined tests pass. Gradle repeatedly reports
the known non-fatal `C:\.android` metrics warning and Kotlin-daemon marker
denial, then succeeds with the worker fallback.

Connected evidence remains unavailable. `adb devices -l` failed because
`adb.exe` could not create `\\.android` (`Permission denied`), and
`:app:connectedDebugAndroidTest` failed before device enumeration because the
ADB bridge could not be created for the same environment limitation. No
package-context or functional instrumentation claim is made.

Compatibility/deferred considerations remain unchanged: legacy originals,
legacy JSON field names/FQNs, V0 nullable-scale/direct-measurement behavior,
atomic descriptor-relative photo operations, and FileProvider behavior are
preserved. ZIP/bundle/import-export redesign, OCR/rendering/UI performance,
authentication/privacy/release cleanup, and private legacy display-name Drive
helper revival remain out of scope. The roadmap remains active pending the
independent Reviewer, Foreman review, and final Sol Ultra Inspector sequence.

## Stage 5 Coder repair evidence — typed admission, import transaction ownership, and Drive target revalidation (2026-08-25)

This entry records the bounded Coder response to the reconciled Stage 5 brief.
It is not a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No Stage 6+ work, staging, commit, push, or publication was performed.

The repairs are:

- `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt`:
  required-photo admission now keeps cancellation transparent, maps canonical
  recovery to typed `RECOVERY`, validation to typed `VALIDATION`, and other
  admission failures to typed local-persistence failure before any remote
  mutation. The failed outcome publishes `SyncState.Error` and `onError`.
  Focused tests assert typed outcomes, state/error delivery, zero remote
  mutations, and cancellation preservation.
- `app/src/main/java/com/example/myapplication/MainActivity.kt` and
  `app/src/main/java/com/example/myapplication/stage3/DocumentSwitchCoordinator.kt`:
  import legacy-photo publication, canonical durable/live replacement, photo
  transaction commit/rollback, and post-commit cleanup now run under one
  shared document barrier. The new within-document-transaction import seam
  avoids nested barrier acquisition and preserves stale-session and rollback
  behavior; cleanup recaptures both canonical authorities before GC.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt` and
  `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`:
  prepared journal bytes now provide an owner identity. Commit, rollback,
  canonical-recovery preparation, and marker cleanup verify that identity so a
  stale transaction cannot mutate a newer transaction's evidence. Legacy
  file-only commit markers remain readable for compatibility, while new
  transactions use an identity-bound marker. A deterministic stale-owner test
  retains the newer marker and recovery evidence.
- `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt`:
  authoritative folder/file reads and mutation responses request and validate
  parents, stable IDs, exact `annotations.json` naming, document identity,
  schema, and compatible source-fingerprint properties. Created/updated
  resources are re-read and revalidated before `Uploaded` is returned;
  moved-parent and identity-mismatch tests reject before remote write in the
  exercised race seams. Existing adoption and conditional cursor/ETag behavior
  remains covered.

Final validation used the checked-in wrapper with task-local
`GRADLE_USER_HOME=C:\Users\david\Desktop\MyApplication\.gradle-review-final`
and `ANDROID_USER_HOME=C:\Users\david\Desktop\MyApplication\.android-review-home-5`.
Counts below are from the final Gradle XML reports:

```text
$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL; 132 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip.

$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 164 tests, 0 failures, 0 errors, 0 skipped.

$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 221 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip.

$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; 38 actionable tasks, 3 executed, 35 up-to-date.

$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 0 errors and 76 existing warnings; no warning suppression added.

git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
PASS; no whitespace errors (only normal LF-to-CRLF notices).
```

Connected evidence remains unavailable. `adb devices -l` exited before device
enumeration because `adb.exe` could not create `\\.android` (`Permission denied`).
The final `$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path;
.\gradlew.bat --gradle-user-home .gradle-review-final --no-daemon --stacktrace
--console=plain :app:connectedDebugAndroidTest` attempt likewise failed while
creating the ADB bridge. No package-context or functional instrumentation claim
is made. Gradle also retained the non-fatal `C:\.android` metrics warning;
Kotlin-daemon marker access was denied and the worker fallback was used where
needed.

Compatibility/deferred considerations: legacy photo originals, legacy JSON
field names/FQNs, V0 nullable-scale/direct-measurement behavior, and
descriptor-relative photo operations remain preserved. Private legacy
display-name helpers, optional-thumbnail null behavior, provider/symlink
capability evidence, connected-device evidence, query control-character
hardening unless a direct regression appears, and the optional standalone
`previousCanonicalSnapshot` migration API remain deferred or out of scope.
ZIP/bundle import/export, OCR/rendering/UI, authentication/privacy/release
cleanup, and all Stage 6+ work remain out of scope. The roadmap remains active
pending the independent Reviewer, Foreman, and final Sol Ultra Inspector
sequence.

## Stage 5 Coder repair evidence — reviewer blockers: admission scope, Drive identity, marker collisions, and typed rejection (2026-08-25)

This entry records the bounded Coder response to the independent Reviewer
blockers. It is not a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5
closure. No Stage 6+ work, staging, commit, push, or publication was
performed.

The repairs are:

- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`
  and `app/src/main/java/com/example/myapplication/MainActivity.kt`: active
  upload admission now reads only the document's canonical photo root. It no
  longer treats an unclaimed basename in the global legacy directory as
  active content, and the active rejection test proves no document target or
  transaction evidence is published and the legacy original is not deleted
  or changed. Explicit legacy migration/display compatibility remains
  separate and preserves originals.
- `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt`:
  source-fingerprinted uploads now require the exact folder/file source
  identity, document/schema properties, exact `annotations.json` name, and
  expected parentage on every authoritative read and mutation response.
  Final folder/file/folder re-reads prevent a move between final reads from
  being returned as `Uploaded`; download performs the analogous post-transfer
  identity and parent recheck. No-fingerprint compatibility remains limited
  to legacy resources with no source property. Deterministic HTTP tests cover
  missing/mismatched source metadata, moved parents, and zero-write
  pre-mutation rejection.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`
  and `app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt`:
  photo transaction marker parsing now claims staged, target, and backup
  names in one namespace, rejecting duplicate or cross-role collisions while
  retaining the corrupt marker as recovery evidence.
- `app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt`,
  `app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt`,
  `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`,
  `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt`,
  `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt`,
  and the directly involved tests: broad rejection catches were narrowed to
  the expected typed validation, stream, Gson, numeric, filesystem, and
  security exceptions. Cancellation remains transparent; assertion and
  programming errors are not converted into skips or ordinary success. The
  Windows symlink assumption is limited to the exact missing-privilege
  capability failure.

Final validation used the checked-in wrapper with task-local
`ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path` and
`GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path`:

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL; 135 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 167 tests, 0 failures, 0 errors, 0 skipped.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 224 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
 BUILD SUCCESSFUL; 38 actionable tasks, 3 executed, 35 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
 BUILD SUCCESSFUL; 30 actionable tasks, 7 executed, 23 up-to-date; 0 errors and 76 existing warnings; no warning suppression added.

git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
PASS; no whitespace errors (only normal LF-to-CRLF notices).
```

The symlink test was skipped only because Windows returned `A required
privilege is not held by the client` while creating the test link. Direct
`adb devices -l` exited 1 because `adb.exe` could not create `\\.android`
(`Permission denied`). The final connected command was attempted:

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD FAILED; Could not create ADB Bridge because adb.exe could not create
`\\.android` (`Permission denied`); no device or instrumentation result.
```

Gradle retained the non-fatal `C:\.android` metrics warning. Kotlin daemon
marker creation was denied under the host profile during compilation and the
Gradle worker fallback completed the JVM/Android-test compilation. No
package-context or functional instrumentation claim is made. Legacy originals,
legacy JSON field names/FQNs, V0 nullable-scale/direct-measurement behavior,
canonical/live/durable ordering, transaction ownership/recovery evidence, and
descriptor-relative photo operations remain preserved. Private legacy
display-name helpers, optional-thumbnail null behavior, provider/symlink
capability evidence beyond the documented host limitation, connected-device
evidence, query control-character hardening without a direct regression, and
the optional standalone `previousCanonicalSnapshot` migration API remain
deferred or out of scope. ZIP/bundle import/export, OCR/rendering/UI,
authentication/privacy/release cleanup, and all Stage 6+ work remain out of
scope. The roadmap remains active pending independent Reviewer, Foreman, and
final Sol Ultra Inspector review.

## Stage 5 Coder repair evidence — PNG validation, recovery ownership, and marker boundary (2026-08-26)

This entry records the bounded Coder response to the final Inspector blockers.
It is not a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No Stage 6+ work, staging, commit, push, or publication was performed.

Repairs in this pass:

- `app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt` now compares the fixed eight-byte PNG signature prefix and applies the terminal-container check to the production probe before Android decode. Real ImageIO-generated PNG coverage uses the default decoder and checks dimensions, exact bytes, descriptor SHA/size, missing/truncated IEND, and trailing bytes; explicit legacy migration covers PNG publication/readback and preserves the legacy original.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt` writes versioned canonical recovery intents carrying the SHA-256 of the prepared file journal. Reopen, pending recovery, target inspection, finalization, and rollback verify that owner before acting. V1 intents remain readable for diagnostics but are non-actionable without provable ownership; active admission remains document-root-only while explicit migration retains the legacy source.
- `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt` and `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt` keep the live resolver open through a pre-authority marker failure so complete old-state rollback remains possible. If the authoritative marker was written, the new canonical/live/metadata tuple is retained with typed recovery evidence and is never reported as ordinary success. Close-enforcing tests cover transaction and remote-acceptance rollback.
- `app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt` gates Loaded/offline Empty readiness on photo canonical recovery, failing closed before ready/editable exposure. `app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt` adds post-transfer and final folder/file identity, parent, source-fingerprint, and cursor revalidation before download acceptance. `app/src/main/java/com/example/myapplication/stage4/SyncMetadataStore.kt` narrows metadata rejection handling to explicit Gson, state, I/O, and security exceptions while preserving cancellation.

Final validation used the checked-in wrapper with task-local
`ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path` and
`GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path`:

The validation set below was rerun after the typed metadata, Android
readiness rejection, and production PNG completeness repairs;
the counts and results are from that final source revision.

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL; 141 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip; 27 actionable tasks: 4 executed, 23 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL; 171 tests, 0 failures, 0 errors, 0 skipped; 27 actionable tasks: 1 executed, 26 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL; 230 tests, 0 failures, 0 errors, 1 Windows symlink-capability skip; 27 actionable tasks: 1 executed, 26 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL; 38 actionable tasks, 3 executed, 35 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL; 30 actionable tasks, 7 executed, 23 up-to-date; 0 errors and 76 existing warnings; no warning suppression added.

git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
PASS; no whitespace errors (normal LF-to-CRLF notices only).

Explicit untracked-candidate check:
3 Stage 5 main files, 4 Stage 5 test files, and
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
were present and enumerated by git ls-files --others --exclude-standard.
The intended untracked-file whitespace check passed.
```

The final connected command was attempted:

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD FAILED before device enumeration; adb.exe could not create
`\\.android` (`Permission denied`), so no device or instrumentation result;
70 actionable tasks: 1 executed, 69 up-to-date.
```

The Windows symlink test was skipped only for the exact host capability error
`A required privilege is not held by the client`. Gradle also emitted the
non-fatal `C:\.android` metrics warning; Android-test Kotlin compilation used
the worker fallback after Kotlin-daemon marker access was denied. No
package-context or functional instrumentation claim is made. Legacy
originals, JSON field names/FQNs, V0 nullable-scale/direct-measurement
behavior, descriptor-relative photo operations, and intentional no-fingerprint
Drive compatibility remain preserved. Private legacy display-name helpers,
optional-thumbnail null behavior, provider/symlink evidence beyond this host
limitation, connected-device evidence, query control-character hardening
without a direct regression, the optional standalone
`previousCanonicalSnapshot` migration API, ZIP/bundle import/export,
OCR/rendering/UI, authentication/privacy/release cleanup, and all Stage 6+
work remain deferred or out of scope. The roadmap remains active pending
independent Reviewer, Foreman, and final Sol Ultra Inspector review.

## Stage 5 Coder repair evidence — transaction lifetime, cleanup recovery, and cross-store phase (2026-08-26)

This entry records the same Coder's fresh validation of the current bounded
Stage 5 candidate after the second independent Reviewer blocker report. It is
not a Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure. No
agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed.

The candidate includes the requested bounded controls:

- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`
  retains versioned journal-bound canonical intent, rejects unowned legacy
  commit markers, uses a durable cleanup marker before marker deletion, rejects
  staged/target/backup name collisions, preserves legacy originals, and
  releases migration-owned resolvers on both pre-authoritative and
  authoritative failure paths.
- `app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt`
  keeps the resolver live through a pre-authoritative commit-marker failure,
  exposes the authoritative-commit boundary to the coordinator, and closes
  only after the new authority is established. Close-enforcing tests cover
  rollback and migration ownership.
- `app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt`
  records the remote-acceptance metadata phase durably, rolls back through a
  live photo resolver before the marker boundary, and retains typed recovery
  evidence after the marker boundary instead of reporting ordinary success.
- `app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt`
  gates loaded and offline-empty document readiness on photo recovery. The
  candidate also retains the prior real-PNG validation/publication coverage,
  active document-scoped admission isolation, Drive identity/parent/fingerprint
  re-reads, typed cancellation/admission failures, and import barrier.

Fresh validation used the checked-in wrapper with task-local
`ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path` and
`GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path`:

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 1m 25s; 148 tests, 0 failures, 0 errors, 1 skipped; 27 actionable tasks: 5 executed, 22 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 47s; 177 tests, 0 failures, 0 errors, 0 skipped; 27 actionable tasks: 1 executed, 26 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL in 57s; 237 tests, 0 failures, 0 errors, 1 skipped; 27 actionable tasks: 1 executed, 26 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 33s; 38 actionable tasks: 3 executed, 35 up-to-date.

$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
BUILD SUCCESSFUL in 59s; 30 actionable tasks: 7 executed, 23 up-to-date; 0 errors and 76 warnings reported by the lint XML; no warning suppression added.
```

The one focused skip was
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`.
It was skipped only because Windows returned
`A required privilege is not held by the client` while creating a symbolic
link. No broad test catch converts assertion or programming errors into this
skip.

The final connected gate was attempted with:

```text
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-final').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD FAILED before device enumeration; adb.exe could not create `\\.android` (`Permission denied`) while creating the ADB bridge; 70 actionable tasks: 2 executed, 68 up-to-date; no device or instrumentation result.
```

Gradle also emitted the non-fatal `C:\.android` metrics warning. Kotlin
daemon marker creation was denied under the host profile and the Gradle
worker fallback completed compilation. This is an environment limitation, not
a connected-test pass; no package-context or functional instrumentation claim
is made.

Final hygiene checks passed: `git diff --check` reported no whitespace errors
(only normal LF-to-CRLF notices), and an explicit `git ls-files --others
--exclude-standard` check enumerated all 8 intended untracked Stage 5/support
files with no trailing-whitespace findings. Existing unrelated untracked
review/evidence/build artifacts remain preserved. The roadmap remains active
pending the required independent Reviewer, Foreman, and final Sol Ultra
Inspector loop. Stage 6, ZIP/bundle import/export, OCR/rendering/UI,
authentication/privacy/release cleanup, connected-device evidence, provider
symlink capability beyond this host, and query-control-character hardening
without a direct regression remain deferred or out of scope.

## Stage 5 Coder blocker-repair evidence — final lint and durable-boundary validation (2026-08-26)

This is the same Coder's final evidence entry for the focused Reviewer repair
brief. It is not an independent Reviewer PASS, Foreman PASS, Inspector PASS,
or Stage 5 closure. No agents were spawned, no Stage 6 work was started, and
no commit, push, or publication was performed.

The bounded repairs and regression coverage now include:

- `app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt`
  keeps production recovery behavior unchanged while allowing the durable
  JVM boundary test to inject the same document-scoped photo store and a
  source page-count seam; loaded and offline-empty readiness remains blocked
  until photo recovery resolves.
- `app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt`
  retains an exact parsed transaction journal if the final cleanup-marker
  deletion fails, so restart reconciliation cannot lose the only recoverable
  identity/evidence.
- `app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt`
  and `app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt`
  exercise typed, close-enforcing metadata-marker deletion failures in both
  commit and rollback cleanup, including restart reconciliation and all five
  Stage 5 markers.
- `app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt`
  uses real `FileSyncMetadataStore`, durable local snapshots, real photo
  journals/resolvers, fresh callback/store instances, and readiness checks
  across pre-phase, post-metadata, post-phase/pre-photo-commit, and partial
  cleanup/restart boundaries. It proves unresolved state is not exposed as
  ordinary success or editable readiness and that recovery leaves a coherent
  canonical/metadata/photo tuple.

All final Gradle commands below used the checked-in wrapper with:

```text
$env:GRADLE_USER_HOME = 'C:\Users\david\.gradle'
$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path
```

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 26s; 149 tests, 0 failures, 0 errors, 1 skipped; 27 actionable tasks: 2 executed, 25 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 16s; 177 tests, 0 failures, 0 errors, 0 skipped; 27 actionable tasks: 1 executed, 26 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
BUILD SUCCESSFUL in 28s; 238 tests, 0 failures, 0 errors, 1 skipped; 27 actionable tasks: 1 executed, 26 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 13s; 38 actionable tasks: 3 executed, 35 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 1m 6s; 30 actionable tasks: 30 executed.
```

The forced lint report was freshly written at
`C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml`
(`app/build/reports/lint-results-debug.html` was also written), with XML
`LastWriteTime=2026-08-26T03:48:30.8247590-04:00`, 76 total issues, 0 errors,
and 76 warnings. No warning suppression was added. This replaces reliance on
the earlier up-to-date lint result.

The current connected attempt was:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
BUILD SUCCESSFUL in 26s; 1 test started and finished on TB336FU - 16; 70 actionable tasks: 6 executed, 64 up-to-date.
```

This is only the repository's package/instrumentation-context sanity test; it
does not establish functional PDF switching, recovery, sync, or UI behavior.
The earlier ADB `\\.android` permission failure is not reproduced by this
current task-local attempt, but remains an environment limitation to keep in
mind for other connected operations.

Final hygiene checks were:

```text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
```

It reported no whitespace errors (only normal LF-to-CRLF notices). An
explicit PowerShell check over the eight intended untracked Stage 5/support
paths, followed by
`git -c safe.directory='C:/Users/david/Desktop/MyApplication' ls-files --others --exclude-standard -- <those eight paths>`,
found all 8 present, all 8 untracked, no missing paths, and no trailing
whitespace. Existing unrelated untracked review, cache, build, and evidence
artifacts remain untouched. `CODEX_AUDIT_ROADMAP.md` still marks Stage 5
active pending the independent Reviewer, Foreman, and final Sol Ultra
Inspector loop; all Stage 6+ work remains deferred.

## Stage 5 Coder blocker-repair evidence — preserved forced test runs (2026-08-26)

This entry supersedes the earlier unpreserved test-count evidence for the
current Coder candidate. It is not an independent Reviewer PASS, Foreman
PASS, Inspector PASS, or Stage 5 closure. No agents were spawned, no Stage 6
work was started, and no commit, push, or publication was performed.

The final test repair makes the complete marker-deletion matrix use the V3
remote-acceptance intent in both commit and rollback paths for each of the
five markers. It asserts typed failure, exact journal/intent/metadata,
commit, and cleanup evidence bytes, expected old/new photo bytes and hashes,
close-enforcing resolver ownership, and fresh-resolver restart cleanup of all
five markers. `Stage4PhotoFixture` now supplies two distinct, valid,
decodable JPEG fixtures; the durable cross-store boundary test validates both
exact bytes and SHA-256 values at initial, pre-phase, post-metadata,
post-phase/pre-photo-commit, failure, and restart boundaries while retaining
real durable metadata, canonical snapshots, photo journals, and callback
readiness gates.

Before the new forced sequence, the previous fixed-path result/report trees
were preserved at:

```text
C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-pre-repair-20260826-040603859
Created: 2026-08-26T04:06:03.8684840-04:00
Both test-results/testDebugUnitTest and reports/testDebugUnitTest present.
```

Each required JVM run below used the checked-in wrapper with:

```text
$env:GRADLE_USER_HOME = 'C:\Users\david\.gradle'
$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path
```

Each command was forced with `--rerun-tasks`; its complete
`app/build/test-results/testDebugUnitTest` and
`app/build/reports/tests/testDebugUnitTest` directories were copied to the
unique evidence root shown immediately after that run, before the next run.
Counts below were parsed from that run's copied XML files.

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 55s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-20260826-040718369
Created: 2026-08-26T04:07:18.3756416-04:00
Test-results timestamp: 2026-08-26T04:08:13.6319388-04:00
HTML-report timestamp: 2026-08-26T04:08:13.6529390-04:00
8 suites; 149 tests, 0 failures, 0 errors, 1 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 43s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-20260826-040829984
Created: 2026-08-26T04:08:29.9982190-04:00
Test-results timestamp: 2026-08-26T04:09:13.5665555-04:00
HTML-report timestamp: 2026-08-26T04:09:13.5915569-04:00
18 suites; 177 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 54s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-20260826-040928258
Created: 2026-08-26T04:09:28.2694010-04:00
Test-results timestamp: 2026-08-26T04:10:22.4386683-04:00
HTML-report timestamp: 2026-08-26T04:10:22.4656670-04:00
22 suites; 238 tests, 0 failures, 0 errors, 1 skipped.
```

The one skipped JVM test remains
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`,
skipped only for the exact Windows capability error `A required privilege is
not held by the client` while creating a symbolic link. Assertion,
programming, and unrelated environment errors are not converted to this
skip.

The remaining final gates were run after the source/test edits:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 7s; 38 actionable tasks: 38 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 57s; 30 actionable tasks: 30 executed.
```

The forced lint XML was copied to
`C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-20260826-041054636\lint-results-debug.xml`.
The source report was
`C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml`,
with `LastWriteTime=2026-08-26T04:11:51.9458020-04:00`; the HTML report was
also written at `app\build\reports\lint-results-debug.html`. XML contained
76 issues: 0 errors and 76 warnings. No warning suppression was added.

Final hygiene command:

```text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
```

It reported no whitespace errors, only normal LF-to-CRLF notices. An
explicit check over all eight intended untracked Stage 5/support paths found
8 present, 8 untracked, no missing paths, and no trailing whitespace:

```text
app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt
app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
```

The explicit untracked-path command was:

```text
$intended = @('app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt','app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt','app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt','app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt','app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt','app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt','app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt','app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt')
$present = @($intended | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
$untracked = @(git -c safe.directory='C:/Users/david/Desktop/MyApplication' ls-files --others --exclude-standard -- $intended)
foreach ($path in $intended) { foreach ($line in Get-Content -LiteralPath $path) { if ($line -match '[ \t]+$') { throw "trailing whitespace: $path" } } }
```

It returned `Intended=8`, `Present=8`, `IntendedUntracked=8`, empty missing
and trailing-whitespace results.

The earlier current-candidate connected attempt remains a single
package/instrumentation-context test on `TB336FU - 16`; it is not functional
PDF, recovery, sync, or UI evidence. Stage 5 remains active in
`CODEX_AUDIT_ROADMAP.md` pending the independent Reviewer, Foreman, and final
Sol Ultra Inspector loop. All Stage 6+ work remains deferred.

## Stage 5 Coder blocker-repair evidence — final V3 and preserved reruns (2026-08-26)

This entry records the final Coder repair after the Reviewer requested
auditable per-run evidence and stronger tuple discrimination. It is not an
independent Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed.

`PhotoContentTransactionTest` now runs every one of the five marker deletion
failures through the V3 remote-acceptance preparation in both commit and
rollback cleanup. Each case checks the typed failure, exact retained journal,
canonical-intent, metadata-phase, commit, and cleanup-marker bytes, expected
old/new photo bytes, close-enforcing resolver use, and fresh-resolver restart
cleanup of all five markers. `Stage4PhotoFixture` provides distinct,
decodable JPEG bytes for the old and incoming photos. The durable boundary
test now checks their exact bytes and SHA-256 values at initial, pre-phase,
post-metadata, post-phase/pre-photo-commit, commit-failure,
rollback-failure, and restart boundaries, including a not-ready callback while
rollback evidence is deliberately still failing.

The pre-edit fixed-path results were preserved at:

```text
C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-pre-repair-20260826-040603859
Last-write timestamp: 2026-08-26T04:06:03.8684840-04:00
Complete test-results/testDebugUnitTest and reports/testDebugUnitTest present.
```

For each forced JVM run below, the complete fixed-path
`app/build/test-results/testDebugUnitTest` and
`app/build/reports/tests/testDebugUnitTest` directories were copied to the
unique evidence root before the next run. Counts were parsed from the copied
XML files, not inferred from a later run.

```text
$env:GRADLE_USER_HOME = 'C:\Users\david\.gradle'
$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-home-5').Path

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 53s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-20260826-041455373
Evidence root last-write: 2026-08-26T04:15:49.3115588-04:00
8 suites; 149 tests, 0 failures, 0 errors, 1 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 44s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-20260826-041605703
Evidence root last-write: 2026-08-26T04:16:49.9110905-04:00
18 suites; 177 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-20260826-041704122
Evidence root last-write: 2026-08-26T04:18:00.1066909-04:00
22 suites; 238 tests, 0 failures, 0 errors, 1 skipped.
```

The one skipped JVM test is
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`,
skipped only for Windows `A required privilege is not held by the client`
while creating a symbolic link. No assertion, programming, or unrelated
environment failure was converted into that skip.

The final build gates were also rerun after the source revision:

```text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 6s; 38 actionable tasks: 38 up-to-date.

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 58s; 30 actionable tasks: 30 executed.
```

Forced lint XML and HTML were preserved at
`C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-20260826-041825544`.
The source XML report was
`C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml`,
timestamped `2026-08-26T04:19:24.4425692-04:00`; it contains 76 issues, 0
errors, and 76 warnings. No warning suppression was added.

Final hygiene used:

```text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
```

It returned no whitespace errors, only normal LF-to-CRLF notices. The exact
eight-path untracked check reported all 8 present, all 8 untracked, no missing
paths, and no trailing whitespace. The existing connected evidence remains
one package/instrumentation-context test on `TB336FU - 16`, not functional
PDF, recovery, sync, or UI proof. The roadmap remains Stage 5 active pending
the independent Reviewer, Foreman, and final Sol Ultra Inspector loop; Stage
6+ remains deferred.

## Stage 5 Coder blocker-repair evidence — cross-store rollback and authoritative marker probe (2026-08-26)

This is the same Coder's final evidence for the focused Reviewer blocker
repair. It is not an independent Reviewer PASS, Foreman PASS, Inspector PASS,
or Stage 5 closure. No agents were spawned, no Stage 6 work was started, and
no commit, push, or publication was performed.

The bounded repair is in:

```text
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt
app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt
app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt
app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt
app/src/test/java/com/example/myapplication/stage4/SyncCoordinatorTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
```

Cross-store compensation now restores photo bytes while retaining the V3
journal, canonical intent, metadata phase, and cleanup evidence. The
coordinator restores canonical durable/live state and metadata before writing
the rollback-complete proof and clearing markers. Reopen/readiness remains
blocked while that proof is absent, and the recovery intent binds document and
snapshot identities plus the exact previous/intended photo-set digests. The
authoritative photo commit check is tri-state (`Absent`, bound, or typed
`Ambiguous`); malformed, unreadable, foreign, or post-write-unverifiable
markers never become false absence. The deterministic tests cover the
photo-first process-boundary window, typed boundary failure, fresh durable
repository/metadata/photo/callback instances, close-enforcing operations, and
safe marker/readback recovery.

Each forced JVM run below used the checked-in wrapper and the preserved review
Gradle cache:

```text
.\gradlew.bat --gradle-user-home .gradle-review-home-5 --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-final-20260826-051352908
Evidence created: 2026-08-26T05:13:52.9275755-04:00; finished: 2026-08-26T05:14:49.4754873-04:00.
Complete test-results/testDebugUnitTest and reports/testDebugUnitTest preserved; 8 XML suites; 153 tests, 0 failures, 0 errors, 1 skipped.

.\gradlew.bat --gradle-user-home .gradle-review-home-5 --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 45s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-final-20260826-051504584
Evidence created: 2026-08-26T05:15:04.6004615-04:00; finished: 2026-08-26T05:15:50.0706335-04:00.
Complete test-results/testDebugUnitTest and reports/testDebugUnitTest preserved; 18 XML suites; 180 tests, 0 failures, 0 errors, 0 skipped.

.\gradlew.bat --gradle-user-home .gradle-review-home-5 --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-final-20260826-051604694
Evidence created: 2026-08-26T05:16:04.7096738-04:00; finished: 2026-08-26T05:17:00.1271495-04:00.
Complete test-results/testDebugUnitTest and reports/testDebugUnitTest preserved; 22 XML suites; 242 tests, 0 failures, 0 errors, 1 skipped.
```

The single skipped JVM test was
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`,
skipped only for the exact Windows capability error `A required privilege is
not held by the client` while creating a symbolic link. Assertion,
programming, and unrelated environment errors are not converted to this skip.

The build gates were:

```text
.\gradlew.bat --gradle-user-home .gradle-review-home-5 --no-daemon --stacktrace --console=plain --rerun-tasks :app:assembleDebug
BUILD SUCCESSFUL in 39s; 38 actionable tasks: 38 executed.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path
$env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path
.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 1m; 30 actionable tasks: 30 executed.
```

The forced lint XML and HTML were preserved at
`C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-final-20260826-051804135`.
The source XML was `app/build/reports/lint-results-debug.xml`, with
`LastWriteTime=2026-08-26T05:19:04.9261722-04:00`; it contained 76 issues,
0 errors, and 76 warnings. No warning suppression was added.

Final hygiene used:

```text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
```

It returned no whitespace errors, only normal LF-to-CRLF notices. The
explicit intended-untracked-path check returned 8 present, 8 untracked, no
missing paths, and no trailing-whitespace findings for:

```text
app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt
app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
```

Connected functional recovery/sync/UI testing remains unavailable. The prior
connected attempt failed before device enumeration because `adb.exe` could not
create `\\.android` (`Permission denied`); it produced no instrumentation
result. The previously available package-context test on `TB336FU - 16` is not
functional PDF, recovery, sync, or UI evidence. Existing review/cache/output
and other user artifacts remain untouched. `CODEX_AUDIT_ROADMAP.md` still
marks Stage 5 active pending independent Reviewer, Foreman, and final Sol
Ultra Inspector closure; Stage 6+ remains deferred.

## Stage 5 Coder blocker-repair evidence — fresh-instance rollback, V3 digest binding, and persistent marker ambiguity (2026-08-26)

This entry records the exact final-candidate validation for the focused
Reviewer repair. It is Coder evidence only: it is not an independent Reviewer
PASS, Foreman PASS, Inspector PASS, or Stage 5 closure. No agents were
spawned, no Stage 6 work was started, and no commit, push, or publication was
performed.

The bounded implementation and regression coverage are present in:

```text
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt
app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt
app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt
app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt
app/src/test/java/com/example/myapplication/stage4/SyncCoordinatorTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
```

The REMOTE_ACCEPTANCE rollback evidence now remains durable until a fresh
resolver proves the exact old canonical durable/live identities, old metadata
identity, and previous photo-set digest; only then can all markers be cleared
and readiness proceed. V3 canonical intents require exactly the complete
11-line form with both SHA-256 photo digests. Commit-marker readback remains a
typed tri-state: persistent unreadable or malformed evidence is ambiguous,
never absent, and conservatively retains the new authority and journal. The
close-enforcing regression coverage checks exact retained journal/intent/
marker/photo bytes, typed failure, fresh-instance behavior, and zero
use-after-close.

The three JVM runs below were forced with `--rerun-tasks`, executed against the
final candidate, and preserved under unique non-overwriting evidence roots.
The XML counts are from the copied result trees, not from stale console text.

```text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 1m 11s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-coder-20260826-055940453
Evidence root created: 2026-08-26T05:59:40.5372620-04:00; latest copied XML: 2026-08-26T05:58:52.7544158-04:00.
8 XML suites; 156 tests, 0 failures, 0 errors, 1 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-coder-20260826-060105140
Evidence root created: 2026-08-26T06:01:05.1530136-04:00; latest copied XML: 2026-08-26T06:00:58.2410909-04:00.
18 XML suites; 182 tests, 0 failures, 0 errors, 0 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 13s; 27 actionable tasks: 27 executed.
Evidence root: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-coder-20260826-060235011
Evidence root created: 2026-08-26T06:02:35.0235083-04:00; latest copied XML: 2026-08-26T06:02:24.6303716-04:00.
22 XML suites; 245 tests, 0 failures, 0 errors, 1 skipped.
```

The only skipped JVM case in the focused and full trees is
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`,
skipped solely for the exact Windows capability error `A required privilege is
not held by the client` while creating a symbolic link. The Stage 0–4 tree
has no skips. Assertion, programming, and unrelated environment errors were
not converted into that skip.

The final build gates were also forced against the same candidate:

```text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:assembleDebug
BUILD SUCCESSFUL in 46s; 38 actionable tasks: 38 executed.
APK evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-assemble-coder-20260826-060425826\debug-apk\app-debug.apk; copied APK last-write 2026-08-26T06:04:20.8166110-04:00.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 1m 19s; 30 actionable tasks: 30 executed.
```

The forced lint source report is
`C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml`,
with `LastWriteTime=2026-08-26T06:05:51.0972535-04:00`; it contains 76
warnings, 0 errors, and 76 total issues. The XML and HTML copies are at
`C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-coder-20260826-060557783`.
No warning suppression was added.

Final hygiene used:

```text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
```

It returned exit code 0 and no whitespace errors; Git emitted only normal
LF-to-CRLF notices. The explicit intended-candidate check found all 8
expected untracked Stage 5/support files present and untracked, with no
missing paths or trailing whitespace:

```text
app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt
app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
```

Connected functional recovery/sync/UI testing remains unavailable: the prior
ADB attempt failed before device enumeration because `adb.exe` could not
create `\\.android` (`Permission denied`). The available package-context test
on `TB336FU - 16` is not functional PDF, recovery, sync, or UI evidence. The
roadmap remains Stage 5 active pending independent Reviewer, Foreman, and
final Sol Ultra Inspector closure; Stage 6+ remains deferred.

## Stage 5 Coder blocker-repair evidence — durable rollback cleanup and empty-readiness gate (2026-08-26)

This is the same Coder's current-candidate evidence only. It is not an
independent Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed. The roadmap remains Stage 5 active.

The bounded repair and regression coverage are in:

```text
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
```

`PhotoAssetStore` now writes a bounded V2 rollback-completion proof containing
the journal bytes/hash, previous metadata identity, both canonical identity
tuples, and both exact photo-set digests before cleanup can delete any
authority marker. The proof is deleted only after all cleanup succeeds; if a
later deletion fails, a fresh resolver can verify the same evidence and finish
cleanup. Cleanup also rejects a same-journal but altered canonical intent.
Rollback-pending evidence sets an explicit unresolved recovery gate, so an
offline/empty document with no authoritative snapshot returns `Failed` and
does not expose background work. Only a fresh exact old canonical/metadata/
photo tuple resolves the gate.

Fresh forced JVM validation was run against this final candidate, and each
result tree was copied before the next JVM run:

```text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 54s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-coder-current-20260826-064019424
Evidence timestamp/latest XML: 2026-08-26T06:41:20.8513037-04:00; 8 XML suites; 158 tests, 0 failures, 0 errors, 1 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 43s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-coder-current-20260826-064019424
Evidence timestamp/latest XML: 2026-08-26T06:42:22.2517972-04:00; 18 XML suites; 182 tests, 0 failures, 0 errors, 0 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-coder-current-20260826-064019424
Evidence timestamp/latest XML: 2026-08-26T06:43:33.3275476-04:00; 22 XML suites; 247 tests, 0 failures, 0 errors, 1 skipped.
```

The one skipped JVM test in the focused and full trees is
`resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes`.
It is skipped only for the exact Windows capability error `A required
privilege is not held by the client` while creating a symbolic link. The
Stage 0–4 tree has no skips. Assertion, programming, and unrelated
environment errors were not converted into this skip.

The build gates were also forced on the same candidate:

```text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:assembleDebug
BUILD SUCCESSFUL in 38s; 38 actionable tasks: 38 executed.
APK evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-assemble-coder-current-20260826-064019424\debug-apk\app-debug.apk; 131139802 bytes; last-write 2026-08-26T06:44:25.8290953-04:00.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 59s; 30 actionable tasks: 30 executed.
Fresh report evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-coder-current-20260826-064019424\lint-results-debug.xml and lint-results-debug.html.
Source report: C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml; LastWriteTime=2026-08-26T06:45:37.7035172-04:00; 76 warnings, 0 errors, 76 total issues.
```

Final hygiene checks reported no whitespace errors from
`git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check`.
The explicit intended-candidate check found all 8 required untracked
Stage 5/support files present and untracked, with zero trailing-whitespace
findings. The connected functional recovery/sync/UI gate remains unavailable:
the prior ADB attempt could not create `\\.android` (`Permission denied`),
and the available `TB336FU - 16` package-context test is not functional PDF,
recovery, sync, or UI proof. Existing review/cache/output and other user
artifacts remain untouched.

## Stage 5 Coder final blocker-repair evidence — prevent V3-to-V1 rollback downgrade (2026-08-26)

This supersedes the immediately preceding V1-boundary validation because the
final source revision also prevents a REMOTE_ACCEPTANCE completion path from
writing a historical V1 rollback-complete marker when its V3 rollback-pending
metadata identity is absent. The V1 read/cleanup guard and its valid,
wrong-owner, and malformed fresh-instance regression remain unchanged. This is
Coder evidence only, not independent Reviewer, Foreman, or Inspector closure.
No agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed. The roadmap remains Stage 5 active.

The final source/test files for this repair are:

~~~text
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
~~~

After the V3 writer guard was added, the required validation commands were
forced against the final candidate. Each JVM result/report tree was copied
before the next run:

~~~text
$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 58s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-v1-final-coder-current-20260826-074847600
8 XML suites; 160 tests, 0 failures, 0 errors, 1 skipped; latest copied XML 2026-08-26T07:48:38.9054283-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 44s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-v1-final-coder-current-20260826-074949047
18 XML suites; 182 tests, 0 failures, 0 errors, 0 skipped; latest copied XML 2026-08-26T07:49:39.9450665-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-v1-final-coder-current-20260826-075058754
22 XML suites; 249 tests, 0 failures, 0 errors, 1 skipped; latest copied XML 2026-08-26T07:50:52.0783205-04:00.
~~~

The one skipped JVM test in the focused and full trees is
resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes.
It is skipped only for the exact Windows capability error A required
privilege is not held by the client while creating a symbolic link. The
Stage 0–4 tree has no skips. Assertion, programming, and unrelated
environment errors were not converted into that skip.

The final build gates were rerun after the source revision:

~~~text
$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 9s; 38 actionable tasks: 3 executed, 35 up-to-date.
APK evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-assemble-v1-final-coder-current-20260826-075119505\app-debug.apk; 131502777 bytes; last-write 2026-08-26T07:51:14.8213578-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 59s; 30 actionable tasks: 30 executed.
Fresh copied reports: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-v1-final-coder-current-20260826-075235859\lint-results-debug.xml and lint-results-debug.html.
Source report: C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml; LastWriteTime=2026-08-26T07:52:28.9048298-04:00; 76 warnings, 0 errors, 76 total issues.
~~~

The final post-log hygiene audit returned exit code 0 from
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check.
Git emitted only normal LF-to-CRLF notices. All 8 intended untracked
Stage 5/support files were present and had zero trailing-whitespace findings.
Connected functional recovery/sync/UI testing remains unavailable because the
prior ADB attempt could not create \\.android (Permission denied); the
available package-context test is not functional PDF, recovery, sync, or UI
evidence. Existing review/cache/output and other user artifacts remain
untouched. Stage 5 remains pending independent Reviewer, Foreman, and final
Sol Ultra Inspector closure; Stage 6 remains deferred.

## Stage 5 Coder blocker-repair evidence — reject legacy V1 rollback completion mixed with V3 (2026-08-26)

This is the same Coder's current-candidate evidence only. It is not an
independent Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed. The roadmap remains Stage 5 active.

The bounded production and regression changes are in:

~~~text
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
~~~

PhotoAssetStore now treats the historical
SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V1 record as a legacy-only cleanup
record. Recovery and the lower-level cleanup/rollback probe reject it with
typed PhotoCanonicalRecoveryException whenever a Stage 5 commit marker,
canonical intent, metadata phase, or V2 rollback proof is present. No marker,
journal, canonical state, metadata state, or photo bytes are deleted on that
mixed-evidence path. The regression builds V3 remote-acceptance evidence,
restores the old photo while canonical/metadata remain new, then injects a
valid downgraded V1 record, a wrong-owner V1 record, and a malformed V1 record.
Fresh resolver and Android session instances remain failed/not-ready with no
background work and exact evidence bytes retained. Cleanup succeeds only after
the exact old canonical/metadata/photo tuple is restored and the valid V3
pending record is present again. The existing process-boundary test was
updated to use the complete V3 metadata-phase/rollback protocol rather than
emitting a V1 completion record from a V3 intent.

A forced two-test repair smoke run completed successfully and was preserved:

~~~text
$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage5.Stage5MetadataBoundaryTest.crossStoreRollback_retainsEvidenceAcrossPhotoFirstProcessBoundary" --tests "com.example.myapplication.stage5.Stage5MetadataBoundaryTest.legacyV1RollbackCompletion_cannotAuthorizeMixedV3EvidenceAcrossFreshInstances"
BUILD SUCCESSFUL in 36s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-v1-targeted-coder-current-20260826-073943906
1 XML suite; 2 tests, 0 failures, 0 errors, 0 skipped; latest copied XML 2026-08-26T07:39:32.7158865-04:00.
~~~

The required final JVM validation runs were forced against this candidate and
each complete result/report tree was copied before the next run:

~~~text
$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 55s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-v1-coder-current-20260826-074053965
8 XML suites; 160 tests, 0 failures, 0 errors, 1 skipped; latest copied XML 2026-08-26T07:40:47.2533114-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 45s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-v1-coder-current-20260826-074156512
18 XML suites; 182 tests, 0 failures, 0 errors, 0 skipped; latest copied XML 2026-08-26T07:41:47.9293096-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-v1-coder-current-20260826-074306431
22 XML suites; 249 tests, 0 failures, 0 errors, 1 skipped; latest copied XML 2026-08-26T07:42:59.9479027-04:00.
~~~

The one skipped JVM test in the focused and full trees is
resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes.
It is skipped only for the exact Windows capability error A required
privilege is not held by the client while creating a symbolic link. The
Stage 0–4 tree has no skips. Assertion, programming, and unrelated
environment errors were not converted into that skip.

The final build gates were run against the same source:

~~~text
$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 8s; 38 actionable tasks: 3 executed, 35 up-to-date.
APK evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-assemble-v1-coder-current-20260826-074329464\app-debug.apk; 131502665 bytes; last-write 2026-08-26T07:43:24.7579383-04:00.

$stage5GradleHome = (Resolve-Path '.gradle-review-home-5').Path; $stage5AndroidHome = (Resolve-Path '.android-review-home-5').Path; $env:GRADLE_USER_HOME = $stage5GradleHome; $env:ANDROID_USER_HOME = $stage5AndroidHome; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 59s; 30 actionable tasks: 30 executed.
Fresh copied reports: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-v1-coder-current-20260826-074442161\lint-results-debug.xml and lint-results-debug.html.
Source report: C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml; LastWriteTime=2026-08-26T07:44:35.4891295-04:00; 76 warnings, 0 errors, 76 total issues.
~~~

Final hygiene used:

~~~text
git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
~~~

It returned exit code 0. Git emitted only normal LF-to-CRLF notices. An
explicit audit of all 8 intended untracked Stage 5/support files found every
path present and zero trailing-whitespace findings; the files are:

~~~text
app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt
app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
~~~

Connected functional recovery/sync/UI testing remains unavailable: the prior
ADB attempt failed before device enumeration because adb.exe could not create
\\.android (Permission denied). The available package-context test is not
functional PDF, recovery, sync, or UI evidence. Existing review/cache/output
and other user artifacts remain untouched. Stage 5 remains pending
independent Reviewer, Foreman, and final Sol Ultra Inspector closure; Stage 6
remains deferred.

## Stage 5 Coder blocker-repair evidence — sixth cleanup marker and V2 proof corruption (2026-08-26)

This is the same Coder's current-candidate evidence only. It is not an
independent Reviewer PASS, Foreman PASS, Inspector PASS, or Stage 5 closure.
No agents were spawned, no Stage 6 work was started, and no commit, push, or
publication was performed. The roadmap remains Stage 5 active.

The bounded test-only repair is in:

~~~text
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
~~~

The existing cross-store cleanup matrix now injects failure at all six
markers, including .stage5-photo-rollback.complete. The sixth-marker case
proves the V2 proof was already durable, retains exact old canonical,
metadata, photo, and proof bytes, remains blocked on a fresh resolver/callback,
and removes all six markers only after a fresh exact old-tuple reconciliation.
The same test also rewrites the V2 proof with an altered journal identity and
with a malformed field count; both fresh resolver and callback paths fail
closed, retain exact evidence, perform no cleanup, and preserve the old tuple.

Fresh forced JVM validation was run against this final candidate. Each result
tree was copied before the next JVM run could overwrite app/build:

~~~text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-focused-coder-current-20260826-070302635
Evidence timestamp/latest XML: 2026-08-26T07:02:54.4882904-04:00; 8 XML suites; 159 tests, 0 failures, 0 errors, 1 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
BUILD SUCCESSFUL in 44s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-stage0-4-coder-current-20260826-070402459
Evidence timestamp/latest XML: 2026-08-26T07:03:55.3124877-04:00; 18 XML suites; 182 tests, 0 failures, 0 errors, 0 skipped.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
BUILD SUCCESSFUL in 56s; 27 actionable tasks: 27 executed.
Evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-full-coder-current-20260826-070513038
Evidence timestamp/latest XML: 2026-08-26T07:05:05.3373076-04:00; 22 XML suites; 248 tests, 0 failures, 0 errors, 1 skipped.
~~~

The one skipped JVM test in the focused and full trees is
resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes.
It is skipped only for the exact Windows capability error A required
privilege is not held by the client while creating a symbolic link. The
Stage 0–4 tree has no skips. Assertion, programming, and unrelated
environment errors were not converted into this skip.

The final build gates were forced on the same candidate:

~~~text
$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:assembleDebug
BUILD SUCCESSFUL in 38s; 38 actionable tasks: 38 executed.
APK evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-assemble-coder-current-20260826-070604502\debug-apk\app-debug.apk; 131139802 bytes; last-write 2026-08-26T07:05:59.2463833-04:00.

$env:GRADLE_USER_HOME=(Resolve-Path '.gradle-review-home-5').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.android-review-home-5').Path; .\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL in 59s; 30 actionable tasks: 30 executed.
Fresh report evidence: C:\Users\david\Desktop\MyApplication\outputs\stage5-validation-lint-coder-current-20260826-070716859\lint-results-debug.xml and lint-results-debug.html.
Source report: C:\Users\david\Desktop\MyApplication\app\build\reports\lint-results-debug.xml; LastWriteTime=2026-08-26T07:07:09.8152259-04:00; 76 warnings, 0 errors, 76 total issues.
~~~

Two initial unprivileged focused attempts were environmental failures before
tests executed: one could not close a transformed dependency JAR under the
review Gradle cache, and a fresh task-local home could not download the
wrapper because network access was denied. The final commands above completed
with elevated filesystem access; no source failure was reported.

Final git diff --check reported no whitespace errors. The explicit intended
candidate check found all 8 required untracked Stage 5/support files present,
with zero trailing-whitespace findings. Connected functional recovery/sync/UI
testing remains unavailable because ADB could not create \\.android
(Permission denied); the available package-context test is not functional
PDF, recovery, sync, or UI proof. Existing review/cache/output and other user
artifacts remain untouched.

## Stage 5 closure evidence — final independent review chain (2026-08-26)

Stage 5 is complete for the uncommitted candidate at baseline `ac9f4e3`. The
Stage 5 scope passed: filenames, bounded and typed payloads, Drive identity
and query handling, validated transfers, and photo transactions. The latest
independent Luna Max Reviewer returned PASS, the bounded Foreman review
returned PASS, and a fresh Terra Max Inspector (`gpt-5.6-terra`, max
reasoning) returned PASS with no blocker. No Stage 6 work was started.

The user-authorized governance update changed `AGENTS.md` to the LEAN CONTEXT
BUREAUCRACY PROTOCOL and made Terra Max (`gpt-5.6-terra`, max reasoning) the
default final Inspector.

Resolved blocker: photo rollback/live-sidecar restart and cleanup-order
evidence was repaired and passed independent re-review, bounded Foreman
review, and fresh Terra Max inspection.

The exact current candidate source and test areas are:

~~~text
app/src/main/java/com/example/myapplication/DriveSyncManager.kt
app/src/main/java/com/example/myapplication/MainActivity.kt
app/src/main/java/com/example/myapplication/stage3/AndroidDocumentSessionCallbacks.kt
app/src/main/java/com/example/myapplication/stage3/DocumentSwitchCoordinator.kt
app/src/main/java/com/example/myapplication/stage4/DriveGateway.kt
app/src/main/java/com/example/myapplication/stage4/PhotoContentTransaction.kt
app/src/main/java/com/example/myapplication/stage4/SyncCoordinator.kt
app/src/main/java/com/example/myapplication/stage4/SyncMetadataStore.kt
app/src/main/java/com/example/myapplication/stage5/LegacyPageDataCodec.kt
app/src/main/java/com/example/myapplication/stage5/PayloadSecurity.kt
app/src/main/java/com/example/myapplication/stage5/PhotoAssetStore.kt
app/src/test/java/com/example/myapplication/stage4/PhotoContentTransactionTest.kt
app/src/test/java/com/example/myapplication/stage4/Stage3RemoteAcceptanceIntegrationTest.kt
app/src/test/java/com/example/myapplication/stage4/Stage4PhotoFixture.kt
app/src/test/java/com/example/myapplication/stage4/SyncCoordinatorTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5MetadataBoundaryTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PayloadSecurityTest.kt
app/src/test/java/com/example/myapplication/stage5/Stage5PhotoAssetStoreTest.kt
app/src/test/java/com/example/myapplication/stage5/TestPhotoPathOperations.kt
~~~

Preserved green evidence for the final candidate is:

~~~text
.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage4.*" --tests "com.example.myapplication.stage5.*"
focused Stage 4/5 JVM: 166 tests, 0 failures, 0 errors, with the expected qualified Windows symlink capability skip

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest --tests "com.example.myapplication.stage0.*" --tests "com.example.myapplication.stage1.*" --tests "com.example.myapplication.stage2.*" --tests "com.example.myapplication.stage3.*" --tests "com.example.myapplication.stage4.*"
Stage 0–4 JVM: 183 tests, 0 failures, 0 errors, 0 skips

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:testDebugUnitTest
full JVM: 255 tests, 0 failures, 0 errors, with the expected qualified Windows symlink capability skip

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
BUILD SUCCESSFUL

.\gradlew.bat --no-daemon --stacktrace --console=plain --rerun-tasks :app:lintDebug
BUILD SUCCESSFUL; lintDebug: 0 errors and the existing warnings

git -c safe.directory='C:/Users/david/Desktop/MyApplication' diff --check
exit code 0
~~~

The latest narrow repair compilation also passed:

~~~text
$env:ANDROID_USER_HOME = (Resolve-Path '.android-review-stage5-repair').Path; .\gradlew.bat --gradle-user-home .gradle-review-final --offline --no-daemon --stacktrace --console=plain --rerun-tasks :app:compileDebugUnitTestKotlin -x :app:compileDebugJavaWithJavac
BUILD SUCCESSFUL; 20 actionable tasks
~~~

Later forced focused-test, assemble, and lint attempts after that narrow
repair were unavailable before producing new product evidence: focused tests
and lint hit Windows ZIPFS/cache `AccessDeniedException` for the transformed
`ui-test-manifest-1.7.0-api.jar`, while assemble hit the signing-lock
`AccessDeniedException` for `.android-review-stage5-repair/debug.keystore.lock`.
These are environment limitations, not product PASS claims and not a reason
to replace the preserved green evidence above. Connected Android testing is
unavailable because ADB could not create `\\.android` (`Permission denied`),
and there is no terminal-green external CI evidence. No APK/AAB or device
result beyond the existing logged evidence is claimed.

No test rerun was needed for this documentation-only entry. Stage 6 remains
pending; import/export, connected functional recovery/sync/UI/device evidence,
external CI, existing lint warnings, and other historical deferred items remain
deferred or unavailable as recorded above. No commit or publication was made.

## Stage 6 current-candidate qualification evidence — Android gate passed (2026-08-30)

This entry records the uncommitted Stage 6 candidate based on baseline
`ea0f31f7fb6a580dfc116bf39acf04a1e66e2759` on
`codex/stage-3-transactional-switching`. It is Coder evidence only: the
independent Reviewer and Terra closure are still pending. No Stage 7 work was
started, and no commit or push was made.

### Candidate behavior and bounded repairs

- Export/import use a versioned, self-contained `.sotaware` bundle: typed
  `manifest.json`, canonical `snapshot.json`, and document-scoped photo
  bytes. The qualification bundle carried `formatVersion`/snapshot schema
  version 1 and storage schema version 1, source identity/fingerprint, exact
  photo descriptors, byte counts, and SHA-256 values.
- The canonical round trip covers scale, measurements, pen paths,
  highlighter paths, page notes, page shapes, photo pins, two JPEG photos,
  image notes, and image shapes. Typed validation rejects malformed or
  incomplete snapshots, non-finite/out-of-range values, unsafe names, wrong
  photo sets or hashes, invalid image bytes/dimensions, and oversized input.
  ZIP handling retains bounded entry/count/total-size/ratio checks, ZIP-slip
  containment, link rejection, and aborts the archive without unbounded
  `closeEntry()` draining after a claimed-size/actual-read rejection.
- Import stages and validates the complete bundle/photo set, records recovery
  evidence, persists canonical durable state before live replacement, and
  retains evidence until live and durable rollback are verified. Export
  prepares, finishes, validates, and closes a bounded app-private temporary
  archive before opening the selected SAF destination; only the successful
  copy has the required flush/close completion boundary, and pre-open or
  preparation failure cannot truncate an existing destination.
- The qualification-exposed Android path defect was repaired by threading an
  explicit trusted app-private root through the Stage 4 photo transaction and
  secure descriptor traversal at the two production staging call sites.
  Generic callers retain strict symlink rejection; only the explicit trusted
  `filesDir` boundary permits Android-managed ancestors such as `/data/user/0`.

### JVM, build, and instrumentation evidence

The exact task-local validation set was:

~~~text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage6.DocumentBundleServiceTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest
focused result: BUILD SUCCESSFUL; 130 tests, 0 failures, 0 errors, 3 qualified Windows symlink-capability skips

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
full result: BUILD SUCCESSFUL; 294 tests, 0 failures, 0 errors, 3 qualified Windows symlink-capability skips

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
result: BUILD SUCCESSFUL; qualification APK SHA-256 F42FC30DE1824A8C64AA4360F2073FD625A5A0036B2CCCA7C392242DA2EFF0F9

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
result: BUILD SUCCESSFUL; 74 warnings, 0 errors

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
result: BUILD SUCCESSFUL on HNY0DSR8/TB336FU; 1 package-context instrumentation test, 0 failures, 0 errors, 0 skips
~~~

The connected test is installation/instrumentation/package-context evidence,
not a substitute for the functional qualification below.

### HNY0DSR8 functional qualification

On authorized `HNY0DSR8` (`TB336FU`, Android 16/API 36), the qualification
used the APK above through a fresh uninstall/install, selected
`stage6-qual-plan.pdf` through normal SAF, and reopened the PDF normally.
Normal UI then created scale, measurement, pen, highlighter, page note, page
`CLOUD` shape, two real camera JPEGs, an image note, and an image `RECTANGLE`
shape. Normal SAF exported
`astage6-qual-plan-final.sotaware.zip` to shared Downloads, outside app-private
data, where the preserved artifact is 8,649,659 bytes. Its verified
SHA-256 is
`B7982AEAF13565DC878242E8527EFE354D4915C8171FC7B338280DB57DF850D0`;
the task handoff string omitted the `1` after `...C817`, so the verified
artifact hash is recorded here.

The bundle was then selected through normal SAF import. The imported canonical
snapshot was compared across all domains against the exported snapshot, and
both photo files were restored byte-for-byte before and after force-stop,
relaunch, and normal reopen:

~~~text
photo-2a83c89a-9990-4e5a-abef-d9a1da873c3a.jpg  4,329,430 bytes  AAFBB9B918BC0F80A4BB536A4ED29D38A7E91BDB21D3357E5371E04415296BDD
photo-3d53077c-9002-4e2d-b64a-87993c3dca88.jpg  4,315,521 bytes  6A5B2A1D3FA10B439CAC6B3231C090A88DAECC2FD9254742091F9F46CA9A9482
~~~

The preserved evidence directory is
`outputs/stage6-android-qualification/`; its `bundle-final`, `postimport-current`,
and `postrelaunch-current` trees contain the manifest/snapshot and matching
photo byte/hash evidence. Stage 6 remains pending formal independent Reviewer
and Terra closure; this entry does not issue a Stage 6 or final qualification
verdict.

## Stage 6 current-candidate closeout update — descriptor repair qualified (2026-08-30)

This update records the later qualification of the same uncommitted candidate
from baseline `ea0f31f7fb6a580dfc116bf39acf04a1e66e2759`. It supersedes the
preceding Coder entry's APK, bundle, photo, and test-count values for current
qualification only; historical entries remain preserved. Formal independent
Reviewer and Terra closure are still pending. No Stage 7 work, commit, or push
was started.

### Final bounded repair and bundle evidence

- `DocumentBundleService` rejects ZIP general-purpose data-descriptor bit
  `0x0008` before `ZipInputStream` extraction. The forged-small-claim
  `DocumentBundleServiceTest` regression proves the archive is rejected before
  extraction or `closeEntry()`; the existing rejected-entry no-`closeEntry()`
  regression remains. Existing bounded entry/count/total-size/compression-
  ratio, ZIP-slip, link, name, and payload protections remain in force, and
  the app exporter continues to emit explicit-size STORED entries.
- The bundle is a versioned, self-contained `.sotaware` archive containing
  typed `manifest.json`, canonical `snapshot.json`, and document-scoped photo
  bytes. Typed validation covers source identity, schema/version, byte counts,
  hashes, required fields, finite values, photo descriptors and image bytes;
  the Android artifact included all canonical domains: pages=1, paths=2,
  measurements=1, notes=1, pageShapes=1, photoPins=1, imageNotes=1,
  imageShapes=1, and a present scale.
- Import validates and stages the complete bundle, persists canonical durable
  state before live replacement, and retains recovery evidence until live and
  durable rollback are verified. SAF export prepares, finishes, validates, and
  closes a bounded app-private archive before opening the selected destination;
  only the successful copy has the flush/close completion boundary.
- The Android trusted-root repair explicitly permits Android-managed ancestors
  only at the trusted app-private `filesDir` boundary used by the production
  photo staging calls. Generic path callers retain strict symlink and traversal
  rejection.

### Current JVM, build, and instrumentation evidence

~~~text
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest --tests com.example.myapplication.stage6.DocumentBundleServiceTest --tests com.example.myapplication.stage4.PhotoContentTransactionTest --tests com.example.myapplication.stage5.Stage5PhotoAssetStoreTest --tests com.example.myapplication.stage5.Stage5PayloadSecurityTest --tests com.example.myapplication.stage5.Stage5MetadataBoundaryTest
focused result: BUILD SUCCESSFUL; 131 tests, 3 skipped, 0 failures, 0 errors

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
full result: BUILD SUCCESSFUL; 295 tests, 3 skipped, 0 failures, 0 errors

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
result: BUILD SUCCESSFUL; current qualification APK SHA-256 C28357C6DA15C2C4AAB5E4CDC0EEAA7B8D66594E12BA9AC5CE64195C236BDD06

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
result: BUILD SUCCESSFUL; 0 errors

.\gradlew.bat --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
result: BUILD SUCCESSFUL on HNY0DSR8/TB336FU, Android API 36; 1 package-context test, 0 failures, 0 errors, 0 skips

git diff --check
result: clean
~~~

### Current HNY0DSR8 functional qualification

On authorized `HNY0DSR8` (`TB336FU`, Android 16/API 36), the current APK was
freshly uninstalled and installed. Normal SAF opened
`stage6-qual-plan.pdf`; normal UI populated scale, one measurement, pen and
highlighter paths, a page note, a `CLOUD` page shape, two camera JPEG photos,
an image note, and a `RECTANGLE` image shape. Normal SAF exported the finished
bundle outside app data to shared Downloads. The preserved export is 9,069,681
bytes with SHA-256
`5BC8976F377BC0F89027CAD19DBDC41FB04C00C68406BB7B3846EA8FA930B1D3`.
Its manifest, snapshot, and photos are self-contained.

After fresh reinstall proof showed only `cache` and `code_cache`, the same PDF
was reopened through normal SAF and the bundle was imported through normal SAF.
The private canonical snapshot then matched the exported snapshot semantically
for every domain listed above. Both photos were restored byte-for-byte:

~~~text
photo-8829...jpg  4,546,592 bytes  e2bac2ad20c1265dc450a92ef1a6a349b37ec3230d2c2482db3dc6ff23b54beb
photo-306e...jpg  4,516,955 bytes  42b9ae4f8d05495f699c46d2e1d848a3da6856d87139b8185d2b9fb8cec6fe87
~~~

Force-stop, explicit `MainActivity` relaunch, and normal same-PDF reopen
restored all page domains; the photo gallery showed `Photos (2)`. Post-relaunch
snapshot semantic equality and both exact photo hashes remained unchanged.
Evidence is preserved under
`outputs/stage6-android-qualification/repair-*.png`,
`outputs/stage6-android-qualification/repair-export-final-inspection/`,
`outputs/stage6-android-qualification/postrepair-import-*.json/jpg`, and
`outputs/stage6-android-qualification/postrepair-relaunch-*.json/jpg`.

Stage 6 remains pending formal independent Reviewer and Terra closure. This
entry records current Coder/qualification evidence only and does not issue a
Stage 6 or final qualification verdict; Stage 7 remains pending.

## Stage 6 final closure — Reviewer and Terra PASS (2026-08-30)

The same uncommitted candidate based on baseline
`ea0f31f7fb6a580dfc116bf39acf04a1e66e2759` is now closed/passed for Stage 6:
Reviewer Halley returned **PASS**, the bounded Foreman review returned
**PASS**, and Terra Chandrasekhar returned **PASS**. The current qualification
APK SHA-256 is
`C28357C6DA15C2C4AAB5E4CDC0EEAA7B8D66594E12BA9AC5CE64195C236BDD06`.

The existing evidence above remains authoritative: focused Stage 6 plus
adjacent Stage 4/5 validation passed with 131 tests, 3 skips, and 0 failures;
the full unit suite passed with 295 tests, 3 skips, and 0 failures; assemble,
lint, and the connected package-context test passed; and the HNY0DSR8/TB336FU
fresh-install functional round trip proved the self-contained export/import,
all-domain semantic equality, exact two-photo byte/hash restoration, and
force-stop/relaunch/reopen persistence. No Stage 7 work was started.
