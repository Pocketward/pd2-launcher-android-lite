#!/usr/bin/env bash
# The native ARM64 tree of the runtime: the userland's staging tree (../userland/build.sh), Mesa
# (mesa.sh) and PanVK (panvk.sh), of which stage-native.py takes what Lite runs and makes it
# runnable at the prefix. Output: parts/native/tree (with relocate.list).
#
# Usage: native.sh [--identity]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

if [[ "${1:-}" == --in-tools ]]; then
    python3 /src/build/stage-native.py /cache/work/staging /cache/parts/mesa/tree /cache/parts/panvk/tree \
        /cache/parts/native/tree
    exit 0
fi

identity() {
    part_identity "$here/native.sh" "$here/stage-native.py" \
        "userland $("$LITE_DIR/userland/build.sh" --identity | sha256sum | cut -d' ' -f1)" \
        "mesa $("$here/mesa.sh" --identity | sha256sum | cut -d' ' -f1)" \
        "panvk $("$here/panvk.sh" --identity | sha256sum | cut -d' ' -f1)"
}
if [[ "${1:-}" == --identity ]]; then
    identity
    exit 0
fi

id="$(identity)"
if part_ready native "$id"; then
    echo "native: reusing parts/native"
    exit 0
fi
"$LITE_DIR/userland/build.sh"
"$here/mesa.sh"
"$here/panvk.sh"
rm -rf "$LITE_PARTS/native"
mkdir -p "$LITE_PARTS/native"
run_in "$(lite_image tools)" build/native.sh --in-tools
part_done native "$id"
