#!/usr/bin/env bash
# Builds the APK with Gradle in the pinned Android build image (../containers/Containerfile.android);
# Gradle never runs on the host. The runtime asset must be packed first (runtime.sh).
#
#   apk.sh          debug APK, with the JVM tests where the repository has them:
#                   out/pd2-launcher-lite.apk
#   apk.sh release  release APK signed with the key the environment names (PD2_LITE_KEYSTORE,
#                   PD2_LITE_KEYSTORE_PASSWORD, PD2_LITE_KEY_ALIAS, PD2_LITE_KEY_PASSWORD), checked
#                   with apksigner and zipalign: out/pd2-launcher-lite-release.apk
#   apk.sh verification-metadata
#                   writes app/gradle/verification-metadata.xml again: the SHA-256 of everything
#                   Gradle downloads for the debug and release builds and the tests, which Gradle
#                   checks in every build (the release's signing check is skipped: it only resolves)
#
# Gradle's caches live in the build cache (gradle/, m2/, android/). PD2_LITE_DEBUG_KEYSTORE may
# name the key a debug build is signed with (else Android's tools make one in the cache).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../config.sh
. "$here/../config.sh"

target="${1:-debug}"
app="$LITE_DIR/app"
if [[ "$target" != verification-metadata && ! -f "$app/app/src/main/assets/runtime.tar.zst" ]]; then
    echo "apk: no runtime asset: run runtime.sh first" >&2
    exit 1
fi
case "$target" in
    debug)
        tasks=":app:assembleDebug"
        [[ -d "$app/app/src/test" ]] && tasks+=" :app:testDebugUnitTest"
        after=""
        ;;
    release)
        tasks=":app:assembleRelease"
        apk=app/build/outputs/apk/release/app-release.apk
        # The image's own aapt2 and apksigner also describe the APK for the release notes (its
        # package line, and its signer's certificate), so nothing depends on another SDK's tools.
        after=" && apksigner verify --verbose --print-certs $apk && zipalign -c 4 $apk"
        after+=" && aapt2 dump badging $apk > $apk.badging && apksigner verify --print-certs $apk > $apk.certs"
        ;;
    verification-metadata)
        tasks="--write-verification-metadata sha256 help :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease -x :app:preReleaseBuild"
        after=""
        ;;
    *) echo "usage: apk.sh [debug|release|verification-metadata]" >&2; exit 2 ;;
esac

engine="$(container_engine)"
image="$(lite_image android)"
mkdir -p "$LITE_CACHE/gradle" "$LITE_CACHE/m2" "$LITE_CACHE/android" "$LITE_CACHE/home"
if [[ "$target" == debug && -n "${PD2_LITE_DEBUG_KEYSTORE:-}" ]]; then
    cp "$PD2_LITE_DEBUG_KEYSTORE" "$LITE_CACHE/android/debug.keystore"
fi

# Only Gradle's and Android's caches: the parts and downloads are not Gradle's to change. The
# verification metadata is written from empty Gradle caches, as a build on a new machine starts:
# Gradle records only what it downloads, and a filled cache would leave out the parent POMs and
# BOMs that it reads from there.
run_args=(--rm --security-opt label=disable -v "$app:/work")
fresh=()
trap 'rm -rf "${fresh[@]}"' EXIT
for d in gradle m2 android home; do
    src="$LITE_CACHE/$d"
    if [[ "$target" == verification-metadata && ( "$d" == gradle || "$d" == m2 ) ]]; then
        src="$(mktemp -d "$LITE_CACHE/$d-fresh.XXXXXX")"
        fresh+=("$src")
    fi
    run_args+=(-v "$src:/cache/$d")
done
if [[ "$target" == verification-metadata ]]; then
    rm -f "$app/gradle/verification-metadata.xml"
fi
if [[ "$target" == release ]]; then
    for v in PD2_LITE_KEYSTORE PD2_LITE_KEYSTORE_PASSWORD PD2_LITE_KEY_ALIAS PD2_LITE_KEY_PASSWORD; do
        [[ -n "${!v:-}" ]] || { echo "refusing: a release APK needs $v (docs/BUILDING.md)" >&2; exit 1; }
    done
    [[ -f "$PD2_LITE_KEYSTORE" ]] || { echo "refusing: no keystore at PD2_LITE_KEYSTORE" >&2; exit 1; }
    keystore="$(realpath "$PD2_LITE_KEYSTORE")"
    # Only the keystore file, read-only; the passwords pass by name only (-e NAME), so they are
    # not on the engine's command line.
    run_args+=(-v "$keystore:/signing/release.jks:ro" -e PD2_LITE_KEYSTORE=/signing/release.jks
        -e PD2_LITE_KEYSTORE_PASSWORD -e PD2_LITE_KEY_ALIAS -e PD2_LITE_KEY_PASSWORD)
fi
# Gradle runs as the calling user: Podman's keep-id; rootless Docker's root is that user.
[[ "$engine" == podman ]] && run_args+=(--userns=keep-id)

# AGP reads ANDROID_USER_HOME as its ".android" folder, so the debug key is the cache's
# android/debug.keystore.
"$engine" run "${run_args[@]}" \
    -e HOME=/cache/home -e ANDROID_USER_HOME=/cache/android -e GRADLE_USER_HOME=/cache/gradle \
    -e MAVEN_USER_HOME=/cache/m2 "$image" \
    sh -c "./gradlew --no-daemon --console=plain clean $tasks$after"

if [[ "$target" == verification-metadata ]]; then
    echo "written: $app/gradle/verification-metadata.xml"
    exit 0
fi

mkdir -p "$LITE_OUT"
if [[ "$target" == release ]]; then
    out="$LITE_OUT/pd2-launcher-lite-release.apk"
    cp "$app/app/build/outputs/apk/release/app-release.apk" "$out"
    cp "$app/app/build/outputs/apk/release/app-release.apk.badging" "$out.badging"
    cp "$app/app/build/outputs/apk/release/app-release.apk.certs" "$out.certs"
else
    out="$LITE_OUT/pd2-launcher-lite.apk"
    cp "$app/app/build/outputs/apk/debug/app-debug.apk" "$out"
fi
echo "APK: $out ($(du -h "$out" | cut -f1))"
