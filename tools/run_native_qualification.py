#!/usr/bin/env python3
"""Run the checked-in native and SAF qualification matrix.

This module deliberately keeps the host-side workflow small and dependency
free.  It does not discover a device, clear an arbitrary package, or infer
that a skipped Android test passed.  The command line must identify the
disposable target explicitly; the device is guarded again before every
target-only reset.

The module is also imported by ``test_native_qualification.py``.  Keep the
source discovery, instrumentation parser, and safety checks usable without an
Android SDK or a connected device.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as _datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


DISPOSABLE_CONFIRMATION = "I_UNDERSTAND_DISPOSABLE_TARGET"
PHYSICAL_CONFIRMATION = "I_UNDERSTAND_PHYSICAL_TARGET"
LIVE_CONFIRMATION = "I_UNDERSTAND_LIVE_ACCOUNT_DATA"

SAF_CLASS = "com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest"
SAF_PHASE_ORDER = (
    "pdf-export",
    "pdf-picker-recreation",
    "pdf-picker-cancel",
    "export",
    "import",
    "relaunch",
    "retired-format",
)
RECOVERY_CLASS = "com.example.myapplication.stage10.AuditProcessRecoveryInstrumentedTest"
RECOVERY_METHOD_ORDER = (
    ("stage", "stageInterruptedPublications"),
    ("restart", "recoverAfterProcessRestart"),
)
RECOVERY_ARGUMENT = "stage10.recovery"
LIVE_CLASS_MARKERS = ("Stage9BLiveProviderQualificationInstrumentedTest",)
SMOKE_CLASS_PREFIX = "com.example.myapplication.stage10.Audit"
SMOKE_EXCLUDED_CLASSES = {
    RECOVERY_CLASS,
    "com.example.myapplication.stage10.AuditRecoveryInstrumentedTest",
}
SMOKE_CLASS = "com.example.myapplication.stage10.Stage10BackupPolicyInstrumentedTest"
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


class QualificationError(RuntimeError):
    """Base class for a qualification that could not be certified."""


class QualificationBlocked(QualificationError):
    """The run was intentionally refused or could not be certified."""


class QualificationFailure(QualificationError):
    """A selected command or test failed."""


@dataclasses.dataclass(frozen=True)
class TestIdentity:
    fqcn: str
    method: str
    source: str
    line: int

    @property
    def selector(self) -> str:
        return f"{self.fqcn}#{self.method}"

    def as_dict(self) -> dict[str, Any]:
        return {
            "class": self.fqcn,
            "method": self.method,
            "selector": self.selector,
            "source": self.source,
            "line": self.line,
        }


@dataclasses.dataclass(frozen=True)
class Invocation:
    name: str
    expected: tuple[TestIdentity, ...]
    reset_target: bool
    force_stop: bool = False
    phase: str | None = None
    arguments: tuple[tuple[str, str], ...] = ()

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "phase": self.phase,
            "expected": [item.as_dict() for item in self.expected],
            "expected_count": len(self.expected),
            "reset_target": self.reset_target,
            "force_stop": self.force_stop,
            "arguments": dict(self.arguments),
        }


@dataclasses.dataclass
class InstrumentationSummary:
    process_exit: int
    instrumentation_code: int | None
    identities: set[str]
    started_identities: set[str]
    terminal_codes: dict[str, int]
    terminal_outcomes: dict[str, str]
    status_events: list[dict[str, Any]]
    reported_count: int | None
    declared_count: int | None
    ok_count: int | None
    tests_run: int | None
    skipped_count: int
    skip_markers: list[str]
    failure_markers: list[str]
    result_messages: list[str]
    stream_tail: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "process_exit": self.process_exit,
            "instrumentation_code": self.instrumentation_code,
            "identities": sorted(self.identities),
            "started_identities": sorted(self.started_identities),
            "terminal_codes": dict(sorted(self.terminal_codes.items())),
            "terminal_outcomes": dict(sorted(self.terminal_outcomes.items())),
            "status_events": self.status_events,
            "reported_count": self.reported_count,
            "declared_count": self.declared_count,
            "ok_count": self.ok_count,
            "tests_run": self.tests_run,
            "skipped_count": self.skipped_count,
            "skip_markers": self.skip_markers,
            "failure_markers": self.failure_markers,
            "result_messages": self.result_messages,
            "stream_tail": self.stream_tail,
        }


@dataclasses.dataclass
class ExecutionOutcome:
    status: str
    reason: str
    summary: InstrumentationSummary
    expected: list[str]

    def as_dict(self) -> dict[str, Any]:
        value = self.summary.as_dict()
        value.update({
            "status": self.status,
            "reason": self.reason,
            "expected": self.expected,
        })
        return value


def _now() -> str:
    return _datetime.datetime.now(_datetime.timezone.utc).isoformat()


def _json_default(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        return dataclasses.asdict(value)
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, set):
        return sorted(value)
    raise TypeError(f"cannot encode {type(value).__name__}")


def _atomic_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True, default=_json_default) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _mask_comments(text: str) -> str:
    """Mask comments while preserving offsets and line numbers.

    The source parser only needs declarations and annotations.  Keeping the
    offsets makes a source line and a method window refer to the original
    checked-in file while preventing commented examples from becoming tests.
    """

    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    text = re.sub(r"//[^\n]*", blank, text)
    return re.sub(r"/\*.*?\*/", blank, text, flags=re.DOTALL)


_CLASS_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:public|private|protected|internal|open|final|abstract|"
    r"sealed|data|inner|static)\s+)*(?:class|object)\s+([A-Za-z_]\w*)\b"
)
_TEST_RE = re.compile(
    r"@(?:[A-Za-z_]\w*\.)*Test\b(?:\s*\([^)]*\))?\s*"
    r"(?:(?:public|private|protected|internal|open|final|suspend|static|override)\s+)*"
    r"(?:fun|void)\s+([A-Za-z_]\w*)\s*\(",
    flags=re.MULTILINE,
)


def _matching_brace(text: str, opening: int) -> int:
    depth = 0
    in_string = False
    in_triple_string = False
    in_char = False
    escaped = False
    index = opening
    while index < len(text):
        if in_triple_string:
            if text.startswith('"""', index):
                in_triple_string = False
                index += 3
                continue
            index += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif text[index] == "\\":
                escaped = True
            elif text[index] == '"':
                in_string = False
            index += 1
            continue
        if in_char:
            if escaped:
                escaped = False
            elif text[index] == "\\":
                escaped = True
            elif text[index] == "'":
                in_char = False
            index += 1
            continue
        if text.startswith('"""', index):
            in_triple_string = True
            index += 3
            continue
        if text[index] == '"':
            in_string = True
            index += 1
            continue
        if text[index] == "'":
            in_char = True
            index += 1
            continue
        character = text[index]
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return len(text)


def _class_regions(masked: str) -> list[tuple[int, int, str]]:
    regions: list[tuple[int, int, str]] = []
    for match in _CLASS_RE.finditer(masked):
        opening = masked.find("{", match.end())
        if opening < 0:
            continue
        regions.append((match.start(), _matching_brace(masked, opening), match.group(1)))
    return regions


def _package_name(masked: str) -> str:
    match = re.search(r"(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)", masked)
    return match.group(1) if match else ""


