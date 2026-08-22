# SOTAware Construct Audit Working Roadmap

Canonical roadmap: `SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md` (documentation commit `adbee4eeb0c990226a7f9e887f719a2dbcd9105d`).

This file tracks implementation status only. The canonical document remains the source of truth for the complete remediation project.

| Stage | Status |
| --- | --- |
| Stage 0: Establish reliable gates | complete |
| Stage 1: Create one canonical document snapshot | complete |
| Stage 2: Replace local persistence safely | pending |
| Stage 3: Make document switching transactional | pending |
| Stage 4: Replace synchronization with one serialized coordinator | pending |
| Stage 5: Harden filenames, payloads, and photo transactions | pending |
| Stage 6: Make import/export current and self-contained | pending |
| Stage 7: Fix rendering and OCR | pending |
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
- Device smoke remains unavailable. No Stage 1 remote Actions run was available because the branch was not pushed. Stage 2 is the next recommended assignment; it was not started.
