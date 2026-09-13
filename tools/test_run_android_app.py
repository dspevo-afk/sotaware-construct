"""Execute the actual portable launcher against a synthetic adb, never a device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASH = os.environ.get("BASH_EXE") or shutil.which("bash")
if os.name == "nt" and not os.environ.get("BASH_EXE"):
    git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
    if git_bash.is_file():
        BASH = str(git_bash)

MOCK_ADB = '''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$TEST_ADB_LOG"
if [[ "$1 $2" == 'shell pidof' ]]; then
    printf '%s' "${TEST_PID:-}"
    exit "${TEST_PID_EXIT:-0}"
fi
if [[ "$1" == 'logcat' ]]; then
    printf 'synthetic app event\\n'
    exit "${TEST_STREAM_EXIT:-0}"
fi
exit 0
'''

@unittest.skipUnless(BASH, "Bash is required for portable launcher tests")
class AndroidAppTaskTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="construct-launcher-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for directory in ("tools", "app", "fakebin"):
            (self.root / directory).mkdir()
        self.script = self.root / "tools/run_android_app.sh"
        shutil.copyfile(ROOT / "tools/run_android_app.sh", self.script)
        self.build = self.root / "app/build.gradle.kts"
        self.build.write_text('    applicationId = "com.sotaware.construct"\n', encoding="utf-8")
        adb = self.root / "fakebin/adb"
        adb.write_text(MOCK_ADB, encoding="utf-8", newline="\n")
        adb.chmod(0o755)
        self.log = self.root / "adb-calls.txt"

    def invoke(self, *arguments, **environment):
        env = os.environ.copy()
        env.update(TEST_ADB_LOG=self.log.as_posix(), TEST_PID="24680", TEST_PID_EXIT="0", TEST_STREAM_EXIT="0")
        env.update(environment)
        result = subprocess.run([BASH, "-c", 'export PATH="$(cd "$1" && pwd):$PATH"; shift; exec bash "$@"',
                                 "audit", (self.root / "fakebin").as_posix(), self.script.as_posix(), *arguments],
                                env=env, text=True, capture_output=True, timeout=15)
        calls = self.log.read_text().splitlines() if self.log.exists() else []
        return result, calls

    def test_launch(self):
        result, calls = self.invoke("launch")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["shell monkey -p com.sotaware.construct -c android.intent.category.LAUNCHER 1"], calls)

    def test_stop(self):
        result, calls = self.invoke("stop")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["shell am force-stop com.sotaware.construct"], calls)

    def test_app_scoped_logcat(self):
        result, calls = self.invoke("logcat", TEST_PID="24680\r\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["shell pidof -s com.sotaware.construct", "logcat --pid 24680 -v time"], calls)

    def test_stream_failure_propagates(self):
        result, calls = self.invoke("logcat", TEST_STREAM_EXIT="7")
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))

    def test_output_file_is_app_scoped(self):
        output = self.root / "output.txt"
        result, calls = self.invoke("logcat", output.as_posix())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("synthetic app event\n", output.read_text())
        self.assertEqual("logcat --pid 24680 -v time", calls[-1])

def rejected_pid(value, exit_code=0):
    def test(self):
        result, calls = self.invoke("logcat", TEST_PID=value, TEST_PID_EXIT=str(exit_code))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertEqual(["shell pidof -s com.sotaware.construct"], calls)
    return test

for name, pid, code in (("missing", "", 0), ("malformed", "host-pid", 0),
                        ("multiple", "123 456", 0), ("zero", "0", 0),
                        ("overflow", "999999999999999999", 0), ("error", "24680", 1)):
    setattr(AndroidAppTaskTest, "test_reject_" + name + "_pid", rejected_pid(pid, code))

def rejected_config(text):
    def test(self):
        self.build.write_text(text, encoding="utf-8")
        result, calls = self.invoke("logcat")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertEqual([], calls)
    return test

for name, text in (("missing", ""), ("invalid", 'applicationId = "--bad"\n'),
                   ("duplicate", 'applicationId = "com.first"\napplicationId = "com.second"\n')):
    setattr(AndroidAppTaskTest, "test_reject_" + name + "_app_id", rejected_config(text))

if __name__ == "__main__":
    unittest.main(verbosity=2)
