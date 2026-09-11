# Stage 9B implementation and qualification

Status: The scoped recovery correction and host-test heap follow-up pass local build, JVM and lint gates; both APKs are byte-identical to the successful targeted native qualification. The independent adoption-delta review reports no concrete production blocker, with its stated scope and recovery limits. The first publication CI failed an existing large-snapshot fixture with heap exhaustion; see the latest CI follow-up and external checkpoint for the replacement publication result. Stage 10 is not started. Earlier checkpoint statuses are historical.
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

## Scoped adoption and photo-index recovery correction, 2026-09-10

Baseline: `cef6f77ad1990cb55f86f14959e53bb1db538270`, originally clean on
`codex/stage-3-transactional-switching`. The user authorized direct repair, commit and normal
push of the two independent-review findings. This is a bounded recovery correction, not
Stage 10 or a renewed claim of whole-stage device/live-provider qualification.

### Behavior and preserved boundaries

A definitive HTTP 412 from the first manifest PUT retires only its exact prepared intent
through a locked compare/delete/readback. No new adoption mutation has occurred at this point;
a fresh selection can therefore preserve and adopt legitimate externally changed content.
Retirement does not authorize the old selection. Ambiguous outcomes and failures of later
writes retain recovery evidence. The additional content-change regression reproduces the
previous lockout and verifies both stale rejection and preservation of the updated snapshot.

For a retained ambiguous intent, a freshly and explicitly selected cursor may replace it only
after all recorded resources are verified in their exact original state. Account/root/document/source,
remote IDs, full properties, canonical content, immutable asset evidence and parent boundaries
remain fixed. Old selections still fail their cursor/ETag fences; changed, partial, fully adopted
but unacknowledged, or unavailable evidence cannot be discarded through reselection. The
existing anchored store compare-replaces and read-back verifies the intent atomically. There
is no delete/prepare gap, automatic selection upgrade, blind PUT replay or remote deletion.

Under the shared root lock, a complete consecutive staged pool index is checked against the
committed index, descriptors, actual content hashes and existing limits. Only that verified
uncommitted metadata is discarded. The committed retention transaction and all asset bytes
remain authoritative; rolling forward the proposal would double-apply a failed live release.
Malformed, conflicting, missing-asset or wrong-generation staging is preserved and rejected.
An interrupted first publication's verified orphan is counted once, not twice, at admission;
unknown physical evidence still counts against the unchanged limits. Failed construction closes
the directory anchor instead of leaking it while rejecting corrupt staging.

Thirteen new adoption JVM cases and eleven pool JVM cases cover positive recovery, stale or
unsafe reselection, fresh-gateway interruption, exact bounds, live release/collection, corrupt
staging preservation and descriptor closure. Two new Android cases exercise actual production
photo-store capture/reopen and release with interrupted staging. Existing assertions remain.

### Executed evidence

The first offline command selected an obsolete repository-local dependency cache and failed
before test execution. The current configured user cache resolved that environment issue;
no dependency versions, build configuration, keystore or global settings were changed.
The unchanged-production red gate then ran 45 cases with 15 failures, including the adoption
reselection and valid interrupted-index defects. The first repaired focused gate passed all
163 cases without skips. A final small descriptor-closure repair and its regression are included
in the complete matrix and native evidence below, not credited to that earlier focused run.
A later one-case definite-conflict red gate reproduced the retained no-mutation intent against
the first repaired candidate. Its first-PUT retirement correction is included in final matrix2.

