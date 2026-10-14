# PD2 Launcher Lite

> **Unofficial.** PD2 Launcher Lite is a community-made tool. It is not affiliated with or endorsed by the Project Diablo 2 team or Blizzard Entertainment.

An Android app that plays [Project Diablo 2](https://www.projectdiablo2.com/) from a copy of your own Diablo II folder. You copy a Diablo II folder that already has Project Diablo 2 installed (for example from your PC) to your phone or handheld; Lite imports it and opens Project Diablo 2's official launcher, which keeps the game up to date and starts it. You play with touch or a controller on your device's GPU. No root, no Linux container app, no PC streaming.

Lite is the small version of [PD2 Launcher for Android](https://github.com/Pocketward/pd2-launcher-android). It has the same screens, controls and in-game menu, but it does not install the game, and it runs Windows programs with a different, smaller runtime.

## How Lite differs from PD2 Launcher for Android

| | PD2 Launcher for Android | PD2 Launcher Lite |
|---|---|---|
| Getting the game | Downloads Blizzard's installers and installs Diablo II, Lord of Destruction and Project Diablo 2 (CD keys needed), or imports a folder | **Imports only:** a Diablo II folder that already has Project Diablo 2. No game downloads, no installers, no CD keys |
| Project Diablo 2 updates | The app's own **Update** | Project Diablo 2's **official launcher** |
| Starting the game | **Play** starts the game; the official launcher is optional | **Start launcher** opens the official launcher; its Play starts the game, and the launcher then closes |
| Runtime | FEX-Emu runs an x86-64 Linux system with Wine | Wine built for ARM64 runs natively; Box64's WowBox64 translates only the game's and the launcher's 32-bit x86 code. The .NET runtime that the launcher needs is included |
| App size | About 560 MB | About 100 MB |
| Storage | About 10 GB during the installation | About 0.5 GB for the app, plus your game folder (about 3 GB) |

The two apps can be installed side by side. Lite's icon has a gold **LITE** ribbon.

## What your device needs

- An ARM64 device with Android 13 or later, using 4 KB memory pages (almost every phone and handheld does).
- A Qualcomm Adreno GPU (Snapdragon devices), or an Arm Mali GPU of the v10 to v13 architectures on Arm's CSF kernel driver, such as the Mali-G310, G610, G615, G715, G720 and G725. Other GPUs are not supported.
- About 0.5 GB of free storage for the app and its runtime, plus the size of your game folder. While you import, the folder you copied to the device is there too; you can delete it afterwards.
- An internet connection when the official launcher checks for and downloads Project Diablo 2 updates.

The app checks this when it first starts and says what is missing. A Mali GPU of those architectures that the bundled graphics driver does not know gets a warning instead: the app lets you go on, but the game may not start.

## What you need

- A Diablo II folder with Lord of Destruction 1.14b or 1.14d and Project Diablo 2 installed (with its `ProjectD2` folder inside). Copy it to your device first, for example to `Download`.

## Install

Download the APK from the [releases page](https://github.com/Pocketward/pd2-launcher-android-lite/releases) and open it on your device. Android asks once to allow installs from the app you opened it with (your browser or file manager). The app is not on Google Play.

## Using it

The app walks you through four steps:

1. **Check:** your device, notifications and battery settings (both optional, but without them Android may stop the game while the app is in the background), and the game's support files, which the app unpacks once.
2. **Import:** pick your copied Diablo II folder with Android's folder picker. Lite checks that Project Diablo 2 is in it and says why when it is not.
3. **Complete:** Lite copies the folder into its own storage and checks the copy. The folder you picked is only read.
4. **Play:** **Start launcher** opens Project Diablo 2's official launcher. Its **Play** starts the game, and the launcher closes once the game shows. When you leave the game, the app comes back to this screen. The launcher also updates Project Diablo 2 and manages loot filters; links in it (news, Donate) open in your browser.

From the Play screen: **Settings**, **Controls**, **Repair** (check the copy again, or start over with a new import; your characters are kept) and **About**.

In the game:

- **Touch:** tap to click, long press and drag as with a mouse. On-screen controls (a virtual controller) show while no controller is connected; switch them with **Touch pad** in the in-game menu and arrange them with **Edit controls**: move, resize, add buttons for any key, or reset them.
- **Controller:** works as an Xbox controller. Press **Select + Start** (or **L3 + R3**, set in Controls) to switch to mouse mode for menus and typing; hold it to open the in-game menu. Every button can be rebound.
- **In-game menu:** the floating button, Android's Back gesture, or the controller chord opens a menu on the right edge, without pausing the game: Keyboard, Touch pad, Show fps, Controls, Edit controls, Settings and Stop game. Over the official launcher it offers Keyboard, Settings and Close launcher.
- **Screen:** the game fills your screen in Project Diablo 2's own shape (widescreen in the game, 4:3 in its menus), with black bars only where your screen's shape differs. Lite does not change Project Diablo 2's own resolution.

The official launcher and the game keep running when you switch apps; a notification shows while they do. Tap it to go back; its **Stop** ends them, as **Stop game** does. The game's sound stops while you use another app, unless sound in the background is on in the official launcher's **Options**.

**Settings:**

- **Game:** the game resolution (fewer lines run smoother; Automatic picks one for your GPU), the graphics profile (Default or Performance), and Limit to 60 fps on screens faster than 60 Hz. The renderer, Skip to Battle.net and sound in the background are in the official launcher's **Options**.
- **Controller:** the on-screen controls, the mouse-mode chord, the pointer speed and every button.
- **App:** keeping the screen on, the fps counter, your game files and storage, Android's battery optimisation and notifications for the app, and **Share logs** and **Save logs** (see Problems).

## Your files

Lite's copy of your game folder (with your characters and loot filter) appears in Android's Files app under **PD2 Launcher Lite**. **Settings → App → Game files** imports files into it and exports files from it. While the game or the launcher runs, the copy is read-only.

Uninstalling the app removes its copy of the game folder. Copy your characters (the `Save` folder) out first if you want to keep them.

## Problems

Open an [issue](https://github.com/Pocketward/pd2-launcher-android-lite/issues) with your device's model, Android version and GPU (the Check screen shows them) and what happened, and attach the app's logs: **Share logs** in **Settings → App**, or under the error on the Check, Import, Complete and Play screens (and on Complete while it copies or is stopped), makes one file to send; **Save logs** beside it saves that file on the device instead, for example in Downloads. The file holds the device's facts, the app's own log and the logs of the game's last start; nothing from your game folder. It holds no CD keys, but it can show your character names and network addresses.

## Building from source

This repository holds the Android app (`app/`), the native ARM64 userland it carries (`userland/`), the build of Wine, Box64's `wowbox64.dll`, Mesa and the runtime (`build/`), small Windows helpers (`helpers/`), our PanVK backend for Arm's kbase driver (`mesa-kbase/`), and our patches (`patches/`). The build runs in pinned containers (Podman or rootless Docker) and fetches every upstream at a pinned version, checked by SHA-256 or SHA-512. See [`docs/BUILDING.md`](docs/BUILDING.md) for the steps and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the pieces fit together.

## Disclaimer

Project Diablo 2, Diablo, and Blizzard are names of their respective owners. PD2 Launcher Lite contains none of their software or data: it runs the copy of the game you import.
