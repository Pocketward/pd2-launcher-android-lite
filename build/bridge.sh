#!/usr/bin/env bash
# The XInput bridge (../bridge/xinput-bridge.c), the game's xinput1_4.dll: it reads the app's
# gamepad file. A freestanding i686 DLL with no C runtime: clang compiles it for
# i686-pc-windows-msvc, llvm-dlltool writes a minimal import library for only the kernel32
# functions it names, and lld-link links it with /Brepro, so the output has no timestamp. The
# pinned toolchain (../containers/Containerfile.bridge) gives the same bytes in every build; the
# build stops when the DLL is not the one ../config.sh pins (size and SHA-256).
# Output: parts/bridge/xinput1_4.dll.
#
# Usage: bridge.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

identity() {
    part_identity "pin=$XINPUT_BRIDGE_SIZE $XINPUT_BRIDGE_SHA256" "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" \
        "$here/bridge.sh" "$LITE_DIR/containers/Containerfile.bridge" \
        "$LITE_DIR/bridge/xinput-bridge.c" "$LITE_DIR/bridge/xinput-bridge.def"
}

if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

if ! in_container; then
    id="$(identity)"
    if part_ready bridge "$id"; then
        echo "bridge: reusing parts/bridge"
        exit 0
    fi
    rm -rf "$LITE_PARTS/bridge"
    mkdir -p "$LITE_PARTS/bridge"
    run_in "$(lite_image bridge)" build/bridge.sh
    part_done bridge "$id"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/kernel32.def" <<'DEF'
LIBRARY KERNEL32.dll
EXPORTS
CloseHandle@4
CreateFileA@28
CreateFileMappingA@24
DisableThreadLibraryCalls@4
ExitProcess@4
GetEnvironmentVariableA@12
GetStdHandle@4
GetTickCount@0
MapViewOfFile@20
Sleep@4
OpenProcess@12
TerminateProcess@8
WaitForSingleObject@8
WriteFile@20
DEF
llvm-dlltool -m i386 -k -d "$work/kernel32.def" -l "$work/kernel32.lib"
clang --target=i686-pc-windows-msvc -Os -ffreestanding -fno-builtin -fno-stack-protector \
    -c /src/bridge/xinput-bridge.c -o "$work/bridge.obj"
# The output's file name goes into /Brepro's hash: helper.exe is the name the pin was made with.
lld-link /nologo /dll /entry:DllMain /def:/src/bridge/xinput-bridge.def /subsystem:windows /nodefaultlib \
    /Brepro /out:"$work/helper.exe" "$work/bridge.obj" "$work/kernel32.lib"
size="$(stat -c %s "$work/helper.exe")"
sha="$(sha256sum < "$work/helper.exe" | cut -d' ' -f1)"
if [[ "$size" != "$XINPUT_BRIDGE_SIZE" || "$sha" != "$XINPUT_BRIDGE_SHA256" ]]; then
    echo "the XInput bridge is $size bytes, SHA-256 $sha; pinned: $XINPUT_BRIDGE_SIZE bytes, $XINPUT_BRIDGE_SHA256" >&2
    exit 1
fi
cp "$work/helper.exe" /cache/parts/bridge/xinput1_4.dll
echo "XInput bridge: $size bytes, SHA-256 $sha (the pinned file)"
