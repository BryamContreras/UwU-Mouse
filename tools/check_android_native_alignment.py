#!/usr/bin/env python3
"""Validate packaged native libraries for Android's 16 KB page-size contract.

Uses ELF and ZIP binary headers, so the same check works on Windows and CI without
an NDK executable. This checks packaging/segment layout, not native runtime behavior.
https://developer.android.com/guide/practices/page-sizes
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import glob
import json
from pathlib import Path
import struct
import sys
import zipfile


PAGE_SIZE = 16 * 1024
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2
ABIS = {
    "armeabi": (32, 40),
    "armeabi-v7a": (32, 40),
    "arm64-v8a": (64, 183),
    "x86": (32, 3),
    "x86_64": (64, 62),
}


@dataclass(frozen=True)
class Segment:
    index: int
    kind: int
    flags: int
    offset: int
    address: int
    file_size: int
    memory_size: int
    alignment: int

    @property
    def end(self) -> int:
        return self.address + self.memory_size


def validate_elf(data: bytes, abi: str) -> dict:
    """Reject truncated ELF structures and incompatible LOAD/RELRO layouts."""
    if abi not in ABIS:
        raise ValueError(f"Unsupported Android native ABI: {abi}")
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("Invalid or truncated ELF identification header")
    elf_class, encoding, identification_version = data[4:7]
    if elf_class not in (1, 2) or encoding != 1 or identification_version != 1:
        raise ValueError("Invalid ELF class, version or Android little-endian encoding")
    bits = 32 if elf_class == 1 else 64
    header_size, program_size, section_size = (52, 32, 40) if bits == 32 else (64, 56, 64)
    if len(data) < header_size:
        raise ValueError("Truncated ELF file header")
    header_format = "<HHIIIIIHHHHHH" if bits == 32 else "<HHIQQQIHHHHHH"
    (kind, machine, version, _entry, program_offset, section_offset, _flags,
     declared_header_size, program_entry_size, program_count, section_entry_size,
     section_count, string_section_index) = struct.unpack_from(header_format, data, 16)
    if kind != 3 or version != 1 or declared_header_size != header_size:
        raise ValueError("Invalid ELF shared-object header")
    if (bits, machine) != ABIS[abi]:
        raise ValueError(f"ELF class/machine does not match packaged ABI {abi}")
    if program_count == 0 or program_count == 0xFFFF or program_entry_size != program_size:
        raise ValueError("Missing or unsupported ELF program-header table")
    if program_offset < header_size or program_offset + program_count * program_size > len(data):
        raise ValueError("Truncated or invalid ELF program-header table")
    if section_count:
        if (section_entry_size != section_size or section_offset < header_size or
                section_offset + section_count * section_size > len(data) or
                string_section_index >= section_count):
            raise ValueError("Truncated or invalid ELF section-header table")
    elif section_offset != 0 or string_section_index != 0:
        raise ValueError("Unsupported extended ELF section-header counts")

    segments = []
    for index in range(program_count):
        start = program_offset + index * program_size
        if bits == 32:
            segment_kind, offset, address, _physical, file_size, memory_size, flags, alignment = \
                struct.unpack_from("<IIIIIIII", data, start)
        else:
            segment_kind, flags, offset, address, _physical, file_size, memory_size, alignment = \
                struct.unpack_from("<IIQQQQQQ", data, start)
        if offset + file_size > len(data) or address + memory_size > 1 << bits:
            raise ValueError(f"ELF segment {index} exceeds file or address bounds")
        if alignment > 1 and alignment & (alignment - 1):
            raise ValueError(f"ELF segment {index} alignment is not a power of two")
        segment = Segment(index, segment_kind, flags, offset, address, file_size, memory_size, alignment)
        if segment.kind == PT_LOAD:
            if file_size > memory_size:
                raise ValueError(f"PT_LOAD[{index}] file size exceeds memory size")
            if alignment > 1 and offset % alignment != address % alignment:
                raise ValueError(f"PT_LOAD[{index}] offset/address alignment mismatch")
            if bits == 64 and alignment < PAGE_SIZE:
                raise ValueError(f"PT_LOAD[{index}] alignment {alignment} is below 16384")
        segments.append(segment)
    loads = [segment for segment in segments if segment.kind == PT_LOAD]
    relros = [segment for segment in segments if segment.kind == PT_GNU_RELRO]
    if not loads:
        raise ValueError("ELF shared object has no PT_LOAD segments")
    for relro in relros:
        if not any(load.address <= relro.address and relro.end <= load.end for load in loads):
            raise ValueError(f"GNU_RELRO[{relro.index}] is not contained in a PT_LOAD segment")
        if bits != 64 or relro.end % PAGE_SIZE == 0:
            continue
        protected_end = (relro.end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
        # A non-aligned RELRO end is valid if it consumes the containing LOAD's
        # entire remainder. Also reject a different writable LOAD sharing that tail
        # page: the linker protects page ranges, not individual program headers.
        for load in loads:
            if load.flags & PF_W and max(relro.end, load.address) < min(protected_end, load.end):
                raise ValueError(f"GNU_RELRO[{relro.index}] ends at unaligned address 0x{relro.end:x} "
                                 f"with writable PT_LOAD[{load.index}] data in the trailing 16 KB page")
    return {
        "bits": bits,
        "machine": machine,
        "loadAlignments": [segment.alignment for segment in loads],
        "relroEndAddresses": [segment.end for segment in relros],
        "requires16KB": bits == 64,
    }


def zip_data_offset(source, info: zipfile.ZipInfo, archive_size: int) -> int:
    source.seek(info.header_offset)
    header = source.read(30)
    if len(header) != 30:
        raise ValueError("Truncated ZIP local file header")
    fields = struct.unpack("<IHHHHHIIIHH", header)
    if fields[0] != 0x04034B50 or fields[3] != info.compress_type:
        raise ValueError("Invalid ZIP local file header")
    offset = info.header_offset + 30 + fields[-2] + fields[-1]
    if offset > archive_size or offset + info.compress_size > archive_size:
        raise ValueError("ZIP native library data exceeds archive bounds")
    return offset


def check_apk(path: Path) -> dict:
    report = {"path": str(path), "libraries": [], "failures": []}
    try:
        with zipfile.ZipFile(path) as archive, path.open("rb") as source:
            entries = sorted((entry for entry in archive.infolist() if
                              entry.filename.startswith("lib/") and entry.filename.endswith(".so")),
                             key=lambda entry: entry.filename)
            if not entries:
                raise ValueError("APK contains no packaged native libraries")
            seen = set()
            for entry in entries:
                item = {"entry": entry.filename}
                report["libraries"].append(item)
                try:
                    if entry.filename in seen:
                        raise ValueError("Duplicate native library ZIP entry")
                    seen.add(entry.filename)
                    components = entry.filename.split("/")
                    if len(components) != 3 or not components[2]:
                        raise ValueError("Invalid native library APK path")
                    if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                        raise ValueError("Unsupported native library ZIP encryption/compression")
                    item["abi"] = components[1]
                    item.update(validate_elf(archive.read(entry), components[1]))
                    item["zipCompression"] = "stored" if entry.compress_type == zipfile.ZIP_STORED else "deflated"
                    item["zipDataOffset"] = zip_data_offset(source, entry, path.stat().st_size)
                    if (item["requires16KB"] and entry.compress_type == zipfile.ZIP_STORED and
                            item["zipDataOffset"] % PAGE_SIZE != 0):
                        raise ValueError(f"Uncompressed native library ZIP data offset {item['zipDataOffset']} "
                                         "is not 16 KB aligned")
                    item["ok"] = True
                except (ValueError, OSError, zipfile.BadZipFile, RuntimeError, struct.error) as failure:
                    item["ok"] = False
                    item["error"] = str(failure)
                    report["failures"].append(f"{entry.filename}: {failure}")
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError) as failure:
        report["failures"].append(str(failure))
    report["ok"] = not report["failures"]
    return report


def check_paths(paths: list[Path]) -> dict:
    reports = [check_apk(path) for path in paths]
    failures = [f"{report['path']}: {failure}" for report in reports for failure in report["failures"]]
    if not reports:
        failures.append("No APK inputs were provided")
    return {"ok": not failures, "checkedApks": len(reports),
            "checkedLibraries": sum(len(report["libraries"]) for report in reports),
            "failures": failures, "apks": reports}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", help="APK paths, directories or glob patterns")
    parser.add_argument("--json", action="store_true", help="Emit a machine-readable report")
    args = parser.parse_args(argv)
    paths = []
    missing = []
    for pattern in args.apks:
        matches = sorted(glob.glob(pattern, recursive=True))
        if not matches:
            missing.append(f"APK input did not match a file: {pattern}")
        for match in matches:
            path = Path(match)
            if path.is_dir():
                discovered = sorted(path.rglob("*.apk"))
                if not discovered:
                    missing.append(f"APK directory contains no APKs: {path}")
                paths.extend(discovered)
            else:
                paths.append(path)
    report = check_paths(list(dict.fromkeys(paths)))
    report["failures"].extend(missing)
    report["ok"] = not report["failures"]
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for failure in report["failures"]:
            print(f"FAIL: {failure}")
        if report["ok"]:
            print(f"Native alignment verified: {report['checkedLibraries']} libraries in {report['checkedApks']} APKs")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
