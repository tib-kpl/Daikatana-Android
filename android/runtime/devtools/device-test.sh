#!/system/bin/sh
# Test du runtime sur un appareil via adb, hors de l'appli (voir README, section developpement).
# Usage : sh device-test.sh <secondes> [options du moteur...]
# Prerequis dans $D : bin/ (proot Termux), rootfs-tiny/, game/ (port + liens vers les PAKs), home/
D=${D:-/data/local/tmp/dk}
R=${ROOTFS:-$D/rootfs-tiny}
SECONDS_TO_RUN=${1:-20}; shift

pkill proot; pkill Xvfb; for p in $(pidof daikatana); do kill -9 $p; done
rm -f $R/tmp/dk-audio.fifo && mkfifo $R/tmp/dk-audio.fifo
(cat $R/tmp/dk-audio.fifo > /dev/null &)
mkdir -p $D/home $D/empty

export LD_LIBRARY_PATH=$D/bin PROOT_LOADER=$D/bin/loader PROOT_TMP_DIR=$D/tmp
$D/bin/proot -0 --link2symlink -r $R \
  -b /dev -b $D/empty:/dev/input -b /proc -b /sys -b /storage -b $R/tmp:/dev/shm \
  -b $D/game:/game -b $D/home:/home/dk -w /game \
  /usr/bin/env -i HOME=/home/dk LANG=C.UTF-8 PATH=/usr/sbin:/usr/bin:/sbin:/bin \
  DK_WIDTH=${DK_WIDTH:-1280} DK_HEIGHT=${DK_HEIGHT:-720} \
  DK_LANGUAGE=${DK_LANGUAGE:-english} DK_LANG_DIR=${DK_LANG_DIR:-/game/lang}   DK_RENDERER=${DK_RENDERER:-software} DK_DEBUG=1 \
  /bin/sh /opt/dk/run.sh "$@" > $D/game.log 2>&1 &

sleep $SECONDS_TO_RUN
cp $R/tmp/fb/Xvfb_screen0 $D/shot.xwd
pkill proot; pkill Xvfb; for p in $(pidof daikatana); do kill -9 $p; done
