#!/usr/bin/env python3
"""Reject known JVM org.json float overloads missing from Android's org.json ABI."""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile
from pathlib import Path


FORBIDDEN = {
    ("org/json/JSONObject", "put", "(Ljava/lang/String;F)Lorg/json/JSONObject;"),
    ("org/json/JSONArray", "put", "(F)Lorg/json/JSONArray;"),
    ("org/json/JSONArray", "put", "(IF)Lorg/json/JSONArray;"),
}


def method_references(data: bytes) -> list[tuple[str, str, str]]:
    """Read only the class-file constant pool, including Java 17 dynamic-pool tags."""
    offset = 0

    def take(size: int) -> bytes:
        nonlocal offset
        if offset + size > len(data):
            raise ValueError("Truncated JVM class file")
        result = data[offset:offset + size]
        offset += size
        return result

    def u1() -> int:
        return take(1)[0]

    def u2() -> int:
        return struct.unpack(">H", take(2))[0]

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid JVM class-file header")
    take(4)  # minor and major version
    count = u2()
    pool: list[tuple | None] = [None] * count
    index = 1
    while index < count:
        tag = u1()
        if tag == 1:
            # The identifiers matched here are ASCII. Replacement decoding is sufficient
            # for unrelated modified-UTF8 string literals and cannot invent an ASCII ABI.
            pool[index] = (tag, take(u2()).decode("utf-8", errors="replace"))
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, u2(), u2())
        elif tag == 15:
            pool[index] = (tag, u1(), u2())
        else:
            raise ValueError(f"Unsupported class-file constant-pool tag {tag}")
        index += 1

    def entry(index: int, tag: int) -> tuple:
        if not 0 < index < count or pool[index] is None or pool[index][0] != tag:
            raise ValueError("Invalid constant-pool reference")
        return pool[index]

    references = []
    for item in pool:
        if item is None or item[0] not in (10, 11):
            continue
        owner = entry(entry(item[1], 7)[1], 1)[1]
        name_and_type = entry(item[2], 12)
        name = entry(name_and_type[1], 1)[1]
        descriptor = entry(name_and_type[2], 1)[1]
        references.append((owner, name, descriptor))
    return references


def compiled_classes(path: Path):
    if path.is_dir():
        for item in sorted(path.rglob("*.class")):
            yield str(item), item.read_bytes()
    elif path.is_file() and path.suffix == ".class":
        yield str(path), path.read_bytes()
    elif path.is_file() and path.suffix in (".jar", ".zip"):
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith(".class"):
                    yield f"{path}!/{name}", archive.read(name)
    else:
        raise ValueError(f"Missing or unsupported compiled-class input: {path}")


def check_paths(paths: list[Path]) -> dict:
    failures = []
    scanned = 0
    for path in paths:
        before = scanned
        try:
            for name, data in compiled_classes(path):
                scanned += 1
                try:
                    for reference in method_references(data):
                        if reference in FORBIDDEN:
                            owner, method, descriptor = reference
                            failures.append(f"{name}: Android has no {owner}.{method}{descriptor}; use an Object overload or convert Float to Double")
                except ValueError as error:
                    failures.append(f"{name}: {error}")
            if scanned == before:
                failures.append(f"No compiled classes found in {path}")
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            failures.append(str(error))
    return {
        "ok": not failures,
        "checked": True,
        "scanned_classes": scanned,
        "inputs": [str(path) for path in paths],
        "scope": "Known incompatible JSONObject/JSONArray put float method references in JVM production bytecode; not a complete Android API compatibility analysis",
        "failures": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", type=Path, action="append", required=True,
                        help="Production .class directory or JVM jar; repeat to check multiple artifacts")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = check_paths(args.classes)
    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    elif result["ok"]:
        print(f"Android JSON ABI OK: {result['scanned_classes']} classes, no incompatible float put references")
    else:
        for failure in result["failures"]:
            print(f"ERROR: {failure}", file=sys.stderr)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
