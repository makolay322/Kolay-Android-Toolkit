package app.kolay.app.doc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import java.io.File;
import java.io.IOException;

/**
 * Page image pipeline: decode (EXIF-upright, memory-capped) → perspective warp of the page quad → rotation → filter.
 * Works at full resolution up to a pixel cap; filters run in row bands so the Java heap only ever holds small arrays.
 */
public final class Imaging {
    private Imaging() {}

    /** Pixel cap for exported pages (~12 MP). */
    public static final long FULL = 12_000_000L;

    public enum Filter {
        ORIGINAL("Orijinal"), AUTO("Otomatik"), GRAY("Gri"), BW("Siyah-Beyaz"), SHADOW("Gölge giderme");
        public final String label;
        Filter(String l) { label = l; }
    }

    /** Decodes upright (EXIF applied), subsampled by powers of two-ish so that width × height ≤ {@code maxPixels}. */
    public static Bitmap decode(File f, long maxPixels) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (d, info, s) -> {
            long px = (long) info.getSize().getWidth() * info.getSize().getHeight();
            int sample = 1;
            while (px / ((long) sample * sample) > maxPixels) sample++;
            if (sample > 1) d.setTargetSampleSize(sample);
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            d.setMutableRequired(false);
        });
    }

    private static float dist(float[] q, int a, int b) { return (float) Math.hypot(q[b] - q[a], q[b + 1] - q[a + 1]); }

    /**
     * The quad (normalised TL, TR, BR, BL) straightened into a rectangle sized by its edge lengths (≤ {@code maxPixels}).
     * Always a new mutable bitmap; {@code src} is left alone.
     */
    public static Bitmap warp(Bitmap src, float[] quadN, long maxPixels) {
        int sw = src.getWidth(), sh = src.getHeight();
        float[] q = new float[8];
        for (int i = 0; i < 8; i += 2) { q[i] = quadN[i] * sw; q[i + 1] = quadN[i + 1] * sh; }
        float ow = Math.max(dist(q, 0, 2), dist(q, 6, 4)), oh = Math.max(dist(q, 0, 6), dist(q, 2, 4));
        ow = Math.max(ow, 16); oh = Math.max(oh, 16);
        double k = Math.min(1.0, Math.sqrt(maxPixels / ((double) ow * oh)));
        int w = Math.max(16, (int) Math.round(ow * k)), h = Math.max(16, (int) Math.round(oh * k));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        if (!m.setPolyToPoly(q, 0, new float[]{0, 0, w, 0, w, h, 0, h}, 0, 4)) {
            m.setScale(w / (float) sw, h / (float) sh);   // degenerate quad: fall back to the whole image
        }
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFFFFFFFF);
        cv.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    /** Clockwise rotation by a multiple of 90°; returns {@code b} itself for 0, otherwise a new bitmap ({@code b} untouched). */
    public static Bitmap rotate(Bitmap b, int deg) {
        deg = ((deg % 360) + 360) % 360;
        if (deg == 0) return b;
        int w = b.getWidth(), h = b.getHeight();
        boolean swap = deg != 180;
        Bitmap r = Bitmap.createBitmap(swap ? h : w, swap ? w : h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.postRotate(deg);
        if (deg == 90) m.postTranslate(h, 0);
        else if (deg == 180) m.postTranslate(w, h);
        else m.postTranslate(0, w);
        new Canvas(r).drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return r;
    }

    /**
     * Full page render: decode ≤ {@code maxPixels}, warp, rotate, filter. The result is a new bitmap owned by the caller.
     * On OutOfMemoryError it retries once at a quarter of the pixels.
     */
    public static Bitmap render(File src, float[] quad, int rotation, Filter f, long maxPixels) throws IOException {
        try { return renderOnce(src, quad, rotation, f, maxPixels); }
        catch (OutOfMemoryError e) { return renderOnce(src, quad, rotation, f, maxPixels / 4); }
    }

    private static Bitmap renderOnce(File src, float[] quad, int rotation, Filter f, long maxPixels) throws IOException {
        Bitmap b = decode(src, maxPixels);
        Bitmap w;
        try { w = warp(b, quad, maxPixels); } finally { b.recycle(); }
        Bitmap r = rotate(w, rotation);
        if (r != w) w.recycle();
        try { apply(r, f); } catch (OutOfMemoryError e) { r.recycle(); throw e; }
        return r;
    }

    // ---- filters (in place, mutable bitmap) ------------------------------------------------------------------

    private static final int BAND = 64;

    public static void apply(Bitmap b, Filter f) {
        switch (f) {
            case AUTO: auto(b); break;
            case GRAY: gray(b); break;
            case BW: bw(b); break;
            case SHADOW: shadow(b); break;
            default: break;
        }
    }

    private static int luma(int c) { return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8; }

    private static int pct(int[] hist, long total, double p) {
        long target = (long) (total * p), run = 0;
        for (int v = 0; v < 256; v++) { run += hist[v]; if (run > target) return v; }
        return 255;
    }

    private static int[] stretch(int lo, int hi) {
        int[] lut = new int[256];
        if (hi - lo < 32) { for (int v = 0; v < 256; v++) lut[v] = v; return lut; }
        for (int v = 0; v < 256; v++) lut[v] = Math.max(0, Math.min(255, (v - lo) * 255 / (hi - lo)));
        return lut;
    }

    /** Per-channel levels: 1 % → black, paper (98.5 %) → white. Fixes a colour cast and flat contrast. */
    private static void auto(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] hr = new int[256], hg = new int[256], hb = new int[256];
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 250_000));
        int[] row = new int[w];
        long n = 0;
        for (int y = 0; y < h; y += step) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += step) { int c = row[x]; hr[(c >> 16) & 0xFF]++; hg[(c >> 8) & 0xFF]++; hb[c & 0xFF]++; n++; }
        }
        int[] lr = stretch(pct(hr, n, 0.01), pct(hr, n, 0.985)), lg = stretch(pct(hg, n, 0.01), pct(hg, n, 0.985)), lb = stretch(pct(hb, n, 0.01), pct(hb, n, 0.985));
        int[] px = new int[w * BAND];
        for (int y = 0; y < h; y += BAND) {
            int rows = Math.min(BAND, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            for (int i = 0, e = w * rows; i < e; i++) {
                int c = px[i];
                px[i] = 0xFF000000 | lr[(c >> 16) & 0xFF] << 16 | lg[(c >> 8) & 0xFF] << 8 | lb[c & 0xFF];
            }
            b.setPixels(px, 0, w, 0, y, w, rows);
        }
    }

    /** Luminance with a 1 % / 99 % stretch. */
    private static void gray(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] hist = new int[256];
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 250_000));
        int[] row = new int[w];
        long n = 0;
        for (int y = 0; y < h; y += step) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += step) { hist[luma(row[x])]++; n++; }
        }
        int[] lut = stretch(pct(hist, n, 0.01), pct(hist, n, 0.99));
        int[] px = new int[w * BAND];
        for (int y = 0; y < h; y += BAND) {
            int rows = Math.min(BAND, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            for (int i = 0, e = w * rows; i < e; i++) { int v = lut[luma(px[i])]; px[i] = 0xFF000000 | v << 16 | v << 8 | v; }
            b.setPixels(px, 0, w, 0, y, w, rows);
        }
    }

    /** Block-averaged small copy of one channel (0 = luma, 1..3 = R, G, B); sizes in {@code dims}. */
    private static int[] small(Bitmap b, int f, int channel, int[] dims) {
        int w = b.getWidth(), h = b.getHeight();
        int sw = (w + f - 1) / f, sh = (h + f - 1) / f;
        dims[0] = sw; dims[1] = sh;
        int[] sum = new int[sw * sh], cnt = new int[sw * sh];
        int[] px = new int[w * BAND];
        for (int y = 0; y < h; y += BAND) {
            int rows = Math.min(BAND, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            for (int r = 0; r < rows; r++) {
                int so = ((y + r) / f) * sw, po = r * w;
                for (int x = 0; x < w; x++) {
                    int c = px[po + x];
                    int v = channel == 0 ? luma(c) : (c >> (8 * (3 - channel))) & 0xFF;
                    int k = so + x / f;
                    sum[k] += v; cnt[k]++;
                }
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = cnt[i] == 0 ? 255 : sum[i] / cnt[i];
        return sum;
    }

    /** Box mean with radius r via an integral image (small images only: sums fit an int). */
    private static int[] boxMean(int[] a, int w, int h, int r) {
        int[] ii = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int rs = 0;
            for (int x = 0; x < w; x++) { rs += a[y * w + x]; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + rs; }
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                int s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0];
                out[y * w + x] = s / ((y1 - y0) * (x1 - x0));
            }
        }
        return out;
    }

    /** Separable max filter (grey dilation): removes thin dark strokes (text) from a small background estimate. */
    private static int[] dilate(int[] a, int w, int h, int r) {
        int[] t = new int[w * h], out = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int m = 0;
            for (int k = Math.max(0, x - r), e = Math.min(w - 1, x + r); k <= e; k++) m = Math.max(m, a[y * w + k]);
            t[y * w + x] = m;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int m = 0;
            for (int k = Math.max(0, y - r), e = Math.min(h - 1, y + r); k <= e; k++) m = Math.max(m, t[k * w + x]);
            out[y * w + x] = m;
        }
        return out;
    }

    /** Bilinear sampling helpers for upscaling a small map to full resolution. */
    private static final class Up {
        final int[] x0, x1; final int[] wx;   // wx in 1/256
        final int f, sw, sh;
        Up(int w, int f, int sw, int sh) {
            this.f = f; this.sw = sw; this.sh = sh;
            x0 = new int[w]; x1 = new int[w]; wx = new int[w];
            for (int x = 0; x < w; x++) {
                float s = (x + 0.5f) / f - 0.5f;
                int i = (int) Math.floor(s);
                wx[x] = Math.round((s - i) * 256);
                x0[x] = Math.max(0, Math.min(sw - 1, i)); x1[x] = Math.max(0, Math.min(sw - 1, i + 1));
            }
        }
        int ry0, ry1, wy;
        void row(int y) {
            float s = (y + 0.5f) / f - 0.5f;
            int i = (int) Math.floor(s);
            wy = Math.round((s - i) * 256);
            ry0 = Math.max(0, Math.min(sh - 1, i)) * sw; ry1 = Math.max(0, Math.min(sh - 1, i + 1)) * sw;
        }
        int at(int[] m, int x) {
            int a = m[ry0 + x0[x]], b = m[ry0 + x1[x]], c = m[ry1 + x0[x]], d = m[ry1 + x1[x]];
            int top = a * 256 + (b - a) * wx[x], bot = c * 256 + (d - c) * wx[x];
            return (top * 256 + (bot - top) * wy) >> 16;
        }
    }

    private static int factor(Bitmap b, int target) { return Math.max(1, Math.max(b.getWidth(), b.getHeight()) / target); }

    /** Adaptive threshold (Bradley): darker than 85 % of the local mean → ink, with a soft ramp against jagged edges. */
    private static void bw(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int f = factor(b, 640);
        int[] dims = new int[2];
        int[] sm = small(b, f, 0, dims);
        int sw = dims[0], sh = dims[1];
        int[] mean = boxMean(sm, sw, sh, Math.max(2, Math.max(sw, sh) / 24));
        Up up = new Up(w, f, sw, sh);
        int[] px = new int[w * BAND];
        for (int y = 0; y < h; y += BAND) {
            int rows = Math.min(BAND, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            for (int r = 0; r < rows; r++) {
                up.row(y + r);
                int o = r * w;
                for (int x = 0; x < w; x++) {
                    int l = luma(px[o + x]);
                    int t = up.at(mean, x) * 85 / 100;
                    int v = Math.max(0, Math.min(255, (l - t + 8) * 16));
                    px[o + x] = 0xFF000000 | v << 16 | v << 8 | v;
                }
            }
            b.setPixels(px, 0, w, 0, y, w, rows);
        }
    }

    /** Shadow removal: per-channel background (dilate + blur on a small copy) divided out, so paper becomes even white. */
    private static void shadow(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int f = factor(b, 400);
        int[] dims = new int[2];
        int[][] bg = new int[3][];
        for (int ch = 0; ch < 3; ch++) {
            int[] sm = small(b, f, ch + 1, dims);
            bg[ch] = boxMean(dilate(sm, dims[0], dims[1], 3), dims[0], dims[1], 5);
        }
        Up up = new Up(w, f, dims[0], dims[1]);
        int[] px = new int[w * BAND];
        for (int y = 0; y < h; y += BAND) {
            int rows = Math.min(BAND, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            for (int r = 0; r < rows; r++) {
                up.row(y + r);
                int o = r * w;
                for (int x = 0; x < w; x++) {
                    int c = px[o + x], out = 0xFF000000;
                    for (int ch = 0; ch < 3; ch++) {
                        int sh = 8 * (2 - ch);
                        int v = (c >> sh) & 0xFF, base = Math.max(24, up.at(bg[ch], x));
                        int n = Math.min(255, v * 255 / base);
                        out |= n << sh;
                    }
                    px[o + x] = out;
                }
            }
            b.setPixels(px, 0, w, 0, y, w, rows);
        }
    }
}
