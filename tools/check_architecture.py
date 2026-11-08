#!/usr/bin/env python3
"""Static architecture and Binder-prefix checks. Uses Python's standard library only."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


TOKENS = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
PROJECT_CALL = re.compile(r'\bproject\s*\(\s*(?:path\s*=\s*)?["\']:(?P<name>\$?[A-Za-z][A-Za-z0-9_-]*)["\']\s*\)')
SOURCE_EXTENSIONS = {".kt", ".java"}
PRIVATE_BUILD_PATH = re.compile(r"(?:^|/)(?:evidence|attachments|release-apks|\.codex)(?:/|$)", re.I)
LEGACY_BACKEND_ADAPTERS = r"(?:BackendRegistry|BackendProvider|BackendIdentity|ShellMain|SupervisedInjector|NativeCaptureBroker|WorkerConnection|InjectionWorkerMain|IInjector|IBackendEvents|IPhysicalInputCallback)"


def clean_text(text: str, *, strings: bool = False) -> str:
    def replace(match: re.Match[str]) -> str:
        value = match.group()
        if value.startswith(("//", "/*")) or strings:
            return "".join("\n" if char == "\n" else " " for char in value)
        return value

    return TOKENS.sub(replace, text)


def within(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root):
        raise ValueError(f"Path outside repository: {relative}")
    return path


def project_dependencies(text: str) -> set[str]:
    text = clean_text(text)
    matches = list(PROJECT_CALL.finditer(text))
    if len(matches) != len(re.findall(r"\bproject\s*\(", text)):
        raise ValueError("Unrecognized project dependency expression; teach the checker its explicit form")
    result: set[str] = set()
    for match in matches:
        name = match["name"]
        if not name.startswith("$"):
            result.add(name)
            continue
        variable = re.escape(name[1:])
        loops = re.findall(rf"\bfor\s*\(\s*{variable}\s+in\s+listOf\s*\(([^)]*)\)\s*\)", text, re.S)
        if len(loops) != 1:
            raise ValueError(f"Cannot resolve dynamic project dependency {name}")
        labels = re.findall(r'["\']([A-Za-z][A-Za-z0-9_-]*)["\']', loops[0])
        residue = re.sub(r'["\'][A-Za-z][A-Za-z0-9_-]*["\']|[\s,]', "", loops[0])
        if not labels or residue:
            raise ValueError(f"Project list for {name} must contain literal module IDs")
        result.update(labels)
    return result


def canonical_aidl(text: str, expected_interface: str) -> list[str]:
    body = re.search(rf"\binterface\s+{re.escape(expected_interface)}\s*\{{(.*?)\}}", clean_text(text), re.S)
    if body is None:
        raise ValueError("Binder interface declaration missing")
    methods: list[str] = []
    names: set[str] = set()
    for declaration in body[1].split(";"):
        declaration = declaration.strip()
        if not declaration:
            continue
        method = re.fullmatch(r"((?:oneway\s+)?[\w.\[\]]+)\s+(\w+)\s*\((.*?)\)", declaration, re.S)
        if method is None:
            raise ValueError("Unrecognized Binder method declaration")
        result, name, parameters = method.groups()
        if name in names:
            raise ValueError(f"Duplicate Binder method: {name}")
        names.add(name)
        types: list[str] = []
        if parameters.strip():
            for parameter in parameters.split(","):
                parts = parameter.split()
                if len(parts) < 2 or not re.fullmatch(r"\w+", parts[-1]):
                    raise ValueError(f"Unrecognized Binder parameter in {name}")
                types.append(" ".join(parts[:-1]))
        methods.append(f"{result} {name}({','.join(types)})")
    return methods


def source_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*") if path.is_file() and path.suffix in SOURCE_EXTENSIONS)


def build_input_violations(text: str) -> list[tuple[int, str]]:
    """Build inputs must work from public source and explicitly configured SDKs.

    Diagnostic scripts may still write ignored evidence. This checks build literals,
    not the output destinations of device/test tools, and never returns literal values.
    """
    text = clean_text(text)
    violations: list[tuple[int, str]] = []
    for match in TOKENS.finditer(text):
        token = match.group()
        if not token.startswith(('"', "'")):
            continue
        literal = token[3:-3] if token.startswith('"""') else token[1:-1]
        path = literal.replace("\\", "/")
        reason = None
        if PRIVATE_BUILD_PATH.search(path):
            reason = "build inputs must belong to public source"
        elif re.match(r"^[A-Za-z]:/|^//|^/(?:home|Users|Volumes|mnt|tmp|private)/", path):
            reason = "configure external toolchains instead of hardcoding a machine path"
        if reason:
            violations.append((text.count("\n", 0, match.start()) + 1, reason))
    return violations


