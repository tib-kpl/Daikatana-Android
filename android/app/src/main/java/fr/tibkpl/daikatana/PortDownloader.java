package fr.tibkpl.daikatana;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Telecharge le port Daikatana (moteur 1.3 arm64) depuis la source officielle de PortMaster,
 * comme le fait PortMaster lui-meme : OpenKatana ne redistribue ni le moteur ni ses fichiers.
 */
final class PortDownloader {

    interface Progress {
        void onProgress(int percent);
    }

    /** Catalogue officiel des ports PortMaster (URL et MD5 de chaque archive). */
    private static final String CATALOG_URL =
            "https://github.com/PortsMaster/PortMaster-New/releases/latest/download/ports.json";
    private static final String PORT_NAME = "daikatana.zip";
    private static final String ZIP_PREFIX = "daikatana/";

    private PortDownloader() {}

    /** Telecharge, verifie (MD5) et extrait le port dans destDir (contenu de daikatana/). */
    static void install(File cacheDir, File destDir, Progress progress) throws IOException {
        JSONObject source;
        try {
            source = new JSONObject(new String(fetch(CATALOG_URL), StandardCharsets.UTF_8))
                    .getJSONObject("ports").getJSONObject(PORT_NAME).getJSONObject("source");
        } catch (org.json.JSONException e) {
            throw new IOException("Catalogue PortMaster illisible", e);
        }
        String url = source.optString("url");
        String md5 = source.optString("md5");
        if (url.isEmpty() || md5.isEmpty()) throw new IOException("Port absent du catalogue PortMaster");

        File zip = new File(cacheDir, PORT_NAME);
        download(url, zip, source.optLong("size", -1), progress);
        String actual = md5Of(zip);
        if (!actual.equalsIgnoreCase(md5)) {
            zip.delete();
            throw new IOException("Archive corrompue (MD5 " + actual + ", attendu " + md5 + ")");
        }
        extract(zip, destDir);
        zip.delete();
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File dest, long expectedSize, Progress progress) throws IOException {
        HttpURLConnection conn = open(url);
        long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : expectedSize;
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onProgress(percent);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "OpenKatana");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " : " + url);
        }
        return conn;
    }

    private static String md5Of(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Extrait le contenu de daikatana/ (sans les visuels du jeu, inutiles ici). */
    private static void extract(File zip, File destDir) throws IOException {
        String destPath = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith(ZIP_PREFIX)) continue;
                name = name.substring(ZIP_PREFIX.length());
                if (name.isEmpty() || name.equals("cover.png") || name.equals("screenshot.jpg")) continue;
                File target = new File(destDir, name);
                if (!target.getCanonicalPath().startsWith(destPath)) throw new IOException("Chemin invalide : " + name);
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Creation impossible : " + target);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Creation impossible : " + parent);
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
            }
        }
    }
}
