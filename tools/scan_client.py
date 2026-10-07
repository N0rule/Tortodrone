#!/usr/bin/env python3
"""Read JAR constant pools; write candidate strings/integers only under local/research.

Matches are leads, not verified endpoints or port numbers. No extraction or execution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PATTERN = re.compile(
    r"https?://|(?:\d{1,3}\.){3}\d{1,3}|"
    r"\b[\w-]+\.(?:com|net|org|io)\b|"
    r"server|host|port|login|auth|session|exchange|region|redirect|socket|protocol",
    re.IGNORECASE,
)


def constants(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a Java class")
    count = struct.unpack_from(">H", data, 8)[0]
    pos, index = 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
             11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            # Replacement is deliberate: JVM modified UTF-8 may contain surrogate pairs.
            yield "utf8", data[pos:pos + size].decode("utf-8", errors="replace")
        else:
            size = sizes[tag]
            if tag == 3:
                yield "integer", struct.unpack_from(">i", data, pos)[0]
        pos += size
        index += 2 if tag in (5, 6) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", type=Path, default=ROOT / "local/client/code")
    parser.add_argument("--output", type=Path, default=ROOT / "local/research/client-constants.jsonl")
    args = parser.parse_args()
    output = args.output.resolve()
    research = (ROOT / "local/research").resolve()
    installation = (ROOT / "local/client").resolve()
    if (not output.is_relative_to(research) or output.is_relative_to(args.code.resolve())
            or output.is_relative_to(installation)):
        parser.error("output must be under local/research and outside the input directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    with output.open("w", encoding="utf-8") as report:
        for jar in sorted(args.code.glob("*.jar")):
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            report.write(json.dumps({"jar": jar.name, "sha256": digest}) + "\n")
            with zipfile.ZipFile(jar) as archive:
                for entry in archive.infolist():
                    if not entry.filename.endswith(".class"):
                        continue
                    for kind, value in constants(archive.read(entry)):
                        if (kind == "utf8" and PATTERN.search(value)) or (
                            kind == "integer" and 1024 <= value <= 65535
                        ):
                            report.write(json.dumps({"jar": jar.name, "class": entry.filename,
                                                     "kind": kind, "value": value}) + "\n")
                            total += 1
    print(f"Wrote {total} candidate constants to {output}; review before sharing.")


if __name__ == "__main__":
    main()
