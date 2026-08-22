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

The final independent review initially reported the regenerated wrapper/cache artifacts as the only blockers. After cleanup, the reviewer re-checked the tree and found only one documentation correction: the roadmap’s stale pending completion label, which was corrected before staging. The remaining historical-stream, durable-preferences, OCR-quality, embedded-text-extractor, and remote-CI limitations were accepted as documented Stage 0 limitations and deferred to the applicable later stages. No Stage 1+ scope creep was found. No push was performed.

## Known remaining failures and deferred issues

- The 77 non-blocking lint warnings are enumerated above; they remain for later cleanup and were not suppressed.
- All Priority 0 through Priority 3 application remediation remains deferred. Stage 0 must not implement `DocumentSnapshotV1`, UUID identity, persistence replacement, sync redesign, auth migration, OCR/rendering redesign, import/export redesign, reducer architecture, responsive UI redesign, or broad `MainActivity.kt` decomposition.
