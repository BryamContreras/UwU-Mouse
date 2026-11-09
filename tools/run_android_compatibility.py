"""Install the same APK pair and run framework compatibility checks on one emulator."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

APP = "dev.uwumouse.app"
TEST_CLASS = f"{APP}.runtime.AndroidCompatibilityTest"
RUNNER = f"{APP}.test/androidx.test.runner.AndroidJUnitRunner"
LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
FULL_SUITE_TESTS = 9


def run(adb: list[str], *arguments: str, timeout: int = 120) -> str:
    result = subprocess.run([*adb, *arguments], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(arguments)} failed:\n{result.stdout}")
    return result.stdout


def verify_artifacts(folder: Path) -> dict[str, str]:
    expected = {}
    for line in (folder / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.lstrip("*")
        if name not in {"app.apk", "tests.apk"} or name in expected:
            raise ValueError(f"Unexpected or duplicate compatibility artifact: {name}")
        actual = hashlib.sha256((folder / name).read_bytes()).hexdigest()
        if actual != digest:
            raise ValueError(f"Compatibility artifact checksum mismatch: {name}")
        expected[name] = actual
    if set(expected) != {"app.apk", "tests.apk"}:
        raise ValueError("The shared compatibility artifact must contain app.apk and tests.apk")
    return expected


def instrumentation_succeeded(output: str, expected_tests: int) -> bool:
    # am instrument can return process status 0 even when JUnit reported a failure.
    statuses = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", output, re.M)]
    return (statuses.count(0) == expected_tests and all(value >= 0 for value in statuses)
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M) is not None
            and "INSTRUMENTATION_FAILED" not in output and "FAILURES!!!" not in output)


def instrument(adb: list[str], api: int, network: str, output_path: Path, *, network_only: bool = False) -> None:
    selection = f"{TEST_CLASS}#activationNetworkPermissionReflectsPlatformGrant" if network_only else TEST_CLASS
    output = run(adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", selection,
                 "-e", "expectedApi", str(api), "-e", "expectedLocalNetwork", network,
                 RUNNER, timeout=600)
    output_path.write_text(output, encoding="utf-8")
    print(output, end="")
    if not instrumentation_succeeded(output, 1 if network_only else FULL_SUITE_TESTS):
        raise RuntimeError(f"Instrumentation did not pass completely; see {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--expected-api", type=int, choices=(34, 35, 36, 37), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    arguments = parser.parse_args()
    adb = [arguments.adb, "-e"]  # Never select an attached physical phone.
    checksums = verify_artifacts(arguments.artifacts)
    if run(adb, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Compatibility installation is restricted to an Android emulator")
    api = int(run(adb, "shell", "getprop", "ro.build.version.sdk").strip())
    if api != arguments.expected_api:
        raise RuntimeError(f"Expected Android API {arguments.expected_api}, emulator is API {api}")
    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    evidence = {"api": api, "apkSha256": checksums,
                "abis": run(adb, "shell", "getprop", "ro.product.cpu.abilist").strip(),
                "fingerprint": run(adb, "shell", "getprop", "ro.build.fingerprint").strip()}
    (arguments.output_dir / "environment.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    try:
        for name in ("app.apk", "tests.apk"):
            print(run(adb, "install", "-r", "-t", str(arguments.artifacts / name)), end="")
        network = "not-required"
        if api >= 37:
            # Runtime revocation can kill the app. Change it before launching instrumentation.
            run(adb, "shell", "pm", "revoke", APP, LOCAL_NETWORK)
            network = "denied"
        instrument(adb, api, network, arguments.output_dir / "instrumentation.txt")
        if api >= 37:
            run(adb, "shell", "pm", "grant", APP, LOCAL_NETWORK)
            instrument(adb, api, "granted", arguments.output_dir / "local-network-granted.txt", network_only=True)
    finally:
        (arguments.output_dir / "logcat.txt").write_text(run(adb, "logcat", "-d", "-v", "threadtime"), encoding="utf-8")
        (arguments.output_dir / "package.txt").write_text(run(adb, "shell", "dumpsys", "package", APP), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
