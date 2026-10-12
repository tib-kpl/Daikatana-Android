#!/bin/sh
# Construit le runtime Linux embarque dans l'APK : android/runtime/out/rootfs.tgz
#
# S'execute en root dans un conteneur Debian 13 (trixie) arm64, le depot monte en /src :
#   docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh
# (sur une machine x86_64, l'emulation arm64 de Docker/QEMU est necessaire)
set -e
RUNTIME=/src/android/runtime
OUT=$RUNTIME/out
export DEBIAN_FRONTEND=noninteractive

# 1. Bibliotheques du moteur + outils de compilation du pont manette
apt-get update -q
apt-get install -y -q --no-install-recommends \
    libsdl2-2.0-0 libopenal1 libvorbisfile3 libstdc++6 libgl1 libgl1-mesa-dri libglx-mesa0 \
    libegl1 xvfb x11-xkb-utils xkb-data \
    gcc libc6-dev libsdl2-dev

# 2. Fichiers propres au runtime (run.sh, asound.conf) et pont manette (LD_PRELOAD)
cp -r "$RUNTIME/rootfs-overlay/." /
chmod 755 /opt/dk/run.sh
gcc -O2 -shared -fPIC -Wall -o /opt/dk/libdkbridge.so "$RUNTIME/dkbridge/dkbridge.c" \
    $(sdl2-config --cflags) -ldl -lpthread

# 3. Rootfs minimal : uniquement les fichiers utilises (fermeture des dependances)
mkdir -p "$OUT"
sh "$RUNTIME/mkmin.sh" /tmp/rootfs
echo "nameserver 1.1.1.1" > /tmp/rootfs/etc/resolv.conf

# 4. Liste des paquets Debian redistribues (licences, sources : https://sources.debian.org)
#    et licences correspondantes, dans le rootfs et a cote de l'archive
(cd /tmp/rootfs && find . -type f -o -type l) | sed 's|^\.||' | grep -v '^/opt/dk/' \
    | xargs dpkg -S 2>/dev/null | cut -d: -f1 | tr ',' '\n' | sed 's/ //g' | sort -u > /tmp/pkgs
mkdir -p /tmp/rootfs/usr/share/doc/openkatana
xargs dpkg-query -W -f '${Package} ${Version} (source : ${source:Package} ${source:Version})\n' \
    < /tmp/pkgs > /tmp/rootfs/usr/share/doc/openkatana/packages.txt
while read -r pkg; do
    [ -f "/usr/share/doc/$pkg/copyright" ] && \
        install -D -m 644 "/usr/share/doc/$pkg/copyright" "/tmp/rootfs/usr/share/doc/$pkg/copyright"
done < /tmp/pkgs
cp /tmp/rootfs/usr/share/doc/openkatana/packages.txt "$OUT/packages.txt"

# 5. Archive (.tgz et non .tar.gz : AGP decompresse les assets .gz)
tar -C /tmp/rootfs --numeric-owner -czf "$OUT/rootfs.tgz" .
chmod 644 "$OUT/rootfs.tgz"
ls -la "$OUT/rootfs.tgz"
