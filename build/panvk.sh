#!/usr/bin/env bash
# Mesa's PanVK (the Vulkan driver for Arm's Mali GPUs) for the native userland, cross-built for
# arm64 glibc, with our backend for Arm's kbase kernel driver (../mesa-kbase/), from its own Mesa
# pin (PANVK_MESA_* in ../config.sh), apart from Turnip's release (mesa.sh):
#   1. the Mesa tree: the pinned archive; the software WSI patches Turnip gets too
#      (../patches/mesa/*-wsi-*.patch: cached reads, present counter, pacing by the game view);
#      then ../patches/mesa-panvk/*.patch; then our files from ../mesa-kbase/ and Arm's kbase uAPI
#      headers, fetched at the commit config.sh pins (KBASE_UAPI_*, each file by SHA-256) into
#      include/kbase-uapi/.
#   2. host tools, native amd64, once per Mesa commit and image: mesa_clc, vtn_bindgen2 and
#      panfrost_compile (Mesa builds PanVK's internal OpenCL shaders with them; a cross build
#      cannot build them itself because they link the build machine's LLVM).
#   3. the driver, cross aarch64 through Debian multiarch, with -Dmesa-clc=system
#      -Dprecomp-compiler=system and the host tools on PATH. Prefix: the userland's placeholder
#      prefix, as for Turnip (the ICD's library_path is prefix/libdir; the app rewrites it).
# Output: parts/panvk/tree (libvulkan_panfrost.so and panfrost_icd.aarch64.json below the prefix).
#
# Usage: panvk.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

libdir=lib/aarch64-linux-gnu