def discover_tests(source_root: Path) -> list[TestIdentity]:
    """Discover checked-in JUnit identities from Kotlin and Java sources.

    Discovery is intentionally structural rather than a hardcoded count.  A
    selected class must have every current ``@Test`` method represented before
    any APK is installed or package is reset.
    """

    source_root = source_root.resolve()
    if not source_root.is_dir():
        raise QualificationBlocked(f"instrumentation source root does not exist: {source_root}")

    found: list[TestIdentity] = []
    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        original = path.read_text(encoding="utf-8")
        masked = _mask_comments(original)
        package = _package_name(masked)
        regions = _class_regions(masked)
        if not regions:
            continue
        for match in _TEST_RE.finditer(masked):
            containing = [region for region in regions if region[0] <= match.start() < region[1]]
            if not containing:
                continue
            _, _, class_name = min(containing, key=lambda region: region[1] - region[0])
            fqcn = f"{package}.{class_name}" if package else class_name
            found.append(TestIdentity(
                fqcn=fqcn,
                method=match.group(1),
                source=str(path.relative_to(source_root).as_posix()),
                line=original.count("\n", 0, match.start()) + 1,
            ))

    unique: dict[str, TestIdentity] = {}
    for item in found:
        if item.selector in unique:
            prior = unique[item.selector]
            raise QualificationFailure(
                f"duplicate instrumentation identity {item.selector}: "
                f"{prior.source} and {item.source}"
            )
        unique[item.selector] = item
    return sorted(unique.values(), key=lambda item: (item.fqcn, item.method, item.source))


def _source_for_identity(source_root: Path, identity: TestIdentity) -> str:
    path = source_root / Path(identity.source)
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise QualificationBlocked(f"cannot read selected test source {path}: {error}") from error


def _method_window(source: str, method: str) -> str:
    declaration = re.search(
        r"\b(?:fun|void)\s+" + re.escape(method) + r"\s*\(", source
    )
    if declaration is None:
        return ""
    start = source.rfind("@Test", 0, declaration.start())
    if start < 0:
        start = declaration.start()
    next_test = re.search(r"(?m)^\s*@Test\b", source[declaration.end():])
    end = declaration.end() + next_test.start() if next_test else len(source)
    return source[start:end]


def _string_constants(source: str) -> dict[str, str]:
    constants: dict[str, str] = {}
    for match in re.finditer(
        r"\b(?:const\s+val|static\s+final\s+String)\s+([A-Za-z_]\w*)\s*=\s*\"([^\"]+)\"",
        source,
    ):
        constants[match.group(1)] = match.group(2)
    return constants


def discover_saf_phase_map(
    source_root: Path,
    identities: Sequence[TestIdentity],
) -> dict[str, TestIdentity]:
    """Map each guarded Stage9B phase to its actual current test method."""

    candidates = [item for item in identities if item.fqcn == SAF_CLASS]
    if not candidates:
        raise QualificationBlocked(f"missing checked-in SAF class {SAF_CLASS}")
    by_phase: dict[str, TestIdentity] = {}
    by_source: dict[str, str] = {}
    for identity in candidates:
        if identity.source not in by_source:
            by_source[identity.source] = _source_for_identity(source_root, identity)
        source = by_source[identity.source]
        constants = _string_constants(source)
        window = _method_window(source, identity.method)
        match = re.search(
            r"\bassumePhase\(\s*(?:\"([^\"]+)\"|([A-Za-z_]\w*))\s*\)",
            window,
        )
        if not match:
            continue
        token = match.group(1) or match.group(2)
        phase = match.group(1) or constants.get(token, "")
        if not phase:
            raise QualificationFailure(
                f"could not resolve the phase guard in {identity.selector}"
            )
        prior = by_phase.get(phase)
        if prior is not None and prior.selector != identity.selector:
            raise QualificationFailure(
                f"phase {phase!r} is guarded by both {prior.selector} and {identity.selector}"
            )
        by_phase[phase] = identity

    expected = set(SAF_PHASE_ORDER)
    actual = set(by_phase)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing or unexpected:
        detail: list[str] = []
        if missing:
            detail.append("missing=" + ",".join(missing))
        if unexpected:
            detail.append("unexpected=" + ",".join(unexpected))
        raise QualificationBlocked(
            "checked-in SAF phase manifest does not match the seven guarded "
            + "phases (" + "; ".join(detail) + ")"
        )
    return {phase: by_phase[phase] for phase in SAF_PHASE_ORDER}


def _recovery_guard_is_source_verified(
    source: str,
    window: str,
    expected_value: str,
) -> bool:
    """Verify a recovery test invokes the checked-in argument guard.

    The guard helper is intentionally allowed to have a small name variation
    so the runner remains compatible with Kotlin and Java test spelling.  The
    method must still carry the expected value, and the source must contain
    the exact argument key.  A direct key/value comparison in the test body
    is accepted as well as a helper call such as ``assumeRecovery("stage")``.
    """

    if RECOVERY_ARGUMENT not in source:
        return False
    quoted_value = rf"(?:\"|')\s*{re.escape(expected_value)}\s*(?:\"|')"
    if not re.search(quoted_value, window):
        return False
    direct_key = re.search(
        rf"(?:\"{re.escape(RECOVERY_ARGUMENT)}\"|'{re.escape(RECOVERY_ARGUMENT)}')"
        rf"[^\n{{}};)]{{0,180}}{quoted_value}",
        window,
        flags=re.IGNORECASE,
    )
    if direct_key:
        return True
    return bool(re.search(
        rf"\b(?:assume|require|guard|expect|only)[A-Za-z_]*"
        rf"\s*\([^\n;{{}}]*{quoted_value}",
        window,
        flags=re.IGNORECASE,
    ))


def discover_recovery_method_map(
    source_root: Path,
    identities: Sequence[TestIdentity],
) -> dict[str, TestIdentity]:
    """Resolve and verify the two ordered process-recovery test methods."""

    candidates = [item for item in identities if item.fqcn == RECOVERY_CLASS]
    expected_methods = {method for _, method in RECOVERY_METHOD_ORDER}
    actual_methods = {item.method for item in candidates}
    if not candidates:
        raise QualificationBlocked(f"missing checked-in recovery class {RECOVERY_CLASS}")
    if actual_methods != expected_methods:
        missing = sorted(expected_methods - actual_methods)
        unexpected = sorted(actual_methods - expected_methods)
        detail: list[str] = []
        if missing:
            detail.append("missing=" + ",".join(missing))
        if unexpected:
            detail.append("unexpected=" + ",".join(unexpected))
        raise QualificationBlocked(
            "checked-in recovery class must expose exactly the verified method pair"
            + (" (" + "; ".join(detail) + ")" if detail else "")
        )

    sources: dict[str, str] = {}
    result: dict[str, TestIdentity] = {}
    for mode, method in RECOVERY_METHOD_ORDER:
        identity = next(item for item in candidates if item.method == method)
        source = sources.setdefault(identity.source, _source_for_identity(source_root, identity))
        window = _method_window(source, identity.method)
        if not window or not _recovery_guard_is_source_verified(source, window, mode):
            raise QualificationBlocked(
                f"recovery method {identity.selector} is not guarded by "
                f"{RECOVERY_ARGUMENT}={mode}"
            )
        result[mode] = identity
    return result