```text
gradlew.bat --no-daemon --offline --console=plain --stacktrace --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Final matrix2: PASS, 773 JVM cases, 767 executed passes, six capability skips, zero failures/errors.
Both APK assemblies pass (the unchanged Android test APK is up-to-date). Affected lint analyses
execute; the unchanged report from matrix1 is explicitly reused: zero errors, 87 warnings.
Targeted `connectedDebugAndroidTest` uses the same wrapper flags and
`ANDROID_SERIAL=emulator-5580` on the task-owned ConstructStage9B Android 16/API 36 emulator.
Runner classes: PhotoPoolLifecycleInstrumentedTest, DriveStorageAdmissionInstrumentedTest,
DriveTransferResourceLifecycleInstrumentedTest and TransferMemoryInstrumentedTest in stage9b,
plus stage9a.PendingOutboxSecurityInstrumentedTest. XML records 14 cases: 13 passes, one
platform hard-link capability skip, zero failures/errors; all five pool lifecycle cases execute.
The Gradle console's progress counters are not substituted for the retained XML totals.

Final native-storage2 100 MiB envelope: 104,857,600 logical bytes, 81,068,288 incremental Java bytes,
32,201,264 incremental native bytes and 65,536 maximum read bytes. The original 128 MiB Java,
64 MiB native and 64 KiB read limits pass unchanged.

Review disposition: the final independent adoption-delta review reports no concrete production
blocker. The requested Luna route hit its usage limit. A read-only gpt-reserve MAX/default
session inspected the candidate but encountered repeated server-stream disconnects. Only its
owned wrapper was interrupted, and the same session resumed for a bounded final verdict.
The final verdict covers the frozen first-PUT 412 retirement, retained reselection/store paths
and new conflict regression; the previously inspected, unchanged pool was not reopened.
No tests were run independently. Effective model identity is not exposed, so no verified-Luna
certification or broader whole-application review is claimed. No executable changes followed
the final tests/review; source and APK identities are retained externally.

Known conservative liveness limit: a process crash after receiving 412 but before local
retirement is durable can leave the old intent without a durable rejection receipt. A later
changed-content selection remains blocked as ambiguous rather than guessing that ownership
is safe or replaying mutations. This correction does not promise automatic recovery from
that additional cross-network/local-storage crash window.

Evidence: `construct-recovery-fix-4m652gy6`, including original backups, red/green XML,
final-matrix2, native-storage2, lint XML, source and APK hashes, review and checkpoint records.
Physical tablet, user documents and live Drive were untouched. Full SAF workflows and real
provider fault injection were not rerun; prior results remain historical and scoped. Commit,
push, exact-SHA CI and notification identities are recorded in Git/GitHub and the external
checkpoint when publication creates them. Stage 10 remains unstarted.

## CI host-test heap follow-up, 2026-09-10

Recovery implementation `944d00524198f09d68a6b4fbe6ab760a425fb1c0` was normally committed
and pushed after the local gates above. Its exact-SHA push run `34552578317` built the APK
but failed one of 773 JVM cases: the pre-existing PendingSnapshotBoundaryTest case above
the single-photo limit threw OutOfMemoryError in HeapCharBuffer on Ubuntu/Temurin 17.
CI lint was skipped. That failed run is retained and is not reported as successful.

The same two existing boundary cases passed in isolation on Windows/JBR 21 with an observed
-Xmx512m worker. A nine-line host configuration addition now gives Gradle Test tasks a finite
1 GiB heap and one fork for the intentionally large legal JSON fixtures. No production code,
fixture size, test assertion, app storage limit, or instrumentation memory bound changes.
The observed final worker command contains -Xmx1g. This is a host-qualification correction,
not a claim that the original failure was reproduced on every platform.

The complete local build/JVM/lint/test-APK matrix reruns with the documented wrapper command
plus --info: PASS, 773 cases, 767 passes, six capability skips, zero failures/errors, fresh
lint analyses/report at zero errors/87 warnings. Both APK hashes exactly match apks2 from
native-storage2. That 14-case native result and memory envelope are therefore explicitly
reused for identical binaries, not described as another device run. All seven production/test
source files also retain their prior tested hashes. The root reviewed the bounded host-only
configuration delta; prior independent review applies only to its unchanged stated scope.

Evidence is host-heap-final-matrix.log, host-heap-final-junit, host-heap-lint.xml,
pending-boundary-heap-repro.log, ci-failure.log and checkpoint.json under
construct-recovery-fix-4m652gy6. The follow-up commit, replacement exact-SHA CI and notification
are recorded in Git/GitHub and the external checkpoint after publication. No Stage 10 or
physical-device/live-Drive work was started.

## Stage 9B public photo-release correction, 2026-09-10

Baseline: `babfe2e08be121df64a15a3bf2b52c5cd6fa140e`, initially clean. Scoped direct
implementation repairs public capture/retained-lease release and pool-anchor cleanup.
The public handles serialize callers and mark release complete only after the callback
succeeds. Failed index publication is reconciled against the exact prior/intended
manifest under the shared root lock. A proved publication retires the registry claim
once; later retries only finish cleanup, even after another owner publishes or collects
the released photo. Pool close separately retries a failed directory-anchor close.
No storage format, photo limits, dependencies, signing configuration or user data changes.

An unreadable publication receipt remains an explicit, in-process unresolved attempt.
If a later generation replaces the receipt before it can be proved, retry retains
ownership and fails closed rather than guessing or decrementing a different owner's
retention. No automatic recovery from that compounded ambiguity is claimed.

### Executed validation

The 22 new public-handle/pool regressions all failed on unchanged production code.
The corrected focused suite passes 50/50 without skips. The unchanged standalone
`ReviewReleaseProbe.java` from the preceding review now passes all four cases: capture
and retained-lease controls plus their injected move/staging-cleanup failures. Each
case collects exactly one released photo, has no remaining hash claim, and closes all
factory-counted anchors. Those counts are JVM lifecycle evidence, not native FD counts.

```text
gradlew.bat --no-daemon --offline --console=plain --stacktrace --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The complete matrix passes: 795 JVM cases, 789 passes, six existing provider/capability
skips, zero failures/errors; both APK assemblies pass. Affected production, unit and
Android-test lint analyses rerun. The unchanged cached lint report is explicitly reused:
zero errors and 87 warnings. No failing assertion or limit was weakened.

