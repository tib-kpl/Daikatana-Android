# Logiciels tiers

OpenKatana redistribue, dans son APK, les logiciels libres suivants sous forme binaire.
Leur code source est disponible aux adresses indiquées.

## proot

- Licence : GPL-2.0-or-later
- Binaires : paquets Termux `proot`, `libtalloc` (LGPL-3.0-or-later) et `libandroid-shmem`
  (BSD-3-Clause), téléchargés par `android/runtime/prepare-proot.py` depuis
  https://packages.termux.dev
- Sources : https://github.com/termux/proot et https://github.com/termux/termux-packages
  (recettes de compilation `packages/proot`, `packages/libtalloc`, `packages/libandroid-shmem`)

## Runtime Linux (paquets Debian 13 « trixie », arm64)

Le rootfs embarqué (`assets/runtime/rootfs.tgz`) ne contient que des fichiers de paquets
Debian non modifiés, plus les fichiers propres à OpenKatana sous `/opt/dk` et `/etc/asound.conf`.
Les principaux composants sont glibc, SDL2, OpenAL Soft, Mesa, le serveur Xvfb de X.Org,
libvorbis et dash.

- Liste exacte des paquets et de leurs versions : `packages.txt`, joint à chaque release et
  présent dans le rootfs (`/usr/share/doc/openkatana/packages.txt`).
- Licences : fichiers `copyright` de chaque paquet, inclus dans le rootfs
  (`/usr/share/doc/<paquet>/copyright`).
- Sources : https://sources.debian.org, et https://snapshot.debian.org pour une version précise
  (`apt-get source <paquet>=<version>`).

## Non redistribué

- **Fichiers du jeu Daikatana** (© Ion Storm / Eidos Interactive) : fournis par l'utilisateur,
  qui doit posséder le jeu.
- **Moteur Daikatana 1.3** (port PortMaster de JohnnyonFlame) : téléchargé par l'application
  sur l'appareil, depuis la source officielle de PortMaster
  (https://github.com/PortsMaster/PortMaster-New), au premier lancement. Son empreinte MD5
  est vérifiée.
