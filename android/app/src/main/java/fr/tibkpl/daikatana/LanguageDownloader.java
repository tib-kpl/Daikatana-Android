package fr.tibkpl.daikatana;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Telecharge les modules de langue officiels du patch Daikatana 1.3 (release Linux x86_64
 * sur GitHub). Ils ne sont pas executes : libdkbridge.so (dklang.c) y lit les textes pour
 * traduire ceux du moteur arm64. OpenKatana ne les redistribue pas.
 */
final class LanguageDownloader {

    interface Progress {
        void onProgress(int percent);
    }

    private static final String URL =
            "https://github.com/maraakate/daikatana/releases/download/v12-21-2025/Daikatana-Linux-2025-12-21-x64.tar.bz2";
    private static final String SHA256 = "7b37a0ed29cc268a460a668f5cd7f73d6f64a2c2265e7aade8382efdd46cf69e";

    private LanguageDownloader() {}

    /** Installe dlls/language_*.so de la release dans destDir. */
    static void install(File cacheDir, File destDir, Progress progress) throws IOException {
        File archive = new File(cacheDir, "daikatana13-linux.tar.bz2");
        download(archive, progress);
        String actual = sha256Of(archive);
        if (!actual.equalsIgnoreCase(SHA256)) {
            archive.delete();
            throw new IOException("Archive corrompue (SHA-256 " + actual + ")");
        }
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("Creation impossible : " + destDir);
        int found = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16)))) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = tar.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if (!entry.isFile() || !entry.getName().contains("/dlls/language_") || !name.endsWith(".so")) continue;
                try (OutputStream out = new FileOutputStream(new File(destDir, name))) {
                    int n;
                    while ((n = tar.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                found++;
            }
        } finally {
            archive.delete();
        }
        if (found == 0) throw new IOException("Modules de langue absents de l'archive");
    }

    private static void download(File dest, Progress progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "OpenKatana");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode() + " : " + URL);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent != last) progress.onProgress(last = percent);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String sha256Of(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
}
