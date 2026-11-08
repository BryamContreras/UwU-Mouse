from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_architecture import build_input_violations, project_dependencies, within


class ArchitectureTest(unittest.TestCase):
    def test_public_sources_and_explicit_sdk_configuration_are_allowed(self):
        text = '''
            val sdk = providers.environmentVariable("FLUTTER_ROOT").orNull
            val embedding = file("$sdk/bin/cache/artifacts/engine/android-arm64/flutter.jar")
            java.srcDir("../../app/src/main/kotlin")
            implementation(project(":mapper-core"))
            // Never use file("../../evidence/private-fixture")
            val message = "Inspect diagnostic evidence after running the device test"
        '''
        self.assertEqual([], build_input_violations(text))

    def test_private_build_inputs_fail_without_disclosing_literal_contents(self):
        for path in ("../../evidence/local-profile.json", "$rootDir/attachments/capture.bin",
                     "${rootDir}/release-apks/old.apk", ".codex/device.json", "evidence"):
            with self.subTest(path=path):
                result = build_input_violations('java.srcDir("' + path + '")')
                self.assertEqual(1, len(result))
                self.assertEqual(1, result[0][0])
                self.assertNotIn(path, result[0][1])

    def test_machine_absolute_paths_fail_but_sdk_runtime_paths_do_not(self):
        for path in ("C:/flutter", r"D:\\LocalSdk\\flutter", "/home/developer/sdk",
                     "/Users/developer/sdk", "//server/private/sdk"):
            with self.subTest(path=path):
                self.assertEqual(1, len(build_input_violations('val sdk = "' + path + '"')))
        self.assertEqual([], build_input_violations('val layout = "/system/usr/keylayout"'))

    def test_literal_loop_dependencies_are_resolved_and_dynamic_ones_fail_closed(self):
        self.assertEqual({"mapper-core", "injection-api"}, project_dependencies('''
            for (module in listOf("mapper-core", "injection-api")) implementation(project(":$module"))
        '''))
        with self.assertRaises(ValueError):
            project_dependencies('implementation(project(moduleFromEnvironment))')

    def test_parent_paths_cannot_escape_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            self.assertEqual(root / "android", within(root, "android"))
            with self.assertRaises(ValueError):
                within(root, "../private")


if __name__ == "__main__":
    unittest.main()
