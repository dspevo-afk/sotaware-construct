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

The October 2 reopened audit repairs seven confirmed finding groups locally,
with independent source review, 1,070 JVM passes, both APKs, zero lint errors,
and the complete account-free native plan (one existing capability skip).
Original-red/candidate-green PDF and camera probes passed on the disposable
tablet. Repairs remain uncommitted; no stage is advanced. Live Drive was not run
because its test assumes destructive backup/app-data replacement. See the latest
[implementation entry](CODEX_AUDIT_IMPLEMENTATION_LOG.md) and
[repair ledger](docs/audits/2026-10-02/REPAIRS.md) for exact results and limits.
The repaired debug app remains installed on the disposable tablet with synthetic
Construct data cleared and its test APK removed. It cold-launches to the empty
Projects screen. The exclusive tablet window is released.

The September 25 document-selection and camera-session owner extraction is
implemented and qualified for local debug use without advancing a stage. The
final host gate passes with 1,060 JVM passes, six existing skips, both debug
APKs, and zero lint errors. On an account-free API 36 emulator, the 50 planned
local native invocations yielded 49 passes and one Android hard-link capability
skip (129 method passes, one skip); all seven actual SAF phases and both
process-recovery phases passed. The full runner remains BLOCKED by that skip
and its explicit live-provider omission. On the signed-in TB336FU, a synthetic
PDF photo capture through the physical camera persisted after force-stop and
relaunch. Signed release is BLOCKED by missing external upload-keystore
settings; live Drive qualification is BLOCKED by the current test's destructive
root/app-data assumptions on the populated tablet. See the latest
implementation-log entry for candidate identities, review, and evidence.

At the September 25 handoff the tablet ran the qualified debug APK and was
signed in after force-stop/relaunch; an older duplicate package was disabled
with its data preserved. That is historical app-identity evidence. The October
2 disposable qualification/reset state above is current; no stage is advanced.

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


Sub-drawing tool work (rounds 3-4, 2026-11-20, uncommitted on the task branch): the
naming popup arms from an observed crop-list snapshot; the open sub-drawing carries a
visible Rename / Back to page strip placed below the floating page-number and undo/redo
controls with a one-line clipped label, and its repeated bottom banner is gone; every crop
on a page gets its own palette colour (badge colour was replaced in round 5, below); the
top-left arrow returns to the page in both orientations; a cancelled new crop is removed
by an abandon command that drops its creation record so Undo/Redo cannot resurrect it;
labels show the typed name plus the sheet code verbatim (104 (E1)); the rename popup says
Cancel keeps the current name; the name field saves on keyboard Done and its dialog
scrolls with imePadding so OK stays reachable. Commands: ./gradlew --console=plain
:app:assembleDebug :app:testDebugUnitTest :app:lintDebug - BUILD SUCCESSFUL in 2m 37s,
1121 JVM tests, zero failures/errors, six existing skips, lint zero errors and no new
warning categories. Strip placement, badge appearance, dialog keyboard behaviour are
verified by compile/lint/unit tests only - a tablet and landscape-phone tap-through
remains. No audit stage is advanced.


Sub-drawing badge behaviour (round 5, 2026-11-20, uncommitted on the task branch): a badge is now part of its drawing. Each crop stores its own radius as a page-width
fraction (SubDrawing.badgeRatio), so it grows and shrinks with page zoom like a photo pin,
keeps that size across close/reopen, bundle export/import and Drive sync, and a resize is one
undoable reducer command (AnnotationReducer.resizeSubDrawingBadge). A crop drawn while zoomed
into a small room spawns a badge sized for that zoom (SubDrawingGeometry.badgeRatioForZoom);
a crop with no stored size (drawings saved before sizes existed) falls back to a size scaled
by its own crop and capped, so old sheets never show a huge marker. With the Pan tool a
long-press on a badge selects that sub-drawing with the usual cyan highlight and the existing
Open/Delete toolbar; while it is selected a pinch resizes only that badge (the page does not
zoom), a plain pinch with nothing selected still zooms the page, and tapping empty space ends
the selection. Badges are black and white only - white circle, black outline, black dividing
line, black sheet number over black room name, shrinking to fit at every size; crop rectangles
keep their palette colours. The page shows one marker set at a time: badges only by default,
rectangles only after a long-press on the Sub-drawing tool button, never both, and the choice
is stored in app_settings so it survives an app restart. Wire validation bounds badgeRatio to
the pinchable range and an absent field stays the derived default. Commands: ./gradlew
--stacktrace --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug - BUILD
SUCCESSFUL in 2m 45s, 1129 JVM tests, zero failures/errors, six existing skips, lint zero errors.
Published to the beta feed only (tools/publish_beta.ps1): sotaware-beta beta-3; the official feed
is untouched. Badge painting, selection highlight, pinch feel, spawn size at a zoom, and the
tool-button long-press toggle are verified by compile/lint/unit tests only - a tablet tap-through
remains. No audit stage is advanced.

