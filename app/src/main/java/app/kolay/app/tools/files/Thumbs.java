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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lazy image/video thumbnails with a memory LRU cache. A request is tied to its ImageView by tag, so a recycled row never
 * shows another file's picture; rows scrolled away before their turn are skipped.
 */
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L << 20)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Thumbs() {}

    /** Shows the cached thumbnail now or loads it in the background; {@code onLoaded} runs on the UI thread when it arrives. */
    static void load(ImageView v, FileItem it, int px, Runnable onLoaded) {
        String key = it.path + "|" + it.modified + "|" + px;
        v.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) { v.setImageBitmap(hit); onLoaded.run(); return; }
        POOL.execute(() -> {
            if (!key.equals(v.getTag())) return;   // row reused for another file meanwhile
            Bitmap b = null;
            try {
                File f = new File(it.path);
                Size s = new Size(px, px);
                CancellationSignal cs = new CancellationSignal();
                b = Files.mime(it.name).startsWith("video/") ? ThumbnailUtils.createVideoThumbnail(f, s, cs) : ThumbnailUtils.createImageThumbnail(f, s, cs);
            } catch (Exception | OutOfMemoryError ignored) { }
            if (b == null) return;
            CACHE.put(key, b);
            Bitmap bm = b;
            UI.post(() -> { if (key.equals(v.getTag())) { v.setImageBitmap(bm); onLoaded.run(); } });
        });
    }
}
