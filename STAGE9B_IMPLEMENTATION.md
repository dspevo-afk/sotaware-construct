# Stage 9B implementation and qualification

Status: Post-publication correction implemented and locally qualified; fresh independent review and publication are pending. The original Stage 9B publication is `0d2156b`; the correction below supersedes its three reviewed defects. Stage 10 is not started.
Baseline: `69019f4440e9704e12e59332b845144dce1788f1` on `codex/stage-3-transactional-switching`.
The starting worktree was clean. The user authorized implementation, normal commits/pushes,
and completion or interruption email. The continuation explicitly authorizes the development
tablet for destructive SOTAware Construct app-local qualification, and real Drive only inside
a clearly isolated disposable test folder. Unrelated device data and real project documents
remain protected.

## Controlling task and current-format policy

This is the expanded Stage 9B requested after the independent 69019f4 review.
Backward compatibility is not required: obsolete binary/JSON migration, inline Drive payloads,
legacy serialization and source-compatible alternate mutation APIs are to be retired.
Unsupported old inputs must be rejected explicitly and preserved, not silently loaded as empty,
automatically migrated, overwritten or deleted. Current atomic saves, previous-good recovery,
quarantine, rollback, journals, source/session fences and undo-retained photo bytes remain required.
The user-supplied Stage 9B prompt is the full acceptance contract; this file records its execution.

## Implementation checkpoints

- **9B.0 admission:** reproduce and repair blank/oversized PDF notes, detached image note/shape
  targets and invalid updates; preserve valid controls. Reject malformed text/geometry/IDs,
  duplicate identity, stale/unsupported targets and exceeded budgets before state/history/effects.
  Distinguish accepted, unchanged and rejected commands; invalid Save retains dialog/input.
- **9B.1 current model:** stable IDs for notes, paths and measurements, explicit surface ownership,
  immutable committed values and stale-command checks. Share PDF/photo note and shape semantics
  with intentional surface capabilities. Define source-space coordinates and font/stroke/size
  units independent of zoom, density, sampled bitmap size or a legacy 800px reference.
  Bump incompatible schemas and reject retired versions across local/bundle/Drive/outbox owners.
- **9B.2 mutation/history:** mandatory current-session reducer on all editable routes; remove
  HistoryAction/dual stacks/arbitration and nullable direct-mutation fallbacks after migration.
  Preserve bounded chronological history, lifecycle retention, replacement epochs, exact failed
  switch rollback, stable selections, one gesture/undo step and exactly one semantic effect.
  Photo commits must be observable without manual redraw counters; capture/apply stays singular.
- **9B.3 display/export:** shared shape geometry, transforms, sizing, note text layout and bounds
  for viewer, hit-test and export, including multiline/bold/rotated text and consistent arrows,
  clouds and fill/stroke. UI selection handles may be screen-sized and are not exported.
  Qualify crop/rotation, EXIF, zoom/density/sample changes and actual rendered export.
- **9B.4 immutable assets:** bounded versioned manifest with exact identity/revision and immutable
  asset descriptors; no inline photo Base64 and no whole-set byte arrays through capture/outbox/
  transfer/recovery. File-backed immutable content, scoped durable resumable state, bounded
  buffers/concurrency, all assets before authoritative publication, zero unchanged-photo upload
  bytes, integrity/existence checks, idempotent ambiguous completion and exact canceled handoff.
  Reuse coordinator, leases, outbox, transaction barriers and reachability/recovery authority.
  Preserve per-file/count and 100 MiB aggregate limits; do not just raise the JSON cap.
  Missing photo persistence capability must not default to success. Current bundles remain
  self-contained/offline and use the same validators/assets with truthful SAF completion.
  Conservative collection preserves current/previous-good/live/history/pending/camera/export/
  in-flight ownership; unknown reachability never permits deletion, including remote assets.
- **9B.5 retirement:** remove proven obsolete Drive display-name APIs/implementations/timer state,
  LegacyPageDataCodec/V0 JSON/PageData adapters, local markup/scale/photo migration and obsolete
  OCR/search/thumbnail wrappers after current callers are migrated. Keep live ToolOptionsSheet
  and legitimate current state-saving behavior. Fix typed bounded recents by successful open
  timestamp and stable identity, with no fabricated migration timestamps. Update current docs.

