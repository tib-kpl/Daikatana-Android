package fr.tibkpl.daikatana;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Detection, validation et memorisation du dossier des fichiers du jeu d'origine. */
final class GameData {

    /** PAKs du jeu retail (dossier "data/"), exiges par le moteur Daikatana 1.3. */
    static final String[] REQUIRED_FILES = {"pak1.pak", "pak2.pak", "pak3.pak"};

    /** Sous-dossiers acceptes si l'utilisateur choisit le dossier parent. */
    private static final String[] SUBDIRS = {"data", "gamedata"};

    private static final String PREFS = "daikatana";
    private static final String KEY_DATA_PATH = "data_path";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_RENDER_HEIGHT = "render_height";
    private static final String KEY_GPU_FAILED = "gpu_failed";
    private static final String KEY_RENDERER = "renderer";
    private static final String KEY_SHOW_FPS = "show_fps";

    /** Hauteurs de rendu proposees (largeur au format de l'ecran) : 720p fluide, 1080p net. */
    static final int[] RENDER_HEIGHTS = {540, 720, 1080};

    /** Langues du patch 1.3 : code du module (language_<code>.so) et nom affiche. */
    static final String[] LANGUAGES = {"english", "french", "german", "italian", "spanish"};
    static final String[] LANGUAGE_NAMES = {"English", "Français", "Deutsch", "Italiano", "Español"};

    private GameData() {}

    static String getSavedPath(Context context) {
        return prefs(context).getString(KEY_DATA_PATH, null);
    }

    static void savePath(Context context, File dir) {
        prefs(context).edit().putString(KEY_DATA_PATH, dir.getAbsolutePath()).apply();
    }

    /** Langue du jeu choisie, ou a defaut celle de l'appareil si le jeu la propose. */
    static String getLanguage(Context context) {
        String saved = prefs(context).getString(KEY_LANGUAGE, null);
        if (saved != null) return saved;
        String device = Locale.getDefault().getLanguage();
        String[] isoCodes = {"en", "fr", "de", "it", "es"};
        for (int i = 0; i < isoCodes.length; i++) {
            if (isoCodes[i].equals(device)) return LANGUAGES[i];
        }
        return "english";
    }

    static void saveLanguage(Context context, String language) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply();
    }

    static String languageName(String language) {
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(language)) return LANGUAGE_NAMES[i];
        }
        return language;
    }

    static int getRenderHeight(Context context) {
        return prefs(context).getInt(KEY_RENDER_HEIGHT, 720);
    }

    static void saveRenderHeight(Context context, int height) {
        prefs(context).edit().putInt(KEY_RENDER_HEIGHT, height).apply();
    }

    static String getRenderer(Context context) { return prefs(context).getString(KEY_RENDERER, "auto"); }
    static boolean showFps(Context context) { return prefs(context).getBoolean(KEY_SHOW_FPS, false); }
    static void saveGraphics(Context context, int height, String renderer, boolean fps) {
        prefs(context).edit().putInt(KEY_RENDER_HEIGHT, height).putString(KEY_RENDERER, renderer)
                .putBoolean(KEY_SHOW_FPS, fps).putBoolean(KEY_GPU_FAILED, false).apply();
    }

    /** Vrai si le rendu GPU a echoue sur cet appareil : on reste alors en rendu logiciel. */
    static boolean isGpuFailed(Context context) {
        return prefs(context).getBoolean(KEY_GPU_FAILED, false);
    }

    static void setGpuFailed(Context context, boolean failed) {
        prefs(context).edit().putBoolean(KEY_GPU_FAILED, failed).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Retourne le dossier contenant les PAKs : le dossier choisi lui-meme,
     * ou son sous-dossier data/ ou gamedata/. Null si introuvable.
     */
    static File findDataDir(File root) {
        if (root == null || !root.isDirectory()) return null;
        if (missingFiles(root).isEmpty()) return root;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (String name : SUBDIRS) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().equalsIgnoreCase(name)
                        && missingFiles(child).isEmpty()) {
                    return child;
                }
            }
        }
        return null;
    }

    /** Fichiers requis absents de dir (comparaison insensible a la casse : PAK1.PAK accepte). */
    static List<String> missingFiles(File dir) {
        Set<String> present = new HashSet<>();
        String[] names = dir == null ? null : dir.list();
        if (names != null) {
            for (String name : names) present.add(name.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_FILES) {
            if (!present.contains(required)) missing.add(required);
        }
        return missing;
    }

    /**
     * Convertit l'URI renvoyee par le selecteur de dossier Android en chemin reel.
     * Ex. "primary:Games/Daikatana" -> /storage/emulated/0/Games/Daikatana,
     *     "1234-ABCD:Daikatana"     -> /storage/1234-ABCD/Daikatana (carte SD).
     */
    static File treeUriToFile(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) {
            return null;
        }
        String docId = DocumentsContract.getTreeDocumentId(uri);
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String volume = docId.substring(0, colon);
        String relative = docId.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + volume);
        return relative.isEmpty() ? base : new File(base, relative);
    }
}
