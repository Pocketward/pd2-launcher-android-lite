# Pins, paths and helpers shared by every PD2 Launcher Lite build script. Sourced, never executed.
# shellcheck shell=bash
# shellcheck disable=SC2034 # the pins are read by the scripts that source this file

# An error in a command substitution stops the script too (set -e alone does not reach there).
shopt -s inherit_errexit

LITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The build cache, outside the repository: dl/ the pinned downloads, work/ sources and build
# trees, parts/ the finished parts (each with the BUILDINFO of its inputs; a part whose inputs did
# not change is used again).
LITE_CACHE="${PD2_LITE_CACHE:-$HOME/.cache/pd2-lite}"
LITE_DL="$LITE_CACHE/dl"
LITE_WORK="$LITE_CACHE/work"
LITE_PARTS="$LITE_CACHE/parts"
# The APK and the files next to it.
LITE_OUT="$LITE_DIR/out"

# The app's data folder as every native program is built for it: a placeholder of 200 bytes,
# longer than any data folder Android gives an app. The app replaces it with its real data folder
# as it unpacks the runtime (the files that hold it are listed in the archive's relocate.list).
DATA_PLACEHOLDER="/pd2launcher-data-placeholder-$(printf '%0170d' 0)"
# The prefix of the native userland: its place in the data folder.
USERLAND=$DATA_PLACEHOLDER/files/userland/current

# debian:trixie-slim, multi-arch index digest, and the snapshot.debian.org timestamp of every
# Debian package (trixie, trixie-updates, trixie-security; Debian 13.7).
DEBIAN_IMAGE=docker.io/library/debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
DEBIAN_SNAPSHOT=20261006T000000Z

# Debian 13's glibc source package, which userland/glibc.sh builds with Debian's patch series and
# ours (patches/glibc). SHA-256 sums recorded when it was first fetched from the snapshot above.
GLIBC_DEB_VERSION=2.41-12+deb13u4
GLIBC_DSC=glibc_2.41-12+deb13u4.dsc
GLIBC_DSC_SHA256=0915324aa646bb99abfaa9aa6ecb734b30babd28454a3e51f3152912370fbd3e
GLIBC_ORIG=glibc_2.41.orig.tar.xz
GLIBC_ORIG_SHA256=f24aa441021121a79266f0d75242706cab8843a47901fefe74527491807f1998
GLIBC_DEBIAN=glibc_2.41-12+deb13u4.debian.tar.xz
GLIBC_DEBIAN_SHA256=dda4153511bfd543502d18e5bc9323110996fe9c531f14ae3fad6eb17f027c7d

# Mesa: Zink and Turnip (KGSL). SHA-256 as docs.mesa3d.org/relnotes/26.2.3.html publishes it.
MESA_VERSION=26.2.3
MESA_SHA256=1628058a8d2c0615975de5a15ab7bbb9638c50000b5bed9456ff423ea034a81f
MESA_URL="https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"

# Mesa for PanVK on Mali (build/panvk.sh), separate from the release above: main after the v11
# models (MR !41803), until the 26.3 branch exists. SHA-256 of GitLab's archive of that commit,
# recorded on first download.
PANVK_MESA_COMMIT=5bd05bf437123e11ae194df89494a3ab9da1e2ac
PANVK_MESA_SHA256=607946081ba8d9b29b4d02f1fb1e6fae1193ca787f18a840747243f9b3e49755
PANVK_MESA_URL="https://gitlab.freedesktop.org/mesa/mesa/-/archive/$PANVK_MESA_COMMIT/mesa-$PANVK_MESA_COMMIT.tar.gz"

