# Stage 10: integrated final qualification

Status: IMPLEMENTED; local qualification passed; broader final qualification remains OPEN.
This record does not approve employee distribution or a public release.
Baseline: `e5b797d286c0259b10839943b28ce04e2bfa0487`.
Branch: `codex/stage-3-transactional-switching`.
The starting worktree was clean. The user requested implementation of the next stage.

## Scope and unchanged acceptance boundaries

Stage 10 is qualification, not another feature rewrite. The canonical final matrix in
`SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md` remains authoritative. The Stage 9B
current-format-only decision replaces historical migration obligations with explicit,
non-destructive retired-format rejection. Current recovery and identity guarantees remain.

The internal-company decision does not silently waive the broader Stage 10 release,
physical-device, provider, backup or device-transfer gates. Evidence below distinguishes
implemented and exercised behavior from outstanding environment or deployment prerequisites.
No real project document, original tablet installation, signing identity, Google account,
or real Drive file is a disposable fixture. No public/private release is authorized here.

## Added qualification coverage

- Two existing, different `plan.pdf` fixtures are copied byte-for-byte into Android test
  assets and served by the existing read-only qualification provider. No production
  provider, schema, application state owner or dependency is changed.
- `Stage10DocumentIsolationInstrumentedTest` edits both same-name PDFs through real note
  dialogs, checks independent IDs/fingerprints and exact disk state, alternates the actual
  recent-drawing selector six times, and immediately switches after another edit. New
  Activity owners must reopen exactly the correct notes. Initial openings use the existing
  debug fixture intent; the switching path uses real application controls, not direct
  coordinator/reducer calls. This is not a claim that an Activity restart is process death.
- `Stage10BackupPolicyInstrumentedTest` checks the installed backup flag and compiled
  cloud/device-transfer/legacy exclusion resources, including device-protected domains.
  Configuration checks are not a cloud restore or OEM device-transfer test.
- Existing Stage 7-9B native suites and five independent SAF phases remain the oracles
  for rendering/OCR, memory, storage, camera, annotation parity, export/import and actual
  process death. Their assertions are not weakened or replaced by package-install smoke.

## Evidence ownership

Raw command output, XML, source and APK hashes, test sources, and gate disposition are
retained outside the repository in OS-temp `construct-stage10-r_ua71pr`.
The single source of task execution state is its `checkpoint.json`.
A new task-owned `ConstructStage10` Android 16/API 36.1 emulator is isolated from the
pre-existing Stage 9B emulator and the populated physical tablet. Synthetic app data on
this new emulator is disposable. No pre-existing app data may be cleared for this task.

## Gate ledger

The final gate ledger and closure disposition are appended after execution. Until then,
no unlisted check is passed, and no prior candidate evidence is relabeled as a fresh run.

## Executed candidate and disposition, 2026-09-11

**IMPLEMENTED; local qualification passed, broader Stage 10 qualification remains open.**
Production code is unchanged from the baseline. All 268 frozen source/build/test inputs
and both APK hashes matched when the interrupted task was resumed and after SAF execution.
The tests below executed during this Stage 10 task; resumption verified their saved XML
and exact inputs rather than unnecessarily rerunning the unchanged full matrix.

| Gate | Actual result |
| --- | --- |
| Debug and Android-test APK assembly | PASS, incremental; not a clean rebuild claim |
| Fresh full JVM suite | 884 collected; 878 passed; six existing capability skips; zero failures/errors |
| Fresh lint | Zero errors; 87 warnings |
| New Stage 10 Android cases | 4/4 passed separately and again within the full suite |
| Full isolated Android suite | 82 collected; 75 passed; seven explicit skips; zero failures/errors |
| Five separately selected actual SAF phases | 5/5 executed and passed without skips |
| Same-name drawing and rapid UI switching | Real note dialogs, recent-drawing selector, independent IDs/fingerprints and durable readback passed |
| Fresh-install bundle import and process recovery | Complete current state/photo hashes restored; target absence checked before install; relaunch asserts a different PID |
| Retired bundle rejection | Actual SAF path preserves the input archive and current state |
| Cropped/rotated PDF export | Actual SAF output validation passed |
| Large/scanned/cropped/rotated rendering and OCR | Existing functional cases reran in the full native suite |
| 100 MiB photo envelope | Peak incremental Java 80,031,920 bytes; native 28,633,632 bytes; maximum read 65,536 bytes; within fixed budgets |
| Installed backup configuration | Backup flag and compiled legacy/cloud/device-transfer exclusions passed |

Five full-suite skips were the deliberately separately sequenced SAF phases; all five
subsequently executed. The remaining native skips are an Android hard-link capability
case and the opt-in live-provider case. They are not represented as passing tests.

