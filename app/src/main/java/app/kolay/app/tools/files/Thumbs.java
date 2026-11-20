package app.kolay.app.tools.files;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lazy image/video thumbnails with a memory LRU cache and a disk tier (cacheDir/thumbs, JPEG, trimmed to 40 MB once per process). A request is tied to its ImageView by tag, so a recycled row never
 * shows another file's picture; rows scrolled away before their turn are skipped.
 */
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L << 20)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final long DISK_MAX = 40L << 20;
    private static boolean trimmed;

    private Thumbs() {}

    private static File diskFile(File dir, String key) throws Exception {
        StringBuilder h = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return new File(dir, h + ".jpg");
    }

    /** Deletes the oldest cached thumbnails until the folder fits {@link #DISK_MAX}. */
    private static synchronized void trim(File dir) {
        if (trimmed) return;
        trimmed = true;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) { if (total <= DISK_MAX) break; total -= f.length(); f.delete(); }
    }

    /** Shows the cached thumbnail now or loads it in the background; {@code onLoaded} runs on the UI thread when it arrives. */
    static void load(ImageView v, FileItem it, int px, Runnable onLoaded) {
        String key = it.path + "|" + it.modified + "|" + px;
        v.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) { v.setImageBitmap(hit); onLoaded.run(); return; }
        File dir = new File(v.getContext().getCacheDir(), "thumbs");
        POOL.execute(() -> {
            if (!key.equals(v.getTag())) return;   // row reused for another file meanwhile
            Bitmap b = null;
            File df = null;
            try {
                dir.mkdirs();
                trim(dir);
                df = diskFile(dir, key);
                if (df.isFile()) {
                    b = android.graphics.BitmapFactory.decodeFile(df.getPath());
                    if (b != null) df.setLastModified(System.currentTimeMillis());
                }
            } catch (Exception | OutOfMemoryError ignored) { }
            if (b == null) b = generate(it, px, df);
            if (b == null) return;
            CACHE.put(key, b);
            Bitmap bm = b;
            UI.post(() -> { if (key.equals(v.getTag())) { v.setImageBitmap(bm); onLoaded.run(); } });
        });
    }

    private static Bitmap generate(FileItem it, int px, File df) {
        Bitmap b = null;
        try {
            File f = new File(it.path);
            Size s = new Size(px, px);
            CancellationSignal cs = new CancellationSignal();
            b = Files.mime(it.name).startsWith("video/") ? ThumbnailUtils.createVideoThumbnail(f, s, cs) : ThumbnailUtils.createImageThumbnail(f, s, cs);
        } catch (Exception | OutOfMemoryError ignored) { }
        if (b != null && df != null) {
            try (FileOutputStream o = new FileOutputStream(df)) { b.compress(Bitmap.CompressFormat.JPEG, 80, o); }
            catch (Exception ignored) { df.delete(); }
        }
        return b;
    }
}