No framework/dependency migration, generic plugin framework, multiplayer, broad MainActivity
rewrite, package/module reorganization, signing/public distribution, Git cleanup/history rewrite,
unrelated user-data wipe or unrelated backlog is part of this task. Target-app uninstall,
reinstall, clear-data and process-death tests on the authorized development tablet are in scope.

## Execution ownership

ChatGPT/Astra owns architecture, shared-file integration, expensive build/device gates,
independent review adjudication, exact-path staging, commit, normal push and email.
Workers are leaves with exclusive assignments; MAX reasoning and default service tier
are requested through the installed CLI. Earlier recovered workers used `gpt-reserve` after the ordinary allowance was exhausted.
The current continuation is solo: no new agents were launched. Existing independent reviews
remain scoped to their inspected source; the subsequent Drive protocol and live-harness delta
has no fresh independent review. No independent PASS is claimed for those repairs.

## Qualification and evidence

Required: admission red/green; PDF/photo parity and identity/history regressions; actual Android
dialog/gesture/overlay/lifecycle/export checks; current-format restart and fresh-install bundle
SAF round trip; deterministic real-adapter interruption/resumption/conflict/cancellation/disk
failure/ambiguous-completion tests; Android supported-envelope memory and byte measurements.
A separately authorized synthetic live-provider fixture is required for live-transfer qualification.
No real user documents may be used as disposable fixtures. Missing live credentials/fixture
authority must be reported BLOCKED, not represented by mocked HTTP evidence.

Final integrated gates: assembleDebug; freshly executed testDebugUnitTest; lintDebug with zero
errors; assembleDebugAndroidTest; relevant and full connectedDebugAndroidTest on a task-owned
isolated emulator. One expensive matrix at a time. Fresh independent Luna integrated review
and post-repair delta review are required. Record candidate identity, skips, actual commands,
source/test manifests and retained evidence. Retired tests require safety-assertion mapping.
Prior 569-JVM/91-warning evidence belongs to the baseline, not changed Stage 9B code.

## Current progress

The original uncommitted integration and all original worker results were recovered in place.
No reset/clean or second implementation occurred. The authorized tablet has a development installation and normal Google authorization.
Real provider tests created only uniquely named disposable Stage9B folders; their exact resource
records are retained outside the repository. Final integrated matrix45 passes 685 JVM cases
(679 executed, six capability skips), zero failures/errors, both APK builds, and zero-error lint.
The final live-provider run passes upload, streamed readback, unchanged-photo identity and bytes,
and Keep Local conflict preservation. Final isolated-tablet qualification has now passed; a fresh independent
post-repair review remains open. No whole-stage pass or publication is claimed.
Historical matrix6 failures are retained in external evidence, not treated as the current state.

The integrated repair now includes typed accepted/unchanged/rejected mutations, initialized-page
and scale admission, immutable nested history values, ready-session renderer binding, canonical
selection rebinding, and unconditional PDF/photo gesture cancellation cleanup. Shared text
layout centers actual RTL/mixed-direction line extents and uses the same visual bounds for
rendering and hit tests. Obsolete unreferenced OCR/cache and zero-on-error parser wrappers are
removed; current source/session-fenced routes remain.

Remote publication reserves durable exact resource IDs, verifies the accepted scoped manifest,
and preserves canceled-after-commit local acknowledgment. Download/verification staging is owned
per attempt; terminal coordinator cleanup releases only process-owned outbox leases while keeping
unacknowledged durable data. Raw local manifests are bounded and validated before DTO decoding.
Test-only fake transport/inline fixture assets now reside in JVM test sources, not production.

A populated fractional-coordinate manifest regression exposed a Gson Float-versus-parsed-decimal
structural comparison bug. The red canary failed against the compiled candidate while five empty
controls passed. Wire-normalized comparison retains strict raw-field validation and digest checks;
all six codec controls and both direct real-HTTP-adapter handoff controls then passed. These are
focused diagnostics, not substitutes for the complete final matrix. Synthetic Windows HTTP
fixtures explicitly supply the preexisting test filesystem seam; no native durability is claimed.

