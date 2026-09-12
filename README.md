# SOTAware Construct

Internal Android app for construction drawings: PDF viewing, annotations,
measurements, photos, OCR/search, local storage, bundles, and Google Drive sync.

## Current status

The Activity-recreation note/undo repair has passed independent review. Fresh host,
synthetic Android and actual file-picker checks pass; the owner authorized publication.
**This is not a release or distribution approval.**
Signed-release install/upgrade, the broader live-account/provider matrix,
Pixel-specific smoke, and actual cloud/device-transfer qualification remain open.
See [Stage 10 qualification](STAGE10_QUALIFICATION.md) for evidence and limits.

The installable app ID is `com.sotaware.construct`. The implementation namespace
remains `com.example.myapplication`; do not rename persistence types or packages
as a cosmetic cleanup. Unsupported older document formats are rejected rather
than silently migrated. Current-format recovery and data integrity are required.

## Build and test

Use the checked-in Gradle wrapper, a supported JDK, and Android SDK 36.
Configure the local SDK in an ignored `local.properties` file or the environment.
Do not commit local paths, signing keys, passwords, accounts, or documents.

```powershell
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:assembleDebug
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:testDebugUnitTest
.\gradlew.bat --no-daemon --stacktrace --console=plain :app:lintDebug
```
On Linux/macOS use `./gradlew` with the same arguments. Android instrumentation
requires an authorized, explicitly selected test device. Some SAF tests require
separate sequenced invocations and fresh installs; a plain suite run skips them.
Never clear an installed app or use real Drive documents as disposable fixtures.

## Project documents

- [Working roadmap](CODEX_AUDIT_ROADMAP.md) and
  [current implementation log](CODEX_AUDIT_IMPLEMENTATION_LOG.md).
- [Canonical audit and acceptance plan](SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md).
- [Current-format and photo synchronization contract](STAGE9B_CONTRACT.md).
- [Authentication and release setup](STAGE9_AUTH_RELEASE_SETUP.md) and
  [internal-scope decisions](STAGE9_ACCEPTANCE_DECISIONS.md).
- [Historical implementation evidence](docs/archive/README.md).
- [Repository operating instructions](AGENTS.md).

## Repository hygiene

Keep synthetic test fixtures under the existing test source/resource directories.
Captured logs/screenshots, machine-specific IDE state, and local build caches do
not belong in Git. Use a unique directory outside the repository for substantial
scratch work. Keep the committed roadmap/log concise; archive prior chronology
without losing acceptance decisions, limitations, or evidence references.
