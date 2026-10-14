#!/usr/bin/env bash
# Cross-builds Xvfb from the pinned X server release for aarch64 against the staged sysroot and
# installs it into the staging tree. Not taken from Debian's xvfb package because its XKB paths
# are compile-time only (no run-time setting); built here for the prefix (../config.sh):
#   xkbcomp location   XKB_BIN_DIRECTORY   xkb/xkbInit.c, meson option xkb_bin_dir
#   XKM output folder  XKM_OUTPUT_DIR      xkb/ddxLoad.c (falls back to /tmp/), xkb_output_dir
#   XKB data           XKB_BASE_DIRECTORY  xkb/xkbInit.c (also -xkbdir at run time), xkb_dir
#   GLX swrast driver  DRI_DRIVER_PATH     include/meson.build (from dri.pc's dridriverdir)
# with our X server patches (../patches/xserver). The build goes to parts/xvfb, which a later run
# with the same inputs installs without building.
#
# Usage: xvfb.sh             build (or reuse parts/xvfb) and install into work/staging
#        xvfb.sh --identity  print the inputs the build depends on
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

identity() {
    part_identity "xserver=$XSERVER_VERSION $XSERVER_SHA256" "shell=$ANDROID_SHELL" \
        "xkm-output-dir=$XKM_OUTPUT_DIR" "prefix=$USERLAND" "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" \
        "$LITE_DIR/userland/xvfb.sh" "$LITE_DIR/userland/packages.lock" "$LITE_DIR/userland/fetch-debian.sh" \
        "$LITE_DIR/containers/Containerfile.tools" "$LITE_DIR"/patches/xserver/*.patch
}

if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

if ! in_container; then
    id="$(identity)"
    if part_ready xvfb "$id"; then
        echo "xvfb: reusing parts/xvfb"
        run_in "$(lite_image tools)" userland/xvfb.sh --install
    else
        fetch "$XSERVER_URL" "$XSERVER_SHA256" >/dev/null
        rm -rf "$LITE_PARTS/xvfb"
        mkdir -p "$LITE_PARTS/xvfb"
        run_in "$(lite_image tools)" userland/xvfb.sh --build
        part_done xvfb "$id"
    fi
    exit 0
fi

work=/cache/work
sysroot="$work/sysroot"
out=/cache/parts/xvfb
[[ -d "$sysroot/usr/include" ]] || { echo "no sysroot: run fetch-debian.sh first" >&2; exit 1; }

if [[ "${1:-}" == --build ]]; then
    build="$work/xvfb-build"
    dest="$work/xvfb-dest"
    rm -rf "$build" "$dest"
    mkdir -p "$build"
    tar -xJf "/cache/dl/$(basename "$XSERVER_URL")" -C "$build"
    src="$build/xorg-server-$XSERVER_VERSION"
    for p in /src/patches/xserver/*.patch; do
        patch -d "$src" -p1 --forward --fuzz=0 --quiet < "$p"
        echo "applied $(basename "$p")"
    done

    cat > "$build/cross.ini" <<EOF
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
nm = 'aarch64-linux-gnu-nm'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkgconf'

[properties]
sys_root = '$sysroot'
pkg_config_libdir = ['$sysroot/usr/lib/aarch64-linux-gnu/pkgconfig', '$sysroot/usr/share/pkgconfig', '$sysroot/usr/lib/pkgconfig']

[built-in options]
c_args = ['--sysroot=$sysroot', '-DXSERVER_SHELL="$ANDROID_SHELL"']
cpp_args = ['--sysroot=$sysroot']
c_link_args = ['--sysroot=$sysroot']
cpp_link_args = ['--sysroot=$sysroot']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

    meson setup "$build/out" "$src" --cross-file "$build/cross.ini" \
        --prefix="$USERLAND/usr" --libdir=lib/aarch64-linux-gnu \
        --buildtype=release -Db_ndebug=true \
        -Dxvfb=true -Dxorg=false -Dxephyr=false -Dxnest=false -Dxwin=false -Dxquartz=false \
        -Dglx=true -Dglamor=false \
        -Ddri1=false -Ddri2=false -Ddri3=false -Ddrm=false \
        -Dxdmcp=false -Dxdm-auth-1=false -Dsecure-rpc=false \
        -Dudev=false -Dudev_kms=false -Dhal=false -Dsystemd_logind=false \
        -Dpciaccess=false -Dint10=false -Dvgahw=false -Dlinux_apm=false -Dlinux_acpi=false \
        -Dxselinux=false -Dlibunwind=false -Dsha1=libmd \
        -Ddocs=false -Ddevel-docs=false -Ddocs-pdf=false \
        -Ddefault_font_path=built-ins \
        -Dxkb_dir="$USERLAND/usr/share/X11/xkb" \
        -Dxkb_bin_dir="$USERLAND/usr/bin" \
        -Dxkb_output_dir="$XKM_OUTPUT_DIR"

    # The server's GLX loads swrast_dri.so from DRI_DRIVER_PATH, taken from dri.pc's dridriverdir;
    # pkgconf prefixes that variable with the sysroot, so set the prefix's path in the header.
    sed -i "s|^#define DRI_DRIVER_PATH .*|#define DRI_DRIVER_PATH \"$USERLAND/usr/lib/aarch64-linux-gnu/dri\"|" \
        "$build/out/include/dix-config.h"

    # Fail early if a compiled-in path is not the prefix's path.
    for want in "XKB_BIN_DIRECTORY \"$USERLAND/usr/bin\"" \
                "XKB_BASE_DIRECTORY \"$USERLAND/usr/share/X11/xkb\"" \
                "XKM_OUTPUT_DIR \"$XKM_OUTPUT_DIR/\"" \
                "DRI_DRIVER_PATH \"$USERLAND/usr/lib/aarch64-linux-gnu/dri\""; do
        grep -rqF "#define $want" "$build/out/include" || { echo "compiled-in path wrong: $want" >&2; grep -rn "${want%% *}" "$build/out/include" >&2; exit 1; }
    done

    ninja -C "$build/out" -j"$(nproc)"
    DESTDIR="$dest" meson install -C "$build/out" --strip --no-rebuild >/dev/null
    install -m 0755 "$dest$USERLAND/usr/bin/Xvfb" "$out/Xvfb"
    # protocol.txt: request and error names for the server's log messages.
    if [[ -f "$dest$USERLAND/usr/lib/xorg/protocol.txt" ]]; then
        install -m 0644 "$dest$USERLAND/usr/lib/xorg/protocol.txt" "$out/protocol.txt"
    fi
    rm -rf "$build" "$dest"
fi

staging="$work/staging"
install -D -m 0755 "$out/Xvfb" "$staging/usr/bin/Xvfb"
if [[ -f "$out/protocol.txt" ]]; then
    install -D -m 0644 "$out/protocol.txt" "$staging/usr/lib/xorg/protocol.txt"
fi
aarch64-linux-gnu-readelf -d "$staging/usr/bin/Xvfb" | sed -n 's/.*Shared library: \[\(.*\)\]/Xvfb needs \1/p'
echo "Xvfb $XSERVER_VERSION installed into staging"
