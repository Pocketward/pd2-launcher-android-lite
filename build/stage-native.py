#!/usr/bin/env python3
"""Stage the native ARM64 tree of the runtime from the userland's staging tree, Mesa and PanVK.

The ARM64 Wine's Unix side opens native libraries (X11, GL, Vulkan, FreeType, fontconfig, GnuTLS,
PulseAudio's client); the app starts the X server, xkbcomp, the input relay and PulseAudio. This
copies those, with their DT_NEEDED closure and data, out of the staging tree (userland/build.sh),
Mesa's install tree (build/mesa.sh) and PanVK's (build/panvk.sh), all below the data-folder
placeholder prefix. Then it
makes the copy runnable at that prefix: every program's ELF interpreter becomes the prefix's
loader, absolute run paths get the prefix, set-id bits go. Last it writes relocate.list: every
file that holds the placeholder, with "text" or "binary" (the app rewrites them as it unpacks the
tree).

Usage: stage-native.py <staging tree> <mesa install tree> <panvk install tree> <output tree>
"""
import os
import re
import shutil
import stat
import subprocess
import sys

PLACEHOLDER = b"/pd2launcher-data-placeholder-" + b"0" * 170
PREFIX = PLACEHOLDER.decode() + "/files/userland/current"
INTERP = PREFIX + "/lib/ld-linux-aarch64.so.1"
LIBDIRS = ["usr/lib/aarch64-linux-gnu", "usr/lib", "usr/lib/aarch64-linux-gnu/pulseaudio",
           "usr/lib/pulse-17.0+dfsg1/modules"]
# Programs the app starts.
PROGRAMS = ["usr/bin/Xvfb", "usr/bin/xkbcomp", "usr/bin/x-input-relay"]
# Libraries Wine's Unix side opens by name, and what those load by name themselves: glvnd's
# vendor libraries, Mesa's DRI entry, Turnip (Adreno) and PanVK (Mali).
LIBS = ["ld-linux-aarch64.so.1", "libc.so.6", "libm.so.6", "libresolv.so.2", "libgcc_s.so.1",
        "libpthread.so.0", "libdl.so.2", "librt.so.1",
        "libX11.so.6", "libX11-xcb.so.1", "libXext.so.6", "libXrender.so.1", "libXrandr.so.2",
        "libXi.so.6", "libXcursor.so.1", "libXfixes.so.3",
        "libGL.so.1", "libGLX.so.0", "libGLdispatch.so.0", "libGLX_mesa.so.0",
        "libEGL.so.1", "libEGL_mesa.so.0",
        "libvulkan.so.1", "libvulkan_freedreno.so", "libvulkan_panfrost.so", "libfreetype.so.6",
        # Wine's font setup opens it; without it win32u's setup ends early and the process gets
        # no desktop.
        "libfontconfig.so.1",
        # Wine's schannel (TLS) and bcrypt's public-key algorithms.
        "libgnutls.so.30"]
# PulseAudio with only the two modules the app's script loads (a Unix socket for Wine's
# winepulse, a pipe sink the app plays with an AudioTrack).
PULSE = ["usr/bin/pulseaudio", "libpulse.so.0", "usr/lib/pulse-17.0+dfsg1/modules/module-native-protocol-unix.so",
         "usr/lib/pulse-17.0+dfsg1/modules/module-pipe-sink.so", "usr/lib/pulse-17.0+dfsg1/modules/libprotocol-native.so"]
DATA = ["usr/share/X11/xkb", "usr/share/X11/locale", "usr/share/glvnd/egl_vendor.d/50_mesa.json",
        "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json", "usr/share/vulkan/icd.d/panfrost_icd.aarch64.json",
        "usr/lib/aarch64-linux-gnu/dri/zink_dri.so", "usr/lib/aarch64-linux-gnu/dri/swrast_dri.so",
        "usr/share/drirc.d"]
READELF = shutil.which("aarch64-linux-gnu-readelf") or "readelf"


def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def needed(path):
    if not is_elf(path):
        return []
    out = subprocess.run([READELF, "-dW", path], capture_output=True, text=True, check=True).stdout
    return re.findall(r"\(NEEDED\).*\[(.*)\]", out)


class Sources:
    """Where a relative path comes from: the Mesa tree first, then PanVK's, then the staging tree."""

    def __init__(self, staging, mesa, panvk):
        self.roots = [mesa, panvk, staging]

    def root_of(self, rel):
        for r in self.roots:
            if os.path.lexists(os.path.join(r, rel)):
                return r
        return None

    def find_lib(self, name):
        for d in LIBDIRS:
            rel = os.path.join(d, name)
            if self.root_of(rel):
                return rel
        return None


def copy(src, out, rel, done):
    """Copy rel (following symlink chains, each link kept as a link) once; return the real file."""
    while rel not in done:
        done.add(rel)
        root = src.root_of(rel)
        if root is None:
            raise SystemExit(f"missing {rel}")
        s = os.path.join(root, rel)
        dst = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.islink(s):
            target = os.readlink(s)
            if os.path.isabs(target):
                raise SystemExit(f"absolute link {rel} -> {target}")
            if os.path.lexists(dst):
                os.unlink(dst)
            os.symlink(target, dst)
            rel = os.path.normpath(os.path.join(os.path.dirname(rel), target))
            continue
        if os.path.isdir(s):
            shutil.copytree(s, dst, symlinks=True, dirs_exist_ok=True)
        else:
            shutil.copy2(s, dst)
        return rel
    return rel


