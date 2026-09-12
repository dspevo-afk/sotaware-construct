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