Native source now includes real production dialog/no-op/cancellation checks, pointer-driven stroke
cancellation, foreground-sensitive rendered geometry oracles, supported-envelope transfer-memory
checks, and five separate actual SAF/fresh-target-install/relaunch/rejection phases. The test
DocumentsProvider keeps artifacts/oracles in its own APK storage. A separate test-only fixture
control endpoint verifies the exact signed debug-target UID; the DocumentsProvider retains its
normal permission boundary and all measured source/import/export grants come from DocumentsUI.
The current-production emulator suite and all five actual SAF phases passed. The earlier direct fresh-target tablet attempt was blocked.
The later isolated-tablet full suite and five actual SAF phases passed; see the final ledger below.

### Baseline admission regression evidence

On an isolated source archive of exact `69019f4`, the seven new admission tests compiled
and executed: five expected failures (blank PDF note, oversized PDF note, invalid PDF
note update, unattached image note and shape) plus two passing valid/no-op controls.
No production repair was present in that archive. Raw XML and command log are retained
in the task-owned external evidence directory; these failures are baseline evidence.

### Isolated Android harness

A new task-owned API 36.1 emulator was created from SDK system images, without copying
user AVD data. The exact baseline package-context instrumentation ran 1/1 successfully
with `ANDROID_SERIAL=emulator-5580`. This qualifies the execution harness only, not Stage
9B behavior. The connected physical TB336FU device was excluded from that earlier baseline run.
Its use is now authorized for the continuation; current device results must be recorded separately.

## Solo provider repair and qualification checkpoint, 2026-09-10

Two real-provider defects were repaired without resetting the original worktree:
Drive file `size` uses a strict decimal int64 JSON string, not a JSON number;
and manifest/adoption updates require the provider's verified conditional-write endpoint.
`DriveConditionalWrites` confines the v2 metadata/PUT surface to real issued ETag preconditions.
Native disposable-provider probes verified current-tag acceptance and stale-tag rejection;
v3 discovery, creation, media reads, and immutable-photo transport retain their existing owners.
This is a provider protocol adaptation, not support for retired application formats.

Provider-format red regressions, strict malformed metadata/ETag/bounds checks, no automatic
write retries/redirects, and exact response ownership are preserved in focused JVM evidence.
An in-place tablet upgrade recovered a failed durable upload with the same manifest/photo IDs,
the exact frozen snapshot digest, an advanced accepted cursor, and cleared pending state.
The subsequent fresh-install live test used normal Google authorization and actual SAF/UI.
The harness now waits for accepted metadata to settle beyond the production three-second
edit debounce before introducing an external writer. Manual completion alone is not queue idle.
No production conflict, identity, or conditional-update assertion was relaxed.

Evidence lives in the existing external continuation directory, under
`solo-resume-20260909-230509`; exact local paths remain in the external checkpoint only.
`final-integrated45/source.json` identifies the final executable sources. Later changes to
this record, roadmap, and implementation log are documentation-only. Both task-only native
provider probes were archived outside the repository and removed before this final matrix.

| Gate | Actual evidence and disposition |
| --- | --- |
| Fresh JVM, debug APK, Android test APK, lint | `final-integrated45`: PASS; 685 JVM cases, 679 executed, six skips, zero failures/errors; lint zero errors and 87 warnings. |
| Provider protocol red/green | `provider-int64-red26`, `conditional-fixture-tests39`, and final matrix45; real current/stale ETag survey retained separately as diagnostic evidence. |
| Pending recovery without clearing app data | `tablet-in-place-recovery40`, `in-place-recovery40-proof.json`: PASS, with exact manifest bytes independently fetched from the disposable Drive resource. |
| Full isolated emulator | `connected-emulator40`: PASS, 66 executed and seven conditional/capability skips; zero failures/errors. Production is byte-for-byte source-identical to matrix45. |
| Actual five emulator SAF phases | `emulator-five-saf40`: five passes, zero skips/failures. Relevant production and SAF test sources are unchanged in matrix45. |
| Emulator 100 MiB memory envelope | Four 25 MiB photos; Java delta 105,103,360 bytes, native delta 31,616,032 bytes, max read 65,536 bytes: PASS. |
| Fresh-target live Drive and conflict | `tablet-live44-fresh-settled`: one executed pass, no skips; upload/readback, annotation-only update, unchanged photo identity/bytes, and Keep Local preservation. |
| Live unchanged-photo upload byte count | NOT MEASURED over live HTTP. Zero upload bytes are proved by the deterministic real-adapter transport test, not inferred from live IDs. |
| Final full tablet and five actual SAF reruns | BLOCKED before launch by remote execution safety rejection. Prior matrix18 tablet passes are retained but not relabeled as final candidate45 execution. |
| Fresh independent post-repair review | NOT RUN under the explicit solo continuation. Prior reviewer PASS does not cover the new Drive protocol and live harness delta. |
| Git/publication | Uncommitted on original branch and HEAD; no reset/clean, commit, push, release, or success email. |

