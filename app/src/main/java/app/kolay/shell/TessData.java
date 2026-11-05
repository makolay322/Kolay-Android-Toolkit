package app.kolay.shell;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * OCR language data, downloaded on demand into files/tessdata/ (the APK ships none since 1.029).
 *
 * Source: tesseract-ocr/tessdata_fast at a pinned commit, over HTTPS from GitHub only ({@link Config#trustedHost}); the exact size
 * and SHA-256 of every file are compiled in, and a file is moved into place (atomic rename) only after both match. A
 * deu.traineddata copied from the APK by older versions is the same file and simply counts as installed.
 */
final class TessData {
    private TessData() {}

    /** tessdata_fast commit the files below were taken from. */
    static final String COMMIT = "87416418657359cb625c412a48b6e1d6d41c29bd";
    private static final String BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" + COMMIT + "/";

    static final String[] LANGS = {"deu", "tur", "eng"};
    private static final long[] SIZES = {1_525_436L, 4_550_554L, 4_113_088L};
    private static final String[] SHA256 = {
        "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
        "7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7",
        "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    };

    private static final Set<String> busy = new HashSet<>();

    private static int index(String lang) {
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(lang)) return i;
        return -1;
    }

    static File dir(Context c) { return new File(c.getFilesDir(), "tessdata"); }
    private static File file(Context c, String lang) { return new File(dir(c), lang + ".traineddata"); }

    static boolean installed(Context c, String lang) {
        File f = file(c, lang);
        return index(lang) >= 0 && f.isFile() && f.length() > 1024;
    }

    static long installedSize(Context c, String lang) { return installed(c, lang) ? file(c, lang).length() : 0; }

    static long downloadSize(String lang) { int i = index(lang); return i < 0 ? 0 : SIZES[i]; }

    /**
     * At start-up: creates the folder and removes only leftovers that are certainly incomplete (the old copy step's
     * deu.tmp, downloads abandoned over an hour ago). Installed .traineddata files are never touched here.
     */
    static void cleanup(Context c) {
        File d = dir(c);
        if (!d.isDirectory()) d.mkdirs();
        new File(d, "deu.tmp").delete();
        File[] kids = d.listFiles();
        long old = System.currentTimeMillis() - 3600_000L;
        if (kids != null) for (File k : kids)
            if (k.getName().endsWith(".download") && k.lastModified() < old) k.delete();
    }

    /** Blocking (call off the main thread). Throws when the file cannot be fetched or does not match the pinned size/hash. */
    static void download(Context c, String lang, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        int i = index(lang);
        if (i < 0) throw new IOException("bilinmeyen dil");
        synchronized (busy) { if (!busy.add(lang)) throw new IOException("bu dil zaten indiriliyor"); }
        File d = dir(c);
        File tmp = new File(d, lang + "." + System.nanoTime() + ".download");
        try {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("klasör oluşturulamadı");
            Updater.download(BASE + lang + ".traineddata", tmp, SIZES[i], progress, cancelled);
            if (!Verify.sha256(tmp).equals(SHA256[i])) throw new SecurityException("özet (SHA-256) uyuşmuyor");
            if (!tmp.renameTo(file(c, lang))) throw new IOException("dosya yerleştirilemedi");
        } finally {
            tmp.delete();
            synchronized (busy) { busy.remove(lang); }
        }
    }

    static boolean delete(Context c, String lang) {
        if (index(lang) < 0) return false;
        synchronized (busy) { if (busy.contains(lang)) return false; }
        return file(c, lang).delete();
    }
}
