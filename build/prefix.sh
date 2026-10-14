#!/usr/bin/env bash
# The Wine prefix template of the runtime, made by the ARM64 Wine itself: `wineboot -i` and
# `reg import` run in an arm64 container (qemu-user through binfmt_misc) with the native tree
# mounted at its placeholder prefix and the full Wine (with wowbox64.dll as the x86 CPU DLL, so
# the 32-bit side is set up too). Then:
#   - G: is the game folder (../../game from the prefix), and the game's registry values point to
#     G:\Diablo II; Game.exe gets Windows 7 (Wine 11's default makes Fog.dll's critical-section
#     check fail); WPF draws in software (its hardware path through wined3d costs more CPU)
#   - the prefix is never updated at a start (.update-timestamp "disable")
#   - every file that is a copy of a Wine file becomes a relative link into the runtime's Wine
#     (prefix-dedupe.py); links to modules the reduced Wine leaves out dangle, as if the file
#     were missing
#   - the game's xinput1_4.dll is the XInput bridge (bridge.sh)
#   - C:\windows\keep-on-screen.exe keeps the official launcher's window on the X screen, and
#     C:\windows\close-launcher.exe asks the launcher to close once its game shows (helpers.sh)
#   - C:\Program Files (x86)\dotnet links to the .NET runtime beside the prefix (files/dotnet)
# Output: parts/prefix/prefix.
#
# Usage: prefix.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

work=$LITE_WORK/prefix
identity() {
    part_identity "$here/prefix.sh" "$here/prefix-dedupe.py" "$here/stage-wineb.py" "debian=$DEBIAN_IMAGE" \
        "wine $(part_hash wine)" "wowbox64 $(part_hash wowbox64)" "native $(part_hash native)" \
        "bridge $(part_hash bridge)" "helpers $(part_hash helpers)"
}

case "${1:-}" in
    --identity)
        identity
        exit 0
        ;;
    --stage-wine) # in the Wine image: the full Wine for the prefix build
        python3 /src/build/stage-wineb.py /cache/parts/wine/full /cache/parts/wowbox64/wowbox64.dll \
            /cache/work/prefix/wine "$USERLAND/lib/ld-linux-aarch64.so.1" --xtajit
        exit 0
        ;;
    --wineboot) # in the arm64 container
        export HOME=/files/home WINEPREFIX=/files/prefix WINEDEBUG=-all BOX64_LOG=0 BOX64_NOBANNER=1
        export WINEDLLOVERRIDES="mscoree=;mshtml=;winemenubuilder.exe=;winedbg.exe=d"
        export FONTCONFIG_FILE="$USERLAND/etc/fonts/fonts.conf"
        mkdir -p "$HOME"
        /wine/bin/wine wineboot -i
        /wine/bin/wine reg import 'Z:\files\lite.reg'
        /wine/bin/wineserver -w
        exit 0
        ;;
    --finish) # in the tools image
        p=/cache/work/prefix/files/prefix
        # wineboot ends with 0 even when a part of the setup failed: the 64-bit side, the 32-bit
        # side (through wowbox64.dll) and the imported values must all be there.
        for f in drive_c/windows/system32/kernel32.dll drive_c/windows/system32/xtajit.dll \
            drive_c/windows/syswow64/kernel32.dll drive_c/windows/syswow64/winebrowser.exe; do
            [[ -f "$p/$f" ]] || { echo "prefix: wineboot made no $f" >&2; exit 1; }
        done
        grep -q '^\[Software\\\\Wow6432Node\\\\Blizzard Entertainment\\\\Diablo II\]' "$p/system.reg" &&
            grep -q '^"DisableHWAcceleration"=dword:00000001' "$p/user.reg" ||
            { echo "prefix: the registry values were not imported" >&2; exit 1; }
        rm -rf "$p/.wineserver" "$p/drive_c/windows/sysarm32"
        echo disable > "$p/.update-timestamp"
        ln -sfn ../../game "$p/dosdevices/g:"
        python3 /src/build/prefix-dedupe.py "$p" /cache/work/prefix/wine
        bridge="$p/drive_c/windows/syswow64/xinput1_4.dll"
        rm -f "$bridge"
        cp /cache/parts/bridge/xinput1_4.dll "$bridge"
        cp /cache/parts/helpers/keep-on-screen.exe "$p/drive_c/windows/keep-on-screen.exe"
        cp /cache/parts/helpers/close-launcher.exe "$p/drive_c/windows/close-launcher.exe"
        mkdir -p "$p/drive_c/Program Files (x86)"
        ln -sfn ../../../dotnet "$p/drive_c/Program Files (x86)/dotnet"
        rm -rf /cache/parts/prefix/prefix
        mv "$p" /cache/parts/prefix/prefix
        echo "prefix: $(find /cache/parts/prefix/prefix -type f | wc -l) files, $(find /cache/parts/prefix/prefix -type l | wc -l) links, $(du -sh /cache/parts/prefix/prefix | cut -f1)"
        exit 0
        ;;
esac

for part in wine wowbox64 native bridge helpers; do
    [[ -f "$LITE_PARTS/$part/BUILDINFO" ]] || { echo "prefix: build the part $part first" >&2; exit 1; }
done
id="$(identity)"
if part_ready prefix "$id"; then
    echo "prefix: reusing parts/prefix"
    exit 0
fi
grep -qs '^flags: .*F' /proc/sys/fs/binfmt_misc/qemu-aarch64 ||
    { echo "prefix: needs qemu-user for aarch64 in binfmt_misc with the F flag (for example qemu-user-static)" >&2; exit 1; }
rm -rf "$work" "$LITE_PARTS/prefix"
mkdir -p "$work/files" "$LITE_PARTS/prefix"
run_in "$(lite_image wine)" build/prefix.sh --stage-wine
cat > "$work/files/lite.reg" <<'EOF'
Windows Registry Editor Version 5.00

[HKEY_CURRENT_USER\Software\Blizzard Entertainment\Diablo II]
"InstallPath"="G:\\Diablo II"
"GamePath"="G:\\Diablo II\\Diablo II.exe"

[HKEY_LOCAL_MACHINE\Software\Wow6432Node\Blizzard Entertainment\Diablo II]
"InstallPath"="G:\\Diablo II"
"GamePath"="G:\\Diablo II\\Diablo II.exe"

[HKEY_CURRENT_USER\Software\Wine\AppDefaults\Game.exe]
"Version"="win7"

[HKEY_CURRENT_USER\Software\Microsoft\Avalon.Graphics]
"DisableHWAcceleration"=dword:00000001
EOF
engine_run --platform linux/arm64 -v "$LITE_DIR:/src:ro" -v "$LITE_PARTS/native/tree:$USERLAND:ro" \
    -v "$work/wine:/wine:ro" -v "$work/files:/files" "$DEBIAN_IMAGE" bash /src/build/prefix.sh --wineboot
run_in "$(lite_image tools)" build/prefix.sh --finish
part_done prefix "$id"
rm -rf "$work"
