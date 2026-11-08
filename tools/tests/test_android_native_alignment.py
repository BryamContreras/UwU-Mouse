import contextlib
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_android_native_alignment import check_paths, main, validate_elf


def elf(segments=None, bits=64, machine=None):
    """Build actual ELF headers/program tables, without requiring a native compiler."""
    if segments is None:
        segments = [(1, 5, 0, 0, 512, 512, 16384 if bits == 64 else 4096)]
    header_size, entry_size = (64, 56) if bits == 64 else (52, 32)
    output = bytearray(max(header_size + entry_size * len(segments),
                           max(offset + file_size for _, _, offset, _, file_size, _, _ in segments)))
    output[:16] = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1, 1)) + bytes(9)
    machine = machine if machine is not None else (183 if bits == 64 else 40)
    struct.pack_into("<HHIQQQIHHHHHH" if bits == 64 else "<HHIIIIIHHHHHH", output, 16,
                     3, machine, 1, 0, header_size, 0, 0, header_size, entry_size, len(segments), 0, 0, 0)
    for index, (kind, flags, offset, address, file_size, memory_size, alignment) in enumerate(segments):
        if bits == 64:
            struct.pack_into("<IIQQQQQQ", output, header_size + index * entry_size,
                             kind, flags, offset, address, 0, file_size, memory_size, alignment)
        else:
            struct.pack_into("<IIIIIIII", output, header_size + index * entry_size,
                             kind, offset, address, 0, file_size, memory_size, flags, alignment)
    return bytes(output)


def relro_elf(end=0x5000, writable_end=0xA000, next_writable=None):
    segments = [(1, 5, 0, 0, 512, 512, 16384),
                (1, 6, 0x4000, 0x4000, min(0x1000, writable_end - 0x4000), writable_end - 0x4000, 16384),
                (0x6474E552, 4, 0x4000, 0x4000, min(0x1000, end - 0x4000), end - 0x4000, 1)]
    if next_writable is not None:
        segments.append((1, 6, next_writable, next_writable, 0x100, 0x100, 16384))
    return elf(segments)


class AndroidNativeAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def apk(self, name="app.apk", entries=None, stored=False, aligned=False):
        entries = entries if entries is not None else {"lib/arm64-v8a/libfixture.so": elf()}
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            for member, payload in entries.items():
                info = zipfile.ZipInfo(member)
                info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                if stored and aligned:
                    padding = (-(archive.fp.tell() + 30 + len(member.encode("utf-8")))) % 16384
                    if 0 < padding < 4:
                        padding += 16384
                    if padding:
                        info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                archive.writestr(info, payload)
        return path

    def test_real_elf_headers_accept_16k_and_64k_loads_for_both_64bit_abis(self):
        for abi, machine, alignment in [("arm64-v8a", 183, 16384), ("x86_64", 62, 65536)]:
            result = validate_elf(elf([(1, 5, 0, 0, 512, 512, alignment)], machine=machine), abi)
            self.assertTrue(result["requires16KB"])
            self.assertEqual([alignment], result["loadAlignments"])

    def test_4k_load_mismatched_offsets_and_non_power_of_two_alignment_fail(self):
        for segment, reason in [((1, 5, 0, 0, 512, 512, 4096), "below 16384"),
                                ((1, 5, 0, 4096, 512, 512, 16384), "offset/address"),
                                ((1, 5, 0, 0, 512, 512, 24576), "power of two")]:
            with self.assertRaisesRegex(ValueError, reason):
                validate_elf(elf([segment]), "arm64-v8a")

    def test_relro_with_writable_tail_rejects_even_when_every_load_is_16k_aligned(self):
        with self.assertRaisesRegex(ValueError, "GNU_RELRO.*writable PT_LOAD"):
            validate_elf(relro_elf(), "arm64-v8a")

    def test_relro_aligned_end_or_entire_load_remainder_is_safe(self):
        self.assertTrue(validate_elf(relro_elf(end=0x8000), "arm64-v8a")["requires16KB"])
        self.assertTrue(validate_elf(relro_elf(writable_end=0x5000), "arm64-v8a")["requires16KB"])
        self.assertTrue(validate_elf(relro_elf(writable_end=0x5000, next_writable=0x8000),
                                     "arm64-v8a")["requires16KB"])
        with self.assertRaisesRegex(ValueError, "trailing 16 KB page"):
            validate_elf(relro_elf(writable_end=0x5000, next_writable=0x6000), "arm64-v8a")

    def test_32bit_4k_libraries_are_structurally_checked_without_a_16k_requirement(self):
        for abi, machine in [("armeabi-v7a", 40), ("x86", 3)]:
            result = validate_elf(elf(bits=32, machine=machine), abi)
            self.assertFalse(result["requires16KB"])
            self.assertEqual([4096], result["loadAlignments"])
        path = self.apk(entries={"lib/armeabi-v7a/libfixture.so": elf(bits=32)}, stored=True)
        self.assertTrue(check_paths([path])["ok"])

    def test_stored_libraries_require_aligned_zip_data_but_compressed_libraries_are_extracted(self):
        aligned = self.apk("aligned.apk", stored=True, aligned=True)
        report = check_paths([aligned])
        self.assertTrue(report["ok"], report["failures"])
        self.assertEqual(0, report["apks"][0]["libraries"][0]["zipDataOffset"] % 16384)
        unaligned = self.apk("unaligned.apk", stored=True)
        report = check_paths([unaligned])
        self.assertFalse(report["ok"])
        self.assertIn("ZIP data offset", report["failures"][0])
        extracted = self.apk("extracted.apk")
        self.assertTrue(check_paths([extracted])["ok"])
        bad_elf = self.apk("bad-extracted.apk", entries={"lib/arm64-v8a/libfixture.so":
                            elf([(1, 5, 0, 0, 512, 512, 4096)])})
        self.assertFalse(check_paths([bad_elf])["ok"])

    def test_truncated_header_program_table_payload_and_section_table_fail_closed(self):
        source = elf()
        bad_section = bytearray(source)
        struct.pack_into("<Q", bad_section, 40, len(source) - 1)
        struct.pack_into("<HHH", bad_section, 58, 64, 1, 0)
        no_load = bytearray(source)
        struct.pack_into("<I", no_load, 64, 4)
        too_much_file_data = elf([(1, 5, 0, 0, 512, 256, 16384)])
        for payload in (b"", source[:10], source[:40], source[:100], source[:-1], bytes(bad_section),
                        bytes(no_load), too_much_file_data):
            with self.assertRaises(ValueError):
                validate_elf(payload, "arm64-v8a")

    def test_abi_class_machine_and_relro_containment_are_validated(self):
        for payload, abi in [(elf(bits=32), "arm64-v8a"), (elf(), "x86_64"), (elf(), "unknown")]:
            with self.assertRaises(ValueError):
                validate_elf(payload, abi)
        with self.assertRaisesRegex(ValueError, "not contained"):
            validate_elf(elf([(1, 5, 0, 0, 512, 512, 16384),
                              (0x6474E552, 4, 0, 0x4000, 0, 100, 1)]), "arm64-v8a")

    def test_missing_empty_non_native_and_corrupt_archives_fail(self):
        empty = self.apk("empty.apk", entries={})
        java_only = self.apk("java.apk", entries={"classes.dex": b"fixture"})
        corrupt = self.root / "corrupt.apk"
        corrupt.write_bytes(b"not a ZIP file")
        for paths in ([], [self.root / "missing.apk"], [empty], [java_only], [corrupt]):
            self.assertFalse(check_paths(paths)["ok"])
        invalid_elf = self.apk("invalid.apk", entries={"lib/arm64-v8a/libfixture.so": b"invalid"})
        self.assertFalse(check_paths([invalid_elf])["ok"])

    def test_zip_local_header_corruption_is_detected(self):
        path = self.apk(stored=True, aligned=True)
        data = bytearray(path.read_bytes())
        data[0:4] = b"BAD!"
        path.write_bytes(data)
        self.assertFalse(check_paths([path])["ok"])

    def test_cli_expands_globs_and_returns_json_failure_for_missing_patterns(self):
        self.apk("one.apk")
        self.apk("two.apk", entries={"lib/x86_64/libfixture.so": elf(machine=62)})
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main([str(self.root / "*.apk"), "--json"])
        self.assertEqual(0, result)
        report = json.loads(output.getvalue())
        self.assertEqual(2, report["checkedApks"])
        self.assertEqual(2, report["checkedLibraries"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main([str(self.root / "missing*.apk"), "--json"])
        self.assertEqual(1, result)
        self.assertFalse(json.loads(output.getvalue())["ok"])


if __name__ == "__main__":
    unittest.main()
