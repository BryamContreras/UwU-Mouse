"""Check the shared build versions before compiling locally or in CI."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_toolchain(root: Path = ROOT) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in (root / "android/toolchain.properties").read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise ValueError(f"Invalid toolchain property: {line}")
        properties[key.strip()] = value.strip()
    return properties


def check(root: Path, versions: dict[str, str], flutter_json: Path | None) -> None:
    pinned = (root / ".flutter-version").read_text(encoding="utf-8").strip()
    if pinned != versions["flutter"]:
        raise ValueError(".flutter-version differs from android/toolchain.properties")
    pubspec = (root / "pubspec.yaml").read_text(encoding="utf-8")
    if not re.search(r"^  sdk:\s*\^" + re.escape(versions["dart"]) + r"\s*$", pubspec, re.M):
        raise ValueError("pubspec.yaml must require the shared Dart version")
    wrapper = (root / "android/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    if f"gradle-{versions['gradle']}-bin.zip" not in wrapper:
        raise ValueError("Gradle wrapper differs from android/toolchain.properties")
    if not re.search(r"^distributionSha256Sum=[a-f0-9]{64}$", wrapper, re.M):
        raise ValueError("Gradle wrapper must include its distribution SHA-256")
    if flutter_json is not None:
        installed = json.loads(flutter_json.read_text(encoding="utf-8-sig"))
        expected = {
            "frameworkVersion": versions["flutter"],
            "frameworkRevision": versions["flutterRevision"],
            "dartSdkVersion": versions["dart"],
        }
        for key, value in expected.items():
            if installed.get(key) != value:
                raise ValueError(f"Installed {key}={installed.get(key)!r}; expected {value!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--flutter-json", type=Path)
    parser.add_argument("--github-output", type=Path)
    arguments = parser.parse_args()
    versions = load_toolchain(arguments.root)
    try:
        check(arguments.root, versions, arguments.flutter_json)
    except (KeyError, ValueError, OSError) as error:
        parser.exit(1, f"Toolchain check failed: {error}\n")
    if arguments.github_output:
        with arguments.github_output.open("a", encoding="utf-8") as output:
            for key, value in versions.items():
                output.write(f"{key}={value}\n")
    print(json.dumps({"toolchain": versions, "installedChecked": arguments.flutter_json is not None}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
