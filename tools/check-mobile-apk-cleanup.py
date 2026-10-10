"""Compare identical mobile builds across the resource-only packaging cleanup."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("before", type=Path)
parser.add_argument("after", type=Path)
parser.add_argument("--baseline", type=Path)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
allowlist = Path(__file__).resolve().parents[1] / "app/mobile-unused-protobuf-resources.txt"
expected = set(allowlist.read_text(encoding="utf-8").splitlines())

def sha(data):
    return hashlib.sha256(data).hexdigest()

def inventory(path):
    with ZipFile(path) as archive:
        assert archive.testzip() is None, f"Corrupt APK: {path}"
        return {item.filename: (sha(archive.read(item)), item.file_size, item.compress_size)
                for item in archive.infolist()}

before, after = inventory(args.before), inventory(args.after)
removed, added = before.keys() - after.keys(), after.keys() - before.keys()
assert removed == expected, f"Unexpected removals: {removed ^ expected}"
assert not added, f"Unexpected additions: {added}"
assert all(name.startswith("google/") and name.endswith(".proto") for name in expected)
with ZipFile(args.before) as archive:
    for name in expected:
        contents = archive.read(name)
        assert b'syntax = "proto' in contents and b'package ' in contents, name
    for name in archive.namelist():
        if name.endswith((".dex", ".so")):
            contents = archive.read(name)
            assert not any(resource.encode() in contents for resource in expected), name

signature_entries = {name for name in before.keys() & after.keys()
                     if name.upper().startswith("META-INF/") and
                     name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "/MANIFEST.MF"))}
changed = {name for name in before.keys() & after.keys() if before[name] != after[name]}
assert not changed - signature_entries, f"Retained APK content changed: {changed - signature_entries}"
native = {name: after[name][0] for name in after if name.startswith("lib/") and name.endswith(".so")}
abis = sorted({name.split('/')[1] for name in native})
assert abis == ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], abis
assert len(native) == 20, len(native)
if args.baseline:
    baseline = inventory(args.baseline)
    baseline_native = {name: baseline[name][0] for name in baseline if name.startswith("lib/") and name.endswith(".so")}
    assert native == baseline_native, "Native libraries changed from v3.1.155"
    for name in after:
        if name.startswith("assets/") and not name.startswith("assets/dexopt/"):
            assert after[name] == baseline[name], f"Functional asset changed: {name}"

report = {
    "beforeBytes": args.before.stat().st_size, "afterBytes": args.after.stat().st_size,
    "savedBytes": args.before.stat().st_size - args.after.stat().st_size,
    "beforeSha256": sha(args.before.read_bytes()), "afterSha256": sha(args.after.read_bytes()),
    "removedCount": len(removed), "removedUncompressedBytes": sum(before[n][1] for n in removed),
    "removedCompressedBytes": sum(before[n][2] for n in removed),
    "identicalRetainedEntries": len(after) - len(changed), "signatureEntryChanges": sorted(changed),
    "abis": abis, "nativeSha256": native, "removed": sorted(removed),
}
if args.output:
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k not in {"nativeSha256", "removed"}}, indent=2))
