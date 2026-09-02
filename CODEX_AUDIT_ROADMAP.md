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
| Stage 7: Fix rendering and OCR | complete — StrictMode qualification and budget-aware photo decoding validated |
| Stage 8: Repair search, annotation actions, and responsive UI | pending |
| Stage 9: Privacy, authentication, release, and cleanup | pending |
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
- No Stage 7 work has started. The detailed candidate evidence and exact validation record are appended to `CODEX_AUDIT_IMPLEMENTATION_LOG.md`.
