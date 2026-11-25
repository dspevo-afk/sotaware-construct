# Confirmed repair ledger - 2026-10-02

| Severity | Original source | Confirmed behavior and repair |
|---|---|---|
| P1 | `stage2/LocalDocumentRepository.kt:766-839` | Sole corrupt manifest is quarantined; retry treats its absence as fresh and forks source identity. Retained documents/quarantine now prevent fresh allocation after valid recovery options are exhausted. |
| P2 | `stage2/LocalDocumentRepository.kt:618-731` | Mismatched empty restore can erase accepted slots. Restore now validates exact manifest association before mutation, under manifest then document lock. This was a latent API defect, with no mismatched production caller found. |
| P2 | `OcrIndex.kt:204-240` | Whole-pass shared cache lock blocks foreground work and retains commit values. OCR runs outside the authority lock; each page and final marker publish in short fenced transactions. |
| P2 | `stage6/DocumentBundleService.kt:337-339` | Supported 2,048-photo bundles exceed the 64 KiB marker cap. Capacity is derived from all bounded owned artifacts; parsing/cleanup limits remain intact. |
| P2 | `PdfPageRenderer.kt:811-867,1191-1251,1574-1600` | Gutter starts clamp to page edges; navigation pinch release becomes a creation tap. On-page admission and navigation drag state prevent both. |
| P2 | `tools/test_run_logcat_app.ps1:19-42` | PASS retains expected-negative exit code 2 and fails GitHub's shell wrapper. Clear the global code only after all assertions succeed. |
| P2 | `CameraCaptureSessionOwner.kt:361-411,457-463` | Unresolved PUBLISHED recovery reattaches a photo after Undo. Undo/Redo reread the authoritative journal under the owner mutex and document barrier; matching unresolved publication requires save/discard first. |

Main-source paths above are beneath `app/src/main/java/com/example/myapplication`.
The adversarial regressions failed on original code and pass on repaired code.
The 2,048-photo test is an actual bounded service round trip; OCR controls use
deterministic barriers; camera tests use synthetic private assets/save faults;
PDF tests drive the real renderer. ZIP fixtures now reach intended negative
branches while retaining explicit descriptor rejection. Positive PDF gesture
coordinates are fitted-page based; no assertion was removed.

## Validation

Host gates: 1,076 JVM tests, 1,070 passes, six existing filesystem-capability skips, zero failures/errors; both debug APKs; lint zero errors and 111 warnings. Final account-free native coverage on the authorized disposable TB336FU/API 36: 51 passing invocations and one existing hard-link capability skip across the 52-invocation source-verified plan; 135 method passes and 1 skip, zero failures. All seven production SAF phases and both process-recovery phases passed. The full runner remains BLOCKED because the required hard-link method is skipped; live-provider coverage is separately omitted and unqualified; the ten remaining classes passed separately on the same APKs.

App APK: `E72F03BAED72F730CA92CDFBBD4BA2B9F0F2C03F417996550BB3DBCE23F3E943`.

AndroidTest APK: `05B214A6A93949E6A145B04914435CC9856E418E816B6419167AC2D98B25EDC2`.

Independent repair reviews found no blocker in persistence lock ordering,
bundle cleanup, OCR ownership/fences, page navigation admission, or camera
history guards. The complete logs retain earlier fixture/build failures and a
Windows checkpoint reader-sharing conflict. No active checkpoint is read during
the final runner. These are not counted as passes.

No real Drive, physical OEM camera, signed release, power-loss durability,
other-device/provider, or performance/heap certification is claimed. The native
hard-link assumption remains a capability skip and full-run BLOCKED status.
