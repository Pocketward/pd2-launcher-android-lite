#!/usr/bin/env bash
# Our glibc for the native userland: Debian 13's glibc source package (../config.sh pins it) with
# Debian's own patch series, then ours (../patches/glibc), cross-compiled for aarch64 in a pinned
# container (../containers/Containerfile.glibc) for the prefix $USERLAND, then put into the
# staging tree in place of Debian's libc6:
#   - the loader becomes the real file usr/lib/ld-linux-aarch64.so.1 (= <U>/lib, glibc's rtlddir,
#     the interpreter of every program); Debian's usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
#     becomes a link to it
#   - the shared libraries and gconv/ replace Debian's in usr/lib/aarch64-linux-gnu; etc/rpc is
#     added; debug info is stripped, as Debian ships libc6
# Headers, static libraries, programs and locale data are not installed.
#
# Usage: glibc.sh             build (or reuse parts/glibc) and install into work/staging
#        glibc.sh --identity  print the inputs the build depends on
set -euo pipefail
export LC_ALL=C

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

identity() {
    part_identity "glibc=$GLIBC_DEB_VERSION $GLIBC_DSC_SHA256 $GLIBC_ORIG_SHA256 $GLIBC_DEBIAN_SHA256" \
        "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" "prefix=$USERLAND" \
        "$LITE_DIR/userland/glibc.sh" "$LITE_DIR/containers/Containerfile.glibc" \
        "$LITE_DIR"/patches/glibc/*.patch
}

# --- inside the build container: build into /cache/parts/glibc/install -------------------------
if [[ "${1:-}" == --in-build ]]; then
    U=$USERLAND
    part=/cache/parts/glibc
    exec > >(tee "$part/build.log") 2>&1
    echo "== glibc $GLIBC_DEB_VERSION for $U"
    src=/cache/dl/glibc
    mkdir -p "$src"
    sums() {
        cat <<EOF
$GLIBC_DSC_SHA256  $GLIBC_DSC
$GLIBC_ORIG_SHA256  $GLIBC_ORIG
$GLIBC_DEBIAN_SHA256  $GLIBC_DEBIAN
EOF
    }
    if ! (cd "$src" && sums | sha256sum -c --quiet - >/dev/null 2>&1); then
        echo "== fetching the source package from snapshot.debian.org ($DEBIAN_SNAPSHOT)"
        rm -f "$src/$GLIBC_DSC" "$src/$GLIBC_ORIG" "$src/$GLIBC_DEBIAN"
        apt-get update -qq
        (cd "$src" && apt-get source --download-only "glibc=$GLIBC_DEB_VERSION")
    fi
    echo "== verifying source checksums"
    (cd "$src" && sums | sha256sum -c -)

    echo "== unpacking (dpkg-source applies Debian's debian/patches/series)"
    rm -rf /build && mkdir -p /build
    cp "$src/$GLIBC_DSC" "$src/$GLIBC_ORIG" "$src/$GLIBC_DEBIAN" /build/
    (cd /build && dpkg-source --no-check -x "$GLIBC_DSC" glibc >/dev/null)
    echo "Debian patches: $(grep -cv '^#\|^$' /build/glibc/debian/patches/series) in series, $(wc -l < /build/glibc/.pc/applied-patches) applied"

    echo "== applying our patches"
    for p in /src/patches/glibc/*.patch; do
        echo "-- $(basename "$p")"
        patch -d /build/glibc -p1 --forward --no-backup-if-mismatch --fuzz=0 < "$p"
    done

    # Kernel headers only (as Debian's debian/sysdeps/linux.mk does), from the cross
    # linux-libc-dev, so the cross libc6-dev headers next to them are not seen.
    mkdir -p /build/kheaders
    for h in linux asm asm-generic; do ln -s "/usr/aarch64-linux-gnu/include/$h" "/build/kheaders/$h"; done

    # Directory layout: Debian's multiarch layout below <U> (debian/rules: rtlddir=/lib,
    # slibdir=/lib/$(DEB_HOST_MULTIARCH), libdir=/usr/lib/$(DEB_HOST_MULTIARCH)), so the
    # Debian 13 arm64 libraries unpacked into <U> are on ld.so's default search path.
    mkdir -p /build/obj
    cat > /build/obj/configparms <<EOF
BUILD_CC = x86_64-linux-gnu-gcc
BUILD_CXX = x86_64-linux-gnu-g++
CFLAGS = -pipe -O2 -g -fdebug-prefix-map=/build/glibc=.
ASFLAGS = -pipe -O2 -g -fdebug-prefix-map=/build/glibc=.
BUILD_CFLAGS = -O2 -g
LDFLAGS =
BASH := /bin/bash
KSH := /bin/bash
SHELL := /bin/bash
LIBGD = no
bindir = $U/usr/bin
datadir = $U/usr/share
complocaledir = $U/usr/lib/locale
sysconfdir = $U/etc
libexecdir = $U/usr/libexec
rootsbindir = $U/sbin
includedir = $U/usr/include
docdir = $U/usr/share/doc
mandir = $U/usr/share/man
sbindir = $U/usr/sbin
vardbdir = $U/var/lib/misc
libdir = $U/usr/lib/aarch64-linux-gnu
slibdir = $U/lib/aarch64-linux-gnu
rtlddir = $U/lib
extra_libdir = $U/lib:$U/usr/lib
install_root = /build/stage
EOF

    echo "== configure"
    # Debian's options (debian/rules.d/build.mk, debian/sysdeps/arm64.mk), except: prefix and
    # folders (above), no --with-selinux (only nscd uses it), and no --enable-kernel: the aarch64
    # minimum 3.7.0 applies, which is also what Debian's --enable-kernel=3.2 gives on arm64.
    cd /build/obj
    CC="aarch64-linux-gnu-gcc-14 -mbranch-protection=standard -U_FILE_OFFSET_BITS -U_TIME_BITS" \
    CXX="aarch64-linux-gnu-g++-14 -mbranch-protection=standard -U_FILE_OFFSET_BITS -U_TIME_BITS" \
    AUTOCONF=false MAKEINFO=: \
    /build/glibc/configure \
        --host=aarch64-linux-gnu --build=x86_64-linux-gnu \
        --prefix="$U/usr" \
        --without-selinux \
        --disable-crypt \
        --enable-bind-now \
        --enable-fortify-source \
        --enable-stackguard-randomization \
        --enable-stack-protector=strong \
        --enable-multi-arch \
        --enable-memory-tagging \
        --with-pkgversion="Debian GLIBC $GLIBC_DEB_VERSION + PD2 Launcher Lite Android patches" \
        --with-headers=/build/kheaders \
        | tail -n 5

    echo "== make -j$(nproc)"
    make -j"$(nproc)" > /build/make.log 2>&1 || { tail -n 60 /build/make.log; exit 1; }
    tail -n 3 /build/make.log

    echo "== install"
    make install install_root=/build/stage > /build/install.log 2>&1 || { tail -n 60 /build/install.log; exit 1; }
    rm -rf "$part/install"
    mv "/build/stage$U" "$part/install"
    mkdir -p "$part/install/etc"
    file -L "$part/install/lib/ld-linux-aarch64.so.1" "$part/install/lib/aarch64-linux-gnu/libc.so.6"
    exit 0
fi

# --- inside the tools container: install into the staging tree ---------------------------------
if [[ "${1:-}" == --in-tools ]]; then
    inst=/cache/parts/glibc/install
    root=/cache/work/staging
    ma="$root/usr/lib/aarch64-linux-gnu"
    [[ -d "$ma" ]] || { echo "no staging tree: run fetch-debian.sh first" >&2; exit 1; }
    put() { # source, path relative to the staging root
        rm -rf "${root:?}/$2"
        cp --preserve=mode "$1" "$root/$2"
        if [[ "$(head -c4 "$root/$2" | od -An -tx1 | tr -d ' \n')" == 7f454c46 ]]; then
            aarch64-linux-gnu-strip --strip-debug "$root/$2"
        fi
    }
    # Loader at glibc's rtlddir; Debian's copy in the multiarch folder becomes a link to ours.
    put "$inst/lib/ld-linux-aarch64.so.1" usr/lib/ld-linux-aarch64.so.1
    rm -f "$ma/ld-linux-aarch64.so.1"
    ln -s ../ld-linux-aarch64.so.1 "$ma/ld-linux-aarch64.so.1"
    for f in "$inst"/lib/aarch64-linux-gnu/*; do
        put "$f" "usr/lib/aarch64-linux-gnu/$(basename "$f")"
    done
    # gconv modules: ours only (no gconv-modules.cache: iconvconfig does not run in a cross build;
    # glibc then reads gconv-modules and gconv-modules.d/ directly).
    rm -rf "$ma/gconv"
    while IFS= read -r -d '' f; do
        rel="usr/lib/aarch64-linux-gnu/gconv/${f#"$inst/usr/lib/aarch64-linux-gnu/gconv/"}"
        mkdir -p "$(dirname "$root/$rel")"
        put "$f" "$rel"
    done < <(find "$inst/usr/lib/aarch64-linux-gnu/gconv" -type f -print0 | sort -z)
    put "$inst/etc/rpc" etc/rpc
    # Read only by ldconfig, which does not run here; it names Debian's /usr/lib paths.
    rm -f "$root/etc/ld.so.conf.d/aarch64-linux-gnu.conf"
    rmdir --ignore-fail-on-non-empty "$root/etc/ld.so.conf.d" 2>/dev/null || true
    echo "glibc $GLIBC_DEB_VERSION + $(ls /src/patches/glibc/*.patch | wc -l) patches installed into staging"
    exit 0
fi

# --- on the host ----------------------------------------------------------------------------------
if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi
# The Debian libraries of the lock are built against Debian's libc6: it must be the version our
# glibc is made from.
locked="$(awk '$2 == "libc6" {print $3}' "$LITE_DIR/userland/packages.lock")"
[[ "$locked" == "$GLIBC_DEB_VERSION" ]] ||
    { echo "glibc: packages.lock has libc6 ${locked:-none}, but our glibc is $GLIBC_DEB_VERSION (config.sh)" >&2; exit 1; }
id="$(identity)"
if part_ready glibc "$id"; then
    echo "glibc: reusing parts/glibc"
else
    rm -rf "$LITE_PARTS/glibc"
    mkdir -p "$LITE_PARTS/glibc"
    run_in "$(lite_image glibc)" userland/glibc.sh --in-build
    [[ -f "$LITE_PARTS/glibc/install/lib/ld-linux-aarch64.so.1" ]] || { echo "glibc: no loader in the build" >&2; exit 1; }
    part_done glibc "$id"
fi
run_in "$(lite_image tools)" userland/glibc.sh --in-tools