The failed live41 conflict setup overlapped the app's own debounced upload; a read-only
scoped probe confirmed stable v2/v3/list cursor agreement. The settled, fresh-target live44
run passed without changing production cursor checks. A populated-fixture rerun failure and
an operator authorization timeout remain recorded as test/setup failures, not suppressed.
The final fresh-tablet action was rejected before executing; no alternate destructive route
was attempted. Disposable provider fixtures and their exact cleanup records remain retained.
Final completion requires the outstanding tablet qualification and one permitted independent
delta review. Stage 10 remains unstarted; this checkpoint is not a publication authorization.

## Safe tablet qualification and final fixture cleanup, 2026-09-10

This later entry supersedes the tablet blocker in the previous checkpoint, not the
independent-review requirement. No original-app wipe was retried. A separate task-owned
QA application and test APK used a distinct application ID, provider authorities, and
DocumentsUI fixture label. The isolated copy was outside the repository; all production
source was hash-identical. Ten build/test-only identity substitutions are recorded in
`solo-qualification-20260910-013649/isolated-final-delta47.json`.

| Final gate | Evidence and result |
| --- | --- |
| JVM and APK builds | `final-fixture-cleanup47`: 685 cases, 679 executed, six capability skips, zero failures/errors; debug and Android test APK builds pass. |
| Lint | `lintDebug` completed up-to-date with unchanged production inputs; explicitly reused report has zero errors and 87 warnings. |
| Final full tablet suite | `tablet-isolated-final47`: 66 executed passes, zero failures, seven conditional/capability skips. |
| Five actual tablet SAF phases | The same final pipeline separately passes rendered PDF export, complete bundle export, fresh-install import, process-death relaunch, and retired-format rejection; five passes and no skips. |
| Exact original-package checks | `tablet-default-cleanup47`: 11 executed passes and one platform hard-link skip; actual default package, FileProvider, storage, descriptor ownership, outbox security, and memory. |
| Emulator delta | `emulator-cleanup-delta47`: all four affected native tests pass. Full emulator40 and five SAF40 evidence are reused for unchanged paths. |
| Physical supported memory envelope | Exact production APK: 104,857,600 logical photo bytes; peak incremental Java 78,557,784 bytes, native 35,180,752 bytes, maximum producer read 65,536 bytes; all limits pass. |
| Hard-link capability distinction | The Android hard-link fixture is skipped by platform permission; the actual Windows hard-linked staging regression executes and passes in final JVM47. The six JVM skips are individually recorded. |
| Real Drive continuity | Production APK is byte-identical to live44/final45; the live harness is unchanged. Final test APK differs only in two unrelated native test-cleanup helpers. Prior live44 and in-place pending-recovery40 evidence remain scoped and valid. |

The exact-package boundary run exposed a test-only cleanup race: asynchronous
SharedPreferences writes could recreate a UUID-scoped fixture preference after its deletion.
Both storage/lifetime helpers now synchronously drain queued writes before deleting their
own test preference. The final default-package rerun creates no new residual preference files.
All 17 pre-existing durable application files remain byte-for-byte unchanged.

Four preferences created by the earlier test remain, each containing only
`restore_google_session=false`. Their names, content hashes, creation windows, and backups
are recorded externally. Their exact deletion was rejected by the runtime and was not retried
through another route. They are nonblocking test residue, not modified user documents.
The isolated provider oracles and both APK pairs are retained outside the repository.

The remaining publication blocker is one fresh independent read-only delta review covering
14 files: the prior provider/harness repair scope plus the two native cleanup helpers.
No new subagents were launched; root inspection and passing tests are not labeled an
independent PASS. The scope and hashes are in `independent-review-source47.json`.
No commit, push, release, or success email occurred. Stage 10 remains unstarted.

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

