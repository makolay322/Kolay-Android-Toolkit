package app.kolay.shell;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * The old-Office plugin (.doc/.xls/.ppt reader: Apache POI + an adapter, one dex file built by tools/office-plugin/build.sh),
 * downloaded on demand into files/plugins/. The only code Kolay ever downloads besides its own signed update.
 *
 * Source: the asset of the fixed GitHub prerelease {@link #TAG}, over HTTPS from GitHub only ({@link Config#trustedHost}).
 * Its exact size and SHA-256 are compiled in: the download is moved into place (read-only) only after both match, and the
 * bytes are checked again every time before they are loaded, so a changed file is deleted instead of run.
 */
final class OfficePlugin {
    private OfficePlugin() {}

    /** Release tag holding the plugin. Never "latest" and never a version tag: see docs/OPERATIONS.md. A new build gets a new tag. */
    static final String TAG = "office-plugin-1";
    private static final String URL = Config.REPO_BASE + "releases/download/" + TAG + "/kolay-office-plugin.dex";
    static final long SIZE = 1_587_960L;
    private static final String SHA256 = "3535755782435a37c1c2af94e7ace959bdb71aa707b455c969f6571777caa224";

    private static final AtomicBoolean busy = new AtomicBoolean();

    private static File dir(Context c) { return new File(c.getFilesDir(), "plugins"); }
    private static File file(Context c) { return new File(dir(c), TAG + ".dex"); }

    static boolean installed(Context c) { File f = file(c); return f.isFile() && f.length() == SIZE; }

    /** Blocking (call off the main thread). Throws when the file cannot be fetched or does not match the pinned size/hash. */
    static void download(Context c, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IOException("eklenti zaten indiriliyor");
        File d = dir(c);
        File tmp = new File(d, "office." + System.nanoTime() + ".download");
        try {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("klasör oluşturulamadı");
            Updater.download(URL, tmp, SIZE, progress, cancelled);
            if (!Verify.sha256(tmp).equals(SHA256)) throw new SecurityException("özet (SHA-256) uyuşmuyor");
            if (!tmp.setReadOnly()) throw new IOException("dosya salt okunur yapılamadı");
            File f = file(c);
            if (f.exists()) { f.setWritable(true); f.delete(); }
            if (!tmp.renameTo(f)) throw new IOException("dosya yerleştirilemedi");
        } finally {
            tmp.delete();
            busy.set(false);
        }
    }

    /**
     * The plugin's bytes for {@code InMemoryDexClassLoader}: read from the read-only file and checked against the pinned
     * size and SHA-256 right here, so exactly the verified bytes are loaded. A mismatch deletes the file and throws.
     */
    static ByteBuffer dex(Context c) throws IOException {
        File f = file(c);
        f.setReadOnly();
        byte[] b = new byte[(int) SIZE];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            if (off != b.length || in.read() != -1) { delete(c); throw new SecurityException("eklenti dosyası değişmiş (boyut)"); }
        }
        if (!Verify.sha256(new ByteArrayInputStream(b)).equals(SHA256)) { delete(c); throw new SecurityException("eklenti dosyası değişmiş (SHA-256)"); }
        return ByteBuffer.wrap(b);
    }

    static boolean delete(Context c) {
        if (busy.get()) return false;
        File f = file(c);
        f.setWritable(true);
        return !f.exists() || f.delete();
    }
}
