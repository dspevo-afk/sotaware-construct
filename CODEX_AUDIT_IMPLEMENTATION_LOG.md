# SOTAware Construct Implementation Log

## Independent review accepted and publication authorized, 2026-09-11

The owner accepted the separate direct independent review and requested commit/push.
Repair-only candidate SHA-256: `9f6a5f419a95d15b61e991c8ccbf68ede2f170bbfb5c684b32ec70259cebf049`.
Review result: PASS, no scoped blocking findings. This is not a spawned Luna approval.
Fresh reviewer JVM evidence: 895 collected, 889 passed, six capability skips, zero
failures/errors; both APK assembly gates pass using unchanged up-to-date outputs.
Lint report: zero errors and 80 warnings, with unchanged analysis inputs reused.
The author's native/SAF evidence was inspected and reused, not independently rerun.
All 271 input hashes and both APK hashes matched again before publication.
The preserved housekeeping diff was inspected; archived roadmap/log bytes match HEAD.
Evidence: OS-temp `construct-lifecycle-independent-0gh_nhdk/independent-review.md`
and `construct-publication-audit-w0nl9aqw`. The earlier review/publication holds below
are historical. Broader Stage 10 deployment qualifications are still open.

## Activity-recreation handoff repair, 2026-09-11

Status: IMPLEMENTED LOCALLY; host/native/SAF validation PASS; independent review BLOCKED.
This supersedes the unresolved lifecycle diagnosis below, not the broader release gates.

The new Activity's coordinator could read absent/older disk state while its predecessor
was still flushing the retained ViewModel. Applying that result cleared the new note and
undo history. Two controlled real-repository omission probes reproduce the live-state
loss when handoff admission is absent; the corresponding permanent regressions pass.

A ViewModel-owned host handoff now joins predecessor save/cleanup before replacement
resolve/load/clear. Failed saves block admission and remain retryable; late old owners
cannot close a newer host. Callback rebinds receive a final flush even without ON_PAUSE.
Restored viewer navigation also waits for the existing verified document/page readiness
predicate. Native helpers wait for the actual decoded PDF gesture surface, not merely
the toolbar. Original assertion lines remain intact, with no arbitrary readiness sleeps.

Final fresh gates: debug and test APK builds PASS; JVM 895 collected, 889 passed,
six capability skips, zero failures/errors; lint zero errors/80 warnings. Fresh synthetic
API 36.1 full native suite: 82 collected, 75 passed, seven skips, zero failures. All five
sequenced SAF workflows then execute and pass without skips. The original recreation
case also passes eight additional invocations. The two remaining native omissions are
hard-link capability and opt-in live-provider cases. Eleven new JVM cases pass.

All 271 final source/build/test/fixture hashes and both APK hashes were verified after
native execution. Earlier failed native/readiness runs and red/green probe outputs are
retained separately; they are not relabeled as passing. The tablet and real Drive data
were untouched. No dependency, document format, signing or release configuration changed.

Normal Luna and the documented Reserve route both returned usage-limit errors before
inspection. Direct author review is complete, but no fresh independent PASS is claimed.
The repair remains uncommitted; the existing staged housekeeping is preserved. No main
merge, push, release or success email occurred during this repair. Publication is held
for independent review, with broader Stage 10 release qualifications still open.

Evidence: OS-temp `construct-lifecycle-repair-qjtbuol1`, especially `checkpoint.json`,
`repair-report.md`, `matrix2-junit`, `no-handoff-state-loss-junit`, and the final
`native-final/native-checkpoint.json`. The full command ledger and APK identities are
external evidence; historical implementation chronology remains in its existing archive.


## Branch integration and housekeeping, 2026-09-11

Status: IMPLEMENTED LOCALLY; PUBLICATION BLOCKED by fresh native lifecycle evidence.
This is internal development integration, not Stage 10 release qualification.

The reviewed Stage 10 candidate is preserved as `2e64182`. Historical main and
both documentation branches were reconciled using normal local merge commits.
Earlier Stage 0-2 branches were already ancestors. The current roadmap/format
policy is retained; every original commit remains reachable. Main and GitHub
were not advanced. No branch deletion, history rewrite, force push or email.

Housekeeping removes 24 captured logs/screenshots, two obsolete highlight-analysis
scripts and one stale commit-instructions note. Eleven generated cache/IDE files
are untracked while their local copies are preserved. Ignore rules prevent these
artifacts from returning. Complete prior roadmap/log chronology is archived
byte-for-byte; short root indexes and README links retain the active contracts.

All optional Android resource deletions were withdrawn. All 268 application,
resource, build, fixture and test inputs match the earlier reviewed candidate.
No runtime code, resource, dependency, identity, persistence or sync change remains.

## Final validation

Fresh host gates PASS: debug and test-APK assembly; 884 JVM cases, 878 passed,
six capability skips, zero failures/errors; lint zero errors and 80 warnings.
Seven version-check advisories absent offline are not claimed fixed dependencies.
Static archive, ignore-rule, documentation-link, YAML and XML checks pass.

Fresh-install native suite FAIL: 82 collected, 74 passed, seven skipped, one failure
in `HistoryLifecycleInstrumentedTest.activityRecreationPreservesTheSameDocumentUndoOwner`.
The same ViewModel survived, but the new note was absent and undo unavailable at
the assertion; history epoch changed from 2 to 3. Permanent loss, transient state
or a test-timing root cause has not been established. Publication remains held.

The rebuilt debug APK differs only in classes4.dex from the prior tablet artifact;
the disassembly delta is confined to generated Compose stability initializers.
No byte-identical APK or reused final native PASS is claimed. The test APK is unchanged.
Five SAF phases were NOT RUN in this task after the native failure.

Earlier exploratory resource-removal failures are preserved separately: a dialog
visibility assertion, an unchanged focused pass and a full rerun on reused durable
fixed-URI fixtures. That rerun was not clean-install evidence. No test was weakened.
The optional resource changes were restored before the final fresh-install run.

## Qualification and history

Signed release install/upgrade, broad live-provider/account qualification,
Pixel-specific smoke and actual restore/device transfer remain OPEN. The tablet,
real Drive data, pre-existing emulator and detached worktrees were untouched.
See [current qualification](STAGE10_QUALIFICATION.md) and [roadmap](CODEX_AUDIT_ROADMAP.md).

The complete [implementation chronology](docs/archive/CODEX_AUDIT_IMPLEMENTATION_LOG_2026-09-11.md)
and [roadmap history](docs/archive/CODEX_AUDIT_ROADMAP_2026-09-11.md) remain available.
Current evidence is in OS-temp `construct-housekeeping-dovcfsc7`, including the
checkpoint, original branch bundle, candidate patch and `native-restored-fresh.log`.
Investigate the lifecycle failure before finishing the prepared integration.
