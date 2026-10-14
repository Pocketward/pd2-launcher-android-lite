# How PD2 Launcher Lite works

PD2 Launcher Lite runs Project Diablo 2, a 32-bit Windows game, on ARM64 Android devices without root. It runs an ARM64 build of Wine in WoW64 mode, so only the game's 32-bit x86 code is emulated, by Box64's `wowbox64.dll`:

```
Android app (Kotlin, Jetpack Compose)       screens, input, the game's picture
 └─ game screen (GameActivity)               owns every process below
     ├─ Xvfb                                 the X screen
     ├─ x-input-relay                        touch and keys into X
     ├─ PulseAudio                           sound
     └─ Wine (ARM64) ─ wowbox64.dll (x86) ─ Diablo II + Project Diablo 2
           └─ ARM64 Mesa: Zink (OpenGL on Vulkan) ─ Turnip (Adreno) or PanVK (Mali)
```

[`BUILDING.md`](BUILDING.md) says how to build each part.

## The app (`app/`)

A Kotlin app with Jetpack Compose screens and one native library, `com.pd2launcher.lite` (a debug build is `com.pd2launcher.lite.dev`, named PD2 Launcher Lite Dev, so it installs beside it).

- **minSdk 33, targetSdk 28:** our glibc answers the system calls Android 13's app seccomp policy blocks. Android lets an app run programs from its own data folder only up to target 28, so the app targets it, below its minimum.
- **Runtime** (`Runtime.kt`): the APK's `runtime.tar.zst` unpacks once into the app's `files` folder: `userland/current` (the native tree), `wine`, `prefix` and `dotnet`. The native programs and Wine are built for a placeholder data folder; the unpack rewrites it to the app's own data folder in the files `relocate.list` names (in place in binaries, padded with `/`). A new runtime replaces the old one; the delete never follows a link.
- **First run** (`Onboarding.kt`, `Setup.kt`, `GameCopy.kt`): Check (the device: an Adreno GPU or a Mali that the bundled PanVK runs, and 4 KB pages; the runtime unpack), Import (the player picks a Diablo II folder with Project Diablo 2 and its official launcher; the app checks it and the free space), Complete (the app copies it into its own storage, reading only the picked folder, and checks the copy), then Play. The app opens on Play when its copy has `Game.exe`, `ProjectD2/ProjectDiablo.dll` and `ProjectD2/PD2Launcher.exe`.
- **Screens** (`ui/`, `MainActivity.kt`): Check, Import, Complete, Play (Start launcher opens the official PD2 launcher, whose Play starts the game), Repair (Check again; Start over keeps the characters, `KeptSaves.kt`), Settings (Game, App, Controller), About, and the on-screen controls editor.
- **Game screen** (`GameActivity.kt`, `ui/GameView.kt`): the X screen at its own shape, as large as the display allows; the menu rail, the Controls panel, the on-screen controls and their editor, and Settings over the running game. `ProgramWatch.kt` follows the session's programs once a second: the rail shows the launcher's rows until `Game.exe` runs. The session ends and Play shows again 5 s after the game ends, or after the launcher closes without playing. The launcher itself only starts the game and stays open behind it (it does not exit, hide or go to a tray; its Options have no choice for that), so Lite ends it (`LauncherExit.kt`): once the game draws, its updater does not run and the launcher wrote nothing to storage for 3 s, `close-launcher.exe` asks it to close, and its process is ended only if it is still there 10 s later (20 s while it still writes). When the game draws with DirectDraw (the launcher's Options; the game runs with `-ddraw`), each touch is moved past cnc-ddraw's black bars, whose mouse does not take them off (`input/DdrawPointer.kt`; the picture's shape, menu or world, is read off the framebuffer, `ScreenPixels.kt`).
- **Game files** (`files/`): `GameFilesProvider.kt` shows the app's copy in Android's Files app and in file pickers. It writes only data files in the game folder, never a program or library, and refuses writes while the game or an import runs.
- **Native library** (`cpp/pd2lite.c`): the process spawner (a new session, only the given descriptors, the userland's own loader), the framebuffer memfd, the frame copy and a full memory barrier for the gamepad file; `cpp/mali_probe.c` reads a Mali's GPU id from Arm's kbase driver (`/dev/mali0`, the CSF handshake our PanVK backend uses) for the device check (`MaliProbe.kt`, `HostGpu.kt`).

## A game session

`Session.kt` starts the processes for the game screen and ends them all when it closes:

- **Xvfb** (display `:7`, a debug build's `:8`), with its framebuffer in a memfd the app passes as descriptor 3 (`-fbfd 3`). The X screen has the display's landscape shape at the lines Settings → Game resolution picks (`GameOptions.kt`): Automatic is the full size on an Adreno and 720 lines on a Mali (every frame is copied on the CPU, which grows with the X screen); the choices are only sizes where the official launcher's 800 x 600 window fits.
- **x-input-relay** reads the app's touch, key and mouse events from a socket and replays them into the X screen through XTEST.
- **PulseAudio** with a Unix socket for Wine and a pipe sink; the app plays the samples with an Android audio track.
- **Wine** starts the official `PD2Launcher.exe` from the prefix's drive `G:`, the app's copy of the game; its Play starts `ProjectD2\Game.exe` in the same session, on the same X screen. Beside it, `keep-on-screen.exe` (`helpers/keep-on-screen.c`, an ARM64 Windows program in the same Wine) moves a top-level window that fits the X screen but lies partly off it to the middle, with `SetWindowPos`: the launcher restores the place it saved, perhaps on a PC or a larger X screen, and Xvfb has no window manager (a move on the X side alone, Wine undoes). It ends when the game starts. `close-launcher.exe` (`helpers/close-launcher.c`, the same kind of program) posts `WM_CLOSE` to the launcher's windows, as Windows does to end a program, but only when a window of `Game.exe` shows; the launcher then closes as from its own close button (it saves its window's place). Before each start the app writes the settings files `d2gl.json` and `ddraw.ini` in its copy (`GameCopy.applySettings`): D2GL full screen at the X screen's size (a copy without the file gets the main app's first-launch configuration), and cnc-ddraw at that size with the picture's shape kept (`maintas`). D2GL and cnc-ddraw draw PD2's menus (800 x 600, 4:3) and its world (1068 x 600, 16:9) as large as fits, with black bars on the other sides; no other PD2 file changes. The official launcher reads `ddraw.ini` at its start and writes the same values at its Play.
- **The session's watch** (`ProgramWatch.kt`, once a second, from the names of the app's processes): the rail shows the launcher's rows until `Game.exe` runs, and the session ends a few seconds after the game ends or the launcher closes. While the launcher runs alone, `LauncherStall.kt` looks for a launcher that stopped responding (no window a minute after its start, its window's thread waiting on a lock for 20 s, a picture that stays the same for 30 s while half a core or more is in use without reads, or a core in use for 45 s without reads or writes); the rail then asks whether to close it.
- **Web links:** Wine's `winebrowser` runs `xdg-open`; the session's `xdg-open` (first on `PATH`, `WebLinks.kt`) hands each http or https link to the app, which opens it in Android's browser.

