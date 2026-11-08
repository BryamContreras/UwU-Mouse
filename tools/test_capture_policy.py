#!/usr/bin/env python3
"""Compile and run the production native capture policy tests without Android."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(arguments, **kwargs):
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
    return subprocess.run(arguments, text=True, capture_output=True, **kwargs)


def compiler_environment(build_dir, explicit):
    environment = os.environ.copy()
    requested = explicit or environment.get("CXX")
    if requested:
        compiler = shutil.which(requested) or (requested if Path(requested).is_file() else None)
        if not compiler:
            raise RuntimeError("C++ compiler not found: " + requested)
        return compiler, environment
    for candidate in (["cl.exe"] if os.name == "nt" else []) + ["g++", "clang++"]:
        compiler = shutil.which(candidate)
        if compiler:
            return compiler, environment
    if os.name == "nt":
        vswhere = Path(environment.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
        if vswhere.is_file():
            found = run([str(vswhere), "-latest", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"])
            installation = found.stdout.strip()
            vcvars = Path(installation) / "VC/Auxiliary/Build/vcvars64.bat"
            if found.returncode == 0 and installation and vcvars.is_file():
                # Only this helper's build directory is written; the toolchain stays untouched.
                setup = build_dir / "capture-test-environment.cmd"
                setup.write_text('@echo off\ncall "' + str(vcvars).replace("%", "%%") + '" >nul\nif errorlevel 1 exit /b 1\nset\n', encoding="utf-8")
                configured = run([environment.get("COMSPEC", "cmd.exe"), "/d", "/c", str(setup)])
                if configured.returncode == 0:
                    for line in configured.stdout.splitlines():
                        if "=" in line and not line.startswith("="):
                            key, value = line.split("=", 1)
                            existing = next((present for present in environment if present.lower() == key.lower()), key)
                            environment[existing] = value
                    search_path = next((value for key, value in environment.items() if key.lower() == "path"), "")
                    compiler = shutil.which("cl.exe", path=search_path)
                    if compiler:
                        return compiler, environment
    raise RuntimeError("Install a C++17 compiler, set CXX, or pass --compiler (MSVC, g++ or clang++).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", help="C++ compiler executable; defaults to CXX or toolchain discovery")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    build_dir = root / "build/native-capture-policy-tests"
    build_dir.mkdir(parents=True, exist_ok=True)
    compiler, environment = compiler_environment(build_dir, args.compiler)
    source = root / "android/capture-native/src/test/cpp/capture_policy_test.cpp"
    executable = build_dir / ("capture-policy-test.exe" if os.name == "nt" else "capture-policy-test")
    if Path(compiler).name.lower() in {"cl", "cl.exe", "clang-cl", "clang-cl.exe"}:
        command = [compiler, "/nologo", "/std:c++17", "/EHsc", "/W4", "/WX", "/O2", "/Fe" + str(executable), "/Fo" + str(build_dir / "capture-policy-test.obj"), str(source)]
    else:
        command = [compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-pedantic", "-O2", str(source), "-o", str(executable)]
    compiled = run(command, cwd=build_dir, env=environment)
    if compiled.returncode:
        sys.stderr.write(compiled.stdout + compiled.stderr)
        return compiled.returncode
    tested = run([str(executable)], cwd=build_dir, env=environment)
    if tested.returncode:
        sys.stderr.write(tested.stdout + tested.stderr)
        return tested.returncode
    result = json.loads(tested.stdout)
    result["compiler"] = Path(compiler).name
    print(json.dumps(result))
    return 0 if result.get("failures") == 0 else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print("Native capture policy tests failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