def recovery_invocations(
    method_map: Mapping[str, TestIdentity],
) -> list[Invocation]:
    """Build the fixed stage-then-restart pair after source verification."""

    return [
        Invocation(
            name=f"recovery-{mode}",
            expected=(method_map[mode],),
            reset_target=mode == "stage",
            force_stop=mode == "restart",
            arguments=((RECOVERY_ARGUMENT, mode),),
        )
        for mode, _ in RECOVERY_METHOD_ORDER
    ]


def _is_live_class(fqcn: str) -> bool:
    return any(marker in fqcn for marker in LIVE_CLASS_MARKERS)


def _is_recovery_class(fqcn: str) -> bool:
    return fqcn == RECOVERY_CLASS


def _classes(identities: Sequence[TestIdentity]) -> dict[str, tuple[TestIdentity, ...]]:
    grouped: dict[str, list[TestIdentity]] = {}
    for identity in identities:
        grouped.setdefault(identity.fqcn, []).append(identity)
    return {
        fqcn: tuple(sorted(items, key=lambda item: item.method))
        for fqcn, items in sorted(grouped.items())
    }


def _resolve_selector(selector: str, identities: Sequence[TestIdentity]) -> tuple[TestIdentity, ...]:
    groups = _classes(identities)
    if not selector or selector.endswith("#"):
        raise QualificationBlocked(f"invalid empty instrumentation selector: {selector!r}")
    if "#" in selector:
        fqcn, method = selector.split("#", 1)
        matches = tuple(item for item in identities if item.fqcn == fqcn and item.method == method)
        if not matches:
            raise QualificationBlocked(f"selected test identity does not exist: {selector}")
        return matches
    if selector not in groups:
        raise QualificationBlocked(f"selected test class does not exist: {selector}")
    return groups[selector]


def _invocations_for_selectors(
    selectors: Sequence[str],
    identities: Sequence[TestIdentity],
    *,
    reset_target: bool,
    arguments: tuple[tuple[str, str], ...] = (),
) -> list[Invocation]:
    invocations: list[Invocation] = []
    if len(set(selectors)) != len(selectors):
        raise QualificationBlocked("the same --class selector was supplied more than once")
    for selector in selectors:
        expected = _resolve_selector(selector, identities)
        invocations.append(Invocation(
            name=selector,
            expected=expected,
            reset_target=reset_target,
            arguments=arguments,
        ))
    return invocations


def build_plan(
    suite: str,
    identities: Sequence[TestIdentity],
    selectors: Sequence[str] = (),
    phases: Sequence[str] = (),
    *,
    include_live: bool = False,
) -> tuple[list[Invocation], list[dict[str, str]]]:
    """Create a complete, identity-checked invocation plan without ADB."""

    if suite not in {"smoke", "saf", "audit", "full", "recovery"}:
        raise QualificationBlocked(f"unsupported qualification suite: {suite}")
    grouped = _classes(identities)
    omissions: list[dict[str, str]] = []
    selected_phases = tuple(phases) if phases else SAF_PHASE_ORDER
    unknown_phases = sorted(set(selected_phases) - set(SAF_PHASE_ORDER))
    if unknown_phases:
        raise QualificationBlocked("unknown SAF phases: " + ", ".join(unknown_phases))
    if len(set(selected_phases)) != len(selected_phases):
        raise QualificationBlocked("the same SAF phase was selected more than once")
    selected_phases = tuple(phase for phase in SAF_PHASE_ORDER if phase in selected_phases)
    if "import" in selected_phases and "export" not in selected_phases:
        raise QualificationBlocked("the import SAF phase requires export in the same run")
    if "relaunch" in selected_phases and "import" not in selected_phases:
        raise QualificationBlocked("the relaunch SAF phase requires import in the same run")

    if suite == "recovery":
        raise QualificationBlocked(
            "recovery planning requires build_plan_with_source so the fixed "
            "method pair and argument guards are verified"
        )

    # The source-root-independent identity map is attached by the caller for
    # full execution through ``build_plan_with_source`` below.  This branch is
    # kept separate so parser tests can validate selector safety without a
    # checkout.  A caller that needs SAF/full must use the source-aware helper.
    if suite in {"saf", "full"}:
        raise QualificationBlocked(
            "SAF/full planning requires build_plan_with_source so guarded phase identities are verified"
        )

    if suite == "audit":
        if phases:
            raise QualificationBlocked("--phase is only valid for the saf/full suites")
        if not selectors:
            raise QualificationBlocked("audit suite requires at least one --class selector")
        invocations: list[Invocation] = []
        for item in _invocations_for_selectors(selectors, identities, reset_target=True):
            if any(_is_recovery_class(identity.fqcn) for identity in item.expected):
                raise QualificationBlocked(
                    f"{RECOVERY_CLASS} is guarded; use --suite recovery for its "
                    "source-verified stage/restart pair"
                )
            if any(_is_live_class(identity.fqcn) for identity in item.expected):
                if not include_live:
                    raise QualificationBlocked(
                        f"live class {item.name} requires --include-live and its account confirmation"
                    )
                item = dataclasses.replace(item, arguments=(("stage9b.live", "true"),))
            invocations.append(item)
        return invocations, omissions

    if suite == "smoke":
        if phases:
            raise QualificationBlocked("--phase is only valid for the saf/full suites")
        if selectors:
            selected = list(selectors)
            selected_classes = {selector.split("#", 1)[0] for selector in selected}
            unsupported = sorted(
                fqcn for fqcn in selected_classes
                if (not fqcn.startswith(SMOKE_CLASS_PREFIX) and fqcn != SMOKE_CLASS)
                or fqcn in SMOKE_EXCLUDED_CLASSES
            )
            if unsupported:
                raise QualificationBlocked(
                    "smoke suite has a source-derived class set; use --suite audit for "
                    "other selectors: " + ", ".join(unsupported)
                )
        else:
            selected = sorted(
                fqcn for fqcn in grouped
                if (fqcn.startswith(SMOKE_CLASS_PREFIX) or fqcn == SMOKE_CLASS)
                and fqcn not in SMOKE_EXCLUDED_CLASSES
            )
            if not selected:
                raise QualificationBlocked(
                    "smoke suite found no deterministic stage10 audit or backup-policy classes"
                )
        invocations = _invocations_for_selectors(selected, identities, reset_target=True)
        if any(_is_recovery_class(identity.fqcn) for invocation in invocations for identity in invocation.expected):
            raise QualificationBlocked(
                f"{RECOVERY_CLASS} is guarded; use --suite recovery for its "
                "source-verified stage/restart pair"
            )
        if any(_is_live_class(identity.fqcn) for invocation in invocations for identity in invocation.expected):
            raise QualificationBlocked("smoke suite cannot include account-dependent live tests")
        return invocations, omissions

    raise AssertionError("unreachable suite branch")


