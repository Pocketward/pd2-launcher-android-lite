# Building PD2 Launcher Lite

Everything is built in pinned containers; only the container engine, bash and curl run on your computer. One command builds the APK:

```sh
./build.sh            # debug APK: out/pd2-launcher-lite.apk
./build.sh release    # release APK: out/pd2-launcher-lite-release.apk (signing below)
```

## What you need

- An x86-64 Linux computer with Podman (or Docker in rootless mode: the build refuses Docker that runs as root, whose files the build could not remove), bash, curl, `flock`, `sha256sum` and `sha512sum`, and zstd if you use image archives (`PD2_IMAGE_ARCHIVE`, below).
- A static `qemu-aarch64` binfmt_misc handler with the `F` flag (Debian/Ubuntu: `qemu-user-static`; Arch: `qemu-user-static` and `qemu-user-static-binfmt`). The build runs ARM64 programs under it: the package resolver and the ARM64 Wine that makes the prefix.
- About 25 GB of free disk space for the build images and the cache, and a good connection for the first build.

Every download is checked against a SHA-256 (or SHA-512) in `config.sh`, `userland/packages.lock`, the container definitions or Gradle's `app/gradle/verification-metadata.xml`; a mismatch stops the build. Every Debian package comes from one snapshot.debian.org timestamp, and every container starts from one image digest (`config.sh`). The Android SDK packages that `sdkmanager` installs in the app's build image (platforms, build-tools, CMake) are checked by `sdkmanager` against Google's repository index.

## The parts

`build.sh` builds the parts in this order. Each part goes into the cache's `parts/` folder with a `BUILDINFO` file that lists its inputs (pins, scripts, patches, container definitions, the parts it uses); a later build uses a part again while its inputs are the same.

| Script | What it builds | Output (in the cache) |
| --- | --- | --- |
| `build/native.sh` | The native ARM64 userland: `userland/build.sh` (below), then Mesa (`build/mesa.sh`) and PanVK (`build/panvk.sh`), of which `build/stage-native.py` takes what Lite runs and makes it runnable at the prefix | `parts/native/tree` |
| `build/mesa.sh` | Mesa with Zink, Turnip (KGSL), softpipe and glvnd's GL and EGL, without LLVM; `patches/mesa/` | `parts/mesa/tree` |
| `build/panvk.sh` | Mesa's PanVK (Mali) from its own Mesa pin with our kbase backend (`mesa-kbase/`) and Arm's kbase uAPI headers (pinned in `config.sh`); the WSI patches of `patches/mesa/`, then `patches/mesa-panvk/`. Mesa's host tools for PanVK's internal shaders (`mesa_clc`, `vtn_bindgen2`, `panfrost_compile`) are built natively first, with LLVM 19 (`containers/Containerfile.panvk`) | `parts/panvk/tree` |
| `build/wine.sh` | Wine's build tools, then Wine for ARM64 (aarch64 and i386 PE, llvm-mingw); `patches/wine/` | `parts/wine/full` |
| `build/wowbox64.sh` | Box64's `wowbox64.dll` (llvm-mingw); `patches/box64/` | `parts/wowbox64/` |
| `build/bridge.sh` | The XInput bridge from `bridge/xinput-bridge.c`; the build stops unless the DLL's SHA-256 is the one `config.sh` pins | `parts/bridge/` |
| `build/helpers.sh` | `keep-on-screen.exe` and `close-launcher.exe` from `helpers/*.c`, ARM64 Windows programs with the XInput bridge's toolchain; the build stops unless their SHA-256 are the ones `config.sh` pins | `parts/helpers/` |
| `build/prefix.sh` | The Wine prefix template, made by the ARM64 Wine under qemu-user; it carries the XInput bridge, `keep-on-screen.exe` and `close-launcher.exe` | `parts/prefix/prefix` |
| `build/runtime.sh` | The reduced Wine (`build/trim.py`, `build/wine-keep.txt`), the .NET 6 x86 runtime (`build/dotnet.sh`), packed by `build/pack.py` | `app/app/src/main/assets/runtime.tar.zst` |
| `build/apk.sh` | The APK, with Gradle in the Android build image | `out/` |

`userland/build.sh` makes the staging tree the native part is taken from:

| Script | What it does |
| --- | --- |
| `userland/fetch-debian.sh` | Downloads the Debian 13 ARM64 packages `packages.lock` pins from snapshot.debian.org and unpacks them, without maintainer scripts, into the staging tree and the cross-build sysroot. |
| `userland/glibc.sh` | Builds Debian 13's glibc source package with Debian's patches and `patches/glibc/`, for the prefix, and puts it into the staging tree in place of Debian's. |
| `userland/xvfb.sh` | Builds Xvfb from the pinned xorg-server release with `patches/xserver/` and the prefix's compiled-in paths. |
| `userland/relay.sh` | Builds `x-input-relay` from `userland/x-input-relay.c`. |

The native programs are built for a 200-byte placeholder data folder (`DATA_PLACEHOLDER` in `config.sh`). The app rewrites it to its own data folder as it unpacks the runtime.

## The cache

The cache is `~/.cache/pd2-lite`, or the folder `PD2_LITE_CACHE` names. One build at a time uses it.

- `dl/`: the pinned downloads (sources, Debian packages, llvm-mingw, .NET);
- `work/`: sources and build trees while a part builds, and PanVK's host tools (kept per Mesa pin and build image);
- `parts/`: the finished parts;
- `gradle/`, `m2/`, `android/`, `home/`: Gradle's and Android's caches.

With `PD2_IMAGE_ARCHIVE` set to a folder, a container image that is missing is loaded from its archive there, and an image built here is saved there (`<name>.tar.zst`).

## Release signing

`./build.sh release` signs the APK with the key that these variables name. The key stays outside the repository; `build/apk.sh` mounts its folder read-only and passes the passwords by name only.

| Variable | Value |
| --- | --- |
| `PD2_LITE_KEYSTORE` | the path of the keystore (`.jks`) |
| `PD2_LITE_KEYSTORE_PASSWORD` | the keystore's password |
| `PD2_LITE_KEY_ALIAS` | the key's alias |
| `PD2_LITE_KEY_PASSWORD` | the key's password |

The release build refuses to run without them, and checks the APK with `apksigner` and `zipalign`. A debug build is signed with a debug key that Android's tools make in the cache; `PD2_LITE_DEBUG_KEYSTORE` names another one to use.

## Changing a pin

- **Debian packages:** edit `userland/packages.txt`, then run `userland/fetch-debian.sh --resolve` to write `userland/packages.lock` again (the resolver runs in an ARM64 container).
- **Everything else** (the Debian image and snapshot, glibc, Mesa, PanVK's Mesa and the kbase uAPI headers, the X server, Wine, llvm-mingw, Box64, .NET, the XInput bridge, `keep-on-screen.exe`, `close-launcher.exe`): `config.sh`. A changed pin, script or patch builds the parts that depend on it again.
- **Gradle's plugins and libraries:** after a change, run `build/apk.sh verification-metadata` to write `app/gradle/verification-metadata.xml` again (the SHA-256 of everything Gradle downloads, which Gradle checks in every build).

## Installing a build

```sh
adb install -r out/pd2-launcher-lite.apk
```