## Wine and the x86 code

- **Wine 11** is built for ARM64 with an i386 PE side. Its WoW64 layer loads the x86 CPU DLL `xtajit.dll`, which is Box64's `wowbox64.dll`; it translates the game's 32-bit x86 code into ARM64 code. There is no x86-64 code and no Box64 program.
- **The reduced Wine:** the APK carries only the Wine modules the game and the official launcher load (`build/wine-keep.txt`).
- **The prefix template** is made at build time by the ARM64 Wine itself under qemu-user (`build/prefix.sh`). Its copies of Wine's modules are links into the runtime's Wine. The game's `xinput1_4.dll` is our XInput bridge (`bridge/xinput-bridge.c`), which reads the app's gamepad file; `C:\windows\keep-on-screen.exe` and `C:\windows\close-launcher.exe` are the helpers above.
- **.NET 6 x86** (runtime and Windows Desktop runtime) unpacks beside the prefix for the official PD2 launcher, a WPF program, which draws in software.

## Graphics

```
PD2 (32-bit OpenGL, D2GL) → Wine's opengl32 → ARM64 Mesa: Zink (OpenGL on Vulkan) → Turnip over KGSL (Adreno)
                                                                                    → PanVK over kbase (Mali)
```

- Mesa is built without LLVM: Zink, Turnip for KGSL, softpipe for Xvfb's GLX, and glvnd's GL and EGL.
- PanVK (`build/panvk.sh`) comes from its own Mesa pin with our backend for Arm's kbase kernel driver (`mesa-kbase/`, `patches/mesa-panvk/`): Mesa's Panfrost library has none, and phones with a Mali use kbase. The session picks the Vulkan driver by the GPU (`VulkanDriver` in `HostGpu.kt`): Turnip on an Adreno, PanVK on a supported Mali (the Mali-G310, G610, G615, G715, G720 and G725, the CSF Malis v10 to v13 the pin knows).
- Mesa presents through its software X11 path (`MESA_VK_WSI_DEBUG=sw`) into Xvfb's framebuffer; the game screen copies each new picture into an Android surface.
- Turnip runs with `TU_DEBUG=sysmem`; the first Adreno 6xx generation (610 to 619) adds `noconform`.
- PanVK runs with `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1` (PanVK refuses a v11 Mali without it) and `MESA_GL_VERSION_OVERRIDE=3.3`, `MESA_GLSL_VERSION_OVERRIDE=330`: PanVK has no transform feedback, so Zink offers GL 2.1 without them, and D2GL needs a 3.3 core context (it uses neither transform feedback nor geometry shaders). Automatic's graphics profile on a Mali is Performance.