def build_plan_with_source(
    suite: str,
    identities: Sequence[TestIdentity],
    source_root: Path,
    selectors: Sequence[str] = (),
    phases: Sequence[str] = (),
    *,
    include_live: bool = False,
) -> tuple[list[Invocation], list[dict[str, str]]]:
    """Build the executable plan and verify the current source phase guards."""

    if suite == "recovery":
        if selectors:
            raise QualificationBlocked(
                "recovery suite uses its fixed source-verified method pair; do not pass --class"
            )
        if phases:
            raise QualificationBlocked(
                "--phase is only valid for the saf/full suites"
            )
        method_map = discover_recovery_method_map(source_root, identities)
        return recovery_invocations(method_map), []

    selected_phases = tuple(phases) if phases else SAF_PHASE_ORDER
    unknown_phases = sorted(set(selected_phases) - set(SAF_PHASE_ORDER))
    if unknown_phases:
        raise QualificationBlocked("unknown SAF phases: " + ", ".join(unknown_phases))
    if len(set(selected_phases)) != len(selected_phases):
        raise QualificationBlocked("the same SAF phase was selected more than once")
    selected_phases = tuple(phase for phase in SAF_PHASE_ORDER if phase in selected_phases)
    if "import" in selected_phases and "export" not in selected_phases:
        raise QualificationBlocked("the import SAF phase requires export in the same run")
    if "relaunch" in selected_phases and "import" not in selected_phases:
        raise QualificationBlocked("the relaunch SAF phase requires import in the same run")

    if suite in {"saf", "full"}:
        phase_map = discover_saf_phase_map(source_root, identities)
    else:
        phase_map = {}

    if suite in {"saf", "full"}:
        invocations = [Invocation(
            name=f"saf-{phase}",
            expected=(phase_map[phase],),
            reset_target=phase != "relaunch",
            force_stop=phase == "relaunch",
            phase=phase,
        ) for phase in selected_phases]
    else:
        if phases:
            raise QualificationBlocked("--phase is only valid for the saf/full suites")
        invocations, omissions = build_plan(
            suite, identities, selectors, phases, include_live=include_live
        )
        return invocations, omissions

    if suite == "saf":
        if selectors:
            raise QualificationBlocked("SAF suite uses --phase; --class is only for native audit suites")
        return invocations, []

    # Full is the broad local native matrix plus the ordered SAF phases.  The
    # source-derived count is authoritative; no stale test count is embedded.
    grouped = _classes(identities)
    recovery_map = discover_recovery_method_map(source_root, identities)
    recovery = recovery_invocations(recovery_map)
    if selectors:
        selected_local = []
        selected_live = []
        for selector in selectors:
            selected = _resolve_selector(selector, identities)
            if any(_is_live_class(identity.fqcn) for identity in selected):
                selected_live.append(selector)
            elif any(identity.fqcn == SAF_CLASS for identity in selected):
                raise QualificationBlocked(
                    f"full suite schedules {SAF_CLASS} through its seven guarded phases; "
                    "do not select it as a generic class"
                )
            elif any(_is_recovery_class(identity.fqcn) for identity in selected):
                raise QualificationBlocked(
                    f"full suite schedules {RECOVERY_CLASS} through its fixed "
                    "stage/restart pair; do not select it as a generic class"
                )
            else:
                selected_local.append(selector)
        native = _invocations_for_selectors(selected_local, identities, reset_target=True)
    else:
        native = []
        for fqcn, expected in grouped.items():
            if fqcn in {SAF_CLASS, RECOVERY_CLASS}:
                continue
            if _is_live_class(fqcn):
                continue
            native.append(Invocation(name=fqcn, expected=expected, reset_target=True))

    omissions: list[dict[str, str]] = []
    for fqcn in sorted(grouped):
        if _is_live_class(fqcn) and not include_live:
            omissions.append({
                "class": fqcn,
                "reason": "account-dependent live provider class omitted; pass --include-live explicitly",
            })
    if include_live:
        if selectors:
            live_selectors = selected_live
        else:
            live_selectors = sorted(fqcn for fqcn in grouped if _is_live_class(fqcn))
        native.extend(_invocations_for_selectors(
            live_selectors,
            identities,
            reset_target=True,
            arguments=(("stage9b.live", "true"),),
        ))
    return invocations + recovery + native, omissions


def parse_adb_devices(output: str) -> dict[str, str]:
    """Parse ``adb devices -l`` and reject duplicate serial rows."""

    result: dict[str, str] = {}
    known_states = {"device", "offline", "unauthorized", "unknown"}
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("List of devices"):
            continue
        fields = stripped.split()
        if len(fields) < 2 or fields[1] not in known_states:
            continue
        serial, state = fields[0], fields[1]
        if serial in result:
            raise QualificationBlocked(f"ADB reported duplicate device serial {serial!r}")
        result[serial] = state
    return result


def parse_avd_name(output: str) -> str:
    names = [line.strip() for line in output.splitlines() if line.strip()]
    names = [line for line in names if line.upper() not in {"OK", "OKAY"}]
    if len(names) != 1:
        raise QualificationBlocked(
            "emulator AVD identity was ambiguous; expected exactly one non-status name"
        )
    return names[0]


def parse_packages(output: str) -> set[str]:
    packages: set[str] = set()
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("package:"):
            package = stripped[len("package:"):].strip()
            if package:
                packages.add(package)
    return packages


def validate_confirmation(value: str | None, expected: str, label: str) -> None:
    if value != expected:
        raise QualificationBlocked(
            f"{label} is required; pass the exact explicit confirmation token"
        )


def validate_package_scope(target_package: str, test_package: str, package_to_reset: str) -> None:
    if (
        not PACKAGE_RE.fullmatch(target_package or "")
        or not PACKAGE_RE.fullmatch(test_package or "")
        or target_package == test_package
    ):
        raise QualificationBlocked("target and instrumentation packages must be distinct")
    if package_to_reset != target_package:
        raise QualificationBlocked(
            f"refusing package reset for {package_to_reset!r}; only target {target_package!r} is authorized"
        )


def reset_command(serial: str, target_package: str, test_package: str) -> list[str]:
    validate_package_scope(target_package, test_package, target_package)
    return ["-s", serial, "uninstall", target_package]


def _status_outcome(code: int, pending: Mapping[str, str]) -> str:
    """Classify one per-test status event.

    AndroidJUnitRunner uses status ``1`` for a test start and ``0`` for a
    successful terminal event.  Negative per-test status values are distinct
    from the final instrumentation result: ``-3`` and ``-4`` are skip/ignored
    outcomes, while ``-2`` is a failure/error outcome.  The final
    ``INSTRUMENTATION_CODE`` is handled separately below.
    """

    detail = " ".join(
        str(pending.get(key, ""))
        for key in ("stack", "stream", "shortMsg")
    )
    if code == 1:
        return "START"
    if code in {-3, -4}:
        return "SKIP"
    if code == 0:
        if re.search(r"AssumptionViolatedException|assumption\s+violated|ignored", detail, re.IGNORECASE):
            return "SKIP"
        return "PASS"
    if code == -2:
        if re.search(r"AssertionError|AssertionFailedError|ComparisonFailure", detail, re.IGNORECASE):
            return "ASSERTION"
        if re.search(r"(?:Exception|Error)", detail, re.IGNORECASE):
            return "ERROR"
        return "FAILURE"
    if code < 0:
        return "ERROR"
    return "NONTERMINAL"