### Review and scope

The root inspected the integrated diff and the ownership/publication/cleanup paths.
A fresh read-only `gpt-5.6-luna` MAX/default reviewer was requested, but exited with the
account usage-limit error before reviewing. No independent review or Luna sign-off was
completed, and no alternate worker was launched. Independent acceptance remains blocked.
This is not a claim of full Stage 9B closure or Stage 10 qualification.

Evidence: `construct-release-fix-nl_iew31`, including baseline backups, red/focused/final
JUnit XML, the original reproducer and logs, exact commands, source hashes, and checkpoint.
Changes remain local and uncommitted; no push, replacement CI run or email was performed.
The physical tablet and real Drive documents were untouched.

### Native validation

The rebuilt APK and unchanged test APK were installed only on the existing synthetic
`ConstructStage9B` AVD, `emulator-5580`, Android API 36, with no data clear or device wipe.
All five existing `PhotoPoolLifecycleInstrumentedTest` cases pass, with five success
status records, zero failures/skips and `OK (5 tests)`. This is fresh production-path
Android coverage; the new fault-injection cases remain explicitly JVM-only.

```text
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class com.example.myapplication.stage9b.PhotoPoolLifecycleInstrumentedTest com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
```

Native commands, output and APK hashes are retained in the same external evidence root.
The pre-existing AVD remains running; no newly owned emulator process was launched.
No full SAF, live-Drive or physical-device qualification was rerun.

## Stage 9B capture/freeze publication correction, 2026-09-11

Base remains `babfe2e08be121df64a15a3bf2b52c5cd6fa140e` plus the preserved,
uncommitted public-release correction above. This scoped direct repair fixes the
reviewed capture/freeze rollback defect; it does not open Stage 10.

A thrown manifest move is no longer treated as proof that publication did not occur.
Before deleting its own newly created assets, rollback reloads the authoritative
manifest under the shared root lock and reconciles complete staging while the proposed
bytes still exist. Committed assets remain intact. Failed inspection, ambiguous/corrupt
metadata, or unresolved staging cleanup retains bytes as bounded evidence and propagates
the operation failure. Proven unpublished assets alone are rolled back. Duplicate nested
rollback was removed. Public release/lease code, formats, limits and dependencies are
unchanged; no recovery of already-deleted photo bytes is claimed.

### Executed validation

The new parameterized suite covers capture/freeze, empty/populated pools, pre-move and
post-move errors, unreadable outcomes, retained staging, failed read-back and controls.
It verifies reopen/retry on both the original and a second pool, unchanged live-owner
bytes, exact count/byte admission bounds, no false successful capture, safe collection
and closed synthetic anchors. Final-oracle red: 28 cases, 16 expected failures and
12 passes on the previous candidate. The initial red fixture teardown was hardened
before that final red run; teardown bypasses are not counted as recovery evidence.
Green: all 28 new cases and the complete focused 78-case suite pass without skips.

The unchanged original capture/freeze probe was freshly compiled against the candidate:
all four cases reopen, including both post-move errors, with zero open anchors. The
unchanged original four-case public-release probe also passes with no retained claims
or open anchors. These are JVM failure-injection/lifecycle measurements.

