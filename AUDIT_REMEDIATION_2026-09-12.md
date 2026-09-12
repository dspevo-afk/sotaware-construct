# September 12 audit remediation

Status: all audit repairs and bounded refactors are implemented and scoped
host/native/live qualification has passed within the historical capability
limits below. Publication evidence is recorded in the external checkpoint.
Baseline: `989e0454ececa702bb6d09b878d773fef90ee62a` on
`codex/stage-3-transactional-switching`, including its audited uncommitted
camera-preparation, verified-export, and hybrid-OCR work. All 302 retained audit
input hashes matched before edits; the index was empty. Baseline patches, untracked
inventory/hashes, command results, and restart checkpoint are retained externally.

The scope is the September 12 report's 16 findings and eight bounded refactors.
Existing canonical state/repository, reducer/history, switching/handoff, serialized
sync and immutable photo ownership remain authoritative. No release, dependency
migration, or roadmap-stage advancement is included.

| Item | Observed behavior / production owner | Repair and checked-in regression status | Qualification / limitations |
| --- | --- | --- | --- |
| A01 | Exhausted or missing accepted slots reopen empty; LocalDocumentRepository | Accepted-state schema 1 and restore intent schema 2; explicit retained-byte rejection of retired intent schema 1; repeat/restart, missing-slot, failed-save and exact-rollback regressions | Fresh full host and native repository/recovery/callback cases passed |
| A02 | Generated Drive follows redirects with bearer interceptor | Common initializer disables redirects; real generated client tests 301/302/303/307/308, normal requests, 401 and authorization generation | Generated-client host, native auth and actual live upload/download passed |
| A03 | PDF gesture uses stale selected object/index | PdfSelectionKey resolves ID against current lists; Main gesture admission and reducer commits; deletion/undo/replacement/pinch regressions | Host and actual native gesture regressions passed |
| A04 | Calculated labels truncate fractional inches | Whole-inch formatFeet rounds total inches once; creation, endpoint drag and scale-display callers use it | Host boundaries and portrait/landscape stored-label/endpoint-drag cases passed |
| A05 | Hybrid merge globally reorders embedded words | Embedded sequence retained; recognized groups ordered separately; search respects block boundaries, including deduplicated block starts; mixed two-column PDF fixture | Host and actual Android extraction/merge/phrase cases passed |
| A06 | Embedded gap statistics retain earlier word widths | Central word reset admits the next character after clearing statistics; actual PDF extraction variants share identical later text geometry | Actual Android large/small heading and explicit-space fixtures passed |
| A07 | Suspended setup mutates host after teardown | Admitted setup drain and closed/caller fencing in coordinator; deterministic suspension, cancellation and teardown regressions | Full host, native coordinator, Activity/auth and history lifecycle cases passed |
| A08 | Staged camera journal blocks all ordinary/recovery reads | Separate identity/revision reconciliation; Main startup/foreground/result/new-launch seam reconciles before strict read; sweep requires resolved journal | Host, interrupted-publication and actual process-restart pair passed with matching IDs/hashes |
| A09 | Hit radii omit fitted scale; controls use screen bounds | ViewerTransform owns conversions and screen hit distances; ViewerFloatingControl measures local control bounds; copy is inside the viewer | Host fit/nonsquare cases and native edge toolbar/copy bounds passed; HUD overlap repaired |
| A10 | Photo hit selection disagrees with drawing order | PhotoAnnotationStack selects last shape then last note, shared by tap and drag admission | Pure and native overlapping shape/note drag cases passed |
| A11 | Duplicated Drive loops continue after cancellation | collectDrivePages checks cancellation/admission before and after responses; repeated tokens/IDs and page/item/metadata budgets fail before mutation | Shared policy and both actual generated-client gateway paths passed host checks |
| A12 | Unassociated backup discovery picks first valid root | Known association retained; multiple valid roots throw typed ambiguity and UI explains failure; zero/one/invalid cases retained | Host and real native UI passed: localized ambiguity message, two GETs, no create/association |
| A13 | Recovery reads allocate before input admission | Actual reads use snapshot/metadata limits; accepted bytes captured once and exact rollback compared by stream | Oversize/evidence-retention and exact rollback host tests passed |
| A14 | Process death abandons PDF source/result cache files | Shared directory/request ownership, bounded enumeration and conservative age reconciliation; source/result live callers integrated; active leases shared across owners | Active-owner, terminal, owner-restart and real process-restart cases passed |
| A15 | Logcat script writes PowerShell's automatic PID | Validated appProcessId; no host PID fallback or log clearing; actual PowerShell script with mocked ADB | PASS: seven actual-script cases |
| A16 | Both instruction banners hard-code first-point=false | Both layouts and renderer share MeasurementPointSelection; Back, dialog cancel and completion clear its points | Both actual layout workflows passed: point/Back/dialog cancellation and HUD visibility |
| R01 | Selection/gesture admission | A03/A10; live callers resolve stable IDs, obsolete mutable indices removed | Host/native and integrated review passed |
| R02 | Measurement and viewer geometry | A04/A09; shared formatter and transform own affected callers | Host/native passed, including HUD-overlap regression |
| R03 | OCR tokenizer/merge | A05/A06; actual extraction/merge/search integration | Host/native and integrated review passed |
| R04 | Export temporary ownership | A14; source/result live callers share request owner | Host and actual process restart proof passed |
| R05 | Camera staged recovery | A08; production recovery precedes strict ordinary read and sweep | Host/native and actual process restart proof passed |
| R06 | Drive listing and repository input admission | A11/A13; both former listing routes and recovery capture use shared boundaries | Host and integrated review passed |
| R07 | Repeatable native/SAF runner and CI coverage | Checked-in explicit-target runner, seven SAF phases and process-recovery pair; routine Android-test APK build plus account-free emulator job | 36 parser/safety cases pass; 111 local identities accounted for (110 pass/1 historical skip), seven SAF phases and one live test pass; CI execution recorded separately |
| R08 | Unreachable Drive folder browser | Removed showFolderBrowser route/state and exclusive list/shared-drive helpers; retained createFolder live-provider seam | Reference sweep, compile/tests and integrated review passed |

