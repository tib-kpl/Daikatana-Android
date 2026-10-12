# Third-party software

OpenKatana redistributes the following free software in binary form inside its APK. Their
source code is available at the addresses below.

## proot

- License: GPL-2.0-or-later
- Binaries: Termux packages `proot`, `libtalloc` (LGPL-3.0-or-later) and `libandroid-shmem`
  (BSD-3-Clause), downloaded by `android/runtime/prepare-proot.py` from
  https://packages.termux.dev
- Sources: https://github.com/termux/proot and https://github.com/termux/termux-packages
  (build recipes `packages/proot`, `packages/libtalloc`, `packages/libandroid-shmem`)

## Linux runtime (Debian 13 "trixie" arm64 packages)

The bundled rootfs (`assets/runtime/rootfs.tgz`) only contains files from unmodified Debian
packages, plus OpenKatana's own files under `/opt/dk` and `/etc/asound.conf`, and the Mesa
build described below under `/opt/turnip`. Its main
components are glibc, SDL2, OpenAL Soft, Mesa, the X.Org Xvfb server, libvorbis and dash.

- Exact list of packages and versions: `packages.txt`, attached to every release and included
  in the rootfs (`/usr/share/doc/openkatana/packages.txt`).
- Licenses: each package's `copyright` file, included in the rootfs
  (`/usr/share/doc/<package>/copyright`).
- Sources: https://sources.debian.org, or https://snapshot.debian.org for an exact version
  (`apt-get source <package>=<version>`).

## Mesa (Turnip Vulkan driver and Zink OpenGL driver)

- License: MIT (and other permissive licenses, see `docs/license.rst` in the Mesa sources)
- Built from the official Mesa release by `android/runtime/build-mesa.sh` (version and build
  options in that script), installed in the rootfs under `/opt/turnip`.
- Sources: https://archive.mesa3d.org and https://gitlab.freedesktop.org/mesa/mesa

## Not redistributed

- **Daikatana game files** (© Ion Storm / Eidos Interactive): provided by the user, who must
  own the game.
- **Daikatana 1.3 engine** (PortMaster port by JohnnyonFlame): downloaded by the app on the
  device, from PortMaster's official source (https://github.com/PortsMaster/PortMaster-New),
  on first launch. Its MD5 checksum is verified.
- **Daikatana 1.3 language modules**: downloaded by the app on the device from the official
  1.3 release (https://github.com/maraakate/daikatana/releases) when a language other than
  English is selected. Their SHA-256 checksum is verified. They are only read as data.
