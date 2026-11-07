package app.kolay.app.viewer;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import app.kolay.app.ui.Ctx;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF pages in a zoomable vertical list. Only pages near the screen are rendered, one at a time on a private thread
 * (PdfRenderer is single-threaded); bitmaps live in a byte-bounded LRU and are re-rendered sharper after zooming in.
 */
final class PdfPane extends FrameLayout {
    private static final int MAX_PIXELS = 12_000_000;

    private final Ctx c;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PdfRenderer renderer;
    private final ParcelFileDescriptor pfd;
    private final Runnable onClose;
    private final int count;
    private final float[] aspect;          // height / width per page, 0 = unknown
    private final float firstAspect;
    private final LruCache<Integer, Bitmap> cache;
    private final HashSet<Integer> queued = new HashSet<>();
    private final ListView list;
    private final ZoomLayout zoom;
    private final TextView pill;
    private volatile boolean closed;
    private float quality = 1f;
    private int first, last;               // visible range (UI thread copy for the worker)

    /** Opens on the calling (background) thread; throws for broken or password-protected files. */
    static PdfPane open(Ctx c, ParcelFileDescriptor pfd, Runnable onClose) throws java.io.IOException {
        PdfRenderer r;
        try { r = new PdfRenderer(pfd); }
        catch (java.io.IOException | RuntimeException e) { try { pfd.close(); } catch (java.io.IOException ignored) {} throw e; }
        float a = 1.414f;
        if (r.getPageCount() > 0) {
            try (PdfRenderer.Page p = r.openPage(0)) { if (p.getWidth() > 0) a = p.getHeight() / (float) p.getWidth(); }
        }
        return new PdfPane(c, r, pfd, a, onClose);
    }

    int count() { return count; }

    private PdfPane(Ctx c, PdfRenderer r, ParcelFileDescriptor pfd, float a0, Runnable onClose) {
        super(c.a);
        this.c = c; this.renderer = r; this.pfd = pfd; this.onClose = onClose;
        count = r.getPageCount();
        aspect = new float[count];
        firstAspect = Math.max(0.2f, Math.min(5f, a0));
        int budget = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 160L << 20);
        cache = new LruCache<Integer, Bitmap>(budget) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
            @Override protected void entryRemoved(boolean evicted, Integer k, Bitmap old, Bitmap nu) {
                if (old != nu && !showing(old)) old.recycle();
            }
        };
        list = new ListView(c.a);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(c.dp(8));
        list.setPadding(c.dp(8), c.dp(4), c.dp(8), c.dp(64));
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return count; }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                PageView pv = v instanceof PageView ? (PageView) v : new PageView();
                pv.bind(i);
                return pv;
            }
        });
        zoom = new ZoomLayout(c.a);
        zoom.addView(list, new FrameLayout.LayoutParams(-1, -1));
        zoom.onZoom = s -> {
            float q = Math.min(3f, Math.max(1f, s));
            if (Math.abs(q - quality) / quality > 0.2f) { quality = q; cache.evictAll(); invalidatePages(); }
        };
        addView(zoom, new FrameLayout.LayoutParams(-1, -1));
        pill = ViewerScreen.pill(c, this::askPage);
        addView(pill, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) pill.getLayoutParams()).bottomMargin = c.dp(14);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int f, int n, int total) { first = f; last = f + n - 1; updatePill(); }
        });
        updatePill();
    }

    private void askPage() { ViewerScreen.askNumber(c, "Sayfaya git", count, n -> list.setSelection(n - 1)); }

    private void updatePill() {
        int p = list.getFirstVisiblePosition();
        View ch = list.getChildAt(0);
        if (ch != null && ch.getBottom() < list.getHeight() / 3 && p + 1 < count) p++;
        pill.setText((Math.min(count, p + 1)) + " / " + count);
    }

    private boolean showing(Bitmap b) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof PageView && ((PageView) v).shown == b) return true;
        }
        return false;
    }

    private void invalidatePages() { for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).invalidate(); }

    private void request(int page, int width) {
        if (closed || !queued.add(page)) return;
        final float q = quality;
        worker.execute(() -> {
            Bitmap bmp = null;
            float a = 0;
            try {
                if (closed || page < first - 2 || page > last + 2) return;
                try (PdfRenderer.Page p = renderer.openPage(page)) {
                    a = p.getWidth() > 0 ? p.getHeight() / (float) p.getWidth() : firstAspect;
                    int w = Math.round(width * q), h = Math.round(w * a);
                    if ((long) w * h > MAX_PIXELS) { double f = Math.sqrt(MAX_PIXELS / (double) ((long) w * h)); w = (int) (w * f); h = (int) (h * f); }
                    if (w <= 0 || h <= 0) return;
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                bmp = null;   // a damaged page stays blank; the others still render
            } finally {
                Bitmap done = bmp; float fa = a;
                c.ui.post(() -> {
                    queued.remove(page);
                    if (closed) { if (done != null) done.recycle(); return; }
                    if (fa > 0 && Math.abs(fa - aspect[page]) > 0.001f) { aspect[page] = fa; requestPageLayout(page); }
                    if (done != null && q == quality) cache.put(page, done);
                    invalidatePages();
                });
            }
        });
    }

    private void requestPageLayout(int page) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof PageView && ((PageView) v).page == page) v.requestLayout();
        }
    }

    void close() {
        if (closed) return;   // idempotent: a second call would hit the shut-down worker
        closed = true;
        worker.execute(() -> {
            try { renderer.close(); } catch (RuntimeException ignored) {}
            try { pfd.close(); } catch (java.io.IOException ignored) {}
            if (onClose != null) onClose.run();
        });
        worker.shutdown();
        cache.evictAll();
    }

    /** One page: white sheet with the rendered bitmap when available. */
    private final class PageView extends View {
        int page = -1;
        Bitmap shown;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        PageView() { super(c.a); }

        void bind(int p) { if (p != page) { page = p; shown = null; requestLayout(); invalidate(); } }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            float a = aspect[page] > 0 ? aspect[page] : firstAspect;
            setMeasuredDimension(w, Math.round(w * a));
        }

        @Override protected void onDraw(Canvas cv) {
            cv.drawColor(Color.WHITE);
            Bitmap b = cache.get(page);
            if (b == null) {
                request(page, getWidth());
                b = shown != null && !shown.isRecycled() ? shown : null;   // keep the older sharpness until the new one is in
            }
            shown = b;
            if (b != null && !b.isRecycled()) { dst.set(0, 0, getWidth(), getHeight()); cv.drawBitmap(b, null, dst, paint); }
        }
    }
}
