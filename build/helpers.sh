#!/usr/bin/env bash
# The helpers in the Wine prefix: keep-on-screen.exe (../helpers/keep-on-screen.c) keeps the
# official PD2 launcher's window on the X screen; close-launcher.exe (../helpers/close-launcher.c)
# asks the launcher to close once the game it started shows. Each is a freestanding ARM64 Windows
# program with no C runtime, which the ARM64 Wine runs natively: clang compiles it for
# aarch64-pc-windows-msvc, llvm-dlltool writes minimal import libraries for only the user32 and
# kernel32 functions they name, and lld-link links it with /Brepro, so the output has no
# timestamp. The XInput bridge's pinned toolchain (../containers/Containerfile.bridge) gives the
# same bytes in every build; the build stops when a program is not the one ../config.sh pins (size
# and SHA-256). The prefix template carries them (prefix.sh).
# Output: parts/helpers/keep-on-screen.exe, parts/helpers/close-launcher.exe.
#
# Usage: helpers.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

identity() {
    part_identity "pin=$KEEP_ON_SCREEN_SIZE $KEEP_ON_SCREEN_SHA256" \
        "pin=$CLOSE_LAUNCHER_SIZE $CLOSE_LAUNCHER_SHA256" "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" \
        "$here/helpers.sh" "$LITE_DIR/containers/Containerfile.bridge" "$LITE_DIR/helpers/keep-on-screen.c" \
        "$LITE_DIR/helpers/close-launcher.c"
}

if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

if ! in_container; then
    id="$(identity)"
    if part_ready helpers "$id"; then
        echo "helpers: reusing parts/helpers"
        exit 0
    fi
    rm -rf "$LITE_PARTS/helpers"
    mkdir -p "$LITE_PARTS/helpers"
    run_in "$(lite_image bridge)" build/helpers.sh
    part_done helpers "$id"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/user32.def" <<'DEF'
LIBRARY USER32.dll
EXPORTS
EnumWindows
GetSystemMetrics
GetWindow
GetWindowLongW
GetWindowRect
GetWindowThreadProcessId
IsIconic
IsWindowVisible
PostMessageW
SetWindowPos
DEF
cat > "$work/kernel32.def" <<'DEF'
LIBRARY KERNEL32.dll
EXPORTS
CloseHandle
CreateToolhelp32Snapshot
ExitProcess
GetStdHandle
Process32FirstW
Process32NextW
Sleep
WriteFile
DEF
llvm-dlltool -m arm64 -d "$work/user32.def" -l "$work/user32.lib"
llvm-dlltool -m arm64 -d "$work/kernel32.def" -l "$work/kernel32.lib"
# helper <name> <pinned size> <pinned SHA-256>: helpers/<name>.c to parts/helpers/<name>.exe.
helper() {
    clang --target=aarch64-pc-windows-msvc -Os -ffreestanding -fno-builtin -fno-stack-protector \
        -c "/src/helpers/$1.c" -o "$work/$1.obj"
    lld-link /nologo /entry:start /subsystem:windows /nodefaultlib /Brepro /machine:arm64 \
        /out:"$work/$1.exe" "$work/$1.obj" "$work/user32.lib" "$work/kernel32.lib"
    local size sha
    size="$(stat -c %s "$work/$1.exe")"
    sha="$(sha256sum < "$work/$1.exe" | cut -d' ' -f1)"
    if [[ "$size" != "$2" || "$sha" != "$3" ]]; then
        echo "$1.exe is $size bytes, SHA-256 $sha; pinned: $2 bytes, $3" >&2
        exit 1
    fi
    cp "$work/$1.exe" "/cache/parts/helpers/$1.exe"
    echo "$1.exe: $size bytes, SHA-256 $sha (the pinned file)"
}
helper keep-on-screen "$KEEP_ON_SCREEN_SIZE" "$KEEP_ON_SCREEN_SHA256"
helper close-launcher "$CLOSE_LAUNCHER_SIZE" "$CLOSE_LAUNCHER_SHA256"