Sub-drawing badge hold-to-select and legacy sizes (round 6, 2026-11-20, uncommitted on the task
branch): holding a finger on a badge now selects that sub-drawing - cyan highlight plus the normal
Open/Delete toolbar - and no longer opens it on release, so pinch-to-resize is reachable; a quick
tap still opens the sub-drawing, and a timed hold off a badge keeps starting text selection. A crop
with no stored badge size (drawings made before round 5) derives a marker no bigger than a badge
drawn today at the whole-sheet zoom, so old drawings no longer show huge badges; sizes the user
pinched are kept exactly. Gate: BUILD SUCCESSFUL in 3m 11s, 1131 JVM tests, zero failures/errors,
six existing skips, lint zero errors. Published to the beta feed only: sotaware-beta beta-4.
Hold-to-select, highlight, pinch feel and legacy badge sizes are compile/lint/unit verified - owner
tap-through pending. No audit stage is advanced.


Sub-drawing code cleanup (round 7, 2026-11-20, uncommitted on the task branch): no behaviour,
screen, saved format, field name, version or feed change - the app is the same as published
beta-4. The page's sub-drawing painting and touch targets now live in one owner,
`SubDrawingPageMarkers` (crop rectangles, badges, the creation preview, `badgeAt`, `hitItems`,
the drag rectangle); the open-crop strip and the name/rename dialogs live in
`ui/SubDrawingOverlay.kt`; the viewer keeps one `clearPageSelection()` and one
`applyPageSelection()` so tapping, the overlap picker, holding a badge and a page switch all end
and start a selection the same way; `AnnotationReducer` routes every page-family add, replace and
delete - sub-drawings included - through shared owners. Dead code is gone (`SubDrawingPalette.overlaps`,
`cropFactor`, `DEFAULT_BADGE_CAP`, unused viewer state and imports) and two comments that claimed
the inherited scale was scaled by a crop factor now say what the code does. Gate: BUILD SUCCESSFUL
in 2m 39s, 1133 JVM tests (two new marker/inherited-scale regressions), zero failures/errors, six
existing skips, lint report 130 issues with zero errors. Emulator tap-through on the disposable
blueprint fixture: badges black and white, long-press on the Sub-drawing tool toggles
badges/rectangles, a drag creates a crop that takes an unused colour and its name shows on its
badge, the overlap picker selects it, Open shows the strip ("105 (SHEET 1)", Rename, Back to page),
Rename updates the label, holding a badge turns it cyan with Open/Delete without opening, tapping
empty space deselects, undo/redo steps the name and the crop. The new crop was undone so the
fixture is as found. Two-finger pinch-to-resize cannot be exercised on this emulator. No audit
stage is advanced.


