#!/bin/sh
# Lance Daikatana dans le runtime Linux de l'appli Android.
# Equivalent de Daikatana.sh (PortMaster) : ecran X virtuel (Xvfb) dont l'image est lue
# par l'appli dans /tmp/fb, manette/clavier via libdkbridge.so, son via /tmp/dk-audio.fifo.
#
# Variables fournies par l'appli : DK_WIDTH, DK_HEIGHT, DK_INPUT_PORT, DK_LANGUAGE, DK_LANG_DIR,
# DK_RENDERER (gpu : Mesa Turnip + Zink sur GPU Adreno ; sinon rendu logiciel llvmpipe)

W=${DK_WIDTH:-1920}
H=${DK_HEIGHT:-1080}

mkdir -p "$HOME/.local/share/Daikatana/gamedata" /tmp/fb
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 /tmp/fb/Xvfb_screen0

Xvfb :0 -screen 0 "${W}x${H}x24" -fbdir /tmp/fb -nolisten tcp -ac >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
i=0
while [ ! -S /tmp/.X11-unix/X0 ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done

cd /game || exit 1
export DISPLAY=:0
export LD_LIBRARY_PATH=/game/libs.aarch64
export LD_PRELOAD=/opt/dk/libdkbridge.so

# Rendu GPU (Adreno) : OpenGL -> Zink -> Vulkan Turnip (interface KGSL d'Android).
# Xvfb n'a pas DRI3 : Zink passe par le chemin "drisw" (LIBGL_KOPPER_DRI2) et la
# presentation Vulkan en copie logicielle (MESA_VK_WSI_DEBUG=sw).
if [ "$DK_RENDERER" = "gpu" ] && [ -d /opt/turnip ] && [ -r /dev/kgsl-3d0 ]; then
    export LD_LIBRARY_PATH=/opt/turnip/lib:$LD_LIBRARY_PATH
    export __GLX_VENDOR_LIBRARY_NAME=mesa
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export LIBGL_KOPPER_DRI2=1
    export MESA_VK_WSI_DEBUG=sw
    export VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json
    export XDG_RUNTIME_DIR=/tmp
fi
./daikatana +set game gamedata "$@"
STATUS=$?

kill "$XVFB_PID" 2>/dev/null
exit $STATUS
