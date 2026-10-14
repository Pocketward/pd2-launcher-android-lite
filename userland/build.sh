#!/usr/bin/env bash
# Builds the native userland's staging tree (the cache's work/staging), every step in a container:
#   fetch-debian.sh   pinned Debian 13 arm64 packages -> staging tree and cross-build sysroot
#   glibc.sh          our glibc (Debian's source package + ../patches/glibc) replaces Debian's libc6
#   xvfb.sh           Xvfb from xorg-server 21.1.25 (+ ../patches/xserver) for the prefix
#   relay.sh          x-input-relay, touch and keyboard into the X screen (x-input-relay.c)
# ../build/native.sh then takes from it what Lite runs, with Mesa (../build/mesa.sh).
# Pass --resolve to resolve packages.txt into packages.lock again first.
# --identity prints the inputs the staging tree depends on.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

if [[ "${1:-}" == --identity ]]; then
    part_identity "debian=$DEBIAN_IMAGE $DEBIAN_SNAPSHOT" "prefix=$USERLAND" \
        "$here/build.sh" "$here/fetch-debian.sh" "$here/packages.lock" "$here/relay.sh" "$here/x-input-relay.c" \
        "$LITE_DIR/containers/Containerfile.tools" \
        "glibc $("$here/glibc.sh" --identity | sha256sum | cut -d' ' -f1)" \
        "xvfb $("$here/xvfb.sh" --identity | sha256sum | cut -d' ' -f1)"
    exit 0
fi
if in_container; then
    echo "refusing: build.sh runs on the host and starts the containers itself" >&2
    exit 1
fi

echo "native userland for the prefix $USERLAND"
step() { echo "== $1"; "$here/$1" "${@:2}"; }
step fetch-debian.sh "$@"
step glibc.sh
step xvfb.sh
step relay.sh
