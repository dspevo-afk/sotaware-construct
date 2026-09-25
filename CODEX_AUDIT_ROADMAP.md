# SOTAware Construct Working Roadmap

The [canonical audit and acceptance plan](SOTAWARE_CONSTRUCT_AUDIT_AND_REMEDIATION_PLAN.md)
remains authoritative, subject to the explicit current-format and internal-scope
decisions linked below. This file is a current status index, not another history log.

| Stage | Status |
| --- | --- |
| Stage 0: Establish reliable gates | complete |
| Stage 1: Create one canonical document snapshot | complete |
| Stage 2: Replace local persistence safely | complete |
| Stage 3: Make document switching transactional | complete |
| Stage 4: Replace synchronization with one serialized coordinator | complete |
| Stage 5: Harden filenames, payloads, and photo transactions | complete — Coder implementation and gates complete; Luna Max Reviewer, bounded Foreman, and Terra Max Inspector passed |
| Stage 6: Make import/export current and self-contained | closed/passed — Android gate passed; Reviewer Halley PASS, Foreman PASS, and Terra Chandrasekhar PASS |
| Stage 7: Fix rendering and OCR | closed/passed — final certification, exact-SHA CI, fresh reviews, device qualification, and Terra inspection passed |
| Stage 8: Repair search, annotation actions, and responsive UI | closed/passed — final JVM, build/lint, and emulator qualification passed; bounded reviewer/inspector deferments recorded |
| Stage 9: Privacy, authentication, release, and cleanup | closed (internal-company scope) — lifecycle/auth code and local debug qualification passed; external OAuth/Drive and release qualification deferred pre-deployment |
| Stage 9A: Cross-stage correctness repair | Closed for internal development at published `69019f4`; exact-SHA CI passed. Native/live-provider limits remain explicit. |
| Stage 9B: Annotation consolidation and immutable photo synchronization | scoped recovery correction qualified for internal development; final matrix2/native-storage2 pass, with scoped independent adoption-delta review and explicit recovery limits; see latest STAGE9B_IMPLEMENTATION.md entry |
| Stage 10: Final qualification | lifecycle repair passes host/native/SAF gates and independent review; publication authorized; broader release limits remain in STAGE10_QUALIFICATION.md |

## Current qualification boundary

The tablet now runs the qualified `com.sotaware.construct` debug APK and is
signed in after a verified force-stop/relaunch. An older duplicate package is
disabled with its data preserved. See the latest implementation-log entry for
the app-identity correction; no source or stage changed.

The [MainActivity owner extraction](CODEX_AUDIT_IMPLEMENTATION_LOG.md)
moves the retained ViewModel, PDF page browser, renderer, and page PDF exporter
into separate files without changing their public entry points. The activity
file fell from 8,518 to 4,686 lines; `BlueprintApp` remains the composition
host. Final host gates passed with 1,051 JVM passes, six existing skips, both
APKs, and zero lint errors. All 16 selected browser, renderer, export, and
ViewModel lifecycle tests passed on the TB336FU. No stage is advanced; the
account-dependent live-provider and release boundaries below still apply.

The [audit-recommended seam extraction](CODEX_AUDIT_IMPLEMENTATION_LOG.md)
is implemented in the current worktree without advancing a stage. Stage 4
adoption transitions, Stage 6 bundle export/import, and project Drive consent
now have narrow tested owners. Final host gates pass with 1,051 JVM passes,
six existing skips, both APKs, and zero lint errors. On the TB336FU, all seven
fresh-install production SAF phases and the project consent recreation class
pass. Account-dependent live-provider and release qualification were not
rerun; the prior live-provider limit below remains. See the latest
implementation-log entry for exact APK/source identities and device evidence.

The [September 22 current-worktree audit](docs/audits/2026-09-22/README.md)
findings A1–A7 are repaired locally; see the
[September 23 repair closeout](docs/audits/2026-09-22/REPAIRS.md). Final host
gates pass with 1,035 JVM passes, six existing skips, both APKs, and zero lint
errors. On the TB336FU, all seven actual SAF phases and process recovery pass;
the 50 planned local native invocations yield 49 passes and one hard-link
capability skip (129 method passes, one skip). The account-dependent live
provider class was omitted from that local matrix. An authorized live attempt
verified the first Drive upload and remote snapshot/photo assets, but the
second annotation and unchanged-photo proof did not pass. Its exact disposable
folders were moved to Trash. The full native runner remains BLOCKED rather
than a release qualification. The tested app was freshly installed and signed
in without a backup folder. The repairs were committed and pushed as
`0319560ba21d7420dc498d22fa654ae57971d975`; no stage was advanced. The September 14
repair closeout below remains separate historical evidence.

