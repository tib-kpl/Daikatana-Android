# Daikatana-Android

**Unofficial** Android launcher to play **Daikatana** (Ion Storm, 2000) on ARM64 Android
handhelds and phones, using the engine of the community **Daikatana 1.3** patch, through its
PortMaster port.

> [!IMPORTANT]
> **You must own the game.** Daikatana contains **no game files**: no levels, textures,
> sounds or music. It does not contain the Daikatana 1.3 engine either. To play, you need a
> legal copy of Daikatana for PC (for example from [GOG](https://www.gog.com/game/daikatana)
> or [Steam](https://store.steampowered.com/app/242980/Daikatana/)), and you must copy its
> `data` folder to your device. Without these files, the app will not start the game.

## Features

- Checks for the game files (`pak1.pak`, `pak2.pak`, `pak3.pak`) at startup. If they are
  missing, it asks where they are (internal storage or SD card) and remembers the location.
- Widescreen: 16:9 fullscreen (or the screen's aspect ratio), widescreen FOV, display cutout
  area used.
- Built-in or Bluetooth gamepad (sticks, analog triggers, d-pad), physical keyboard.
- Game languages: English, French, German, Italian and Spanish.
- Sound (OpenAL). The game pauses and goes silent when the app is in the background or the
  screen is locked.
- No emulation: the ARM64 engine runs natively.
- GPU rendering on Adreno (Snapdragon) devices through Mesa Turnip + Zink, with automatic
  fallback to software rendering elsewhere. Resolution: 720p (smooth) or 1080p (sharp).

Tested on the **AYN Odin 2 Portal** (Snapdragon 8 Gen 2, Android 13).

## Installation

1. Download `Daikatana-x.y.z.apk` from the [Releases](../../releases) page and install it
   (allow installing apps from unknown sources).
2. Copy the `data` folder of your PC Daikatana installation to the device, for example to
   `ROMS/PC/Daikatana/data` on the SD card. A Windows installation patched to 1.3 works too;
   its extra files (`pak4.pak` to `pak6.pak`, voice packs…) are used.
3. Start **Daikatana**:
   - grant **all files access**: the engine reads the game files where they are, without
     copying them;
   - pick the `data` folder (or the folder that contains it);
   - optionally, choose the game language.
4. On first launch, Daikatana installs its Linux runtime and **downloads the engine** (the
   PortMaster Daikatana port, 14 MB) from PortMaster's official source. For a language other
   than English, it also downloads the official language modules of the 1.3 patch (22 MB)
   from its GitHub releases. An Internet connection is required that first time only.

To change the game folder later: **long-press the icon → "Changer le dossier du jeu"**.

### Gamepad controls

Daikatana uses a classic FPS layout. It is applied at every launch, like the port's
`gamecontroller.cfg`.

| Button | Action |
|---|---|
| Left / right stick | Move / aim |
| R2 | Fire |
| L2 | Zoom |
| A | Jump |
| B | Crouch |
| X | Use (doors, switches) |
| Y | Cycle HUD item |
| L1 | Use selected item |
| R1 | Run |
| D-pad left / right | Previous / next weapon |
| D-pad up / down | Previous / next item |
| Start | Menu |
| Back (Android) | Escape |

## Current limitations

- GPU rendering requires an Adreno GPU (Snapdragon). Other devices (Mali, PowerVR…) use
  software rendering (Mesa llvmpipe), which is much slower and heats the device.
- A few texts that only exist in the 2023 engine of the port stay in English.
- **ARM64** devices only (arm64-v8a), Android 8.0 or newer.

## How it works

The engine is the PortMaster port of Daikatana 1.3, downloaded from
[PortMaster](https://github.com/PortsMaster/PortMaster-New) on first launch (MD5 checked).
It is a **Linux** ARM64 executable (glibc, SDL2, OpenAL) that Android cannot run directly, so
Daikatana provides a small Linux environment, like PortMaster does on Linux handhelds:

```
Android app (Java)
 ├─ proot ─► minimal Debian arm64 ─► Xvfb (virtual X screen) ─► Daikatana engine
 │                                     ├─ OpenGL: Mesa Zink -> Vulkan Turnip -> Adreno GPU (KGSL)
 │                                     └─ libdkbridge.so (LD_PRELOAD): virtual SDL gamepad,
 │                                        pause, translation of the engine's texts
 ├─ shows the Xvfb framebuffer (mmap) with OpenGL ES, scaled to the screen
 ├─ sends gamepad/keyboard input to libdkbridge (local socket)
 └─ plays the sound: OpenAL → ALSA → FIFO → AudioTrack
```

The port's engine has English built in and does not load the 1.3 language modules. For other
languages, `libdkbridge.so` reads the official `language_english.so` and `language_<lang>.so`
modules as data, and replaces each English text of the engine with its translation before the
game starts.

## Repository layout

| Path | Content |
|---|---|
| `android/app/` | Android app |
| `android/runtime/` | Linux runtime build: rootfs, Mesa Turnip, bridge library, sound, proot |
| `.github/workflows/release.yml` | APK build and release |

## Building

### With GitHub Actions

Every push to `main` builds the APK and publishes it as a new release (version
`0.3.<build number>`). The workflow can also be started manually from the *Actions* tab.

It only uses `ubuntu-latest` runners, which are included in GitHub's free quota, private
repositories included. The ARM64 runtime is built under QEMU emulation; a full release takes
about 5 to 10 minutes.

**Signing**: for updates to install over the previous version, the APK must always be signed
with the same key. Create it once:

```sh
keytool -genkeypair -v -keystore release.jks -alias openkatana -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # value of the OPENKATANA_KEYSTORE_BASE64 secret
```

Then add these repository secrets (*Settings → Secrets and variables → Actions*):
`OPENKATANA_KEYSTORE_BASE64`, `OPENKATANA_KEYSTORE_PASSWORD`, `OPENKATANA_KEY_ALIAS`
(`openkatana`) and `OPENKATANA_KEY_PASSWORD`. Without them, the APK is signed with a debug
key. Keep `release.jks` safe and never commit it.

### Locally

Requirements: JDK 17+, Android SDK (platform 36), Python 3, Docker (with ARM64 emulation on an
x86_64 PC). No game or engine file is needed to build.

```sh
# 1. Mesa Turnip + Zink, cross-compiled -> android/runtime/out/turnip.tgz
docker run --rm -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-mesa.sh

# 2. ARM64 Linux runtime -> android/runtime/out/rootfs.tgz
docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh

# 3. proot (Termux packages) -> android/app/src/main/jniLibs/arm64-v8a/
python3 android/runtime/prepare-proot.py

# 4. APK
cd android && ./gradlew assembleRelease
```

`android/runtime/devtools/` contains tools to test the runtime directly on a device through
adb, outside of the app.

## Credits and licenses

- **Daikatana** © Ion Storm / Eidos Interactive (Square Enix). The game's trademarks and
  content belong to their owners. This project is **neither affiliated with nor endorsed** by
  them.
- **Daikatana 1.3**: community patch by Frank Sapone (maraakate) and team,
  https://github.com/maraakate/daikatana. Its engine and language modules are not
  redistributed by this project: the app downloads them from their official sources.
- **PortMaster port**: JohnnyonFlame, https://portmaster.games.
- **proot** (GPL-2.0) and **libtalloc** (LGPL-3.0): [Termux](https://termux.dev) packages.
- **Linux runtime**: [Debian](https://www.debian.org) 13 packages (SDL2, OpenAL Soft, Mesa,
  X.Org Xvfb, glibc…), each under its own license.
- License details and source code access: [THIRD_PARTY.md](THIRD_PARTY.md).
- Daikatana's icon and artwork are original creations of this project.

## Graphics and returning to a running game

The settings gear pauses the current session. **Retour au jeu** resumes the same engine
process. Graphics changes apply at the next launch: automatic selection, integrated Vulkan
Turnip, software rendering for troubleshooting, 540p/720p/1080p and an engine FPS counter.
The Adreno driver is included; no external driver ZIP is required.

### Launcher and controller settings

The gear opens the launcher settings. **Retour au jeu** resumes the current session and **Redemarrer le jeu** restarts the engine to apply changes. **Configurer la manette** detects connected Android controllers and stores button mappings and right-stick axis choices separately for each device. Generic button codes and RX/RY layouts are accepted. Physical AYN Thor validation remains pending.