Google Drive source folder is remembered (round 8, 2026-11-20, uncommitted on the task branch):
Dave's tablet report - after choosing his projects folder with "Use this folder", the next Google
Drive visit started again at My Drive. The chosen folder was already saved per account in
`project-library-v1.json`, but only as a label: the picker always opened My Drive and the Projects
screen never showed it. Now `ProjectLibrary.chosenDriveRoot` is the single owner of "which folder
does this device open", the picker opens straight into the folder the connected account chose, the
Projects screen lists it ("Google Drive: New Bid Documents", with its sign-in address) and tapping
it opens the picker there, and choosing a different folder replaces the remembered one. A saved
folder that cannot be listed any more (deleted, access removed, a folder belonging to a different
Google account) lands at My Drive with "Your chosen Google Drive folder is not available any
more..." instead of an error or a blank list. The stored record keeps its shape (schema 1; the new
sign-in label is optional on read, so beta-4 libraries restore unchanged), and a saved source can
never be the picker's own "My Drive" id. Gate: BUILD SUCCESSFUL in 2m 17s, 1138 JVM tests, zero
failures/errors, six existing skips, lint report 130 issues with zero errors. Emulator (no account
sign-in, as required): the Projects screen is unchanged with no choice saved; with a synthetic
saved folder it shows "Google Drive: New Bid Documents / dave@example.test", tapping it opens the
Drive picker, and removing it restores the original screen (`chosen_drive_row_visible.png`,
`chosen_drive_row_opens_picker.png`, `projects_after_cleanup.png`). Opening a real folder in Dave's
own Drive, the unreachable-folder message on his tablet, and pinch-free re-sign-in are owner-side:
they need his account, which the emulator must not sign in to. Published to the beta feed only
(tools/publish_beta.ps1): sotaware-beta beta-5 (versionCode 5), carrying the round-7 sub-drawing
cleanup as well; the official feed is untouched. No audit stage is advanced.


Sub-drawing drag shows its rectangle on every page (round 9, 2026-11-20, uncommitted on the task
branch): Dave's tablet report - dragging out the first sub-drawing on a page that has none showed no
box at all mid-drag, only the purple banner; the box appeared once the page held one crop, so it
looked random. Cause: in `PdfPageRenderer`, the live creation preview (`subDrawingDraft` ->
`SubDrawingPageMarkers.drawCreationDraft`) was drawn inside the block that paints existing crops,
gated by `currentSubDrawings.isNotEmpty()`, so a page with no crops painted nothing while dragging -
the drag rule and the pointer loop were correct, which is why the release still captured the area.
The preview is now drawn on its own, outside that block, on every page in both badge and rectangle
mode, from the first movement until the finger lifts; crop painting, sizes and colours stay in
`SubDrawingPageMarkers`. Gate: BUILD SUCCESSFUL in 1m 48s, 1139 JVM tests, zero failures/errors, six
existing skips. Emulator (blueprint.pdf): mid-drag dashed box on sheet 3 with no sub-drawings
(`middrag_sheet3_first_drag.png`), on sheet 1 in badge mode (`middrag_sheet1_drag.png`) and in
rectangle mode (`middrag_sheet1_rectangle_mode.png`); every test drag was cancelled at the naming
dialog so sheets 2-4 of the test PDFs keep no sub-drawings, and the marker mode was toggled back
(`sheet1_badges_restored.png`). Published to the beta feed only (tools/publish_beta.ps1):
sotaware-beta beta-6 (versionCode 6); the official feed is untouched. No audit stage is advanced.


Three tablet reports: sub-drawing release, page codes, Drive folder (round 10, 2026-11-20, uncommitted
on the task branch):
1. Releasing the Sub-drawing drag did nothing (beta-6 regression). Cause: `PdfPageRenderer` armed its
   naming popup by matching the dragged rectangle against the page's crop list
   (`pendingCreatedCrop` -> `sameCrop`), so when the page already held an unnamed crop over exactly that
   area it armed for *that* crop; Cancel discarded the old crop, and discarding it removed the new crop
   too - no dialog, nothing created. The viewer now generates the crop's id at release, passes it to the
   host (`onCreateSubDrawing(crop, id, zoom)`, `MainActivity.createSubDrawing` publishes under that id),
   and arms only for the crop with that id; a page with no crops behaves as before.
2. "Identify Page Codes" registered nothing. Cause: `PageCodeIndex.scan` used
   `checkNotNull(loadPage(page))`, so the first sheet whose text could not be read (sheet numbers drawn
   as vector graphics OCR to nothing) threw, the caller discarded every code already found and showed
   "did not finish". Unreadable sheets are skipped now; readable ones register, progress stays honest,
   and a pass that reads no text on any page still fails so existing labels are kept. Codes show in View
   Pages and on badges/sub-drawing names through the existing owners - unchanged.
