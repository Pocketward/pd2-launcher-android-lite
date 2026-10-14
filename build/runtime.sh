#!/usr/bin/env bash
# The app's runtime asset (app/app/src/main/assets/runtime.tar.zst and runtime.version) from the
# parts: the native tree, the reduced Wine (trim.py with wine-keep.txt, then stage-wineb.py with
# wowbox64.dll), the prefix template and the .NET runtime (dotnet.sh), packed by pack.py.
# With app/app/src/test present, also app/app/test-runtime.tar (the archive uncompressed) for the
# JVM test of the app's archive reader.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

case "${1:-}" in
    --wine) # in the Wine image
        python3 /src/build/trim.py /cache/parts/wine/full /src/build/wine-keep.txt /cache/work/runtime/wine-min
        python3 /src/build/stage-wineb.py /cache/work/runtime/wine-min /cache/parts/wowbox64/wowbox64.dll \
            /cache/work/runtime/wine "$USERLAND/lib/ld-linux-aarch64.so.1"
        rm -rf /cache/work/runtime/wine-min
        exit 0
        ;;
    --pack) # in the tools image
        rm -rf /cache/work/runtime/assets
        python3 /src/build/pack.py /cache/parts/native/tree /cache/work/runtime/wine /cache/parts/prefix/prefix \
            /cache/work/runtime/assets --dotnet /cache/work/runtime/dotnet
        if [[ "${2:-}" == --test-tar ]]; then
            zstd -d -q -f /cache/work/runtime/assets/runtime.tar.zst -o /cache/work/runtime/test-runtime.tar
        fi
        exit 0
        ;;
esac

for part in native wine wowbox64 prefix; do
    [[ -f "$LITE_PARTS/$part/BUILDINFO" ]] || { echo "runtime: build the part $part first" >&2; exit 1; }
done
rm -rf "$LITE_WORK/runtime"
mkdir -p "$LITE_WORK/runtime"
run_in "$(lite_image wine)" build/runtime.sh --wine
"$here/dotnet.sh"
app="$LITE_DIR/app/app"
test_tar=()
[[ -d "$app/src/test" ]] && test_tar=(--test-tar)
run_in "$(lite_image tools)" build/runtime.sh --pack "${test_tar[@]}"
mkdir -p "$app/src/main/assets"
cp "$LITE_WORK/runtime/assets/runtime.tar.zst" "$LITE_WORK/runtime/assets/runtime.version" "$app/src/main/assets/"
if [[ ${#test_tar[@]} -gt 0 ]]; then
    mv "$LITE_WORK/runtime/test-runtime.tar" "$app/test-runtime.tar"
fi
rm -rf "$LITE_WORK/runtime"
echo "runtime: version $(cat "$app/src/main/assets/runtime.version")"
