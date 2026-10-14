# PD2 Launcher Lite

> **Unofficial.** PD2 Launcher Lite is a community-made tool. It is not affiliated with or endorsed by the Project Diablo 2 team or Blizzard Entertainment.

An Android app that runs [Project Diablo 2](https://www.projectdiablo2.com/) from a copy of your Diablo II folder. Lite does not install the game: you copy a Diablo II folder with Project Diablo 2 installed (for example from a PC) to your device, and Lite imports it and starts the game with touch and controller controls on your device's GPU. No root, no Linux container app, no PC streaming.

For an app that also downloads and installs Diablo II, Lord of Destruction and Project Diablo 2, see [PD2 Launcher for Android](https://github.com/Pocketward/pd2-launcher-android).

## What your device needs

- An ARM64 device with Android 13 or later, using 4 KB memory pages (almost every phone and handheld does).
- A Qualcomm Adreno GPU (Snapdragon devices) or an Arm Mali-G310, G610, G615, G715, G720 or G725 GPU. Other GPUs are not supported.
- About 400 MB of free storage for the app and its runtime, plus the size of your game folder.

## What you need

- A Diablo II folder with Lord of Destruction 1.14b or 1.14d and Project Diablo 2 installed (its `ProjectD2` folder inside). Copy it to your device first, for example to `Download`.

## Install

Download the APK from the [releases page](https://github.com/Pocketward/pd2-launcher-android-lite/releases) and open it on your device. Android asks once to allow installs from the app you opened it with (your browser or file manager). The app is not on Google Play.

## Using it

1. **Import Diablo II folder:** pick your copied folder. Lite checks that Project Diablo 2 is in it, then makes its own copy; the folder you picked is only read.
2. **Play** starts Project Diablo 2. **Official PD2 launcher** opens Project Diablo 2's own launcher, which updates Project Diablo 2 and manages loot filters. Lite has no updater of its own. Links in the launcher (news, Donate) open in your browser.

In the game:

- **Touch:** tap to click, long press and drag as with a mouse. On-screen controls (a virtual controller) show while no controller is connected; switch them with **Touch pad** in the in-game menu and arrange them with **Edit controls**.
- **Controller:** works as an Xbox controller. Press **Select + Start** (or **L3 + R3**, set in Controls) to switch to mouse mode for menus and typing; hold it to open the in-game menu. Every button can be rebound.
- **In-game menu:** the floating button, Android's Back gesture, or the controller chord opens a menu on the right edge, without pausing the game: Keyboard, Touch pad, Show fps, Controls, Edit controls, Settings and Stop.
- **Settings:** the renderer (Glide, recommended, or DirectDraw), the game resolution (fewer lines run smoother; Automatic picks one for your GPU), the official launcher's resolution, the graphics profile (Default or Performance), Limit to 60 fps, Skip to Battle.net, sound in the background, the controller, keeping the screen on, the fps counter, and your game files.

## Your files

Lite's copy of your game folder (with your characters and loot filter) appears in Android's Files app under **PD2 Launcher Lite**. **Settings → App → Game files** imports files into it and exports files from it. While the game runs, the copy is read-only.

Uninstalling the app removes its copy of the game folder. Copy your characters out first if you want to keep them.

## Problems

Open an [issue](https://github.com/Pocketward/pd2-launcher-android-lite/issues) with your device's model, Android version and GPU, and what happened.

## Building from source

This repository holds the Android app (`app/`), the native ARM64 userland it carries (`userland/`), the build of Wine, Box64's `wowbox64.dll`, Mesa and the runtime (`build/`), our PanVK backend for Arm's kbase driver (`mesa-kbase/`), and our patches (`patches/`). The build runs in pinned containers (Podman or rootless Docker) and fetches every upstream at a pinned version, checked by SHA-256 or SHA-512. See [`docs/BUILDING.md`](docs/BUILDING.md) for the steps and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the pieces fit together.

## Disclaimer

Project Diablo 2, Diablo, and Blizzard are names of their respective owners. PD2 Launcher Lite contains none of their software or data: it runs the copy of the game you import.