def parse_instrument_output(text: str, process_exit: int = 0) -> InstrumentationSummary:
    """Parse Android instrumentation status events and JUnit result counts."""

    identities: set[str] = set()
    started_identities: set[str] = set()
    terminal_codes: dict[str, int] = {}
    terminal_outcomes: dict[str, str] = {}
    events: list[dict[str, Any]] = []
    pending: dict[str, str] = {}
    stream: list[str] = []
    result_messages: list[str] = []
    status_skip = False
    status_skipped_count = 0

    for line in text.splitlines():
        status = re.match(r"^INSTRUMENTATION_STATUS:\s*([^=]+)=(.*)$", line)
        if status:
            key, value = status.group(1).strip(), status.group(2)
            pending[key] = value
            lower_key = key.lower()
            lower_value = value.lower()
            if lower_key == "stream":
                stream.append(value)
            if lower_key in {"skipped", "ignored"} and lower_value in {"true", "1", "yes"}:
                status_skip = True
            if lower_key in {"skippedcount", "skipcount"}:
                try:
                    status_skipped_count = max(status_skipped_count, int(value))
                except ValueError:
                    pass
            continue
        code_match = re.match(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", line)
        if code_match:
            code = int(code_match.group(1))
            outcome = _status_outcome(code, pending)
            event = {
                "code": code,
                "outcome": outcome,
                "terminal": code <= 0,
                "class": pending.get("class"),
                "test": pending.get("test"),
                "current": pending.get("current"),
                "numtests": pending.get("numtests"),
            }
            events.append(event)
            class_name, method = event["class"], event["test"]
            selector = f"{class_name}#{method}" if class_name and method else None
            if selector:
                identities.add(selector)
                if outcome == "START":
                    started_identities.add(selector)
                if event["terminal"]:
                    terminal_codes[selector] = code
                    terminal_outcomes[selector] = outcome
            pending = {}
            continue
        result = re.match(r"^INSTRUMENTATION_RESULT:\s*([^=]+)=(.*)$", line)
        if result:
            key, value = result.group(1).strip(), result.group(2)
            result_messages.append(f"{key}={value}")
            continue

    instrumentation_code: int | None = None
    code_matches = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, flags=re.MULTILINE)
    if code_matches:
        instrumentation_code = int(code_matches[-1])

    ok_matches = re.findall(r"\bOK\s*\(\s*(\d+)\s+tests?\s*\)", text, flags=re.IGNORECASE)
    ok_count = int(ok_matches[-1]) if ok_matches else None
    junit_matches = list(re.finditer(
        r"Tests\s+run:\s*(\d+),\s*Failures:\s*(\d+)(?:,\s*Errors:\s*(\d+))?"
        r"(?:,\s*Skipped:\s*(\d+))?",
        text,
        flags=re.IGNORECASE,
    ))
    tests_run = int(junit_matches[-1].group(1)) if junit_matches else None
    summary_skipped = int(junit_matches[-1].group(4) or 0) if junit_matches else 0
    skipped_count = max(
        summary_skipped, status_skipped_count,
        sum(outcome == "SKIP" for outcome in terminal_outcomes.values()),
    )

    for match in re.finditer(r"\bSkipped\s*[:=]\s*(\d+)", text, flags=re.IGNORECASE):
        skipped_count = max(skipped_count, int(match.group(1)))

    declared_counts = []
    for event in events:
        value = event.get("numtests")
        if value:
            try:
                declared_counts.append(int(value))
            except ValueError:
                pass
    declared_count = max(declared_counts) if declared_counts else None
    # numtests is an in-progress declaration, not evidence that every test
    # reached a terminal result.  Only the final JUnit summary is executable
    # count evidence.
    reported_count = tests_run if tests_run is not None else ok_count

    skip_markers: list[str] = []
    for pattern in (
        r"AssumptionViolatedException",
        r"assumption\s+violated",
        r"\bignored\s*[:=]",
    ):
        if re.search(pattern, text, flags=re.IGNORECASE):
            skip_markers.append(pattern)
    if status_skip:
        skip_markers.append("INSTRUMENTATION_STATUS skipped=true")
    if any(outcome == "SKIP" for outcome in terminal_outcomes.values()):
        skip_markers.append("per-test skip status")

    failure_markers: list[str] = []
    if junit_matches:
        final_junit = junit_matches[-1]
        if int(final_junit.group(2)) or int(final_junit.group(3) or 0):
            failure_markers.append("JUnit summary failures/errors")
    for marker in (
        "FAILURES!!!",
        "INSTRUMENTATION_FAILED",
        "INSTRUMENTATION_ABORTED",
        "Process crashed",
        "shortMsg=",
    ):
        if marker.lower() in text.lower():
            failure_markers.append(marker)
    if any(outcome == "ASSERTION" for outcome in terminal_outcomes.values()):
        failure_markers.append("per-test assertion failure")
    if any(outcome == "ERROR" for outcome in terminal_outcomes.values()):
        failure_markers.append("per-test error")
    if any(outcome == "FAILURE" for outcome in terminal_outcomes.values()):
        failure_markers.append("per-test failure")
    if instrumentation_code is not None and instrumentation_code != -1:
        failure_markers.append(f"INSTRUMENTATION_CODE={instrumentation_code}")

    joined_stream = "\n".join(stream)
    return InstrumentationSummary(
        process_exit=process_exit,
        instrumentation_code=instrumentation_code,
        identities=identities,
        started_identities=started_identities,
        terminal_codes=terminal_codes,
        terminal_outcomes=terminal_outcomes,
        status_events=events,
        reported_count=reported_count,
        declared_count=declared_count,
        ok_count=ok_count,
        tests_run=tests_run,
        skipped_count=skipped_count,
        skip_markers=sorted(set(skip_markers)),
        failure_markers=sorted(set(failure_markers)),
        result_messages=result_messages,
        stream_tail=joined_stream[-4000:],
    )


