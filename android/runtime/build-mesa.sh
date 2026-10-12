#!/bin/sh
# Compile Mesa (Vulkan Turnip pour GPU Adreno via l'interface KGSL d'Android + OpenGL Zink)
# pour arm64, par compilation croisee depuis un conteneur Debian 13 x86_64 :
#   docker run --rm -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-mesa.sh
# Resultat : android/runtime/out/turnip.tgz (contenu de /opt/turnip), utilise par
# build-runtime.sh s'il est present.
set -e
MESA_VERSION=25.0.7
OUT=/src/android/runtime/out
export DEBIAN_FRONTEND=noninteractive

dpkg --add-architecture arm64
apt-get update -q
apt-get install -y -q --no-install-recommends \
    crossbuild-essential-arm64 binutils meson ninja-build python3-mako python3-yaml python3-packaging \
    glslang-tools bison flex pkg-config wget xz-utils ca-certificates \
    libdrm-dev:arm64 libx11-dev:arm64 libxext-dev:arm64 libxfixes-dev:arm64 libxcb-glx0-dev:arm64 \
    libxcb-shm0-dev:arm64 libx11-xcb-dev:arm64 libxcb-dri2-0-dev:arm64 libxcb-dri3-dev:arm64 \
    libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 libxcb-xfixes0-dev:arm64 \
    libxshmfence-dev:arm64 libxxf86vm-dev:arm64 libxrandr-dev:arm64 libexpat1-dev:arm64 \
    zlib1g-dev:arm64 libzstd-dev:arm64 libglvnd-dev:arm64

cd /tmp
wget -q "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
tar xJf "mesa-$MESA_VERSION.tar.xz"
cd "mesa-$MESA_VERSION"

cat > /tmp/cross-arm64.txt <<'EOF'
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
nm = 'aarch64-linux-gnu-nm'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = ['/usr/lib/aarch64-linux-gnu/pkgconfig', '/usr/share/pkgconfig']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# - zink + Turnip (kgsl) : OpenGL accelere sur Adreno sous Android
# - freedreno (Gallium) : necessaire pour que Mesa 25.0 compile le code DRI2 avec ce jeu d'options
# - sans LLVM ni GBM (pas d'affichage DRM/KMS)
meson setup build --cross-file /tmp/cross-arm64.txt --prefix=/opt/turnip --libdir=lib \
    -Dbuildtype=release -Dstrip=true \
    -Dplatforms=x11 -Dgallium-drivers=zink,freedreno -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=msm,kgsl -Dglx=dri -Degl=enabled -Dgbm=disabled -Dglvnd=enabled \
    -Dllvm=disabled -Dgles1=disabled -Dgles2=enabled -Dvalgrind=disabled -Dlibunwind=disabled \
    -Dlmsensors=disabled -Dvideo-codecs= -Dbuild-tests=false -Dgallium-xa=disabled
ninja -C build
DESTDIR=/tmp/stage ninja -C build install

mkdir -p "$OUT"
tar -C /tmp/stage --numeric-owner -czf "$OUT/turnip.tgz" opt/turnip
ls -la "$OUT/turnip.tgz"
