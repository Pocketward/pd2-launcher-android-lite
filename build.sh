#!/usr/bin/env bash
# Builds PD2 Launcher Lite from pinned sources, every step in a container (docs/BUILDING.md).
#
#   ./build.sh          debug APK: out/pd2-launcher-lite.apk
#   ./build.sh release  release APK signed with the key the environment names:
#                       out/pd2-launcher-lite-release.apk
#
# The parts, in order (each is used again from the cache while its inputs stay the same):
#   build/native.sh    the native ARM64 userland (userland/: Debian packages, our glibc, Xvfb,
#                      x-input-relay) with Mesa (build/mesa.sh: Zink, Turnip) and PanVK
#                      (build/panvk.sh: Mali, with our kbase backend)
#   build/wine.sh      Wine for ARM64 in WoW64 mode
#   build/wowbox64.sh  Box64's wowbox64.dll, Wine's x86 CPU DLL
#   build/bridge.sh    the XInput bridge, the game's xinput1_4.dll
#   build/helpers.sh   keep-on-screen.exe, which keeps the official launcher's window on the X screen
#   build/prefix.sh    the Wine prefix template, made by the ARM64 Wine under qemu-user
#   build/runtime.sh   the runtime asset: the reduced Wine, the .NET runtime, packing
#   build/apk.sh       the APK (Gradle)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
. "$here/config.sh"

target="${1:-debug}"
case "$target" in
    debug | release) ;;
    *) echo "usage: build.sh [release]" >&2; exit 2 ;;
esac
if in_container; then
    echo "refusing: build.sh runs on the host and starts the containers itself" >&2
    exit 1
fi
echo "cache: $LITE_CACHE"
# One build at a time per cache: two would share its downloads, work trees and parts.
mkdir -p "$LITE_CACHE"
exec 9>"$LITE_CACHE/.lock"
flock -n 9 || { echo "refusing: another build uses $LITE_CACHE" >&2; exit 1; }
step() { echo "=== $1"; "$here/build/$1" "${@:2}"; }
step native.sh
step wine.sh
step wowbox64.sh
step bridge.sh
step helpers.sh
step prefix.sh
step runtime.sh
step apk.sh "$target"