## Post-publication integrity correction, 2026-09-10

Baseline: `0d2156bd0f29b99bd2cf6358f4e0a28ee68d1624`, original branch
`codex/stage-3-transactional-switching`, initially clean worktree. The user authorized
repair of the three review findings and Stage 9B closeout. Stage 10 remains unstarted.
Disposition: Stage 9B correction qualification CLOSED/PASS. The three original findings
and review followups are repaired; final matrix5, native5, all five actual SAF5 phases and
independent narrow review3 pass. Publication is authorized on this exact qualified source.
Commit/push, exact-SHA CI and success-notification identities are recorded in Git/GitHub
and the external current checkpoint after publication creates them. Stage 10 is not started.

### Repairs and recovery boundary

- Manifest adoption now reconciles committed server failures, lost responses, cleanup
  failures and invalid acknowledgements through exact scoped canonical readback and a
  stable ETag. Explicit HTTP 412 is terminal for that attempt; PUT is never blindly replayed.
- Production photo-pool admission conservatively collects obsolete indexed transient
  copies before applying the unchanged disk/count limits. Input handles, every live
  capture/export/outbox claim, canonical/history/durable outbox bytes and unknown evidence
  remain protected. Repeated replacement no longer accumulates unreachable history forever.
- Every root-locked pool read/modify/write refreshes the authoritative manifest, including
  older-instance retain, release, collection and a closed store's outstanding capture.
  Publication cannot overwrite a newer index with a stale record map or generation.

Independent review found six additional acceptance gaps. They were repaired rather than
waived. Abandoned pool-index temporaries now count against physical limits, and a single
root-locked staging name prevents repeated failed writes from creating unbounded UUID files.

Adoption now durably records a bounded, account/root/document/source-scoped compensation
intent before the first remote mutation, using the existing anchored atomic transfer store.
A failed manifest, asset or folder acknowledgement followed by unavailable readbacks keeps
that intent across gateway/process recreation. Explicit retry verifies the entire recorded
original/adopted state before conditionally compensating a partial commit or delivering an
already-complete commit without repeated PUTs. Changed external content, properties, parent
or owner blocks recovery without overwriting the external edit. An outage can leave a
transaction explicitly pending; instantaneous atomicity across independent provider files
is not claimed. The intent is retired only after durable local acceptance metadata commits.

Final adoption checks the complete canonical manifest digest, fresh folder identity/properties,
asset descriptors/content/parents and stable metadata, rather than trusting partial or stale
acknowledgements. These repairs touch five production files, including the new small
`DriveAdoptionRecovery.kt` compensation-record owner. They add no alternate document authority,
legacy-format reader, remote garbage collector or production test-auth hook.

### Final revision, parent and canonical-digest corrections

A subsequent narrow independent review found three further blockers. Its ten failing cases
are preserved in `delta2-red` (23 cases executed on unchanged prior production). Twelve new
regressions cover those failures and unchanged/compensated controls. The repaired 127-case
focused adoption/coordinator gate passes without skips or failures.

A retained recovery record no longer bypasses the selected revision. It stores both the
manifest cursor and ETag and requires both on original-state retry. Only an acknowledged
conditional compensation with exact original scope/content and a stable matching readback
can advance this fence through a root-locked compare-and-write/readback. The next preflight
reloads that receipt. A revision-only or ETag-only external edit is rejected before another
PUT, including after an earlier verified rollback. When a compensation acknowledgement is
lost and its exact revision cannot be proved, the intent remains explicitly blocked rather
than treating identical bytes as authority to overwrite an external revision.

Initial, acknowledged and completed-recovery asset validation requires the exact singleton
document parent. An additional external parent is not accepted. Recovery records the decoded
canonical original digest, so valid pretty-printed or reordered current manifests remain
recoverable through an uncommitted failure or committed manifest/asset outage.

The existing combined adoption/conflict test resets its synthetic remote state between two
independent scenarios. Its second scenario now also receives independent local transfer
state, rather than reusing the first scenario's unacknowledged recovery intent. Its explicit
HTTP 412 and unchanged-payload assertions remain intact.

