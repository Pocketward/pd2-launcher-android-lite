#!/usr/bin/env python3
"""pack.py <native tree> <wine tree> <prefix dir> <assets dir> [--long] [--dotnet <tree>]: the app's runtime asset.

Writes <assets>/runtime.tar.zst (zstd -19; --long: with a 128 MB window) and runtime.version (a
hash of the contents). Paths are relative to the app's files folder: relocate.list first (the
files that hold the data-folder placeholder, from the native tree's list), then
userland/current/..., wine/..., prefix/... and, with --dotnet, dotnet/... (the official PD2
launcher's x86 .NET runtime). Prints the sizes."""
import hashlib
import io
import os
import subprocess
import sys
import tarfile

native, wine, prefix, assets = sys.argv[1:5]
long_window = "--long" in sys.argv
dotnet = sys.argv[sys.argv.index("--dotnet") + 1] if "--dotnet" in sys.argv else None
os.makedirs(assets, exist_ok=True)
tar_path = os.path.join(assets, "runtime.tar")
digest = hashlib.sha256()


def add(tar, root, arcroot):
    total = 0
    for dp, dns, fns in os.walk(root):
        dns.sort()
        for n in sorted(dns + fns):
            p = os.path.join(dp, n)
            arc = os.path.join(arcroot, os.path.relpath(p, root))
            if arc.endswith("/relocate.list") and arcroot == "userland/current":
                continue
            ti = tar.gettarinfo(p, arc)
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = ""
            ti.mtime = 0
            digest.update(arc.encode())
            digest.update(oct(ti.mode).encode())
            if ti.isreg():
                data = open(p, "rb").read()
                digest.update(data)
                total += len(data)
                tar.addfile(ti, io.BytesIO(data))
            elif ti.issym() or ti.isdir():
                digest.update(ti.linkname.encode())
                tar.addfile(ti)
            else:
                # A hard link or a special file: the app unpacks neither.
                raise SystemExit(f"pack.py: {p} is not a file, a link or a folder")
    return total


reloc = [l.split(None, 1) for l in open(os.path.join(native, "relocate.list")) if l.strip()]
listing = "".join(f"{kind} userland/current/{rel.strip()}\n" for kind, rel in reloc)
# The ARM64 Wine holds the placeholder too (its programs' ELF interpreter).
PLACEHOLDER = b"/pd2launcher-data-placeholder-" + b"0" * 170
for dp, dns, fns in os.walk(wine):
    for n in fns:
        p = os.path.join(dp, n)
        if not os.path.islink(p) and PLACEHOLDER in open(p, "rb").read():
            listing += f"binary wine/{os.path.relpath(p, wine)}\n"
listing = listing.encode()
digest.update(listing)
sizes = {}
with tarfile.open(tar_path, "w", format=tarfile.GNU_FORMAT) as tar:
    ti = tarfile.TarInfo("relocate.list")
    ti.size = len(listing)
    ti.mode = 0o644
    tar.addfile(ti, io.BytesIO(listing))
    sizes["userland"] = add(tar, native, "userland/current")
    sizes["wine"] = add(tar, wine, "wine")
    sizes["prefix"] = add(tar, prefix, "prefix")
    if dotnet:
        sizes["dotnet"] = add(tar, dotnet, "dotnet")
out = tar_path + ".zst"
cmd = ["zstd", "-19", "-T0", "-q", "-f", "--rm", tar_path, "-o", out]
if long_window:
    cmd.insert(1, "--long=27")
subprocess.run(cmd, check=True)
with open(os.path.join(assets, "runtime.version"), "w") as f:
    f.write(digest.hexdigest()[:16] + "\n")
for k, v in sizes.items():
    print(f"{k:10s} {v / 1e6:8.1f} MB unpacked")
print(f"runtime.tar.zst {os.path.getsize(out) / 1e6:.1f} MB")