## Remaining broader qualification gates

| Gate | Disposition and missing evidence |
| --- | --- |
| Physical Pixel smoke | NOT RUN: no connected Pixel; the populated tablet was not substituted or modified |
| Signed release install and upgrade | BLOCKED: verifySotawareReleaseSigning exited 1 because required external signing configuration is incomplete |
| Fresh live-provider/account/conflict matrix | NOT RUN: no real account or Drive mutations in this task; older scoped evidence remains historical |
| Cloud restore and actual device transfer | BLOCKED/NOT RUN: runtime rejected the combined device preflight; installed-policy tests do not prove restore/transfer behavior |
| Final publication and clean committed tree | NOT RUN: full qualification remains open; changes intentionally remain local |

No passing executed product test was weakened. The initial SAF launcher failed before
any workflow because Gradle had already uninstalled the target; an exact package-presence
check corrected that harness assumption. A subsequent Python child stalled before its
first runner command. Its recorded process handle was verified and reaped; the unchanged
runner then completed in the recovered interpreter with all original AVD/package/hash
safety checks. The failure logs and corrected runner are retained in the task evidence.

Review: direct self-review of the qualification tests and source/evidence consistency;
no independent worker review or production-code change is claimed. No commit, push,
release, distribution or completion email occurred. This is not a full Stage 10 PASS.

## Handoff and resource reconciliation

The owned `ConstructStage10` emulator was stopped normally and verified absent from
ADB. Pre-existing emulators, physical devices, apps and data were left alone. Task-owned
build/test workers have exited; the stalled SAF child was reaped through its recovered
process handle. Raw logs, APKs and isolated AVD backing files remain outside the repository.

The external `checkpoint.json` records complete command arguments, environment, result
codes, source/APK hashes and resource ownership. `saf-checkpoint.json` contains all five
executed workflow results and their actual ADB commands; the runner is `run-saf.py`.
`stage10-candidate.patch` preserves the entire local candidate, including new tests/assets,
without changing the real Git index. Its SHA-256 and final worktree state are in the
checkpoint. Broader missing gates must be completed before a full Stage 10 closure claim.


## Final direct review and physical-tablet qualification, 2026-09-11

The user-requested final review found no new blocking source defect in the Stage 10
qualification candidate. Production and test sources were not changed by this review.
The broader Stage 10 gate remains OPEN; this is not release or distribution approval.
This entry supersedes the earlier tablet-untouched/current-review statements for this
new task only. The prior execution history and its limitations are preserved above.

With explicit user permission, the installed SOTAware app on TB336FU / Android 16 /
API 36 was uninstalled. The exact frozen debug and test APKs were then installed.
All 268 executable/source/fixture inputs and both APK hashes matched before and after
the new tests. No device account, real Drive file, unrelated app or emulator was changed.

| Fresh final-review check | Result |
| --- | --- |
| Debug and Android-test APK assembly | PASS, incremental; no clean-rebuild claim |
| Full JVM suite | 884 collected; 878 passes; six capability skips; zero failures/errors |
| Lint | Zero errors / 87 warnings; unchanged analyses reused, report regenerated |
| Full physical-tablet suite | 82 collected; 75 passes; seven explicit skips; zero failures/errors |
| Four new Stage 10 cases | All four executed and passed on the physical tablet |
| Five separately selected actual SAF workflows | All five executed and passed; zero skips |
| Supported photo-envelope memory/read budgets | Existing native assertions executed and passed |
| Targeted platform backup request | App rejected with `Backup is not allowed` |
| Release-signing prerequisite | FAIL, exit 1: external signing configuration is missing |

Five native skips were the separately sequenced SAF workflows and were subsequently
executed successfully. Only the hard-link capability case and opt-in live-provider
case remained unexecuted on the tablet. SAF checks covered cropped/rotated PDF export,
complete bundle export, fresh-install import, distinct-process durable recovery, and
non-destructive retired-bundle rejection with unchanged production assertions.

Backup rejection is not a cloud-restore or device-transfer result. The roadmap's Pixel
check, signed-release install/upgrade, fresh live-account/provider qualification, and
actual cloud/OEM transfer remain open. No new numbered stage follows Stage 10.
No Luna worker review, commit, push, release, distribution or completion email is claimed.

The main app was left freshly installed and launched; the instrumentation package and
synthetic test data were removed. The installed APK hash matches the frozen candidate.
The launcher screenshot shows an empty Recent Drawings screen. Build/test processes
exited normally; the slow host launcher completed without termination or a retry.
Evidence: OS-temp `construct-stage10-final-review-qlqc6e59`, especially `checkpoint.json`,
`tablet-full.log`, `tablet-saf-checkpoint.json`, `host-junit`, and `final-review.md`.