The five September 14 functional findings are now repaired and locally qualified.
Final host: 1,014 JVM passes, six existing skips; both APKs; zero lint errors.
Final tablet: 126 passes, one existing capability skip, zero failures; all seven
SAF workflows pass. Five extra retained-data browser repetitions and fresh-process
project-download recovery pass. The explicit live-provider omission and release
qualification limits remain open. See [the repair closeout](docs/audits/2026-09-14/REPAIRS.md).
The earlier audit failure below is historical, superseded for these five findings.
No stage is advanced and the combined repairs remain uncommitted.

The September 14 whole-worktree audit has local bounded repairs, passing host
gates (1,002 JVM passes, six skips; both APKs; lint zero errors), and a failed final
physical-tablet matrix: the project-browser deactivated-node crash is reproduced.
All seven SAF phases pass; a hard-link capability skip and the live-provider
omission remain explicit. Four other correctness/recovery findings remain open.
See [the audit](docs/audits/2026-09-14/README.md). The earlier scoped qualifications
below do not override this newer combined-run failure. No stage is advanced.

The annotation-tool follow-up is implemented locally: page-code identification
in Menu, long-press text selection and tool settings, project-persistent defaults,
Pan appearance editing, anchored note resizing, polyline measurements, and all
photo annotation tools with camera hidden. Snapshot schema 3 carries the new
domains. Host gates pass with 995 JVM passes, six existing skips, both APKs, and
zero lint errors. All 17 landscape, four portrait, and seven actual SAF checks
pass. The verified build is installed on the tablet with test data cleared and
auto-rotation restored. See
[tool behavior](docs/annotation-tools.md) and the latest
[implementation entry](CODEX_AUDIT_IMPLEMENTATION_LOG.md). No stage is advanced.

Page-code region identification, Google Drive backup sign-in after project
imports, and viewer fling are repaired locally. Both APKs, 990 JVM passes and
lint (zero errors) are qualified; the requested tablet workflows pass. A broader
combined run exposes a pre-existing project-folder layout crash with accumulated
test data, also reproduced on the complete pre-task source reconstruction. This
remains open and is not a green combined native matrix. The existing project
browser implementation is preserved; no audit stage is advanced. See the latest
[implementation-log entry](CODEX_AUDIT_IMPLEMENTATION_LOG.md) and
[page-code behavior](docs/page-code-identification.md).

Project-folder browsing and Google Drive project downloads are implemented and locally qualified
as a user-requested feature on the existing branch. This adds remembered local
folders, nested navigation, per-project recent files, and explicit read-only
Drive folder selection with offline PDF downloads. See
[project behavior](docs/project-folders.md) and the latest implementation-log entry
for scoped validation: 979 JVM passes, six existing skips, eight tablet regression
passes, both APK builds, and zero lint errors. Live Drive folder selection was
verified; live private-project downloads and broader release gates were not run.
No audit stage is advanced by this feature.

The September 12 A01–A16/R01–R08 remediation is implemented on the existing task
branch. Final host gates pass: 956 JVM passes, six existing skips, zero failures;
lint zero errors/89 warnings; both APKs build. Local native qualification records
110 passes and one historical hard-link capability skip, including all seven SAF
phases and actual process restart. Integrated/targeted production reviews pass;
later test-tooling/docs are root-reviewed. The live upload/download and unchanged
photo-identity test passes; broader account/conflict/release limits remain open.
Final publication outcomes are retained in the external checkpoint. Delegation
and independent subagent review are now optional per the user's updated AGENTS.md.
See the
[remediation ledger](AUDIT_REMEDIATION_2026-09-12.md). This work does not advance
the roadmap or replace the historical qualification limits below.

The Activity-recreation race is repaired locally. Fresh host/native checks, eight
extra recreation runs and all five actual SAF workflows pass. A separate direct
independent review passed; the owner accepted it and authorized publication. The
previous quota-blocked Luna attempts are not relabeled as worker approvals.
Broader release, live-account/provider, Pixel, and cloud/device-transfer gates are
not waived by branch integration or repository housekeeping. No Stage 11 is defined.

- [Detailed Stage 10 evidence and remaining gates](STAGE10_QUALIFICATION.md).
- [Current-format/photo contract](STAGE9B_CONTRACT.md) and
  [implementation details](STAGE9B_IMPLEMENTATION.md).
- [Internal development decisions](STAGE9_ACCEPTANCE_DECISIONS.md) and
  [authentication/release prerequisites](STAGE9_AUTH_RELEASE_SETUP.md).
- [Current integration and housekeeping log](CODEX_AUDIT_IMPLEMENTATION_LOG.md).

## History

The complete earlier roadmap is preserved in
[the dated archive](docs/archive/CODEX_AUDIT_ROADMAP_2026-09-11.md).
Use it for provenance, not as an instruction to restart a completed stage.