```text
gradlew.bat --no-daemon --offline --console=plain --stacktrace --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The final full matrix passes: 823 JVM cases, 817 passes, six existing Windows/provider
capability skips, zero failures/errors; debug and Android-test APK assembly pass.
Affected production, unit and Android-test lint analyses reran; the unchanged cached
report is explicitly reused at zero errors and 87 warnings.

The rebuilt app and unchanged test APK were installed with `install -r` only on the
existing synthetic `ConstructStage9B` AVD (`emulator-5580`), after identity verification.
All five existing `PhotoPoolLifecycleInstrumentedTest` cases pass on this candidate.
The new publication fault injection remains JVM-only. No data clear, emulator wipe,
physical-tablet, live-Drive, full SAF or release/distribution qualification was performed.

### Review and handoff

Direct root review checked the rollback/publication/staging paths and verified that
all prior release source/tests and pool code outside the freeze/rollback delta remained
unchanged. A fresh read-only Luna MAX/default review was attempted but stopped at the
account usage limit before review. No independent sign-off or full Stage 9B/10 closure
is claimed. Changes remain uncommitted and unstaged; no push, new CI or email was sent.

Evidence: `construct-capture-fix-eg6zr66k`, including baseline backups, red/focused/final
JUnit XML, unchanged probes and logs, full build/lint/native results, source/APK hashes,
root review and `checkpoint.json`. Prior evidence directories remain intact.

## Scoped photo-recovery review waiver and publication, 2026-09-11

The user explicitly waived the fresh Luna review for the public-release and
capture/freeze recovery fixes and authorized commit/push. The quota-blocked review
remains NOT RUN, not PASS. This is a task-specific exception, not a change to AGENTS.md
or authorization for whole-stage closure. Stage 10 remains unstarted.

Publication preflight confirmed the five executable source/test hashes still match
the candidate validated in construct-capture-fix-eg6zr66k. Its 823-case JVM matrix
(817 passes, six existing capability skips), focused 78/78, both APK builds, two
four-case probes, five native lifecycle cases and zero-error/87-warning lint evidence
are reused, not rerun for this documentation-only publication step. Historical
quota-blocked/uncommitted handoffs above are superseded only for this scoped publication.
Commit/push, exact-SHA CI and notification outcomes are recorded in Git/GitHub and
construct-photo-publish-k5gd2fa0/checkpoint.json. No device or live-Drive work is added.

## Stage 9B deferred photo-release ownership correction, 2026-09-11

Base `d36b49705ae9017e6f3a3d6e2da95db818ef14f9`, initially clean. This scoped
repair addresses the reviewed caller-lifetime leak; it does not open Stage 10.
Failed pool releases transfer their exact internal lease/retry phase to a
process-owned, root-scoped recovery owner before releasing the root lock. The
public caller may return without preserving its local capture handle. Recovery
runs before capture/freeze/retain/collection, outside instance/registry locks;
a root-locked recheck prevents admission over a newly queued failed release.
Tickets retire only after both ownership and anchor cleanup succeed. Recovery
is one bounded pass, not a background loop. Failures remain explicit and owned,
without admitting additional captures while that root is blocked. Other roots
are independent. Empty-document admission also drains prior releases without
creating an unnecessary pool. Closed pool operations fail before recovery side
effects. Existing publication receipts, formats, limits, canonical photos and
safe rejection of an already-obscured receipt are preserved.

### Executed validation

Thirty corrected-oracle caller-lifetime cases failed against the byte-identical
base pool (zero errors/skips); all pass after repair. Coverage includes dropped
capture/retained-lease/freeze ownership, pre/post-publication errors, staging,
unreadable receipts, anchor cleanup, concurrent recovery, surviving owners,
independent roots and actual document-store admission/collection. Three added
closed-instance guards and two admission-fencing cases also pass. The existing
external-generation ambiguity oracle remains fail-closed; normal admission
retries or blocks instead of overwriting an unresolved receipt. Forced red-test
teardown is not counted as recovery evidence.

Final focused suite: 113/113, zero failures/errors/skips. Full matrix: 858 cases,
852 executed passes, six existing capability skips, zero failures/errors. Both
APK assembly gates pass. Affected lint analyses reran in the full matrix. The
initial cached lint report was explicitly regenerated using an external init
script forcing only lintReportDebug and disabling the build cache: zero errors,
87 warnings. No repository configuration, assertion or limit was weakened.

```text
gradlew.bat --no-daemon --offline --console=plain --stacktrace --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The frozen APK pair was installed with install -r on the identity-verified,
existing synthetic ConstructStage9B/API 36 AVD only. All five existing
PhotoPoolLifecycleInstrumentedTest cases pass without skips. New fault injection
and synthetic anchor-count oracles are JVM-only, not native FD claims. No data
clear, wipe, physical-tablet, live-Drive, full SAF or release work was performed.
The pre-existing emulator remains running.

Direct implementation self-review checked ownership transfer, retry phases,
root/instance lock ordering, failure propagation and unchanged red oracles.
No fresh independent Luna review is claimed. This is local scoped validation,
not whole-stage acceptance. No commit, push, new CI or notification was performed.
Evidence: `construct-deferred-release-i6lvf1qf`, including source baselines/hashes,
red/focused/full JUnit XML, native output, fresh lint, launch records and checkpoint.
