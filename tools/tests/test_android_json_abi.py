import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_android_json_abi import check_paths


class AndroidJsonAbiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get("JAVA_HOME")
        compiler = str(Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")) if java_home else shutil.which("javac")
        if not compiler or not Path(compiler).is_file():
            raise unittest.SkipTest("JDK javac required for real bytecode fixtures")
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        (cls.root / "org/json").mkdir(parents=True)
        sources = {
            "org/json/JSONObject.java": "package org.json; public class JSONObject { public JSONObject put(String k,float v){return this;} public JSONObject put(String k,Object v){return this;} }",
            "org/json/JSONArray.java": "package org.json; public class JSONArray { public JSONArray put(float v){return this;} public JSONArray put(int i,float v){return this;} public JSONArray put(Object v){return this;} public JSONArray put(double v){return this;} }",
            "Unsafe.java": "import org.json.*; public class Unsafe { void write(JSONObject o,JSONArray a){o.put(\"x\",1.25f);a.put(1.25f);a.put(0,1.25f);} }",
            "Safe.java": "import org.json.*; public class Safe { void write(JSONObject o,JSONArray a){o.put(\"x\",(Object)Float.valueOf(1.25f));a.put(1.25d);} }",
        }
        for name, source in sources.items():
            (cls.root / name).write_text(source, encoding="utf-8")
        subprocess.run([compiler, "--release", "17", "-d", str(cls.root),
                        *(str(cls.root / name) for name in sources)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_real_float_overloads_fail_but_android_compatible_overloads_pass(self):
        unsafe = check_paths([self.root / "Unsafe.class"])
        self.assertFalse(unsafe["ok"])
        self.assertEqual(3, len(unsafe["failures"]))
        self.assertTrue(check_paths([self.root / "Safe.class"])["ok"])
        archive = self.root / "compiled-core.jar"
        with zipfile.ZipFile(archive, "w") as output:
            output.write(self.root / "Unsafe.class", "fixture/Unsafe.class")
        self.assertFalse(check_paths([archive])["ok"])

    def test_missing_empty_and_truncated_artifacts_fail_closed(self):
        self.assertFalse(check_paths([self.root / "missing"])["ok"])
        empty = self.root / "empty"
        empty.mkdir()
        self.assertFalse(check_paths([empty])["ok"])
        truncated = self.root / "Truncated.class"
        truncated.write_bytes((self.root / "Safe.class").read_bytes()[:20])
        result = check_paths([truncated])
        self.assertFalse(result["ok"])
        self.assertIn("Truncated", result["failures"][0])


if __name__ == "__main__":
    unittest.main()