### Regression evidence

The first unchanged-production red gate reproduced 12 failures across 23 cases. A second
13-case review-followup red gate reproduced 11 failures, including committed-write outages,
false final adoption and uncounted abandoned metadata. Failure logs remain preserved.

There are 50 additional JVM cases over the baseline and three new native pool cases.
Coverage includes full property/content validation, conditional rollback, no blind replay,
persistent outage recovery through a new gateway, external-edit preservation, failed local
acceptance retaining intent, retry without repeating accepted writes, pool collection through
actual production stores beyond cumulative 200 MiB history, live export preservation, both
cross-instance release orders, older-instance retain/collection and abandoned index evidence.
Two existing HTTP fixtures now apply and echo the actual multipart metadata/digest rather
than fabricating incomplete acknowledgements. No product assertion was weakened.

| Gate | Final candidate evidence |
| --- | --- |
| Integrated matrix5 | PASS: 749 JVM cases, 743 executed passes, six explicit Windows/platform capability skips, zero failures/errors |
| APK builds | Debug and Android-test APK assemblies PASS; frozen hashes in final-artifacts5.json |
| Lint | Zero errors and 87 warnings; affected analyses execute, existing report explicitly reused |
| Latest focused delta | 127 adoption/coordinator cases pass, no skips/failures; includes all 12 revision/parent/noncanonical cases |
| Native5 full suite | API 36 / Android 16 task-owned emulator: 76 XML cases, 69 executed passes, seven documented skips, zero failures/errors |
| Actual SAF5 phases | Rendered PDF export, bundle export, fresh-install complete import, process-death relaunch and retired-format rejection: five passes, zero skips/failures on the frozen final APK pair |
| Independent review3 | No remaining blockers in the narrow corrected scope; source/diff review only, no reviewer test execution claimed |

Native full-suite skips are one hard-link capability case, the opt-in real-provider case,
and five phase-selected SAF cases which then execute separately and pass as shown above.
Earlier matrix3/4 and native/SAF runs remain historical evidence, not final-candidate counts.

The native failure was a discarded synthetic-camera coordinate tap: InputDispatcher logged
window opacity 0.397705 below its touch threshold during the entrance animation. The harness
now invokes the real accessibility click on that same synthetic button, preserving all camera
result, persistence and lifecycle assertions and the same timeout. No production camera code
changed; the production APK is byte-identical before/after this test-only repair. The full
rerun passes. Final native5 transfer/outbox memory is 104,857,600 logical photo bytes,
80,240,640 incremental Java bytes, 32,696,560 incremental native bytes and 65,536 maximum
read bytes. The existing 128 MiB Java / 64 MiB native / 64 KiB read budgets pass unchanged.

### Independent review and source identity

The root authored the repairs and executed the gates. Independent read-only review used the
available `gpt-reserve` route after the requested Luna route was unavailable, with MAX reasoning
and normal service requested. Effective model identity was not exposed, so no verified-Luna
identity or separate Inspector PASS is claimed. Earlier six-blocker and three-blocker reports
are retained along with the repairs and their failing/passing regressions. The last narrow
review found all three final issues resolved and no new blocker. Its temporary snapshot read
was blocked by ACLs, but it inspected the actual source and diff; the root independently verified
all 14 changed executable/test file hashes against candidate-source5.json before and after
the review and final gates. Its report has a path typo for DriveAdoptionRecovery.kt; the actual
reviewed file is under stage9b, not stage4. No reviewer gate execution is invented.

### Scope and publication

All current native work targets only the task-owned emulator and synthetic fixture provider.
The physical tablet, real accounts/Drive resources, original documents and previously denied
cleanup targets are untouched. Earlier physical/live-provider qualification remains historical
and scoped to unchanged paths, not execution of this corrected APK. New outage fault injection
is deterministic real-HTTP-adapter JVM evidence, not a claimed real-provider outage experiment.

Commands, raw XML/logs, red/green results, source hashes, frozen APKs, independent reviews,
camera diagnosis and current checkpoint are retained outside Git in OS-temp evidence directory
`construct-9b-review-repairs-l9q2lzxk`. Final commit/push/CI/email identities belong in its
external checkpoint once created. Stage 10, release signing and distribution remain separate.