def validate_instrumentation_summary(
    summary: InstrumentationSummary,
    expected: Sequence[TestIdentity],
) -> ExecutionOutcome:
    expected_selectors = [item.selector for item in expected]
    expected_set = set(expected_selectors)
    if summary.process_exit != 0:
        return ExecutionOutcome("FAIL", f"adb instrumentation command exited {summary.process_exit}", summary, expected_selectors)
    if summary.instrumentation_code != -1:
        if summary.instrumentation_code is None:
            reason = "instrumentation did not report the final completion code"
        elif summary.instrumentation_code == 0:
            reason = "instrumentation ended with completion code 0 (canceled/unfinished)"
        else:
            reason = f"instrumentation ended with completion code {summary.instrumentation_code}"
        return ExecutionOutcome(
            "FAIL",
            reason,
            summary,
            expected_selectors,
        )
    if summary.reported_count is None:
        return ExecutionOutcome("FAIL", "instrumentation did not report a JUnit test count", summary, expected_selectors)
    if summary.reported_count != len(expected):
        return ExecutionOutcome(
            "FAIL",
            f"expected {len(expected)} executed tests but instrumentation reported {summary.reported_count}",
            summary,
            expected_selectors,
        )
    missing_start = sorted(expected_set - summary.started_identities)
    missing_terminal = sorted(expected_set - set(summary.terminal_outcomes))
    extra_terminal = sorted(set(summary.terminal_outcomes) - expected_set)
    if missing_start or missing_terminal or extra_terminal:
        details = []
        if missing_start:
            details.append("missing starts=" + ",".join(missing_start))
        if missing_terminal:
            details.append("missing terminal statuses=" + ",".join(missing_terminal))
        if extra_terminal:
            details.append("unexpected terminal identities=" + ",".join(extra_terminal))
        return ExecutionOutcome("FAIL", "; ".join(details), summary, expected_selectors)
    failures = sorted(
        selector for selector, outcome in summary.terminal_outcomes.items()
        if outcome in {"ASSERTION", "ERROR", "FAILURE"}
    )
    if failures or summary.failure_markers:
        markers = ", ".join(summary.failure_markers) if summary.failure_markers else "per-test failure"
        return ExecutionOutcome(
            "FAIL",
            "instrumentation failure markers: " + markers,
            summary,
            expected_selectors,
        )
    skips = sorted(
        selector for selector, outcome in summary.terminal_outcomes.items()
        if outcome == "SKIP"
    )
    if skips or summary.skip_markers or summary.skipped_count:
        return ExecutionOutcome(
            "SKIPPED",
            "instrumentation reported skipped tests: " + ", ".join(skips or summary.skip_markers),
            summary,
            expected_selectors,
        )
    non_pass = sorted(
        selector for selector, outcome in summary.terminal_outcomes.items()
        if outcome != "PASS"
    )
    if non_pass:
        return ExecutionOutcome(
            "FAIL",
            "expected terminal PASS status for: " + ", ".join(non_pass),
            summary,
            expected_selectors,
        )
    return ExecutionOutcome("PASS", "all expected identities and counts passed", summary, expected_selectors)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def hash_paths(paths: Iterable[Path], project_root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    seen: set[Path] = set()
    for root in paths:
        root = root.resolve()
        candidates = [root] if root.is_file() else sorted(root.rglob("*")) if root.is_dir() else []
        for path in candidates:
            if not path.is_file() or path.is_symlink():
                continue
            path = path.resolve()
            if path in seen:
                continue
            seen.add(path)
            try:
                relative = path.relative_to(project_root.resolve()).as_posix()
            except ValueError:
                relative = path.as_posix()
            result[relative] = sha256_file(path)
    return dict(sorted(result.items()))


def tree_digest(hashes: Mapping[str, str]) -> str:
    digest = hashlib.sha256()
    for path, value in sorted(hashes.items()):
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(value.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def derive_project_identity(project_root: Path) -> tuple[str, str]:
    gradle = project_root / "app" / "build.gradle.kts"
    if not gradle.is_file():
        raise QualificationBlocked(f"missing app Gradle configuration: {gradle}")
    source = gradle.read_text(encoding="utf-8")
    application = re.search(r"\bapplicationId\s*=\s*\"([^\"]+)\"", source)
    if not application:
        raise QualificationBlocked("could not derive applicationId from app/build.gradle.kts")
    test_application = re.search(r"\btestApplicationId\s*=\s*\"([^\"]+)\"", source)
    test_package = test_application.group(1) if test_application else application.group(1) + ".test"
    return application.group(1), test_package


def derive_runner(project_root: Path) -> str:
    gradle = project_root / "app" / "build.gradle.kts"
    source = gradle.read_text(encoding="utf-8")
    match = re.search(r"\btestInstrumentationRunner\s*=\s*\"([^\"]+)\"", source)
    return match.group(1) if match else "androidx.test.runner.AndroidJUnitRunner"


def _resolve_path(value: str, root: Path) -> Path:
    path = Path(value)
    return path.resolve() if path.is_absolute() else (root / path).resolve()


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_")[:100] or "command"


class CheckpointStore:
    def __init__(self, output_dir: Path, state: dict[str, Any]):
        self.output_dir = output_dir
        self.state = state
        (output_dir / "commands").mkdir(parents=True, exist_ok=True)
        (output_dir / "reports").mkdir(parents=True, exist_ok=True)
        self.save()

    def save(self) -> None:
        _atomic_json(self.output_dir / "checkpoint.json", self.state)

    def add_command(self, record: dict[str, Any]) -> None:
        self.state.setdefault("commands", []).append(record)
        self.save()

    def add_run(self, record: dict[str, Any]) -> None:
        self.state.setdefault("runs", []).append(record)
        self.save()

    def finish(self, status: str, exit_code: int, error: str | None = None) -> None:
        self.state["status"] = status
        self.state["exit_code"] = exit_code
        self.state["finished_at"] = _now()
        if error:
            self.state["error"] = error
        self.save()
        _atomic_json(self.output_dir / "summary.json", self.state)


class AdbSession:
    def __init__(
        self,
        *,
        adb: Path,
        serial: str,
        avd_name: str | None,
        allow_physical: bool,
        physical_model: str | None,
        target_package: str,
        test_package: str,
        runner: str,
        apk: Path,
        test_apk: Path,
        store: CheckpointStore,
    ):
        self.adb = adb
        self.serial = serial
        self.avd_name = avd_name
        self.allow_physical = allow_physical
        self.physical_model = physical_model
        self.target_package = target_package
        self.test_package = test_package
        self.runner = runner
        self.apk = apk
        self.test_apk = test_apk
        self.store = store
        validate_package_scope(target_package, test_package, target_package)

    def command(
        self,
        name: str,
        arguments: Sequence[str],
        *,
        timeout: float = 120.0,
        scoped: bool = True,
        check: bool = True,
        _connection_retry: bool = False,
    ) -> tuple[int, str]:
        command = [str(self.adb)]
        if scoped:
            command.extend(["-s", self.serial])
        command.extend(str(item) for item in arguments)
        index = len(self.store.state.get("commands", []))
        log_path = self.store.output_dir / "commands" / f"{index:03d}-{_slug(name)}.log"
        record: dict[str, Any] = {
            "index": index,
            "name": name,
            "command": command,
            "started_at": _now(),
            "timeout_seconds": timeout,
            "log": str(log_path.relative_to(self.store.output_dir).as_posix()),
        }
        self.store.state.setdefault("commands", []).append(record)
        self.store.save()
        try:
            completed = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                check=False,
            )
            output = completed.stdout.decode("utf-8", errors="replace")
            return_code = int(completed.returncode)
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode("utf-8", errors="replace")
            output += f"\nCOMMAND TIMEOUT after {timeout} seconds\n"
            return_code = 124
            record["timeout"] = True
        except OSError as error:
            output = f"{type(error).__name__}: {error}\n"
            return_code = 127
            record["os_error"] = str(error)
        log_path.write_text(output, encoding="utf-8")
        record.update({"exit_code": return_code, "finished_at": _now()})
        self.store.save()
        # A shared host ADB server can briefly refuse a new connection. Only
        # these read-only admission queries may be repeated, once; retain the
        # failed command and its exit status before recording the retry.
        admission_reads = {
            ("devices", "-l"),
            ("shell", "pm", "list", "packages"),
            ("shell", "getprop", "ro.kernel.qemu"),
            ("emu", "avd", "name"),
        }
        if (return_code != 0 and not _connection_retry
                and tuple(arguments) in admission_reads
                and "cannot connect to daemon" in output.lower()):
            record["connection_retry"] = "one read-only admission retry"
            self.store.save()
            return self.command(
                name + "-connection-retry", arguments, timeout=timeout,
                scoped=scoped, check=check, _connection_retry=True,
            )
        if check and return_code != 0:
            raise QualificationFailure(
                f"{name} exited {return_code}; see {log_path}"
            )
        return return_code, output

    def guard_device(self) -> None:
        exit_code, devices = self.command(
            "device-list", ["devices", "-l"], scoped=False, timeout=30.0
        )
        if exit_code != 0:
            raise QualificationBlocked("adb devices -l failed")
        selected = parse_adb_devices(devices)
        if self.serial not in selected:
            raise QualificationBlocked(
                f"explicit serial {self.serial!r} was not present in adb devices output"
            )
        if selected[self.serial] != "device":
            raise QualificationBlocked(
                f"explicit serial {self.serial!r} is {selected[self.serial]!r}, not ready"
            )
        _, qemu = self.command("device-qemu", ["shell", "getprop", "ro.kernel.qemu"], timeout=30.0)
        is_emulator = qemu.strip() == "1"
        if is_emulator:
            if not self.avd_name:
                raise QualificationBlocked("--avd-name is required for an emulator target")
            _, actual = self.command("device-avd-name", ["emu", "avd", "name"], timeout=30.0)
            observed = parse_avd_name(actual)
            if observed != self.avd_name:
                raise QualificationBlocked(
                    f"emulator AVD mismatch: expected {self.avd_name!r}, observed {observed!r}"
                )
            return
        if not self.allow_physical:
            raise QualificationBlocked(
                "the selected target is physical; pass --allow-physical and "
                "--physical-ownership-confirmation only for an authorized disposable device"
            )
        if self.physical_model:
            _, actual = self.command(
                "device-model", ["shell", "getprop", "ro.product.model"], timeout=30.0
            )
            observed = actual.strip()
            if observed != self.physical_model:
                raise QualificationBlocked(
                    f"physical model mismatch: expected {self.physical_model!r}, observed {observed!r}"
                )

    def packages(self, label: str) -> set[str]:
        _, output = self.command(label, ["shell", "pm", "list", "packages"], timeout=30.0)
        return parse_packages(output)

    def install_test_apk(self) -> None:
        self.guard_device()
        _, output = self.command("install-test-apk", ["install", "-r", str(self.test_apk)], timeout=180.0)
        if "success" not in output.lower():
            raise QualificationFailure("instrumentation APK install was not acknowledged")
        if self.test_package not in self.packages("verify-test-package"):
            raise QualificationFailure(
                f"instrumentation APK did not install expected package {self.test_package}"
            )

    def ensure_target_installed(self, label: str) -> None:
        if self.target_package not in self.packages(label):
            raise QualificationBlocked(
                f"target package {self.target_package} is not installed before {label}"
            )

    def fresh_target(self, label: str) -> None:
        self.guard_device()
        installed = self.packages(f"{label}-package-presence")
        if self.target_package in installed:
            validate_package_scope(self.target_package, self.test_package, self.target_package)
            _, output = self.command(
                f"{label}-remove-target-only",
                reset_command(self.serial, self.target_package, self.test_package),
                timeout=120.0,
            )
            if "success" not in output.lower():
                raise QualificationFailure(f"target uninstall for {label} was not acknowledged")
        after = self.packages(f"{label}-verify-fresh")
        if self.target_package in after:
            raise QualificationBlocked(
                f"target package {self.target_package} remained installed after authorized reset"
            )
        if self.test_package not in after:
            raise QualificationBlocked(
                f"instrumentation package {self.test_package} disappeared during target reset"
            )
        _, output = self.command(
            f"{label}-install-target",
            ["install", "-r", str(self.apk)],
            timeout=180.0,
        )
        if "success" not in output.lower():
            raise QualificationFailure(f"target install for {label} was not acknowledged")
        self.ensure_target_installed(f"{label}-verify-installed")

    def force_stop_target(self) -> None:
        self.ensure_target_installed("before-force-stop")
        self.command("force-stop-target-only", ["shell", "am", "force-stop", self.target_package], timeout=30.0)

    def invoke(self, invocation: Invocation) -> ExecutionOutcome:
        expected = invocation.expected
        selector = expected[0].fqcn + (f"#{expected[0].method}" if len(expected) == 1 else "")
        arguments: list[str] = [
            "shell", "am", "instrument", "-w", "-r", "-e", "class", selector,
        ]
        if invocation.phase:
            arguments.extend(["-e", "stage9b.phase", invocation.phase])
        for key, value in invocation.arguments:
            arguments.extend(["-e", key, value])
        arguments.append(f"{self.test_package}/{self.runner}")
        exit_code, output = self.command(
            f"instrument-{invocation.name}",
            arguments,
            timeout=900.0 if invocation.phase else 600.0,
            check=False,
        )
        summary = parse_instrument_output(output, process_exit=exit_code)
        outcome = validate_instrumentation_summary(summary, expected)
        report_name = self.store.output_dir / "reports" / (
            f"{len(self.store.state.get('runs', [])):03d}-{_slug(invocation.name)}.json"
        )
        _atomic_json(report_name, outcome.as_dict())
        return outcome


def build_parser() -> argparse.ArgumentParser:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Run checked-in native/SAF qualification on one explicitly owned disposable target."
    )
    parser.add_argument("--serial", required=True, help="one explicit adb serial; auto-selection is refused")
    parser.add_argument("--disposable-confirmation", required=True)
    parser.add_argument(
        "--suite",
        choices=("smoke", "saf", "audit", "full", "recovery"),
        default="smoke",
    )
    parser.add_argument("--class", dest="selectors", action="append", default=[])
    parser.add_argument("--phase", dest="phases", action="append", default=[])
    parser.add_argument("--include-live", action="store_true")
    parser.add_argument("--live-confirmation")
    parser.add_argument("--avd-name", help="required and matched for emulator targets")
    parser.add_argument("--allow-physical", action="store_true")
    parser.add_argument("--physical-ownership-confirmation")
    parser.add_argument("--physical-model")
    parser.add_argument("--project-root", default=str(root))
    parser.add_argument("--source-root", default="app/src/androidTest/java")
    parser.add_argument("--adb", help="explicit adb executable; otherwise PATH lookup is used")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", default="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--target-package")
    parser.add_argument("--test-package")
    parser.add_argument("--runner")
    parser.add_argument("--fixture-root", action="append", default=[])
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--plan-only", action="store_true", help="write the verified plan without invoking adb")
    return parser


