package app.kolay.app.tools.scan;

import android.graphics.Bitmap;
import app.kolay.app.doc.Imaging;
import app.kolay.app.doc.QuadDetector;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Screen;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One scanning flow: the pages (original pictures in the app's private cache, never anywhere else until the user exports)
 * and their edit state. Page fields and the list are touched on the main thread only; background work reads copies and
 * posts results back. {@link #destroy} removes every file and bitmap.
 */
public final class ScanSession {
    /** Preview renders (list, strip, filter choice) stay around this size. */
    static final long THUMB_PX = 360_000L;
    private static final long DETECT_PX = 1_200_000L;
    /** Larger pictures are not copied in (the page is marked unreadable); decoding is sampled down anyway. */
    private static final long MAX_IMPORT_BYTES = 64L << 20;
    /** Cache folders of the flows alive in this process (main thread). A share can open a second activity next to a running flow. */
    private static final java.util.Set<String> LIVE = new java.util.HashSet<>();

    static final class Page {
        final File file;
        float[] quad = QuadDetector.full();   // normalised, upright original; TL, TR, BR, BL
        int rotation;                         // clockwise, applied after straightening
        Imaging.Filter filter;
        Bitmap thumb;                         // processed preview, main thread; never recycled while the flow lives (see render)
        int version;                          // bumped on every edit; stale renders are dropped
        boolean ready;                        // page outline detected (or given up)
        boolean broken;                       // the picture could not be read
        Page(File f, Imaging.Filter filter) { file = f; this.filter = filter; }
    }

    final Ctx c;
    final File dir;
    final List<Page> pages = new ArrayList<>();
    public Imaging.Filter defaultFilter = Imaging.Filter.AUTO;
    /** The screen at the bottom of the flow ({@link DocsScreen}; "Yeni tarama" returns there, "Bitti" leaves it). */
    public Screen root;
    /** Called on the main thread whenever a page's preview or state changed. */
    public Runnable onChange;
    private int seq;
    private volatile boolean destroyed;   // also read by the background writers

    public ScanSession(Ctx c) {
        this.c = c;
        File parent = new File(c.a.getCacheDir(), "scan");
        File[] old = parent.listFiles();   // leftovers of earlier flows (e.g. the app was killed), never a live one
        if (old != null) for (File f : old) if (!LIVE.contains(f.getName())) deleteTree(f);
        dir = new File(parent, Long.toString(System.nanoTime()));
        dir.mkdirs();
        LIVE.add(dir.getName());
    }

    public boolean destroyed() { return destroyed; }

    public int count() { return pages.size(); }

    /** A new file name in this flow's cache folder; any thread. */
    public synchronized File newFile() { return new File(dir, "p" + (++seq) + ".jpg"); }

    /**
     * Main thread: a page from a flatbed / feeder scanner, already written to {@code f} (from {@link #newFile}). The whole
     * picture is the page, so no outline detection; filter = {@link #defaultFilter}.
     */
    public void addScanned(File f) {
        if (destroyed) { c.io.execute(f::delete); return; }
        Page p = new Page(f, defaultFilter);
        p.ready = true;
        pages.add(p);
        changed();
        render(p);
    }

    /** Camera still: written to the cache on a background thread, then outlined. {@code hint} = live outline (may be null). */
    void addJpeg(byte[] jpeg, float[] hint) {
        Page p = new Page(newFile(), defaultFilter);
        pages.add(p);
        changed();
        c.io.execute(() -> {
            try (OutputStream o = new FileOutputStream(p.file)) { o.write(jpeg); }
            catch (IOException e) { c.ui.post(() -> markBroken(p)); return; }
            if (gone(p)) return;
            detect(p, hint, false);
        });
    }

    /** Gallery or shared pictures: copied into the cache (the read grant may end), then outlined. */
    void addUris(List<android.net.Uri> uris) {
        for (android.net.Uri u : uris) {
            Page p = new Page(newFile(), defaultFilter);
            pages.add(p);
            c.io.execute(() -> {
                try (InputStream in = c.a.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(p.file)) {
                    if (in == null) throw new IOException("okunamadı");
                    byte[] b = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(b)) > 0) {
                        if ((total += n) > MAX_IMPORT_BYTES) throw new IOException("çok büyük");
                        o.write(b, 0, n);
                    }
                } catch (Exception e) { c.ui.post(() -> markBroken(p)); return; }
                if (gone(p)) return;
                detect(p, null, true);
            });
        }
        changed();
    }

    /** Background, after writing a page file: the flow ended meanwhile, so the file must not outlive the cleanup. */
    private boolean gone(Page p) {
        if (!destroyed) return false;
        p.file.delete();
        dir.delete();   // only succeeds when empty, i.e. the cleanup already ran
        return true;
    }

    private void markBroken(Page p) {
        if (destroyed) return;
        p.broken = true; p.ready = true;
        changed();
    }

    /**
     * Background: find the page outline on a ~1 MP copy, then render the preview. Imported pictures use the stricter
     * {@link QuadDetector#detectImport} (they are often cropped already); camera stills fall back to the live outline.
     */
    private void detect(Page p, float[] hint, boolean imported) {
        float[] q = null;
        try {
            Bitmap b = Imaging.decode(p.file, DETECT_PX);
            try { QuadDetector d = new QuadDetector(); q = imported ? d.detectImport(b) : d.detect(b); } finally { b.recycle(); }
        } catch (Exception | OutOfMemoryError e) {
            c.ui.post(() -> markBroken(p));
            return;
        }
        final float[] found = q != null ? q : hint;
        c.ui.post(() -> {
            if (destroyed) return;
            if (found != null) p.quad = found;
            p.ready = true;
            p.version++;
            render(p);
        });
    }

    /**
     * Main thread: (re)renders the preview of {@code p} in the background. A replaced or removed preview is left to the
     * GC instead of being recycled: screens further down the stack (the camera's page strip) may still show it until
     * they rebuild, and drawing a recycled bitmap crashes.
     */
    void render(Page p) {
        final int v = p.version;
        final float[] q = p.quad.clone();
        final int rot = p.rotation;
        final Imaging.Filter f = p.filter;
        c.io.execute(() -> {
            Bitmap b = null;
            try { b = Imaging.render(p.file, q, rot, f, THUMB_PX); }
            catch (Exception | OutOfMemoryError ignored) { }
            final Bitmap out = b;
            c.ui.post(() -> {
                if (destroyed || p.version != v || !pages.contains(p)) { if (out != null) out.recycle(); return; }
                if (out == null) { p.broken = true; changed(); return; }
                p.thumb = out;
                changed();
            });
        });
    }

    /** Main thread: after an edit of quad / rotation / filter. */
    void edited(Page p) { p.version++; render(p); }

    void remove(Page p) {
        pages.remove(p);
        p.version++;
        changed();
        p.thumb = null;   // not recycled: see render
        File f = p.file;
        c.io.execute(f::delete);
    }

    void move(int from, int to) {
        if (from < 0 || to < 0 || from >= pages.size() || to >= pages.size()) return;
        pages.add(to, pages.remove(from));
        changed();
    }

    public boolean allReady() {
        for (Page p : pages) if (!p.ready) return false;
        return true;
    }

    /** A readable page as it is now, for background rendering while the page itself may change. */
    public static final class Job {
        final File file; final float[] quad; final int rotation; final Imaging.Filter filter;
        Job(Page p) { file = p.file; quad = p.quad.clone(); rotation = p.rotation; filter = p.filter; }
        /** Background: straightened, rotated and filtered, ≤ {@code maxPixels}; a new bitmap owned by the caller. */
        public Bitmap render(long maxPixels) throws IOException { return Imaging.render(file, quad, rotation, filter, maxPixels); }
    }

    /** Main thread: the readable pages in order. */
    public List<Job> jobs() {
        List<Job> out = new ArrayList<>();
        for (Page p : pages) if (!p.broken) out.add(new Job(p));
        return out;
    }

    private void changed() { if (!destroyed && onChange != null) onChange.run(); }

    /**
     * Main thread, when the root screen of the flow is destroyed (its view is gone, so the previews can be recycled).
     * Bitmaps and files go; safe to call twice. The files are deleted on a thread of their own: the shared pool may be
     * shut down right after this (activity finishing), which would drop a queued task.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        onChange = null;
        for (Page p : pages) if (p.thumb != null) { p.thumb.recycle(); p.thumb = null; }
        pages.clear();
        LIVE.remove(dir.getName());
        File d = dir;
        new Thread(() -> deleteTree(d), "scan-cleanup").start();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