Final gates: debug APK assembly, a forced fresh full JVM run, lint, Android-test
APK assembly; checked-in native/SAF runner and repaired native paths on isolated
synthetic fixtures; authorized live-provider cases where prerequisites exist;
fresh independent Luna review of the integrated production candidate. Subsequent
test-tooling and documentation deltas were root-reviewed under the user's updated
optional-delegation policy.
Skipped or unavailable cases do not count as passes. Publication follows required
review and qualification; historical signing/Pixel/backup/account limitations remain
explicit unless exercised in this task.

Lower-priority disposition: pure embedded pages bypass mixed-source merge admission;
the merge's spatial duplicate index needs its own bounded work admission. A digital
page is not rejected merely to match that unrelated limit. The host regression
`pureEmbeddedAdmissionIsIndependentOfMixedMergeBudget` covers this distinction.
The 1 MiB/page and 8 MiB aggregate cache limits remain. No registry eviction is
justified by the audit's evidence; document mutex identity must remain stable.
Lint comparison is scoped to affected warnings. Rejected font mapping and
unreproduced picker-exception theories are not implementation requirements.

Final host matrix 2 built both APKs and forced fresh JVM execution: **962 tests
in 99 suites, 956 passes, six existing Windows capability skips, zero failures
or errors**. This adds 43 executed passes and two suites to the audit baseline.
Lint is **zero errors, 89 warnings**, matching the audit baseline after removing
nine exclusive folder-browser strings. The app and test APKs stayed byte-identical
during matrix 2 and local native qualification. Later live-harness-only changes
were rebuilt and linted in `native-build14`; the production APK stayed byte-identical.
Actual PowerShell logcat tests: 7 pass.

The checked-in runner accounts for every local native identity: **110 passes,
one existing Android hard-link capability skip, zero failures**, across
`native-full2` and the remaining classes in `native-remaining2`. All seven
production SAF phases and the guarded process-restart pair pass. The full runner
correctly reports BLOCKED for the hard-link assumption; that Android case is not
counted as passed. Its real Windows hard-linked staging regression passes in
matrix 2. This is the previously documented platform limitation in
`STAGE10_QUALIFICATION.md`, not a new product defect.

The native tests exposed a real calibration HUD overlap. The HUD now follows the
existing point owner and stays hidden while the two-point controls are present,
returning after cancellation/completion. Both portrait and landscape regressions
exercise point cancellation, actual dialog cancellation, stored labels and edge
toolbar placement. Drive ambiguity is tested through normal ActivityScenario/native
accessibility, preserving the production Android Looper; its prior Compose test
dispatcher failure remains retained as superseded harness evidence.

One fresh Luna integrated review and targeted reviews passed the integrated
production changes after the create-button ambiguity catch was corrected. The last
numeric skip-count correction was root-reviewed and checked by 36 host runner
tests plus reparsing the retained actual Android skip output; it changes no APK.
The optional review follow-up is to add an explicit package-name predicate to the
specific-message Toast oracle. It does not invalidate the existing native proof.

At the user's September 12 direction, `AGENTS.md` now makes delegation and
independent subagent review optional and reduces the standing instructions from
483 lines/3,838 words to 108 lines/835 words. Core data, validation, cleanup and
publication protections remain. This documentation change does not invalidate
unchanged product qualification.

`native-live6` passes the actual Google authorization, SAF open, real note dialogs,
manual Sync Now, full-domain snapshot/photo download and second annotation-only
edit. Remote asset IDs, descriptors and streamed bytes remain identical after the
second edit; HTTP PUT byte count was not measured. The optional live conflict flag
and broader multi-account matrix were not run. Test-only fixes dismiss the actual
Android immersive tutorial, close Note tool options, wait for visible navigation
destinations and allow five minutes for human authorization. Original failed runs
remain retained. This final harness delta was root-reviewed and passed the live run.

Hash comparison confirms unchanged production, JVM tests, local native tests,
fixtures and toolchain since the reviewed matrix 2/local qualification candidate;
that evidence is reused for those unchanged paths. The live harness and APK were
freshly built and executed. The exact four synthetic Drive roots and cleanup
status are retained externally; no unrelated folder was used as a fixture.
Signed-release, Pixel, cloud/device-transfer and other historical qualification
limits remain as documented. Commit, push, exact-SHA CI and notification outcomes
belong to the final external publication checkpoint, not a prediction in this log.
Bulky logs, original failures, candidate/APK hashes, review reports and the restart
checkpoint are retained in the task-owned external evidence directory
`construct-audit-remediation-047a91c4dab6411eae7e979f741f76d8`.