def validate_arguments(args: argparse.Namespace) -> None:
    validate_confirmation(
        args.disposable_confirmation,
        DISPOSABLE_CONFIRMATION,
        "--disposable-confirmation",
    )
    if args.allow_physical:
        validate_confirmation(
            args.physical_ownership_confirmation,
            PHYSICAL_CONFIRMATION,
            "--physical-ownership-confirmation",
        )
    elif args.physical_ownership_confirmation:
        raise QualificationBlocked(
            "--physical-ownership-confirmation requires --allow-physical"
        )
    if args.include_live:
        validate_confirmation(args.live_confirmation, LIVE_CONFIRMATION, "--live-confirmation")
    elif args.live_confirmation:
        raise QualificationBlocked("--live-confirmation requires --include-live")
    if args.suite == "audit" and not args.selectors:
        raise QualificationBlocked("--suite audit requires one or more --class selectors")
    if args.suite == "saf" and args.selectors:
        raise QualificationBlocked("--suite saf uses --phase; do not pass --class")
    if args.suite == "recovery" and args.selectors:
        raise QualificationBlocked(
            "--suite recovery uses its fixed stage/restart pair; do not pass --class"
        )
    if args.suite == "recovery" and args.phases:
        raise QualificationBlocked("--suite recovery does not accept --phase")
    if len(set(args.phases)) != len(args.phases):
        raise QualificationBlocked("the same --phase was supplied more than once")
    if len(set(args.selectors)) != len(args.selectors):
        raise QualificationBlocked("the same --class selector was supplied more than once")
    if not args.serial.strip() or any(character.isspace() for character in args.serial):
        raise QualificationBlocked("--serial must be one non-empty serial token")


