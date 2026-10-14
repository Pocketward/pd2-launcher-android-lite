#!/usr/bin/env bash
# Mesa (../config.sh pins the release) for the native userland, cross-built for arm64 glibc:
# Zink without LLVM, Turnip (KGSL only), GLX (dri) and EGL as glvnd vendors, X11 only, plus
# softpipe for Xvfb's GLX extension (its swrast_dri.so lists the GLX visuals; the game renders
# through Zink), with our patches (../patches/mesa: the software WSI's paced and direct present,
# and the texture sub-image bounds check without integer overflow). Prefix: the userland's
# placeholder prefix, so the ICD JSON and the DRI search path are relocated like the rest.
# Output: parts/mesa/tree (the install tree, stripped).
#
# Usage: mesa.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

identity() {
    part_identity "mesa=$MESA_VERSION $MESA_SHA256" "prefix=$USERLAND" "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" \
        "$LITE_DIR/build/mesa.sh" "$LITE_DIR/containers/Containerfile.mesa" "$LITE_DIR"/patches/mesa/*.patch
}

if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

if ! in_container; then
    id="$(identity)"
    if part_ready mesa "$id"; then
        echo "mesa: reusing parts/mesa"
        exit 0
    fi
    fetch "$MESA_URL" "$MESA_SHA256" >/dev/null
    rm -rf "$LITE_PARTS/mesa"
    mkdir -p "$LITE_PARTS/mesa"
    run_in "$(lite_image mesa)" build/mesa.sh
    part_done mesa "$id"
    du -sh "$LITE_PARTS/mesa/tree"
    exit 0
fi

src=/cache/work/mesa-src
build=/cache/work/mesa-build
out=/cache/parts/mesa/tree
rm -rf "$src" "$build" "$out"
mkdir -p "$src" "$build" "$out"
tar -xJf "/cache/dl/$(basename "$MESA_URL")" -C "$src" --strip-components=1
for p in /src/patches/mesa/*.patch; do
    patch -d "$src" -p1 --fuzz=0 --quiet < "$p"
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
pkg_config_libdir = ['/usr/lib/aarch64-linux-gnu/pkgconfig', '/usr/share/pkgconfig']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
meson setup "$build/out" "$src" --cross-file "$build/cross.ini" --wrap-mode=nodownload \
    --prefix="$USERLAND/usr" --libdir=lib/aarch64-linux-gnu --buildtype=release -Db_ndebug=true \
    -Dgallium-drivers=zink,softpipe -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dallow-kcmp=disabled \
    -Dplatforms=x11 -Dglx=dri -Degl=enabled -Dglvnd=enabled -Dgbm=disabled -Dopengl=true \
    -Dgles1=disabled -Dgles2=disabled -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
    -Dlmsensors=disabled -Dvulkan-layers= -Dtools= -Dbuild-tests=false -Dhtml-docs=disabled \
    -Dexpat=enabled -Dzstd=enabled -Dshader-cache=enabled -Dxlib-lease=disabled > "$build/setup.log" 2>&1 \
    || { tail -40 "$build/setup.log"; exit 1; }
ninja -C "$build/out" -j"$(nproc)" > "$build/ninja.log" 2>&1 || { tail -60 "$build/ninja.log"; exit 1; }
DESTDIR="$out" meson install -C "$build/out" --strip --no-rebuild > "$build/install.log" 2>&1
rm -rf "$src" "$build"
find "$out" -type f | sed "s#^$out##"
