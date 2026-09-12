# Native and SAF qualification

`tools/run_native_qualification.py` is the checked-in host runner for the
native instrumentation matrix and the production SAF workflow. It derives
test identities from the current Kotlin/Java sources, so a newly added audit
method changes the expected count without editing a stale number. It records
the selected source, APK, fixture, command, and instrumentation identities in
the evidence directory.

The runner is destructive to one application package. It requires an explicit
`--serial` and the exact `--disposable-confirmation
I_UNDERSTAND_DISPOSABLE_TARGET` token. It never auto-selects a device. For an
emulator, pass `--avd-name`; the runner compares it with `adb emu avd name`
before any install or uninstall. A physical device is refused unless both
`--allow-physical` and `--physical-ownership-confirmation
I_UNDERSTAND_PHYSICAL_TARGET` are supplied; `--physical-model` can add a model
identity check. Only the application package derived from `applicationId` is
uninstalled. The instrumentation APK/package is installed with `-r` and must
remain present so its provider fixture and oracle survive target resets.

Build the APKs with the normal Android workflow before invoking the runner.
The runner itself does not invoke Gradle. Its output directory must be new or
empty; use an external, task-owned directory so checkpoints and logs survive a
failed run:

```powershell
$evidence = Join-Path ([IO.Path]::GetTempPath()) ('construct-native-' + [guid]::NewGuid().ToString('N'))
python -B tools/test_native_qualification.py
python -B tools/run_native_qualification.py `
  --serial $env:ANDROID_SERIAL `
  --avd-name $env:ANDROID_AVD_NAME `
  --disposable-confirmation I_UNDERSTAND_DISPOSABLE_TARGET `
  --suite smoke `
  --apk app/build/outputs/apk/debug/app-debug.apk `
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk `
  --output-dir $evidence
```

The narrow `smoke` suite dynamically selects the current account-free
`stage10.Audit*` classes plus `Stage10BackupPolicyInstrumentedTest`, excluding
the guarded process-recovery class and the broad persistence-recovery class.
The current source set contains 16 tests and has no intended skips. Use
`--suite audit` with one or more fully qualified classes or `Class#method`
selectors for a focused native run:

```powershell
python -B tools/run_native_qualification.py `
  --serial $env:ANDROID_SERIAL --avd-name $env:ANDROID_AVD_NAME `
  --disposable-confirmation I_UNDERSTAND_DISPOSABLE_TARGET `
  --suite audit `
  --class com.example.myapplication.stage10.AuditViewerInstrumentedTest `
  --class com.example.myapplication.stage10.AuditMeasurementHintsInstrumentedTest `
  --output-dir $evidence
```

The `saf` suite runs these seven guarded methods in source-verified order:

| Phase | Guarded behavior | Target reset |
| --- | --- | --- |
| `pdf-export` | cropped/rotated production PDF export | fresh target install |
| `pdf-picker-recreation` | picker survives activity recreation | fresh target install |
| `pdf-picker-cancel` | cancellation retires the prepared operation | fresh target install |
| `export` | complete production bundle export | fresh target install |
| `import` | complete replacement and identity retention | fresh target install |
| `relaunch` | durable state after import and force-stop | force-stop target only |
| `retired-format` | rejected retired bundle preserves state/input | fresh target install |

Run all seven with `--suite saf`. Individual `--phase` selections are allowed
for independent phases; `import` requires `export` in the same invocation and
`relaunch` requires `import`. The runner canonicalizes selected phases to the
table order, retains the instrumentation provider/oracle, verifies the target
package is absent after each authorized uninstall, then verifies it is
installed before instrumentation. A missing, extra, or unresolvable phase
guard blocks the run before package mutation.

The `recovery` suite is a fixed, source-verified pair for the interrupted
publication/process-restart fixture. It first invokes
`stageInterruptedPublications` with `stage10.recovery=stage` after a fresh
target install. It then force-stops the target package and invokes
`recoverAfterProcessRestart` with `stage10.recovery=restart`; this second step
does not uninstall or reinstall the target. The runner rejects a missing,
extra, reordered, or incorrectly guarded method before any package reset.
`--suite full` schedules the seven SAF phases followed by this recovery pair,
then the other locally discoverable instrumentation classes. Generic smoke,
audit, and direct full-class dispatch cannot select the guarded recovery class.

The live provider class is reported as an explicit omission and makes the result `BLOCKED`, never green. To run that
account-dependent class intentionally, add both
`--include-live` and `--live-confirmation I_UNDERSTAND_LIVE_ACCOUNT_DATA`; keep
that matrix manual and disposable. A reported assumption, skipped test, count
mismatch, absent status identity, instrumentation failure, or missing phase
coverage is preserved as `SKIPPED`, `FAIL`, or `BLOCKED` in the report and
cannot be treated as a pass.

If the shared host ADB server reports `cannot connect to daemon`, the runner
retries a read-only device/package admission query once and retains both command
logs and exit statuses. Installs, uninstalls and instrumentation are never retried
by this mechanism. A repeated connection failure still stops qualification.

Each evidence directory contains `checkpoint.json`, `summary.json`, one raw
log under `commands/` for every adb command, one parsed report under
`reports/` for every instrumentation invocation, APK/source/fixture SHA-256
identities, the discovered test list, and the planned/omitted coverage. Exit
codes are `0` for a complete pass, `1` for a selected command/test failure, and
`2` for authorization, environment, explicit omission, or skipped-coverage
blocking. Within an instrumentation log, every expected test must have a start
and terminal status, terminal status `0` for a pass, and the invocation must
end with `INSTRUMENTATION_CODE: -1`. A final code `0` is treated as canceled or
unfinished; per-test `-3` and `-4` are retained as skips.

For the deterministic CI job, after `:app:assembleDebugAndroidTest` and an
authorized disposable emulator are ready, the exact runner invocation is:

```bash
python3 -B tools/run_native_qualification.py \
  --serial "$ANDROID_SERIAL" \
  --avd-name "$ANDROID_AVD_NAME" \
  --disposable-confirmation I_UNDERSTAND_DISPOSABLE_TARGET \
  --suite smoke \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output-dir "$RUNNER_TEMP/native-qualification"
```

The workflow should provide both environment values from the emulator it
started and upload the evidence directory. The parser/safety regression suite
is account-free and does not use adb or Gradle:

```bash
python3 -B tools/test_native_qualification.py
```
