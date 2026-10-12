package fr.tibkpl.daikatana;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Prepare tout ce qu'il faut au lancement, dans le stockage prive de l'appli :
 *
 *   files/rootfs/                 runtime Linux (Debian arm64 minimal, extrait de l'APK)
 *   files/daikatana/              moteur du port PortMaster (binaire + libs.aarch64), vu en /game
 *   files/daikatana/gamedata/     fichiers du port + liens vers les fichiers du jeu de l'utilisateur
 *   files/home/                   $HOME du moteur (/home/dk) : configs, sauvegardes
 */
final class GameInstaller {

    /** Ce dont le runtime a besoin pour demarrer le moteur. */
    static final class LaunchConfig {
        final File rootfsDir;
        final File baseDir;   // /game
        final File homeDir;   // /home/dk
        final File userDataDir;

        LaunchConfig(File rootfsDir, File baseDir, File homeDir, File userDataDir) {
            this.rootfsDir = rootfsDir;
            this.baseDir = baseDir;
            this.homeDir = homeDir;
            this.userDataDir = userDataDir;
        }
    }

    interface Progress {
        void onStep(String message);
    }

    private static final String ASSET_ROOTFS = "runtime/rootfs.tgz";
    private static final String ASSETS_PORT = "port";
    private static final String ASSETS_GAMEDATA = "port/gamedata";
    private static final String ASSETS_CFGS = "port/cfgs";
    private static final String KEY_INSTALLED_VERSION = "installed_version";

    private GameInstaller() {}

    static LaunchConfig prepare(Context context, File userDataDir, Progress progress) throws IOException {
        File files = context.getFilesDir();
        File rootfsDir = new File(files, "rootfs");
        File baseDir = new File(files, "daikatana");
        File gameDir = new File(baseDir, "gamedata");
        File homeDir = new File(files, "home");
        File saveGameDir = new File(homeDir, ".local/share/Daikatana/gamedata");
        AssetManager assets = context.getAssets();

        // Runtime et fichiers du port : reinstalles a chaque nouvelle version de l'APK.
        SharedPreferences prefs = context.getSharedPreferences("daikatana", Context.MODE_PRIVATE);
        int version = appVersion(context);
        if (prefs.getInt(KEY_INSTALLED_VERSION, -1) != version) {
            progress.onStep(context.getString(R.string.install_runtime, 0));
            deleteRecursively(rootfsDir);
            long total = assetLength(assets, ASSET_ROOTFS);
            try (InputStream in = assets.open(ASSET_ROOTFS)) {
                TarExtractor.extract(in, rootfsDir, read -> progress.onStep(
                        context.getString(R.string.install_runtime, total > 0 ? (int) (read * 100 / total) : 0)));
            }

            progress.onStep(context.getString(R.string.install_port));
            deleteRecursively(baseDir);
            mkdirs(gameDir);
            copyAssetTree(assets, ASSETS_GAMEDATA, gameDir);
            copyAssetFile(assets, ASSETS_PORT + "/daikatana", new File(baseDir, "daikatana"));
            copyAssetTree(assets, ASSETS_PORT + "/libs.aarch64", new File(baseDir, "libs.aarch64"));
            chmod(new File(baseDir, "daikatana"), 0755);

            prefs.edit().putInt(KEY_INSTALLED_VERSION, version).apply();
        }

        progress.onStep(context.getString(R.string.game_preparing));
        mkdirs(gameDir);
        mkdirs(saveGameDir);
        mkdirs(new File(rootfsDir, "tmp"));

        // Comme Daikatana.sh : pas de .cfg obsoletes dans gamedata, configs du port recopiees.
        File[] stale = gameDir.listFiles((dir, name) -> name.endsWith(".cfg") && !Files.isSymbolicLink(new File(dir, name).toPath()));
        if (stale != null) for (File f : stale) f.delete();
        copyAssetTree(assets, ASSETS_CFGS, saveGameDir);

        linkUserData(userDataDir, gameDir);

        return new LaunchConfig(rootfsDir, baseDir, homeDir, userDataDir);
    }

    /**
     * Cree dans gameDir un lien (nom en minuscules : le moteur Linux est sensible a la casse)
     * vers chaque fichier du dossier "data" de l'utilisateur. Les fichiers du port restent
     * prioritaires ; les .cfg (configs Windows) sont ignores ; les liens d'un ancien dossier
     * sont supprimes.
     */
    private static void linkUserData(File userDataDir, File gameDir) throws IOException {
        Set<String> portFiles = new HashSet<>();
        File[] existing = gameDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (Files.isSymbolicLink(f.toPath())) f.delete();
                else portFiles.add(f.getName().toLowerCase(Locale.ROOT));
            }
        }
        File[] userFiles = userDataDir.listFiles();
        if (userFiles == null) throw new IOException("Lecture impossible : " + userDataDir);
        for (File src : userFiles) {
            String name = src.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".cfg") || portFiles.contains(name)) continue;
            File link = new File(gameDir, name);
            if (Files.isSymbolicLink(link.toPath())) continue; // doublon de casse (ex. A.PAK et a.pak)
            try {
                Os.symlink(src.getAbsolutePath(), link.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("Lien impossible vers " + src + " : " + e.getMessage(), e);
            }
        }
    }

    private static void copyAssetTree(AssetManager assets, String path, File dest) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, path, dest);
            return;
        }
        mkdirs(dest);
        for (String child : children) {
            copyAssetTree(assets, path + "/" + child, new File(dest, child));
        }
    }

    private static void copyAssetFile(AssetManager assets, String path, File dest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        mkdirs(dest.getParentFile());
        try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static long assetLength(AssetManager assets, String path) {
        try (android.content.res.AssetFileDescriptor fd = assets.openFd(path)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1; // asset compresse : pas de taille connue
        }
    }

    static void deleteRecursively(File file) {
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getPath(), mode);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + file, e);
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Creation impossible : " + dir);
    }

    @SuppressWarnings("deprecation")
    private static int appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