def _default_fixture_roots(project_root: Path) -> list[Path]:
    candidates = (
        project_root / "app" / "src" / "androidTest" / "resources",
        project_root / "app" / "src" / "test" / "resources" / "stage0",
        project_root / "app" / "src" / "main" / "assets",
    )
    return [path for path in candidates if path.exists()]


def _config_record(args: argparse.Namespace, project_root: Path) -> dict[str, Any]:
    record = vars(args).copy()
    record["project_root"] = str(project_root)
    record["disposable_confirmation"] = "provided"
    record["physical_ownership_confirmation"] = (
        "provided" if args.physical_ownership_confirmation else None
    )
    record["live_confirmation"] = "provided" if args.live_confirmation else None
    return record


def run_qualification(args: argparse.Namespace) -> int:
    validate_arguments(args)
    project_root = Path(args.project_root).resolve()
    output_dir = _resolve_path(args.output_dir, project_root)
    if output_dir.exists() and any(output_dir.iterdir()):
        raise QualificationBlocked(
            f"output directory must be new or empty so prior evidence is preserved: {output_dir}"
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    state: dict[str, Any] = {
        "checkpoint_version": 1,
        "status": "PLANNED",
        "started_at": _now(),
        "suite": args.suite,
        "config": _config_record(args, project_root),
        "commands": [],
        "runs": [],
        "omissions": [],
    }
    store = CheckpointStore(output_dir, state)
    exit_code = 1
    final_status = "FAIL"
    error_text: str | None = None
    try:
        source_root = _resolve_path(args.source_root, project_root)
        identities = discover_tests(source_root)
        target_package_derived, test_package_derived = derive_project_identity(project_root)
        target_package = args.target_package or target_package_derived
        test_package = args.test_package or test_package_derived
        runner = args.runner or derive_runner(project_root)
        plan, omissions = build_plan_with_source(
            args.suite,
            identities,
            source_root,
            args.selectors,
            args.phases,
            include_live=args.include_live,
        )
        apk = _resolve_path(args.apk, project_root)
        test_apk = _resolve_path(args.test_apk, project_root)
        if not apk.is_file() or not test_apk.is_file():
            missing = [str(path) for path in (apk, test_apk) if not path.is_file()]
            raise QualificationBlocked("required APK does not exist: " + ", ".join(missing))
        source_hashes = hash_paths(
            [source_root, project_root / "app" / "src" / "androidTest" / "AndroidManifest.xml", project_root / "app" / "build.gradle.kts"],
            project_root,
        )
        fixture_paths = (
            [_resolve_path(value, project_root) for value in args.fixture_root]
            if args.fixture_root else _default_fixture_roots(project_root)
        )
        fixture_hashes = hash_paths(fixture_paths, project_root)
        state.update({
            "config": {
                **state["config"],
                "source_root": str(source_root),
                "apk": str(apk),
                "test_apk": str(test_apk),
                "target_package": target_package,
                "test_package": test_package,
                "runner": runner,
            },
            "hashes": {
                "apk": {"path": str(apk), "sha256": sha256_file(apk)},
                "test_apk": {"path": str(test_apk), "sha256": sha256_file(test_apk)},
                "source_tree_digest": tree_digest(source_hashes),
                "source_files": source_hashes,
                "fixture_tree_digest": tree_digest(fixture_hashes),
                "fixture_files": fixture_hashes,
            },
            "discovered_tests": [item.as_dict() for item in identities],
            "plan": [item.as_dict() for item in plan],
            "omissions": omissions,
        })
        store.save()
        if args.plan_only:
            final_status, exit_code = "PLAN_ONLY", 0
            return 0
        adb_value = args.adb or shutil.which("adb")
        if not adb_value:
            raise QualificationBlocked("adb was not supplied and was not found on PATH")
        explicit_adb = Path(adb_value)
        if not explicit_adb.is_absolute() and not any(separator in adb_value for separator in ("/", "\\")):
            located = shutil.which(adb_value)
            adb = Path(located).resolve() if located else explicit_adb.resolve()
        else:
            adb = explicit_adb.resolve()
        if not adb.is_file():
            raise QualificationBlocked(f"adb executable does not exist: {adb}")
        session = AdbSession(
            adb=adb,
            serial=args.serial,
            avd_name=args.avd_name,
            allow_physical=args.allow_physical,
            physical_model=args.physical_model,
            target_package=target_package,
            test_package=test_package,
            runner=runner,
            apk=apk,
            test_apk=test_apk,
            store=store,
        )
        session.guard_device()
        session.install_test_apk()
        for invocation in plan:
            state["current"] = invocation.as_dict()
            store.save()
            if invocation.reset_target:
                session.fresh_target(invocation.name)
            elif invocation.force_stop:
                session.force_stop_target()
            else:
                session.ensure_target_installed(f"before-{invocation.name}")
            outcome = session.invoke(invocation)
            run_record = {
                "name": invocation.name,
                "phase": invocation.phase,
                "status": outcome.status,
                "reason": outcome.reason,
                "expected": [item.as_dict() for item in invocation.expected],
                "result": outcome.as_dict(),
            }
            store.add_run(run_record)
            state.setdefault("phases", {})[invocation.name] = outcome.status
            store.save()
            if outcome.status != "PASS":
                if outcome.status == "SKIPPED":
                    raise QualificationBlocked(
                        f"{invocation.name} was skipped; required coverage is not green"
                    )
                raise QualificationFailure(f"{invocation.name} did not pass: {outcome.reason}")
        if omissions:
            raise QualificationBlocked(
                "full suite completed local coverage but has explicit live omissions; "
                "pass --include-live with account confirmation to qualify the live matrix"
            )
        final_status, exit_code = "PASS", 0
    except QualificationBlocked as error:
        final_status, exit_code, error_text = "BLOCKED", 2, str(error)
    except QualificationFailure as error:
        final_status, exit_code, error_text = "FAIL", 1, str(error)
    except Exception as error:  # preserve a report for unexpected runner defects
        final_status, exit_code, error_text = "FAIL", 1, f"{type(error).__name__}: {error}"
    finally:
        state.pop("current", None)
        store.finish(final_status, exit_code, error_text)
    if error_text:
        print(f"{final_status}: {error_text}", file=sys.stderr)
    print(f"{final_status}: evidence written to {output_dir}")
    return exit_code


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return run_qualification(args)
    except QualificationBlocked as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    except QualificationFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