3. Google Drive showed the Connect screen before the chosen folder. Cause: `DriveProjectPicker` only
   opens the remembered folder after a grant, and the grant came from the Connect button, so every visit
   started at that screen. With a usable sign-in and a saved folder for it, the picker now asks for
   access itself once at open (`autoConnectPending`) and `accept()` puts the chosen folder at the top of
   the path; no sign-in, no saved folder, or an unconfigured build still shows Connect.
Gate: BUILD SUCCESSFUL in 2m 38s, 1144 JVM tests (one new sub-drawing id/naming regression, three new
page-code regressions, one new Drive sign-in regression; the old "a failed page never returns a partial
index" test was updated to the correct skip-and-keep behaviour), zero failures/errors, six existing
skips, lint report 130 issues with zero errors. Emulator (blueprint.pdf, no account sign-in): mid-drag
dashed box (`middrag_box_2.png`), naming dialog on release twice (`middrag_live.png`,
`middrag_box_3.png`), name saved and badge drawn "SHEET 1 / 1104" (`named_badge.png`), both steps undone
so the test PDF keeps no sub-drawings (`after_undo.png`); page-code pass completes and reports honestly
on the text-less fixture (`codes_result.png`); Drive with no sign-in still shows Connect
(`drive_no_signin.png`). Owner-side on his tablet: real Drive folder opening, re-sign-in prompt, and
registering codes on his own drawings. Two-finger pinch cannot be exercised here. No audit stage is
advanced. Published to the beta feed only (tools/publish_beta.ps1): sotaware-beta beta-7 (versionCode
7); the official feed is untouched.

Two tablet reports: page codes on a real 28-sheet set, View Pages labels (round 11, 2026-11-21,
uncommitted on the task branch):
1. "Identify Page Codes" registered only one sheet of twenty-eight. Cause: `PageCodeIndex.codeInRegion`
   joined every OCR word inside the dragged box and accepted the whole string only under the 80-character
   label limit. On a real title block that box always also covers the words beside the sheet number
   ("sheet number:", "checked by:", "PAW Typical Partition Detail"), so the joined text became a long
   sentence, the limit rejected it, and every sheet with such a block was dropped - on Dave's set only the
   bare cover-style page registered. `codeInRegion` now keeps the sheet number(s) inside the box
   ("E2.0", "LS1.00", "A0.10") and leaves the surrounding sentences out; a box holding only the code is
   returned unchanged, and ordinary text in the box still answers with what is there. The retry pass added
   while chasing this report was removed: the instrumented scan showed every page returning its text
   (`loadPage` never returned null and no `OcrSessionStaleException` / `OcrSessionClosedException` fired),
   so a second pass and a doubled progress total treated a symptom. Progress counts the sheets of the
   document once each ("Reading page 25 of 28"), unreadable sheets are still skipped and reported through
   `page_codes_unread`, and a scan that reads nothing at all still fails.
2. View Pages labels showed "E2.1 · 26". Dave asked for the code alone: `PdfPageBrowser` now labels each
   tile with its registered page code ("E2.1"), keeping "SHEET n" only where no code was found.
Gate: BUILD SUCCESSFUL, 1148 JVM tests (three scan regressions rewritten to the single-pass behaviour,
one new title-block regression), zero failures/errors, six existing skips, lint report with zero errors;
the full gate and the test pass were run again after publishing beta-8, when one temporary probe line was
left in `OcrIndex.kt` by a scripted edit. Emulator (`/sdcard/Download/codes-28.pdf`, no account sign-in), scan started from sheet 25:
progress "Reading page 25 of 28" (`scan25_progress.png`), completion "Identified codes on 27 of 28 pages"
(`scan25_final.png`; the cover sheet has no code), View Pages labels LS1.00 / A0.00 / A0.10 / A1.00
(`codes_labels.png`), P2.0 (`codes_list25_down.png`), E1.0 and E2.0 (`codes25_labels3.png`), tail E2.1 /
E3.0 / ME1.0 (`codes25_labels.png`). Not checked here: the long-press text-selection highlight - every
long-press on this fixture's zoomed-out pages pans the view and draws no highlight (`longpress_a.png`,
`longpress_b.png`, `longpress_c.png`), so that painting change needs Dave's own tablet with a page zoomed
onto readable text. Two-finger pinch cannot be exercised here. No audit stage is advanced. Published to
the beta feed only (tools/publish_beta.ps1): sotaware-beta beta-8, and beta-9 (versionCode 9) after the
clean re-gate above; the official feed is untouched.