# Arm's kbase uAPI headers (CSF 1.38) for our PanVK backend (mesa-kbase/): fetched unchanged by
# build/panvk.sh from Sky1-Linux/cix-gpu-kmd at this commit (MALI_RELEASE_NAME r54p1-11eac0; its
# CSF headers are byte-identical to the r54p2 driver's) into the Mesa tree's include/kbase-uapi/.
# Each file's SHA-256 was recorded on first download.
KBASE_UAPI_COMMIT=83fd82b869315c1880d34607d14988c2feb7789e
KBASE_UAPI_URL="https://raw.githubusercontent.com/Sky1-Linux/cix-gpu-kmd/$KBASE_UAPI_COMMIT/include/uapi/gpu/arm/midgard"
KBASE_UAPI_FILES=(
    "0627eac3f45fe91791dd6b6f5f729c64639155b991109d00f397038643f231ea csf/mali_base_csf_kernel.h"
    "a724154c6a15a4d207d36d74e5913446d8005ddfb97905a0a907a2aea2646096 csf/mali_kbase_csf_ioctl.h"
    "3423e718a36ec2fc106a5252fa5ba9f588ce6d1b017d27ed720b3648c9234354 csf/mali_kbase_csf_mem_flags.h"
    "be46ef9ac1141a6bd9ec8f564ee20991441abb625c152547cabf16fef666234e gpu/mali_kbase_gpu_coherency.h"
    "926b2676fae62d7d83e16e142c6597a4bfef6d8f77a3a8ce1d9e2a322cc0d916 gpu/mali_kbase_gpu_id.h"
    "b0ffea96fd3607a89c3654e0be2a5eb1b12a7e35c3bf4c9ccc11b5d5f4dbfe59 mali_base_common_kernel.h"
    "8fed617df47772527c71f9b2cb32913211b37e3e8a534be7ff2a3cea8c52c9ac mali_base_kernel.h"
    "b90f768e44f4f8a2943ac860d17806fb01a76b857b20b8186d5d832c37f2d080 mali_base_mem_priv.h"
    "325ee5ed88d4dbf6268b211b71c441a3f9b0f651e5175ffd9925164d418621c3 mali_gpu_props.h"
    "e53ede4fc9760d2fd7af9fbfadf60d9ed390169d7f58f3a6339d96d3325e9839 mali_kbase_ioctl.h"
    "99d7d2d123c7dda45d4d7cf4c8b85336822353a3638738ecc3282334d6ae3d63 mali_kbase_mem_flags.h"
)

# X server for Xvfb: a 21.1 release with X.Org's security fixes. SHA-256 as X.Org's release
# announcement publishes it.
XSERVER_VERSION=21.1.25
XSERVER_SHA256=6ad4e3c7b59a309b32c92e5c15ce1267110f9e13f1ac78da62361a628da1a0eb
XSERVER_URL="https://www.x.org/releases/individual/xserver/xorg-server-$XSERVER_VERSION.tar.xz"
# Android's own shell, which Xvfb uses to run xkbcomp (patches/xserver/0002).
ANDROID_SHELL=/system/bin/sh
# The folder the app makes for compiled keymaps (Xvfb's compile-time XKM output folder).
XKM_OUTPUT_DIR=$DATA_PLACEHOLDER/files/tmp/xkb

# Wine stable release; SHA-256 of dl.winehq.org's tarball (signed with wine-11.0.tar.xz.sign).
WINE_VERSION=11.0
WINE_SHA256=c07a6857933c1fc60dff5448d79f39c92481c1e9db5aa628db9d0358446e0701
WINE_URL="https://dl.winehq.org/wine/source/11.0/wine-$WINE_VERSION.tar.xz"
# The install prefix Wine is built for. Wine finds its own files relative to its loader, so the
# tree can move; this name only shows in logs.
WINE_PREFIX_DIR=/opt/wine

# llvm-mingw (github.com/mstorsjo/llvm-mingw) for Wine's PE side and wowbox64.dll. SHA-256
# recorded on first download.
LLVM_MINGW_VERSION=20261006
LLVM_MINGW_NAME=llvm-mingw-$LLVM_MINGW_VERSION-ucrt-ubuntu-22.04-x86_64
LLVM_MINGW_SHA256=5f9c6ed95b2d4bdb2869a488c5fd5857fbdabcf288a0aa3eb1da43f6a08d8ab4
LLVM_MINGW_URL="https://github.com/mstorsjo/llvm-mingw/releases/download/$LLVM_MINGW_VERSION/$LLVM_MINGW_NAME.tar.xz"

# Box64 for wowbox64.dll, the x86 CPU DLL of the ARM64 Wine: a commit after 0.4.4 that gives the
# x86 program the real x86 state when a fault happens in generated code (.NET turns its null
# checks into NullReferenceException only then). SHA-256 of GitHub's archive of the commit,
# recorded on first download.
BOX64_COMMIT=a489cfe7d03f6d57aa38580c157711fbfc69ed7a
BOX64_SHA256=234dedad2dd4ff396bedf93a67510e5ce772298ae108b09f47e805e94dffd0a7
BOX64_URL="https://github.com/ptitSeb/box64/archive/$BOX64_COMMIT.tar.gz"
# The build date wowbox64.dll shows (SOURCE_DATE_EPOCH): 2026-10-12, the day of the commit.
BOX64_DATE_EPOCH=1791763200

