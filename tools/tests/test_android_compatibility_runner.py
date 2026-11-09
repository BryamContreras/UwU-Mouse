from pathlib import Path
import hashlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_android_compatibility import FULL_SUITE_TESTS, instrumentation_succeeded, verify_artifacts


class AndroidCompatibilityRunnerTest(unittest.TestCase):
    def successful_output(self, count=FULL_SUITE_TESTS):
        return ("INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n" * count
                + f"INSTRUMENTATION_RESULT: stream=OK ({count} tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_accepts_complete_instrumentation_and_separate_permission_probe(self):
        self.assertTrue(instrumentation_succeeded(self.successful_output(), FULL_SUITE_TESTS))
        self.assertTrue(instrumentation_succeeded(self.successful_output(1), 1))

    def test_process_exit_success_cannot_hide_failure_skip_or_missing_tests(self):
        successful = self.successful_output()
        for output in (successful.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                       successful.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -3", 1),
                       self.successful_output(FULL_SUITE_TESTS-1), self.successful_output(FULL_SUITE_TESTS+1), "",
                       successful.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                       successful + "INSTRUMENTATION_FAILED: Process crashed.\n"):
            with self.subTest(output=output):
                self.assertFalse(instrumentation_succeeded(output, FULL_SUITE_TESTS))

    def test_modified_or_unexpected_apk_cannot_be_claimed_as_same_build(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            sums = []
            for name in ("app.apk", "tests.apk"):
                (folder / name).write_bytes(name.encode())
                sums.append(f"{hashlib.sha256(name.encode()).hexdigest()}  {name}\n")
            (folder / "SHA256SUMS.txt").write_text("".join(sums), encoding="utf-8")
            self.assertEqual({"app.apk", "tests.apk"}, set(verify_artifacts(folder)))
            (folder / "app.apk").write_bytes(b"different build")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                verify_artifacts(folder)
            (folder / "SHA256SUMS.txt").write_text(sums[1] + sums[1], encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                verify_artifacts(folder)


if __name__ == "__main__":
    unittest.main()