identity() {
    local kbase_files
    mapfile -t kbase_files < <(find "$LITE_DIR/mesa-kbase" -type f | LC_ALL=C sort)
    part_identity "mesa=$PANVK_MESA_COMMIT $PANVK_MESA_SHA256" "kbase-uapi=$KBASE_UAPI_COMMIT" \
        "${KBASE_UAPI_FILES[@]/#/uapi }" "prefix=$USERLAND" "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" \
        "$LITE_DIR/build/panvk.sh" "$LITE_DIR/containers/Containerfile.panvk" \
        "$LITE_DIR"/patches/mesa/*-wsi-*.patch "$LITE_DIR"/patches/mesa-panvk/*.patch "${kbase_files[@]}"
}

# The local name of one uAPI header in dl/ (flat: the commit and the header's path).
uapi_name() { # header path below the uAPI root
    echo "kbase-uapi-$KBASE_UAPI_COMMIT-${1//\//_}"
}

if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

if ! in_container; then
    id="$(identity)"
    if part_ready panvk "$id"; then
        echo "panvk: reusing parts/panvk"
        exit 0
    fi
    fetch "$PANVK_MESA_URL" "$PANVK_MESA_SHA256" >/dev/null
    for entry in "${KBASE_UAPI_FILES[@]}"; do
        f="${entry##* }"
        fetch "$KBASE_UAPI_URL/$f" "${entry%% *}" "$(uapi_name "$f")" >/dev/null
    done
    rm -rf "$LITE_PARTS/panvk"
    mkdir -p "$LITE_PARTS/panvk"
    run_in "$(lite_image panvk)" build/panvk.sh
    part_done panvk "$id"
    du -sh "$LITE_PARTS/panvk/tree"
    exit 0
fi

src=/cache/work/panvk-src
build=/cache/work/panvk-build
out=/cache/parts/panvk/tree
rm -rf "$src" "$build" "$out"
mkdir -p "$src" "$build" "$out"

# --- 1. the tree -------------------------------------------------------------------------------
tar -xzf "/cache/dl/$(basename "$PANVK_MESA_URL")" -C "$src" --strip-components=1
for p in /src/patches/mesa/*-wsi-*.patch /src/patches/mesa-panvk/*.patch; do
    patch -d "$src" -p1 --forward --fuzz=0 --quiet < "$p"
    echo "applied ${p#/src/patches/}"
done
(cd /src/mesa-kbase && find . -type f) | while IFS= read -r f; do
    install -D -m 0644 "/src/mesa-kbase/$f" "$src/$f"
done
for entry in "${KBASE_UAPI_FILES[@]}"; do
    f="${entry##* }"
    install -D -m 0644 "/cache/dl/$(uapi_name "$f")" "$src/include/kbase-uapi/$f"
done

# --- 2. host tools -----------------------------------------------------------------------------
host="/cache/work/panvk-host-$PANVK_MESA_COMMIT"
tools=(mesa_clc vtn_bindgen2 panfrost_compile)
have_tools() {
    local t
    [[ "$(cat "$host/IMAGE" 2>/dev/null)" == "$PD2_IMAGE" ]] || return 1
    for t in "${tools[@]}"; do [[ -x "$host/bin/$t" ]] || return 1; done
}
if ! have_tools; then
    echo "== building the host tools"
    rm -rf "$host"
    mkdir -p "$host/bin"
    printf '%s\n' '[binaries]' "llvm-config = '/usr/lib/llvm-19/bin/llvm-config'" > "$host/native.ini"
    meson setup "$host/build" "$src" --native-file "$host/native.ini" --wrap-mode=nodownload \
        --buildtype=release -Db_ndebug=true \
        -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers=panfrost \
        -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dglx=disabled -Degl=disabled -Dgbm=disabled \
        -Dllvm=enabled -Dshared-llvm=enabled -Dmesa-clc=enabled -Dprecomp-compiler=enabled \
        -Dvulkan-layers= -Dtools= -Dbuild-tests=false -Dvalgrind=disabled -Dlibunwind=disabled \
        -Dlmsensors=disabled -Dzstd=disabled -Dxmlconfig=disabled -Dshader-cache=disabled \
        -Dhtml-docs=disabled -Dpanfrost-rust=false > "$build/host-setup.log" 2>&1 \
        || { tail -40 "$build/host-setup.log"; exit 1; }
    ninja -C "$host/build" src/compiler/clc/mesa_clc src/compiler/spirv/vtn_bindgen2 \
        src/panfrost/clc/panfrost_compile > "$build/host-ninja.log" 2>&1 || { tail -60 "$build/host-ninja.log"; exit 1; }
    install -m 0755 "$host/build/src/compiler/clc/mesa_clc" "$host/build/src/compiler/spirv/vtn_bindgen2" \
        "$host/build/src/panfrost/clc/panfrost_compile" "$host/bin/"
    rm -rf "$host/build"
    echo "$PD2_IMAGE" > "$host/IMAGE"
fi
export PATH="$host/bin:$PATH"

# --- 3. the driver -----------------------------------------------------------------------------
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
    --prefix="$USERLAND/usr" --libdir="$libdir" --buildtype=release -Db_ndebug=true \
    -Dvulkan-drivers=panfrost -Dgallium-drivers= -Dplatforms=x11 \
    -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dglx=disabled -Degl=disabled -Dgbm=disabled \
    -Dmesa-clc=system -Dprecomp-compiler=system -Dllvm=disabled -Dpanfrost-rust=false \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled -Dallow-kcmp=disabled \
    -Dvulkan-layers= -Dtools= -Dbuild-tests=false -Dhtml-docs=disabled \
    -Dexpat=enabled -Dzstd=enabled -Dshader-cache=enabled -Dxlib-lease=disabled > "$build/setup.log" 2>&1 \
    || { tail -40 "$build/setup.log"; exit 1; }
ninja -C "$build/out" -j"$(nproc)" > "$build/ninja.log" 2>&1 || { tail -60 "$build/ninja.log"; exit 1; }
dest="$build/dest"
DESTDIR="$dest" meson install -C "$build/out" --strip --no-rebuild > "$build/install.log" 2>&1

lib="$USERLAND/usr/$libdir/libvulkan_panfrost.so"
icd="$USERLAND/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json"
[[ -f "$dest$lib" && -f "$dest$icd" ]] || { echo "PanVK install incomplete" >&2; exit 1; }
got="$(jq -r .ICD.library_path "$dest$icd")"
[[ "$got" == "$lib" ]] || { echo "ICD library_path is $got, want $lib" >&2; exit 1; }
install -D -m 0755 "$dest$lib" "$out$lib"
install -D -m 0644 "$dest$icd" "$out$icd"
aarch64-linux-gnu-readelf -d "$out$lib" | sed -n 's/.*Shared library: \[\(.*\)\]/panvk needs \1/p'
rm -rf "$src" "$build"
find "$out" -type f | sed "s#^$out##"
