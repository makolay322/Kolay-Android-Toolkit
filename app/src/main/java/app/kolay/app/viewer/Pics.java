package app.kolay.app.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import app.kolay.app.ui.Ctx;
import java.util.HashSet;

/** Pictures inside a package, decoded in the background at about the shown width and kept in a byte-bounded LRU. */
final class Pics {
    private final Ctx c;
    private final Zip zip;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final HashSet<String> pending = new HashSet<>(), failed = new HashSet<>();
    volatile boolean closed;

    Pics(Ctx c, Zip zip) { this.c = c; this.zip = zip; }

    boolean failed(String part) { return failed.contains(part); }

    /** UI thread. Cached bitmap, or null after scheduling a decode that calls {@code ready} (UI thread) when done. */
    Bitmap get(String part, int width, Runnable ready) {
        if (part == null || width <= 0 || failed.contains(part)) return null;
        int w = Math.min(2048, ((width + 255) / 256) * 256);
        String key = part + "@" + w;
        Bitmap b = cache.get(key);
        if (b != null || !pending.add(key)) return b;
        c.io.execute(() -> {
            Bitmap out = null;
            try { if (!closed) out = decode(part, w); } catch (Exception | OutOfMemoryError ignored) {}
            Bitmap done = out;
            c.ui.post(() -> {
                pending.remove(key);
                if (closed) return;
                if (done == null) failed.add(part); else cache.put(key, done);
                ready.run();
            });
        });
        return null;
    }

    private Bitmap decode(String part, int w) throws java.io.IOException {
        byte[] data = zip.read(part, Zip.MAX_MEDIA);
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 100_000_000L) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    void close() { closed = true; cache.evictAll(); }
}