def check(root: Path) -> dict[str, object]:
    metadata = json.loads(within(root, "architecture/modules.json").read_text(encoding="utf-8-sig"))
    if metadata.get("schema_version") != 1:
        raise ValueError("Unsupported architecture metadata version")
    modules = metadata["modules"]
    failures: list[str] = []
    graph: dict[str, set[str]] = {}

    def fail(message: str) -> None:
        failures.append(message)

    build_inputs = {within(root, relative) for relative in (
        "android/build.gradle.kts", "android/settings.gradle.kts",
        "android/platform-build/build.gradle.kts", "android/platform-build/settings.gradle.kts",
    )}
    for name, module in modules.items():
        module_root = within(root, module["path"])
        if PRIVATE_BUILD_PATH.search(module["path"].replace("\\", "/")):
            fail(f"{name}: module must belong to public source")
        build = module_root / "build.gradle.kts"
        build_inputs.add(build)
        build_inputs.update((module_root / "src/main").rglob("CMakeLists.txt"))
        for relative in module.get("borrowed_sources", []):
            if PRIVATE_BUILD_PATH.search(relative.replace("\\", "/")) or not within(root, relative).is_file():
                fail(f"{name}: borrowed source must exist in public source")
        try:
            actual = project_dependencies(build.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as error:
            fail(f"{name}: {error}")
            actual = set()
        expected = set(module["allowed_dependencies"])
        if actual != expected:
            fail(f"{name}: project dependencies differ from metadata; unexpected={sorted(actual - expected)}, missing={sorted(expected - actual)}")
        for dependency in actual:
            if dependency not in modules:
                fail(f"{name}: unknown module {dependency}")
        graph[name] = actual

    for build in sorted(build_inputs):
        if not build.is_file():
            fail(f"{build.relative_to(root).as_posix()}: declared build file missing")
            continue
        for line, reason in build_input_violations(build.read_text(encoding="utf-8-sig")):
            fail(f"{build.relative_to(root).as_posix()}:{line}: {reason}")

    visited: set[str] = set()
    visiting: list[str] = []

    def visit(name: str) -> None:
        if name in visiting:
            fail("Project dependency cycle: " + " -> ".join(visiting[visiting.index(name):] + [name]))
            return
        if name in visited or name not in graph:
            return
        visiting.append(name)
        for dependency in sorted(graph[name]):
            visit(dependency)
        visiting.pop()
        visited.add(name)

    for name in graph:
        visit(name)

    production: set[str] = set()

    def production_visit(name: str) -> None:
        if name in production or name not in graph:
            return
        production.add(name)
        if modules[name]["kind"] in {"benchmark-application", "validation-application"}:
            fail(f"Production dependency reaches separate test application {name}")
        for dependency in graph[name]:
            production_visit(dependency)

    production_visit(metadata["production_entry"])

    def forbidden(module: str, pattern: str, reason: str) -> None:
        source = within(root, modules[module]["path"]) / "src/main"
        expression = re.compile(pattern, re.M)
        for path in source_files(source):
            text = clean_text(path.read_text(encoding="utf-8-sig"), strings=True)
            match = expression.search(text)
            if match:
                line = text.count("\n", 0, match.start()) + 1
                fail(f"{path.relative_to(root).as_posix()}:{line}: {reason}")

    forbidden("mapper-core", r"\b(?:android|androidx|io\.flutter)\.[A-Za-z_]", "core must remain independent of Android and Flutter")
    forbidden("capture-api", r"\b(?:android|androidx|io\.flutter)\.[A-Za-z_]", "physical input contract must remain platform independent")
    forbidden("presentation", r"\b(?:android\.accessibilityservice|android\.view\.accessibility|dev\.mobimapper\.(?:capture|windowcontext|backend\.host)|dev\.mobimapper\.mobimapper\.runtime)\.|\bdev\.mobimapper\.mobimapper\.backend\." + LEGACY_BACKEND_ADAPTERS + r"\b", "presentation must not depend on capture, backend composition or accessibility services")
    forbidden("presentation", r"\bdev\.mobimapper\.mobimapper\.(?:MainActivity|R|session\.(?:SessionController|CaptureService|NativeInputHost)|game\.GameCoordinator|profile\.ProfileStore)\b", "presentation must use host ports instead of application implementations")
    forbidden("injection-runtime", r"\bdev\.mobimapper\.(?:capture|windowcontext)\.|\b(?:SupervisedInjector|NativeCaptureBroker|BackendIdentity|ShellMain)\b", "touch runtime must not depend on physical capture, window discovery or privileged composition")
    controller = within(root, "android/app/src/main/kotlin/dev/mobimapper/mobimapper/session/SessionController.kt")
    if re.search(r"\b(?:NativeSessionHost|GameCoordinator|BackendRegistry|MainActivity|MapperRuntimeComposition)\b",
                 clean_text(controller.read_text(encoding="utf-8-sig"), strings=True)):
        fail("SessionController must use SessionEnvironment/SessionEvents instead of concrete application adapters")
    engine = within(root, modules["mapper-core"]["path"]) / "src/main/kotlin/dev/mobimapper/mobimapper/engine"
    for path in source_files(engine):
        if re.search(r"\bdev\.mobimapper\.(?:capture|injection|windowcontext|backend\.host)\.|\bdev\.mobimapper\.mobimapper\.backend\." + LEGACY_BACKEND_ADAPTERS + r"\b", clean_text(path.read_text(encoding="utf-8-sig"), strings=True)):
            fail(f"{path.relative_to(root).as_posix()}: mapping engine must not depend on capture or injection adapters")

    manifest = within(root, "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8-sig")
    if re.search(r"BIND_ACCESSIBILITY_SERVICE|android\.accessibilityservice|accessibility-service", re.sub(r"<!--[\s\S]*?-->", "", manifest)):
        fail("Production manifest declares an accessibility service")
    for path in sorted(within(root, "lib/features").rglob("*.dart")):
        raw = clean_text(path.read_text(encoding="utf-8-sig"))
        if re.search(r"\b(?:MethodChannel|BasicMessageChannel|EventChannel|BinaryMessenger)\b", clean_text(raw, strings=True)) or re.search(r"import\s+['\"][^'\"]*(?:platform/android|method_channel_mapper_gateway)[^'\"]*['\"]", raw):
            fail(f"{path.relative_to(root).as_posix()}: feature UI must depend on MapperGateway, not a native channel adapter")

    contract = metadata["aidl_contract"]
    aidl = within(root, contract["path"]).read_text(encoding="utf-8-sig")
    if not re.search(rf"\bpackage\s+{re.escape(contract['package'])}\s*;", clean_text(aidl)):
        fail("Stable Binder package changed")
    expected_prefix = contract["preserved_prefix"]
    if len(expected_prefix) != 15:
        fail("The preserved Binder prefix must contain the original 15 transactions")
    try:
        actual_methods = canonical_aidl(aidl, contract["interface"])
        if actual_methods[:15] != expected_prefix:
            for index, expected in enumerate(expected_prefix):
                actual = actual_methods[index] if index < len(actual_methods) else "<missing>"
                if actual != expected:
                    fail(f"Binder transaction {index + 1} changed: expected {expected!r}, found {actual!r}")
        if not contract["append_methods_allowed"] and len(actual_methods) != len(expected_prefix):
            fail("Appended Binder methods are disallowed by metadata")
    except ValueError as error:
        actual_methods = []
        fail(f"Binder contract: {error}")

    return {
        "ok": not failures,
        "module_count": len(modules),
        "project_edge_count": sum(len(edges) for edges in graph.values()),
        "production_modules": sorted(production),
        "preserved_binder_transactions": 15,
        "current_binder_transactions": len(actual_methods),
        "failures": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1], help="Repository root")
    parser.add_argument("--json", action="store_true", help="Print a machine-readable result")
    args = parser.parse_args()
    try:
        result = check(args.root.resolve())
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"ok": False, "failures": [str(error)]}
    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    elif result["ok"]:
        print(f"Architecture OK: {result['module_count']} modules, {result['project_edge_count']} edges; original 15 Binder transactions preserved.")
    else:
        for failure in result["failures"]:
            print(f"ERROR: {failure}", file=sys.stderr)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