# .NET 6 x86 (the runtime and the Windows Desktop runtime) for the official PD2 launcher, a .NET 6
# WPF program: Microsoft's zip archives with the SHA-512 of its release metadata
# (dotnet/release-metadata/6.0/releases.json, 6.0.36).
DOTNET_VERSION=6.0.36
DOTNET_RUNTIME_URL="https://builds.dotnet.microsoft.com/dotnet/Runtime/$DOTNET_VERSION/dotnet-runtime-$DOTNET_VERSION-win-x86.zip"
DOTNET_RUNTIME_SHA512=1d7ecca39d4d14c27b067358c433ebf90dde716e5c4bdc244861dc7268b23f172d61bf910a4e16b46e4d629f23774b8311ba8d5a45085d995da4ef25b74c75ea
DOTNET_DESKTOP_URL="https://builds.dotnet.microsoft.com/dotnet/WindowsDesktop/$DOTNET_VERSION/windowsdesktop-runtime-$DOTNET_VERSION-win-x86.zip"
DOTNET_DESKTOP_SHA512=c137de6e19760936986fee027696c824e904b4094d5fc882a22593819c8d08715597d89004dd97042b8e89e4e49eb1052c5d79cb0002f0f5fd057f198686d0c9

# The XInput bridge (bridge/xinput-bridge.c), the game's xinput1_4.dll: the build must give
# exactly this file (the pinned toolchain of containers/Containerfile.bridge reproduces it).
XINPUT_BRIDGE_SIZE=4096
XINPUT_BRIDGE_SHA256=b78963150d55de280266418095e79ebeb4d754571f49076c77f8924b34ee70c7

# keep-on-screen.exe (helpers/keep-on-screen.c), in the prefix template: the build must give
# exactly this file (the same pinned toolchain reproduces it).
KEEP_ON_SCREEN_SIZE=2560
KEEP_ON_SCREEN_SHA256=ba0aad3375a07558ccecd871224a1c13ccfc69cb40c5f3b9784f4c22a3b66821

# close-launcher.exe (helpers/close-launcher.c), in the prefix template: the same rule.
CLOSE_LAUNCHER_SIZE=4096
CLOSE_LAUNCHER_SHA256=d24667d50bb1dd876d6e1c38136f701560cbc76d61592ddbe5ebb86828a66408

# --- helpers --------------------------------------------------------------------------------

# The container engine: Podman, or Docker in rootless mode. In both, the container's root is the
# calling user, so the files a build writes belong to that user; rootful Docker would leave files
# of root that the build cannot remove, so it is refused.
container_engine() {
    if command -v podman >/dev/null 2>&1; then
        echo podman
    elif command -v docker >/dev/null 2>&1; then
        if ! docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q rootless; then
            echo "refusing: Docker runs as root here; use Podman or rootless Docker" >&2
            return 1
        fi
        echo docker
    else
        echo "refusing: no container engine (Podman or rootless Docker) found" >&2
        return 1
    fi
}

# Runs a container with the engine. SELinux labels are off for the bind mounts (an enforcing host
# would refuse the build its own folders otherwise).
engine_run() { # engine-run-arguments...
    "$(container_engine)" run --rm --security-opt label=disable "$@"
}

# Image archives: with PD2_IMAGE_ARCHIVE set to a folder (the CI keeps it between runs), an image
# that is missing is loaded from its archive there, and one built here is archived there.
image_archive() { # image -> its archive's path
    echo "$PD2_IMAGE_ARCHIVE/$(tr '/:' '__' <<<"$1").tar.zst"
}

# True when the engine has the image, loading it from its archive if there is one.
have_image() { # engine image
    "$1" image inspect "$2" >/dev/null 2>&1 && return 0
    [[ -n "${PD2_IMAGE_ARCHIVE:-}" && -f "$(image_archive "$2")" ]] || return 1
    echo "loading $2 from $(image_archive "$2")" >&2
    zstd -dc "$(image_archive "$2")" | "$1" load >&2 && "$1" image inspect "$2" >/dev/null 2>&1
}

# Archives an image just built, when PD2_IMAGE_ARCHIVE is set. A failure leaves no archive.
archive_image() { # engine image
    local file
    [[ -n "${PD2_IMAGE_ARCHIVE:-}" ]] || return 0
    file="$(image_archive "$2")"
    if mkdir -p "$PD2_IMAGE_ARCHIVE" && "$1" save "$2" | zstd -T0 -q -f -o "$file.part" &&
        mv "$file.part" "$file"; then
        echo "archived $2 in $file" >&2
    else
        rm -f "$file.part"
        echo "warning: could not archive $2" >&2
    fi
}