Sub-drawing content in the sheet PDF export, and sub-drawing parity for Clear/photo commands (round 13, 2026-11-21, uncommitted on the task branch):
Dave's exported sheet PDF showed each sub-drawing only as its faint box and round badge: nothing drawn
inside a crop (lines, highlighter, measurements, notes, shapes) appeared, and photos pinned inside a crop
got no photo pages. Cause: `PdfPageExport.kt` drew the crop markers on the sheet page and then went
straight to the page-level pin appendix; it never read `SubDrawing.paths/measurements/notes/shapes/photos`,
and the export snapshot used `copySubDrawing()`, which freezes collections without deep-copying nested
photo-markup maps for the exporter. Fix in `PdfPageExport.kt`: after the sheet page, each sub-drawing gets
its own PDF page rendered sharp from the verified source snapshot through the existing
`PdfBitmapRenderer.renderPageRegionBitmap` (one crop bitmap at a time, budgeted by
`BitmapBudgetPolicy.pdfRenderPlan`, capped at 4096 px), with its own markups drawn in surface-normalized
coordinates, numbered pins, and a title line "SHEET n - name" like the badge; that crop's photo pins then
get appendix pages titled "Sub-drawing SHEET n - name - Pin k", before the sheet-level pin pages. Sheets
with no sub-drawings export exactly as before. `MainActivity` now deep-copies nested markup for export
(`copyForPdfExport`) and numbers pins from one source (page pins, then each crop's pins) so app numbers
match the PDF. Part 2: photo clearing on a crop-owned pin routed through new reducer owners
(`storedPin`/`commitPinReplacement`, `clearPhotoAnnotations`) - previously `clearImageAnnotations` looked
only in `vm.pagePhotoPins` and rejected crop pins; Clear inside an open sub-drawing now calls the new
undoable `clearSubDrawing` (its own markups plus its photos' markups only, sheet and sibling crops
untouched); the viewer publishes the open crop id through `onSubDrawingViewActive(String?)`. Sheet Clear
is unchanged: it clears the page snapshot including every sub-drawing's content, and Undo restores all of
it. Multi-photo pins inside crops already routed through `canAttachSubDrawingPhoto`/
`attachSubDrawingPhoto` (checked, no change needed); project-file round trip already carries crop photos
and photo markups through the canonical snapshot codecs (`SubDrawingSnapshotRoundTripTest`, checked).
Emulator checks (`fresh.pdf`, sheet 1): crop created and opened, red pen line drawn inside it
(`sub_pen.png`), badge on the sheet (`emu_sheet1.png`), sheet exported; the PDF's page 1 is the sheet with
box and badge and page 2 is the crop enlarged sharply with "SHEET 1" title and the red line in place
(rendered to PNGs off-repo). Test crop removed afterwards; `fresh.pdf`, `blueprint.pdf` and
`codes-28.pdf` are untouched on the emulator. Not checked on the emulator: note/photo text entry (the
emulator's Gboard handwriting pad steals keyboard input), measurement label text, multi-photo picker,
and project export/import UI - covered by unit tests instead; two-finger pinch is not possible here.
Gate: BUILD SUCCESSFUL, 1161 JVM tests (seven new `SubDrawingClearCommandsTest` regressions), zero
failures/errors, six existing skips, lint report with zero errors. Published to the beta feed only:
sotaware-beta beta-11 (versionCode 11).

Long-press text selection painted no highlight (round 12, 2026-11-21, uncommitted on the task branch):
Dave's tablet and the emulator both showed the Copy popup over selected words with nothing highlighted.
Cause: `TextSelectionRendering` fed the viewer transform bitmap-pixel rectangles where
`ViewerTransform.toScreen` measures the page in normalized coordinates (0..1), so every highlight rect
and handle anchor was multiplied by the page width a second time and drawn thousands of pixels off the
canvas - selected words with nothing painted. The Copy popup was never affected because
`positionCopyAffordance` divides by `bW`/`bH`. Fix: one `pagePoint(px, py, bW, bH)` normalization in
`TextSelectionRendering`, used by `highlightRect`, `handleAnchors`, `hitsHandle` and both painters; the
painting also moved to the end of the page canvas so search highlights, strokes and sub-drawing markers
can no longer cover a selection, and the highlight now draws fill plus outline so it reads on dense
title-block line work. Emulator (`codes-28.pdf`, sheet 2, Pan tool, hold on the title block): blue
highlight with its handles under the Copy popup (`hl_words.png`), highlight follows the drag
(`hl_drag_png.png`), Copy clears the popup and the highlight (`hl_copy_png.png`). Badge long-press is
untouched code (`SubDrawingPageMarkers`) and was not re-checked here: this fixture page has no crops.
Gate: BUILD SUCCESSFUL in 2m 32s, 1153 JVM tests (five new `TextSelectionRenderingTest` coordinate
regressions), zero failures/errors, six existing skips, lint report with zero errors. Published to the
beta feed only: sotaware-beta beta-10 (versionCode 10).

## History

The complete earlier roadmap is preserved in
[the dated archive](docs/archive/CODEX_AUDIT_ROADMAP_2026-09-11.md).
Use it for provenance, not as an instruction to restart a completed stage.


## UX change plan, phases 2-5 (2026-11-25, uncommitted on codex/stage-3-transactional-switching)

Phases 2-5 of Dave's approved UX change plan are implemented on top of HEAD 36f7fc6 plus the existing
uncommitted work; nothing is committed or published. Phase 2: drag-to-move for selected lines, polylines,
measure vertices, photo pins and sub-drawing badges (badge position is new optional snapshot data,
`badgeX`/`badgeY`, validated both-or-neither in 0..1); long-press means text selection in every tool;
tap selects, double-tap opens. Phase 3: one dark selection toolbar for every selected thing, instant
delete with a "Deleted - Undo" snackbar, auto-select after placing. Phase 4: tap a selected tool to open
a live settings panel (long-press removed), Sub-drawing re-tap and a menu item switch badges/rectangles,
new Settings switches (info box, default off; auto-hide controls; glove mode), Settings now scrolls, no
back/page arrows inside a sub-drawing. Phase 5: search bar with match count, previous/next and a
This sheet / All sheets choice (replaces both dialogs); page-code box with two-finger pan/zoom, corner
handles, move, and Use this box / Redraw; automatic page codes read from the bottom-right corner when a
drawing with no stored codes opens (stored as `source: auto`, never replaces stored codes, a manual pass
cancels it, a short plain-words notice); Slate (default), High contrast and Light themes with every viewer
colour in `ui/theme`. Found and fixed on the way: search highlights were drawn off the sheet (bitmap pixels
passed to the 0..1 transform, the same mistake fixed for text selection in round 12). Still open: on
blueprint.pdf the search highlight sits about one text height below the word; the text-position
convention (`getY()` as top edge) is audited and golden-tested, so it was not changed here.
Gate on the final candidate: BUILD SUCCESSFUL, 1181 JVM tests, zero failures/errors, six existing skips,
lint clean; `:app:assembleDebugAndroidTest` builds. Instrumented tests were not run on a device.

Search highlight offset (2026-11-25): fixed. PDFBox `TextPosition.getY()` is the glyph baseline
(page height minus the text matrix's baseline translate, confirmed in the pdfbox-android 2.0.27.0
bytecode), not the top edge; boxes for upright and upside-down text were built downward from the
baseline. Sideways (90/270) extents were already right and are unchanged, so the rotated golden fixture
still passes. Gate: BUILD SUCCESSFUL, 1184 JVM tests, zero failures/errors, six existing skips, lint zero
errors. Emulator: the blueprint.pdf highlight now sits on the word.
