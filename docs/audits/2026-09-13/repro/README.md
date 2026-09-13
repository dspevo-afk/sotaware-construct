# Negative audit probes

These are the corrected source files actually compiled and executed during the September 13 audit. They assert desired behavior and deliberately fail against application c265c963788261b23126fea8cc91e2812d739539. They are not disabled product tests or evidence of a green release.

Use a new throwaway clone/worktree, never the existing dirty feature checkout. Copy RepositoryAuditProbeTest.kt into app/src/test/java/com/example/myapplication/audit/ and RepositoryAuditExportProbe.kt into app/src/androidTest/java/com/example/myapplication/audit/. These paths are outside normal source sets while stored here.

From that throwaway checkout, with the configured JDK/SDK:

```powershell
.\gradlew.bat --no-daemon --continue --console=plain --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*RepositoryAuditProbeTest'
```

Expected current baseline: three JVM tests, two failures (stale recalibration label and invalid derived length), one passing bitmap-budget assertion. --continue allows the APKs to assemble despite expected test failures. On Unix use ./gradlew with the same arguments.

For native tests, first create and verify a new disposable emulator. Select its exact serial on every ADB command. Do not use, clear, reinstall over or uninstall from an existing physical-device installation. Install the application APK and instrumentation APK only on that verified disposable target, then run:

```text
adb -s <VERIFIED_DISPOSABLE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <VERIFIED_DISPOSABLE_SERIAL> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <VERIFIED_DISPOSABLE_SERIAL> shell am instrument -w -r -e class com.example.myapplication.audit.RepositoryAuditExportProbe,com.example.myapplication.stage10.PdfExportSourceInstrumentedTest,com.example.myapplication.ExampleInstrumentedTest com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
```

Replace the serial placeholder only after verifying target ownership. Current expected result: five native tests, two failures (physical page size and dense photo appendix), three passing controls. Inspect instrumentation statuses and the final summary; ADB can return zero despite test failures. All drawing/photo inputs are synthetic. The probes delete only their own fixture directories.

After fixing each finding, promote the associated probe to the normal regression source set and require a genuine pass. Preserve source-verification, resource-lifetime and cancellation tests. Do not add @Ignore or weaken the numerical/page-size assertions.
