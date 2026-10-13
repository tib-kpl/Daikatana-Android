package fr.tibkpl.daikatana;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lance le moteur (binaire Linux arm64 du port) dans le rootfs Debian via proot,
 * sans emulation : seul l'environnement Linux (glibc, SDL2, Mesa, Xvfb) est fourni.
 */
final class GameRuntime {

    private static final String TAG = "Daikatana";

    private final Context context;
    private final GameInstaller.LaunchConfig config;
    private java.lang.Process process;

    GameRuntime(Context context, GameInstaller.LaunchConfig config) {
        this.context = context;
        this.config = config;
    }

    /** Fichier de l'image affichee par l'ecran X virtuel (voir /opt/dk/run.sh). */
    File framebufferFile() {
        return new File(config.rootfsDir, "tmp/fb/Xvfb_screen0");
    }

    File audioFifo() {
        return new File(config.rootfsDir, "tmp/dk-audio.fifo");
    }

    File logFile() {
        return new File(context.getFilesDir(), "daikatana.log");
    }

    /**
     * Rendu GPU possible : GPU Adreno accessible (interface KGSL) et Mesa Turnip present dans
     * le runtime. Sinon rendu logiciel (Mesa llvmpipe).
     */
    boolean gpuAvailable() {
        return new File("/dev/kgsl-3d0").canRead() && new File(config.rootfsDir, "opt/turnip/lib").isDirectory();
    }

    java.lang.Process start(int width, int height, int inputPort, boolean gpu) throws IOException {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        File prootTmp = new File(context.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) throw new IOException("Creation impossible : " + prootTmp);
        File rootfs = config.rootfsDir;

        List<String> cmd = new ArrayList<>();
        cmd.add(libDir + "/libproot.so");
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        bind(cmd, "/dev");
        bind(cmd, "/proc");
        bind(cmd, "/sys");
        bind(cmd, "/storage");
        bind(cmd, new File(rootfs, "tmp").getAbsolutePath() + ":/dev/shm");
        bind(cmd, config.baseDir.getAbsolutePath() + ":/game");
        bind(cmd, config.homeDir.getAbsolutePath() + ":/home/dk");
        File stats = new File(context.getFilesDir(), "render-stats");
        if (!stats.isDirectory() && !stats.mkdirs()) throw new IOException("Creation du dossier FPS impossible");
        bind(cmd, stats.getAbsolutePath() + ":/logs");
        cmd.add("-w");
        cmd.add("/game");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/home/dk");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("DK_WIDTH=" + width);
        cmd.add("DK_HEIGHT=" + height);
        cmd.add("DK_INPUT_PORT=" + inputPort);
        cmd.add("DK_LANGUAGE=" + config.language);
        cmd.add("DK_LANG_DIR=/game/lang");
        cmd.add("DK_RENDERER=" + (gpu ? "gpu" : "software"));
        cmd.add("DK_LOG_DIR=/logs");
        cmd.add("/bin/sh");
        cmd.add("/opt/dk/run.sh");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.remove("LD_PRELOAD");
        env.put("LD_LIBRARY_PATH", libDir);
        env.put("PROOT_LOADER", libDir + "/libproot-loader.so");
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile());
        Log.i(TAG, "Lancement : " + String.join(" ", cmd));
        process = pb.start();
        return process;
    }

    private static void bind(List<String> cmd, String spec) {
        cmd.add("-b");
        cmd.add(spec);
    }

    /**
     * Arrete le moteur. Tuer proot ne suffit pas (ses processus traces lui survivent) :
     * on tue tous les processus de l'appli autres que le processus Android lui-meme.
     */
    void stop() {
        if (process != null) process.destroy();
        int self = Process.myPid();
        int uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            int pid;
            try {
                pid = Integer.parseInt(p.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self || uidOf(p) != uid) continue;
            try {
                Os.kill(pid, android.system.OsConstants.SIGKILL);
            } catch (Exception ignored) {
                // processus deja termine
            }
        }
    }

    private static int uidOf(File procDir) {
        try {
            for (String line : Files.readAllLines(new File(procDir, "status").toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.split("\\s+")[1]);
            }
        } catch (Exception ignored) {
            // processus disparu ou illisible
        }
        return -1;
    }

    /** Dernieres lignes du journal, pour afficher une erreur a l'utilisateur. */
    String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile().toPath(), StandardCharsets.ISO_8859_1);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