## The native userland (`userland/`)

- A pinned subset of Debian 13 ARM64 (`packages.txt`, `packages.lock`): PulseAudio, X11 and XCB libraries, the Vulkan loader, glvnd, FreeType, fontconfig, GnuTLS.
- **Our glibc:** Debian 13's glibc with our patches, so it never makes the system calls Android's app seccomp policy kills a process for.
- **Xvfb** from xorg-server with the app's compiled-in paths.
- **x-input-relay** (`x-input-relay.c`).

## Patches

- `patches/glibc/0001-rseq-default-off.patch`: no thread registers rseq.
- `patches/glibc/0002-no-set-robust-list.patch`: `set_robust_list` is never called; glibc releases a dead thread's robust mutexes itself.
- `patches/glibc/0003-clone3-to-clone.patch`: threads and `posix_spawn` use `clone`, never `clone3`.
- `patches/glibc/0004-faccessat2-fchmodat2-fallback.patch`: `faccessat` and `fchmodat` use glibc's fallbacks instead of `faccessat2` and `fchmodat2`.
- `patches/glibc/0005-accept-via-accept4.patch`: `accept()` is `accept4(fd, addr, len, 0)`.
- `patches/glibc/0006-sysv-mq-enosys.patch`: SysV IPC and POSIX message queues fail with ENOSYS without a system call.
- `patches/glibc/0007-setxid-no-syscall.patch`: the set*id calls answer in user space.
- `patches/glibc/0008-etc-paths-from-sysconfdir.patch`: the resolver and NSS read their files from the prefix's `etc`.
- `patches/glibc/0009-syscall-blocked-enosys.patch`: `syscall()` returns ENOSYS for a number outside Android's app allowlist.
- `patches/glibc/0010-blocked-wrappers-no-syscall.patch`: the other wrappers of blocked system calls answer without a system call.
- `patches/xserver/0001-os-honour-nolock-for-every-uid.patch`: `-nolock` works for every user, so Xvfb needs no lock file in `/tmp`.
- `patches/xserver/0002-os-build-time-shell-path.patch`: the shell that runs `xkbcomp` is set at build time (`/system/bin/sh`).
- `patches/xserver/0003-vfb-framebuffer-in-an-inherited-descriptor.patch`: `-fbfd <n>` puts the framebuffer in an inherited descriptor (the app's memfd).
- `patches/mesa/0001-wsi-sw-reads-cached-memory.patch`: the software present reads each frame from host-cached memory.
- `patches/mesa/0002-wsi-sw-present-counter.patch`: the software present counts the frames in the file `PD2_PRESENT_COUNTER` names (the fps readout).
- `patches/mesa/0003-wsi-sw-paced-by-the-game-view.patch`: the software present can wait until the app has taken the previous frame.
- `patches/mesa/0004-wsi-sw-direct-present-to-the-app.patch`: the swapchain can present into the app's buffers (only when `PD2_PRESENT_SOCKET` is set).
- `patches/mesa/0005-teximage-subimage-bounds-without-overflow.patch`: the texture sub-image bounds check uses 64-bit sums, so a large offset cannot wrap.
- `patches/mesa-panvk/` (PanVK's Mesa pin, after the four `patches/mesa/*-wsi-*` patches): `0001` registers and builds the kbase backend (`mesa-kbase/`) and adds the kmod hooks it needs; `0002` lets PanVK enumerate `/dev/mali0` and run on that backend; `0004` gives the CRC clear colour a 64-bit mixing hash, so transaction elimination does not skip a clear (`patches/mesa-panvk/README.md`).
- `patches/wine/0001-server-directory-in-the-prefix.patch`: the wineserver's folder is in the prefix, because Android has no `/tmp`.
- `patches/box64/0001-wowbox64-interpreter-fs-base.patch`: wowbox64's interpreter reads FS's base from the emulator, so it finds the 32-bit TEB.
- `patches/box64/0002-wowbox64-suspended-thread-holds-no-lock.patch`: a thread that another thread suspends (.NET's garbage collector does) holds none of Box64's locks, and its context is its live x86 registers (rebuilt from translated code; set again from the context written).
- `patches/box64/0003-lock-cmpxchg8b-unaligned-atomic.patch`: `LOCK CMPXCHG8B` on an address that is not 8-byte aligned is atomic (an exclusive pair on the 16-byte block, or one lock when the 8 bytes cross 16 bytes), in the dynarec and the interpreter. 32-bit .NET changes the 64-bit fields of its 4-byte aligned objects this way.
- `patches/box64/0004-dynarec-validate-block-after-protecting.patch`: a block whose page was written is validated with its x86 code protected (the hash is taken again after the protection), so a write that lands just before is not lost. .NET patches a method's precode once; a block left with the old precode sent threads into the prestub for ever.
- `patches/box64/0005-strongmem-a-jump-not-taken-does-not-end-the-seq.patch`: with `BOX64_DYNAREC_STRONGMEM`, a conditional jump ends a store sequence only on the path where it jumps (with the sequence's end barrier there); the path that goes on keeps it, as straight code would. Code that checks between its stores (.NET's array bounds) no longer has a barrier pair around each store.
- `patches/box64/0006-wowbox64-dynarec-settings-for-named-programs.patch`: `BOX64_DYNAREC_PROGRAMS` names the programs that the environment's `BOX64_DYNAREC_*` settings are for; any other program starts with Box64's defaults for them. The official launcher's settings (`STRONGMEM=1`, `WAIT=0`) are for its own .NET programs, not for the game that it starts and that inherits its environment.

## Releases

`versionName` (`app/app/build.gradle.kts`) is `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-betaN` and the `versionCode` follows from it. A release APK is signed with the project's release key, so Android updates it only with an APK signed by the same key.
