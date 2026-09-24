# September 22 code-audit repairs

Date: September 23, 2026. Branch: `codex/stage-3-transactional-switching`.
The starting HEAD is `c265c963788261b23126fea8cc91e2812d739539`;
the existing feature worktree remains uncommitted.

## Repair scope

The follow-up addresses A1–A7 in the [audit](README.md). The user's request
covered the five bug groups and allowed the small lifecycle/cache refactors.
The work keeps the existing repository, sync coordinator, and UI owners.

| Finding | Change |
| --- | --- |
| A1, remote adoption | Durable pending-local-apply state separates Drive relocation from canonical/photo application. The accepted cursor advances only after local apply. Pending and ambiguous historical adoptions block upload and expose an explicit remote conflict/retry. |
| A2, source fingerprint | Hashing checks cancellation per read, closes an owned stream when cancellation interrupts a blocking read, and rejects repeated zero-byte reads. |
| A3, SAF permissions | New read grants are leased during admission and released after a failed operation only when accepted project, recent, manifest, and active-session ownership checks prove they are unused. Existing and shared grants remain intact. |
| A4, repository save | The raw save overload validates document ID, exact source URI, and fingerprint against the manifest while holding the manifest and document locks before staging bytes. |
| A5, Drive consent | The browser and pending consent correlation survive Activity recreation; operation, generation, account identity, and an Activity-scoped owner reject stale results and require reconnect after process death. Provider tokens are not saved in UI state. |
| A6, owner lifetime | Successfully closed document/sync owners are removed from the composition registries; failed retirements stay available for retry. |
| A7, page-code cache | The disposable cache has a 32 MiB/64-file aggregate budget, with oldest inactive entries evicted while active viewer entries remain leased. Unavailable inventory blocks new writes. |

## Qualification

The final candidate uses JDK 21 and the configured Android SDK. This command
passed after the last production change:

```powershell
.\gradlew.bat --no-daemon --no-build-cache --stacktrace --console=plain '-Pkotlin.incremental=false' :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

The JVM report contains 1,041 tests: 1,035 passed, six existing platform skips,
zero failures/errors. Lint reports zero errors and 120 warnings. Both debug
APKs built. `python -B tools/test_native_qualification.py` passed 36 cases;
`powershell -NoProfile -File tools/test_run_logcat_app.ps1` passed seven
mocked-ADB cases. Focused regressions for adoption/retry, metadata, repository
association, fingerprint cancellation, grant/consent state, cache budgeting,
and document recreation passed before the final full host gate.

Final APK SHA-256:

- `app-debug.apk`: `DBA95756D2B4EA5B5E6585FEA9B5249EA76C44A57DB324DEFD9D16EC8B080ED4`
- `app-debug-androidTest.apk`: `CB40093B9602E773D9A6BF81C967BC10BF12880E7B8D1D2DBD94A5021C096B4A`

The bounded independent reviews found no remaining high-confidence defects in
the repaired sync, persistence/cache, or project/consent paths. Two sync review
findings were fixed before qualification: a resolved metadata fallback now
clears its error, and failed adoption-journal cleanup retains a durable retry
marker. The full host run also exposed older fixtures that bypassed manifest
admission; those fixtures now use `resolveOrCreate` and their tests pass. Native
qualification exposed a local-file picker regression from the new content-only
grant helper; the picker now requests persisted grants only for content URIs,
and the failing tool restoration class passed on the tablet. An intermittent
project-recent reopen timeout showed that navigation to an already ready
document waited behind recent-file and project-library writes. That navigation
now happens as soon as the coordinator confirms the active session, before
those independent writes; the project browser class passed the focused rerun.

The September 14 closeout remains separate historical evidence and is not
reused as proof for these changed inputs.

## Physical-tablet result

The checked-in native runner used a disposable `TB336FU`, API 36, with the
production APK above and AndroidTest APK
`E7EE76373BEA1147A95F105906CE65305C2541CDE62121DB80192FF8FC8D56CD`
for its local matrix. Later test-only edits produced the AndroidTest APK listed
above. The local matrix exercised the real app and SAF flows with synthetic
fixtures. All seven fresh-install SAF phases passed, as did the two process
recovery phases. The full suite stopped at its known hard-link capability
test: that class had three passing methods and one skipped hard-link method.
The ten classes after it were run through the same checked-in runner in a
separate audit selection and all passed. Across the 50 planned local
invocations, 49 passed and one was skipped; at method level, 129 passed and
one was skipped, with zero local failures. The account-dependent live-provider
class was omitted from that local matrix. It was subsequently attempted with
the authorized tablet account. Authentication, disposable-folder creation,
actual SAF import, the first live upload, and remote snapshot/photo-asset
verification succeeded in bounded runs. The complete class did not pass: its
second UI note step timed out before the unchanged-photo second upload could be
proved. Test-only selector/picker assumptions found in the first attempts were
corrected; the final test-only changes compiled with `:app:assembleDebugAndroidTest`
but have no passing live-provider run. Eight exact folders created in these
attempts were selected by their recorded run IDs and moved to Drive Trash, then
absent from a fresh active Drive search. Older matching folders were untouched.
The tested debug app was reinstalled cleanly and signed in without a configured
backup folder. Evidence is in task-owned OS temp `construct-live-20260923-*`.
The full-run status remains **BLOCKED** by the hard-link capability skip and
incomplete live-provider qualification; this is not release qualification.

The full-run and remaining-class evidence is in task-owned OS temp folders
`construct-20260923-repairs-*/native-final-complete` and `native-tail`.
The runner recorded source-tree digest
`a38fdc7b48a3236156c60856b7393ca86d58e446a679390948d69d5e54705870`.
An earlier device attempt hit a transient ADB daemon-start failure before a
test began; a fresh install/probe of that class passed. Earlier native
failures in consent-test setup, local-file grant admission, and project
reopen led to the corrections described above; they are not counted as
passing runs. The final debug APK was reinstalled with synthetic app data
cleared, cold-launched, and verified as the resumed Activity. Tablet
auto-rotation remained enabled with user rotation zero.

`git diff --check` and `git diff --cached --check` pass. The pre-existing
dirty feature worktree was preserved; these repairs are uncommitted, with no
push, release, or roadmap-stage advancement.