def relocate(path):
    """The prefix's loader as interpreter, the prefix before absolute run paths."""
    r = subprocess.run(["patchelf", "--print-interpreter", path], capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip() != INTERP:
        subprocess.run(["patchelf", "--set-interpreter", INTERP, path], check=True)
    rp = subprocess.run(["patchelf", "--print-rpath", path], capture_output=True, text=True).stdout.strip()
    if rp:
        parts = [PREFIX + p if p.startswith("/") and not p.startswith(PREFIX) else p for p in rp.split(":")]
        new = ":".join(parts)
        if new != rp:
            dyn = subprocess.run([READELF, "-dW", path], capture_output=True, text=True, check=True).stdout
            # Keep the tag: DT_RPATH stays DT_RPATH (--force-rpath), DT_RUNPATH stays DT_RUNPATH.
            force = ["--force-rpath"] if "(RPATH)" in dyn else []
            subprocess.run(["patchelf", *force, "--set-rpath", new, path], check=True)


def main():
    staging, mesa_tree, panvk_tree, out = sys.argv[1:5]
    mesa = os.path.join(mesa_tree, PREFIX.lstrip("/"))
    panvk = os.path.join(panvk_tree, PREFIX.lstrip("/"))
    src = Sources(staging, mesa, panvk)
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    for link, target in (("lib", "usr/lib"), ("bin", "usr/bin")):
        os.symlink(target, os.path.join(out, link))
    done = {"lib", "bin"}
    queue = []
    for p in PROGRAMS + LIBS + PULSE:
        rel = p if "/" in p else src.find_lib(p)
        if rel is None:
            raise SystemExit(f"not in the userland: {p}")
        queue.append(rel)
    missing = set()
    seen_real = set()
    for d in DATA:
        real = copy(src, out, d, done)
        if real.endswith(".so"):
            queue.append(real)
    while queue:
        rel = queue.pop()
        real = copy(src, out, rel, done)
        if real in seen_real:
            continue
        seen_real.add(real)
        for n in needed(os.path.join(src.root_of(real), real)):
            r = src.find_lib(n)
            if r is None:
                missing.add(n)
            elif r not in done:
                queue.append(r)
    if missing:
        raise SystemExit(f"libraries not in the userland: {sorted(missing)}")
    relocated = 0
    for dp, dns, fns in os.walk(out):
        for n in fns:
            p = os.path.join(dp, n)
            if os.path.islink(p):
                continue
            mode = os.stat(p).st_mode
            if mode & (stat.S_ISUID | stat.S_ISGID):
                os.chmod(p, mode & ~(stat.S_ISUID | stat.S_ISGID))
            if is_elf(p):
                relocate(p)
                relocated += 1
    # fontconfig: no system fonts (Wine brings its own); cache in the user's XDG cache.
    os.makedirs(os.path.join(out, "etc/fonts"), exist_ok=True)
    with open(os.path.join(out, "etc/fonts/fonts.conf"), "w") as f:
        f.write('<?xml version="1.0"?>\n<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">\n'
                '<fontconfig>\n  <cachedir prefix="xdg">fontconfig</cachedir>\n</fontconfig>\n')
    # The name service: our glibc reads these below its own etc (patches/glibc/0008);
    # the app writes resolv.conf with Android's DNS servers before each session.
    with open(os.path.join(out, "etc/nsswitch.conf"), "w") as f:
        f.write("passwd: files\ngroup:  files\nshadow: files\nhosts:  files dns\n")
    with open(os.path.join(out, "etc/hosts"), "w") as f:
        f.write("127.0.0.1 localhost\n::1       localhost ip6-localhost ip6-loopback\n")
    with open(os.path.join(out, "etc/resolv.conf"), "w") as f:
        f.write("# The app writes Android's DNS servers here before starting programs.\n")
    # Files holding the placeholder.
    reloc = []
    for dp, dns, fns in os.walk(out):
        for n in fns:
            p = os.path.join(dp, n)
            if os.path.islink(p):
                continue
            data = open(p, "rb").read()
            if PLACEHOLDER in data:
                kind = "binary" if data[:4] == b"\x7fELF" or b"\0" in data else "text"
                reloc.append(f"{kind} {os.path.relpath(p, out)}")
    with open(os.path.join(out, "relocate.list"), "w") as f:
        f.write("\n".join(sorted(reloc)) + "\n")
    print(f"ELF files relocated: {relocated}; placeholder in {len(reloc)} files")
    total = 0
    for dp, dns, fns in os.walk(out):
        for n in fns:
            p = os.path.join(dp, n)
            if not os.path.islink(p):
                total += os.path.getsize(p)
    print(f"native tree: {total / 1e6:.1f} MB")


main()
