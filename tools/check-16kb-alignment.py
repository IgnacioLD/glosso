#!/usr/bin/env python3
"""Fail if any native library in an APK/AAB is not 16 KB page-size compatible.

Google Play requires that apps targeting Android 15+ load on devices whose
memory page size is 16 KB. Two things have to hold for a packaged .so:

  1. every ELF PT_LOAD segment is aligned to 16 KB (p_align >= 0x4000 and the
     segment offset/vaddr are congruent modulo 16 KB); and
  2. the entry is stored uncompressed and page-aligned inside the archive, so
     the loader can mmap it (AGP does this for 16 KB since 8.5.1).

Usage: tools/check-16kb-alignment.py <file.apk|file.aab> [more...]
"""

import struct
import sys
import zipfile

PAGE = 16384


def elf_load_alignments(blob):
    if blob[:4] != b"\x7fELF":
        return None
    is64 = blob[4] == 2
    endian = "<" if blob[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(endian + "Q", blob, 0x20)[0]
        phentsize = struct.unpack_from(endian + "H", blob, 0x36)[0]
        phnum = struct.unpack_from(endian + "H", blob, 0x38)[0]
    else:
        phoff = struct.unpack_from(endian + "I", blob, 0x1C)[0]
        phentsize = struct.unpack_from(endian + "H", blob, 0x2A)[0]
        phnum = struct.unpack_from(endian + "H", blob, 0x2C)[0]
    segments = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from(endian + "I", blob, off)[0] != 1:  # PT_LOAD
            continue
        if is64:
            p_offset = struct.unpack_from(endian + "Q", blob, off + 0x08)[0]
            p_vaddr = struct.unpack_from(endian + "Q", blob, off + 0x10)[0]
            p_align = struct.unpack_from(endian + "Q", blob, off + 0x30)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", blob, off + 0x04)[0]
            p_vaddr = struct.unpack_from(endian + "I", blob, off + 0x08)[0]
            p_align = struct.unpack_from(endian + "I", blob, off + 0x1C)[0]
        segments.append((p_align, p_vaddr, p_offset))
    return segments


def data_offset(archive, info):
    with open(archive, "rb") as handle:
        handle.seek(info.header_offset)
        local = handle.read(30)
    name_len = struct.unpack_from("<H", local, 26)[0]
    extra_len = struct.unpack_from("<H", local, 28)[0]
    return info.header_offset + 30 + name_len + extra_len


def check(path):
    failures = []
    checked = 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            checked += 1
            segments = elf_load_alignments(archive.read(info))
            elf_ok = bool(segments) and all(
                align >= PAGE and vaddr % PAGE == offset % PAGE
                for align, vaddr, offset in segments
            )
            zip_ok = info.compress_type == zipfile.ZIP_STORED and (
                data_offset(path, info) % PAGE == 0
            )
            if not (elf_ok and zip_ok):
                failures.append((info.filename, elf_ok, zip_ok, segments))
    return checked, failures


def main(argv):
    if not argv:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    failed = False
    for path in argv:
        checked, failures = check(path)
        print(f"{path}: {checked} native libraries")
        for name, elf_ok, zip_ok, segments in failures:
            aligns = [hex(a) for a, _, _ in segments or []]
            print(
                f"  FAIL {name} (elf16k={elf_ok} zip16k={zip_ok} p_align={aligns})"
            )
            failed = True
    if failed:
        print("16 KB alignment: FAIL")
        return 1
    print("16 KB alignment: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