# Builds (once) and prints the image of containers/Containerfile.<name>. Its tag hashes the
# Containerfile and the Debian pins it is built with.
lite_image() { # name
    local engine image file="$LITE_DIR/containers/Containerfile.$1"
    engine="$(container_engine)"
    image="localhost/pd2-lite-$1:$( (cat "$file"; echo "$DEBIAN_IMAGE $DEBIAN_SNAPSHOT") | sha256sum | cut -c1-16)"
    if ! have_image "$engine" "$image"; then
        "$engine" build --platform linux/amd64 \
            --build-arg "DEBIAN_IMAGE=$DEBIAN_IMAGE" --build-arg "DEBIAN_SNAPSHOT=$DEBIAN_SNAPSHOT" \
            -t "$image" -f "$file" "$LITE_DIR/containers" >&2 || return 1
        archive_image "$engine" "$image"
    fi
    echo "$image"
}

# Runs a script of this repository again inside an image, with the repository read-only at /src
# and the cache at /cache (PD2_LITE_CACHE there). Extra engine arguments go in RUN_EXTRA (array).
run_in() { # image script [args...]
    local image="$1" script="$2"; shift 2
    [[ -n "$image" ]] || { echo "no image for $script" >&2; return 1; }
    # work/ is a scratch area that a new cache (or one restored without it) does not have.
    mkdir -p "$LITE_CACHE" "$LITE_WORK"
    engine_run --platform linux/amd64 -v "$LITE_DIR:/src:ro" -v "$LITE_CACHE:/cache" \
        -e PD2_LITE_IN_CONTAINER=1 -e PD2_LITE_CACHE=/cache -e "PD2_IMAGE=$image" \
        "${RUN_EXTRA[@]}" "$image" bash "/src/$script" "$@"
}
RUN_EXTRA=()

# True inside a container that run_in started.
in_container() { [[ "${PD2_LITE_IN_CONTAINER:-}" == 1 ]]; }

# Downloads a pinned file into dl/ and checks it (SHA-256, or SHA-512 for a 128-digit sum); a file
# there that does not match (an older pin of the same name) is downloaded again. A bad download is
# removed, never kept under the file's name. Prints the local path.
fetch() { # url sum [file name]
    local url="$1" sum="$2" file tool=sha256sum
    file="$LITE_DL/${3:-$(basename "$url")}"
    [[ ${#sum} == 128 ]] && tool=sha512sum
    mkdir -p "$LITE_DL"
    if [[ -f "$file" ]] && echo "$sum  $file" | "$tool" -c --quiet - >/dev/null 2>&1; then
        echo "$file"
        return 0
    fi
    rm -f "$file"
    curl -fsSL --retry 3 -o "$file.part" "$url"
    echo "$sum  $file.part" | "$tool" -c --quiet - >&2 || { rm -f "$file.part"; echo "checksum mismatch: $url" >&2; return 1; }
    mv "$file.part" "$file"
    echo "$file"
}

# Inside a container: unpacks the pinned llvm-mingw (fetched into dl/) into work/ once, recorded
# with its SHA-256, and prints its folder.
llvm_mingw() {
    local dir="/cache/work/$LLVM_MINGW_NAME"
    if [[ "$(cat "$dir/.unpacked" 2>/dev/null)" != "$LLVM_MINGW_SHA256" ]]; then
        rm -rf "$dir"
        tar -xJf "/cache/dl/$LLVM_MINGW_NAME.tar.xz" -C /cache/work
        echo "$LLVM_MINGW_SHA256" > "$dir/.unpacked"
    fi
    echo "$dir"
}

# The identity of a part: one line per input, a file of the repository as its path below the
# repository and its SHA-256 (a missing one is an error), anything else (a pin, another part's
# identity) as given.
part_identity() { # input...
    local a
    for a in "$@"; do
        if [[ "$a" == "$LITE_DIR"/* ]]; then
            [[ -f "$a" ]] || { echo "missing input: $a" >&2; return 1; }
            echo "${a#"$LITE_DIR/"} $(sha256sum < "$a" | cut -d' ' -f1)"
        else
            echo "$a"
        fi
    done
}

# True when parts/<name> was built from inputs with this identity.
part_ready() { # name identity
    [[ -f "$LITE_PARTS/$1/BUILDINFO" && "$(cat "$LITE_PARTS/$1/BUILDINFO")" == "$2" ]]
}

# The SHA-256 of a finished part's BUILDINFO: what a later part records of it.
part_hash() { # name
    sha256sum < "$LITE_PARTS/$1/BUILDINFO" | cut -d' ' -f1
}

# Records a part as finished: written last, so a part that stopped half way is built again.
part_done() { # name identity
    printf '%s\n' "$2" > "$LITE_PARTS/$1/BUILDINFO"
}
