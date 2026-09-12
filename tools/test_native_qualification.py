#!/usr/bin/env python3
"""Parser and safety regression tests for run_native_qualification.py.

These tests are intentionally account-free and never call adb or Gradle.  All
synthetic source files and hash inputs are created below a unique OS temporary
directory so running this file does not modify the checkout.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_native_qualification as qualification  # noqa: E402


ROOT = Path(__file__).resolve().parents[1]
ANDROID_TEST_ROOT = ROOT / "app" / "src" / "androidTest" / "java"


def passing_output(*selectors: str) -> str:
    blocks = []
    for index, selector in enumerate(selectors, start=1):
        fqcn, method = selector.split("#", 1)
        blocks.append(
            "\n".join(
                [
                    f"INSTRUMENTATION_STATUS: class={fqcn}",
                    f"INSTRUMENTATION_STATUS: test={method}",
                    f"INSTRUMENTATION_STATUS: current={index}",
                    f"INSTRUMENTATION_STATUS: numtests={len(selectors)}",
                    "INSTRUMENTATION_STATUS_CODE: 1",
                    f"INSTRUMENTATION_STATUS: class={fqcn}",
                    f"INSTRUMENTATION_STATUS: test={method}",
                    "INSTRUMENTATION_STATUS_CODE: 0",
                ]
            )
        )
    count = len(selectors)
    noun = "test" if count == 1 else "tests"
    return "\n".join(blocks) + f"\nOK ({count} {noun})\nINSTRUMENTATION_CODE: -1\n"


def status_output(
    events: list[tuple[str, int, str | None]],
    *,
    final_code: int | None = -1,
    include_summary: bool = True,
) -> str:
    """Build output with one start/terminal status pair per supplied test."""

    lines: list[str] = []
    for index, (selector, terminal_code, detail) in enumerate(events, start=1):
        fqcn, method = selector.split("#", 1)
        lines.extend([
            f"INSTRUMENTATION_STATUS: class={fqcn}",
            f"INSTRUMENTATION_STATUS: current={index}",
            f"INSTRUMENTATION_STATUS: numtests={len(events)}",
            f"INSTRUMENTATION_STATUS: test={method}",
            "INSTRUMENTATION_STATUS_CODE: 1",
            f"INSTRUMENTATION_STATUS: class={fqcn}",
            f"INSTRUMENTATION_STATUS: current={index}",
            f"INSTRUMENTATION_STATUS: numtests={len(events)}",
        ])
        if detail:
            lines.append(f"INSTRUMENTATION_STATUS: stack={detail}")
        lines.extend([
            f"INSTRUMENTATION_STATUS: test={method}",
            f"INSTRUMENTATION_STATUS_CODE: {terminal_code}",
        ])
    if include_summary:
        failures = sum(code == -2 for _, code, _ in events)
        skips = sum(code in {-3, -4} for _, code, _ in events)
        if failures or skips:
            lines.append(
                f"Tests run: {len(events)}, Failures: {failures}, Errors: 0, Skipped: {skips}"
            )
        else:
            noun = "test" if len(events) == 1 else "tests"
            lines.append(f"OK ({len(events)} {noun})")
    if final_code is not None:
        lines.append(f"INSTRUMENTATION_CODE: {final_code}")
    return "\n".join(lines) + "\n"


class SourceDiscoveryTests(unittest.TestCase):
    def test_kotlin_and_java_test_identities_are_discovered(self) -> None:
        with tempfile.TemporaryDirectory(prefix="construct-native-parser-") as temporary:
            root = Path(temporary)
            kotlin = root / "example" / "SyntheticKotlin.kt"
            kotlin.parent.mkdir(parents=True)
            kotlin.write_text(
                """
                package example
                // @Test fun commentedOut() {}
                class SyntheticKotlin {
                    @Test(timeout = 1000)
                    fun firstCase() {}

                    @Test fun secondCase() = Unit
                }
                """,
                encoding="utf-8",
            )
            java = root / "example" / "SyntheticJava.java"
            java.write_text(
                """
                package example;
                public class SyntheticJava {
                    @org.junit.Test
                    public void javaCase() {}
                }
                """,
                encoding="utf-8",
            )
            discovered = qualification.discover_tests(root)
            self.assertEqual(
                {
                    "example.SyntheticKotlin#firstCase",
                    "example.SyntheticKotlin#secondCase",
                    "example.SyntheticJava#javaCase",
                },
                {item.selector for item in discovered},
            )

    def test_current_saf_manifest_has_exactly_seven_guarded_phases(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        phase_map = qualification.discover_saf_phase_map(ANDROID_TEST_ROOT, identities)
        self.assertEqual(qualification.SAF_PHASE_ORDER, tuple(phase_map))
        self.assertEqual(7, len(phase_map))
        plan, omissions = qualification.build_plan_with_source(
            "saf", identities, ANDROID_TEST_ROOT
        )
        self.assertEqual([], omissions)
        self.assertEqual(qualification.SAF_PHASE_ORDER, tuple(item.phase for item in plan))
        self.assertTrue(plan[0].reset_target)
        self.assertTrue(plan[-1].reset_target)
        relaunch = next(item for item in plan if item.phase == "relaunch")
        self.assertFalse(relaunch.reset_target)
        self.assertTrue(relaunch.force_stop)

    def test_missing_or_unexpected_saf_guard_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory(prefix="construct-native-saf-guard-") as temporary:
            root = Path(temporary)
            source = root / "com/example/myapplication/stage9b"
            source.mkdir(parents=True)
            (source / "Stage9BWorkflowInstrumentedTest.kt").write_text(
                """
                package com.example.myapplication.stage9b
                import org.junit.Test
                class Stage9BWorkflowInstrumentedTest {
                    @Test fun onlyOnePhase() { assumePhase("pdf-export") }
                    private fun assumePhase(value: String) {}
                }
                """,
                encoding="utf-8",
            )
            identities = qualification.discover_tests(root)
            with self.assertRaises(qualification.QualificationBlocked):
                qualification.discover_saf_phase_map(root, identities)

    def _write_recovery_source(self, root: Path, restart_mode: str = "restart") -> None:
        source = root / "com/example/myapplication/stage10"
        source.mkdir(parents=True, exist_ok=True)
        (source / "AuditProcessRecoveryInstrumentedTest.kt").write_text(
            f'''
            package com.example.myapplication.stage10

            import org.junit.Assume
            import org.junit.Test

            class AuditProcessRecoveryInstrumentedTest {{
                private fun assumeRecovery(expected: String) {{
                    val actual = instrumentation.arguments.getString("stage10.recovery")
                    Assume.assumeTrue(actual == expected)
                }}

                @Test
                fun stageInterruptedPublications() {{
                    assumeRecovery("stage")
                }}

                @Test
                fun recoverAfterProcessRestart() {{
                    assumeRecovery("{restart_mode}")
                }}
            }}
            ''',
            encoding="utf-8",
        )

    def test_recovery_pair_selection_order_guards_and_restart_has_no_reset(self) -> None:
        with tempfile.TemporaryDirectory(prefix="construct-native-recovery-") as temporary:
            root = Path(temporary)
            self._write_recovery_source(root)
            identities = qualification.discover_tests(root)
            plan, omissions = qualification.build_plan_with_source(
                "recovery", identities, root
            )
            self.assertEqual([], omissions)
            self.assertEqual(
                ["recovery-stage", "recovery-restart"],
                [item.name for item in plan],
            )
            self.assertEqual(
                ["stageInterruptedPublications", "recoverAfterProcessRestart"],
                [item.expected[0].method for item in plan],
            )
            self.assertEqual(
                [{"stage10.recovery": "stage"}, {"stage10.recovery": "restart"}],
                [dict(item.arguments) for item in plan],
            )
            self.assertTrue(plan[0].reset_target)
            self.assertFalse(plan[0].force_stop)
            self.assertFalse(plan[1].reset_target)
            self.assertTrue(plan[1].force_stop)

    def test_recovery_guard_mismatch_is_blocked_before_planning(self) -> None:
        with tempfile.TemporaryDirectory(prefix="construct-native-recovery-guard-") as temporary:
            root = Path(temporary)
            self._write_recovery_source(root, restart_mode="stage")
            identities = qualification.discover_tests(root)
            with self.assertRaisesRegex(
                qualification.QualificationBlocked,
                "stage10\\.recovery=restart",
            ):
                qualification.build_plan_with_source("recovery", identities, root)

    def test_guarded_recovery_class_is_excluded_from_generic_dispatch(self) -> None:
        recovery = [
            qualification.TestIdentity(
                qualification.RECOVERY_CLASS,
                method,
                "AuditProcessRecoveryInstrumentedTest.kt",
                line,
            )
            for line, (_, method) in enumerate(qualification.RECOVERY_METHOD_ORDER, start=1)
        ]
        ordinary = qualification.TestIdentity(
            "com.example.myapplication.stage10.AuditViewerInstrumentedTest",
            "ordinaryCase",
            "AuditViewerInstrumentedTest.kt",
            1,
        )
        smoke, _ = qualification.build_plan("smoke", [*recovery, ordinary])
        self.assertEqual([ordinary.selector], [item.expected[0].selector for item in smoke])
        with self.assertRaisesRegex(qualification.QualificationBlocked, "guarded"):
            qualification.build_plan(
                "audit", [*recovery, ordinary], [qualification.RECOVERY_CLASS]
            )


class InstrumentationParserTests(unittest.TestCase):
    def identity(self, method: str = "works") -> qualification.TestIdentity:
        return qualification.TestIdentity(
            "example.NativeTest", method, "NativeTest.kt", 1
        )

    def test_pass_requires_identity_and_count(self) -> None:
        expected = self.identity()
        summary = qualification.parse_instrument_output(passing_output(expected.selector))
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("PASS", outcome.status)
        self.assertEqual({expected.selector}, summary.identities)
        self.assertEqual(1, summary.reported_count)

    def test_two_test_class_requires_both_status_identities(self) -> None:
        expected = [self.identity("first"), self.identity("second")]
        summary = qualification.parse_instrument_output(
            passing_output(*(item.selector for item in expected))
        )
        outcome = qualification.validate_instrumentation_summary(summary, expected)
        self.assertEqual("PASS", outcome.status)
        self.assertEqual(2, summary.reported_count)

    def test_assumption_is_skipped_even_when_runner_exits_zero(self) -> None:
        expected = self.identity()
        output = "\n".join(
            [
                f"INSTRUMENTATION_STATUS: class={expected.fqcn}",
                f"INSTRUMENTATION_STATUS: test={expected.method}",
                "INSTRUMENTATION_STATUS: current=1",
                "INSTRUMENTATION_STATUS: numtests=1",
                "INSTRUMENTATION_STATUS_CODE: 1",
                f"INSTRUMENTATION_STATUS: class={expected.fqcn}",
                f"INSTRUMENTATION_STATUS: test={expected.method}",
                "INSTRUMENTATION_STATUS_CODE: -3",
                "INSTRUMENTATION_RESULT: stream=AssumptionViolatedException: capability unavailable",
                "Tests run: 1, Failures: 0, Errors: 0, Skipped: 1",
                "INSTRUMENTATION_CODE: -1",
            ]
        )
        summary = qualification.parse_instrument_output(output)
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("SKIPPED", outcome.status)
        self.assertEqual(1, summary.skipped_count)

    def test_android_ok_summary_still_counts_per_test_assumption_skip(self) -> None:
        expected = [self.identity("passed"), self.identity("capabilityUnavailable")]
        output = status_output([
            (expected[0].selector, 0, None),
            (expected[1].selector, -4, "org.junit.AssumptionViolatedException: sandbox capability"),
        ], include_summary=False, final_code=None)
        output += "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        summary = qualification.parse_instrument_output(output)
        self.assertEqual(2, summary.reported_count)
        self.assertEqual(1, summary.skipped_count)
        self.assertEqual(1, sum(value == "PASS" for value in summary.terminal_outcomes.values()))
        self.assertEqual("SKIPPED", qualification.validate_instrumentation_summary(summary, expected).status)

    def test_retained_android_runner_success_uses_final_code_minus_one(self) -> None:
        expected = self.identity("croppedRotatedPageThroughProductionSaf_writesRenderedPdf")
        output = "\n".join([
            f"INSTRUMENTATION_STATUS: class={expected.fqcn}",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: stream=",
            f"INSTRUMENTATION_STATUS: test={expected.method}",
            "INSTRUMENTATION_STATUS_CODE: 1",
            f"INSTRUMENTATION_STATUS: class={expected.fqcn}",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: stream=.",
            f"INSTRUMENTATION_STATUS: test={expected.method}",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_RESULT: stream=",
            "OK (1 test)",
            "INSTRUMENTATION_CODE: -1",
        ])
        summary = qualification.parse_instrument_output(output)
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("PASS", outcome.status)
        self.assertEqual(-1, summary.instrumentation_code)
        self.assertEqual({expected.selector: 0}, summary.terminal_codes)

    def test_missing_final_completion_code_is_not_pass(self) -> None:
        expected = self.identity()
        output = passing_output(expected.selector).replace("INSTRUMENTATION_CODE: -1\n", "")
        outcome = qualification.validate_instrumentation_summary(
            qualification.parse_instrument_output(output), [expected]
        )
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("final completion code", outcome.reason)

    def test_only_start_status_cannot_pass_from_declared_numtests(self) -> None:
        expected = self.identity()
        output = "\n".join([
            f"INSTRUMENTATION_STATUS: class={expected.fqcn}",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: numtests=1",
            f"INSTRUMENTATION_STATUS: test={expected.method}",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "OK (1 test)",
            "INSTRUMENTATION_CODE: -1",
        ])
        summary = qualification.parse_instrument_output(output)
        self.assertEqual(1, summary.declared_count)
        self.assertEqual(1, summary.reported_count)
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("FAIL", outcome.status)

    def test_partial_terminal_status_requires_every_expected_identity(self) -> None:
        first = self.identity("first")
        second = self.identity("second")
        output = status_output([
            (first.selector, 0, None),
            (second.selector, 1, None),
        ]).replace("OK (2 tests)", "OK (2 tests)")
        outcome = qualification.validate_instrumentation_summary(
            qualification.parse_instrument_output(output), [first, second]
        )
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("missing terminal statuses", outcome.reason)

    def test_status_three_and_four_are_skips_with_or_without_text(self) -> None:
        for code in (-3, -4):
            for detail in (None, "org.junit.AssumptionViolatedException: capability unavailable"):
                with self.subTest(code=code, detail=detail):
                    expected = self.identity(f"skip{abs(code)}")
                    summary = qualification.parse_instrument_output(
                        status_output([(expected.selector, code, detail)])
                    )
                    outcome = qualification.validate_instrumentation_summary(summary, [expected])
                    self.assertEqual("SKIPPED", outcome.status)
                    self.assertEqual("SKIP", summary.terminal_outcomes[expected.selector])

    def test_mixed_failure_and_skip_is_failure_and_retains_categories(self) -> None:
        assertion = self.identity("assertion")
        skipped = self.identity("skipped")
        summary = qualification.parse_instrument_output(status_output([
            (assertion.selector, -2, "java.lang.AssertionError: wrong value"),
            (skipped.selector, -4, None),
        ]))
        outcome = qualification.validate_instrumentation_summary(summary, [assertion, skipped])
        self.assertEqual("FAIL", outcome.status)
        self.assertEqual("ASSERTION", summary.terminal_outcomes[assertion.selector])
        self.assertEqual("SKIP", summary.terminal_outcomes[skipped.selector])

    def test_android_junit4_failure_summary_without_errors_field(self) -> None:
        passed = self.identity("passed")
        failed = self.identity("failed")
        output = status_output([
            (passed.selector, 0, None),
            (failed.selector, -2, "java.lang.AssertionError: component is not displayed"),
        ]).replace("Tests run: 2, Failures: 1, Errors: 0, Skipped: 0",
                   "FAILURES!!!\nTests run: 2,  Failures: 1")
        summary = qualification.parse_instrument_output(output)
        outcome = qualification.validate_instrumentation_summary(summary, [passed, failed])
        self.assertEqual(2, summary.reported_count)
        self.assertEqual("PASS", summary.terminal_outcomes[passed.selector])
        self.assertEqual("ASSERTION", summary.terminal_outcomes[failed.selector])
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("failure markers", outcome.reason)

    def test_nonzero_junit_failure_or_error_count_cannot_pass(self) -> None:
        expected = self.identity()
        for trailer in ("Tests run: 1, Failures: 1", "Tests run: 1, Failures: 0, Errors: 1"):
            with self.subTest(trailer=trailer):
                output = passing_output(expected.selector).replace("OK (1 test)", trailer)
                outcome = qualification.validate_instrumentation_summary(
                    qualification.parse_instrument_output(output), [expected])
                self.assertEqual("FAIL", outcome.status)
                self.assertIn("JUnit summary failures/errors", outcome.reason)

    def test_error_and_failure_terminal_categories_are_retained(self) -> None:
        error = self.identity("error")
        failure = self.identity("failure")
        summary = qualification.parse_instrument_output(status_output([
            (error.selector, -2, "java.lang.IllegalStateException: broken state"),
            (failure.selector, -2, None),
        ]))
        outcome = qualification.validate_instrumentation_summary(summary, [error, failure])
        self.assertEqual("FAIL", outcome.status)
        self.assertEqual("ERROR", summary.terminal_outcomes[error.selector])
        self.assertEqual("FAILURE", summary.terminal_outcomes[failure.selector])

    def test_final_zero_is_canceled_even_when_tests_report_pass(self) -> None:
        expected = self.identity()
        summary = qualification.parse_instrument_output(
            status_output([(expected.selector, 0, None)], final_code=0)
        )
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("canceled", outcome.reason)

    def test_skipped_zero_summary_does_not_create_skip(self) -> None:
        expected = self.identity()
        output = passing_output(expected.selector).replace(
            "OK (1 test)", "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
        )
        summary = qualification.parse_instrument_output(output)
        outcome = qualification.validate_instrumentation_summary(summary, [expected])
        self.assertEqual("PASS", outcome.status)
        self.assertEqual(0, summary.skipped_count)

    def test_failure_and_count_mismatch_are_not_pass(self) -> None:
        expected = self.identity()
        failure = qualification.parse_instrument_output(
            "FAILURES!!!\nINSTRUMENTATION_CODE: -1\n",
            process_exit=0,
        )
        self.assertEqual(
            "FAIL",
            qualification.validate_instrumentation_summary(failure, [expected]).status,
        )
        mismatch = qualification.parse_instrument_output(
            passing_output(expected.selector).replace("OK (1 test)", "OK (2 tests)")
        )
        outcome = qualification.validate_instrumentation_summary(mismatch, [expected])
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("expected 1 executed tests", outcome.reason)

    def test_missing_status_identity_is_not_hidden_by_ok_count(self) -> None:
        expected = self.identity()
        output = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        outcome = qualification.validate_instrumentation_summary(
            qualification.parse_instrument_output(output), [expected]
        )
        self.assertEqual("FAIL", outcome.status)
        self.assertIn("missing starts", outcome.reason)


class SafetyAndPlanTests(unittest.TestCase):
    def _adb_session(self, output: Path):
        store = qualification.CheckpointStore(output, {"commands": []})
        session = qualification.AdbSession(
            adb=Path("synthetic-adb"), serial="emulator-1234", avd_name="Synthetic",
            allow_physical=False, physical_model=None, target_package="example.app",
            test_package="example.app.test", runner="example.Runner",
            apk=Path("synthetic.apk"), test_apk=Path("synthetic-test.apk"), store=store,
        )
        return session, store

    def test_read_only_admission_retry_preserves_both_exit_statuses(self) -> None:
        failure = qualification.subprocess.CompletedProcess([], 1, b"cannot connect to daemon at tcp:5037")
        success = qualification.subprocess.CompletedProcess([], 0, b"package:example.app\n")
        with tempfile.TemporaryDirectory(prefix="construct-native-adb-read-") as temporary:
            session, store = self._adb_session(Path(temporary))
            with mock.patch.object(qualification.subprocess, "run", side_effect=[failure, success]) as execute:
                status, output = session.command("packages", ["shell", "pm", "list", "packages"])
            self.assertEqual((0, "package:example.app\n"), (status, output))
            self.assertEqual(2, execute.call_count)
            self.assertEqual([1, 0], [item["exit_code"] for item in store.state["commands"]])
            self.assertTrue(all((store.output_dir / item["log"]).is_file() for item in store.state["commands"]))

    def test_read_connection_retry_is_bounded_to_one(self) -> None:
        failure = qualification.subprocess.CompletedProcess([], 1, b"cannot connect to daemon at tcp:5037")
        with tempfile.TemporaryDirectory(prefix="construct-native-adb-read-") as temporary:
            session, _ = self._adb_session(Path(temporary))
            with mock.patch.object(qualification.subprocess, "run", return_value=failure) as execute:
                with self.assertRaises(qualification.QualificationFailure):
                    session.command("devices", ["devices", "-l"], scoped=False)
            self.assertEqual(2, execute.call_count)

    def test_mutations_and_instrumentation_are_never_connection_retried(self) -> None:
        failure = qualification.subprocess.CompletedProcess([], 1, b"cannot connect to daemon at tcp:5037")
        for args in (["uninstall", "example.app"], ["install", "example.apk"],
                     ["shell", "am", "instrument", "-w", "example.app.test/example.Runner"]):
            with self.subTest(args=args), tempfile.TemporaryDirectory(prefix="construct-native-adb-read-") as temporary:
                session, _ = self._adb_session(Path(temporary))
                with mock.patch.object(qualification.subprocess, "run", return_value=failure) as execute:
                    with self.assertRaises(qualification.QualificationFailure):
                        session.command("mutation", args)
                self.assertEqual(1, execute.call_count)

    def test_device_selection_is_explicit_and_duplicate_serials_are_rejected(self) -> None:
        devices = qualification.parse_adb_devices(
            "List of devices attached\nemulator-1\tdevice\nphone-2\tunauthorized\n"
        )
        self.assertEqual("device", devices["emulator-1"])
        self.assertEqual("unauthorized", devices["phone-2"])
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.parse_adb_devices(
                "List of devices attached\nemulator-1 device\nemulator-1 device\n"
            )

    def test_avd_identity_rejects_ambiguous_output(self) -> None:
        self.assertEqual("ConstructStage10", qualification.parse_avd_name("ConstructStage10\nOK\n"))
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.parse_avd_name("ConstructStage10\nOtherAvd\n")

    def test_only_target_package_can_be_reset(self) -> None:
        command = qualification.reset_command(
            "serial-1", "com.example.app", "com.example.app.test"
        )
        self.assertEqual(["-s", "serial-1", "uninstall", "com.example.app"], command)
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.validate_package_scope(
                "com.example.app", "com.example.app.test", "com.example.app.test"
            )
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.validate_confirmation(
                "yes", qualification.DISPOSABLE_CONFIRMATION, "confirmation"
            )

    def test_smoke_counts_are_derived_from_current_sources(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        plan, omissions = qualification.build_plan_with_source(
            "smoke", identities, ANDROID_TEST_ROOT
        )
        self.assertEqual([], omissions)
        by_class = {item.name: len(item.expected) for item in plan}
        self.assertEqual(
            6,
            by_class["com.example.myapplication.stage10.AuditViewerInstrumentedTest"],
        )
        self.assertEqual(
            2,
            by_class["com.example.myapplication.stage10.AuditMeasurementHintsInstrumentedTest"],
        )
        self.assertEqual(
            3,
            by_class["com.example.myapplication.stage10.Stage10BackupPolicyInstrumentedTest"],
        )
        self.assertEqual(16, sum(by_class.values()))
        self.assertEqual(
            {
                "com.example.myapplication.stage10.AuditDriveRootUiInstrumentedTest",
                "com.example.myapplication.stage10.AuditViewerInstrumentedTest",
                "com.example.myapplication.stage10.AuditMeasurementHintsInstrumentedTest",
                "com.example.myapplication.stage10.AuditSetupInstrumentedTest",
                "com.example.myapplication.stage10.AuditRepositoryOpenInstrumentedTest",
                qualification.SMOKE_CLASS,
            },
            set(by_class),
        )
        self.assertNotIn(
            qualification.RECOVERY_CLASS,
            {item.name for item in plan},
        )

    def test_full_schedules_recovery_pair_after_ordered_saf(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        plan, _ = qualification.build_plan_with_source(
            "full", identities, ANDROID_TEST_ROOT
        )
        self.assertEqual(
            qualification.SAF_PHASE_ORDER,
            tuple(item.phase for item in plan[:len(qualification.SAF_PHASE_ORDER)]),
        )
        recovery = plan[len(qualification.SAF_PHASE_ORDER):
                        len(qualification.SAF_PHASE_ORDER) + 2]
        self.assertEqual(
            ["recovery-stage", "recovery-restart"],
            [item.name for item in recovery],
        )
        self.assertTrue(recovery[0].reset_target)
        self.assertFalse(recovery[1].reset_target)
        self.assertTrue(recovery[1].force_stop)
        self.assertEqual(
            [{"stage10.recovery": "stage"}, {"stage10.recovery": "restart"}],
            [dict(item.arguments) for item in recovery],
        )

    def test_full_plan_explicitly_omits_live_class(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        plan, omissions = qualification.build_plan_with_source(
            "full", identities, ANDROID_TEST_ROOT
        )
        self.assertTrue(any(item["class"].endswith("Stage9BLiveProviderQualificationInstrumentedTest") for item in omissions))
        self.assertFalse(any(
            identity.fqcn.endswith("Stage9BLiveProviderQualificationInstrumentedTest")
            for invocation in plan
            for identity in invocation.expected
        ))

    def test_full_live_opt_in_is_explicitly_argumented(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        plan, omissions = qualification.build_plan_with_source(
            "full", identities, ANDROID_TEST_ROOT, include_live=True
        )
        self.assertEqual([], omissions)
        live = [
            item for item in plan
            if any(identity.fqcn.endswith("Stage9BLiveProviderQualificationInstrumentedTest")
                   for identity in item.expected)
        ]
        self.assertEqual(1, len(live))
        self.assertEqual({"stage9b.live": "true"}, dict(live[0].arguments))

    def test_saf_dependencies_are_required_when_selecting_phases(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.build_plan_with_source(
                "saf", identities, ANDROID_TEST_ROOT, phases=("import",)
            )
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.build_plan_with_source(
                "saf", identities, ANDROID_TEST_ROOT, phases=("relaunch", "import")
            )

    def test_focused_live_requires_opt_in_and_enables_only_live_invocation(self) -> None:
        identities = qualification.discover_tests(ANDROID_TEST_ROOT)
        live_class = "com.example.myapplication.stage9b.Stage9BLiveProviderQualificationInstrumentedTest"
        local_class = "com.example.myapplication.stage10.AuditDriveRootUiInstrumentedTest"
        with self.assertRaises(qualification.QualificationBlocked):
            qualification.build_plan_with_source(
                "audit", identities, ANDROID_TEST_ROOT, selectors=(live_class,)
            )
        plan, omissions = qualification.build_plan_with_source(
            "audit", identities, ANDROID_TEST_ROOT,
            selectors=(local_class, live_class), include_live=True,
        )
        self.assertEqual([], omissions)
        self.assertEqual({}, dict(plan[0].arguments))
        self.assertEqual({"stage9b.live": "true"}, dict(plan[1].arguments))
        self.assertTrue(plan[1].reset_target)

    def test_hashes_are_stable_and_include_external_fixture_identity(self) -> None:
        with tempfile.TemporaryDirectory(prefix="construct-native-hash-") as temporary:
            root = Path(temporary)
            fixture = root / "fixtures" / "fixture.pdf"
            fixture.parent.mkdir(parents=True)
            fixture.write_bytes(b"synthetic-fixture")
            first = qualification.hash_paths([root / "fixtures"], root)
            second = qualification.hash_paths([root / "fixtures"], root)
            self.assertEqual(first, second)
            self.assertIn("fixtures/fixture.pdf", first)
            self.assertEqual(
                qualification.tree_digest(first), qualification.tree_digest(second)
            )


if __name__ == "__main__":
    raise SystemExit(unittest.main())
