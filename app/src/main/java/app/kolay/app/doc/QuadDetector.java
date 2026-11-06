package app.kolay.app.doc;

import android.graphics.Bitmap;

/**
 * Finds the outline of a document page, pure Java: downscale (≤ 360 px) → 5×5 Gaussian → Sobel (luma, and chroma when the
 * input has colour: per pixel the strongest of |∇Y|, k·|∇U|, k·|∇V|) → Canny (non-maximum suppression + hysteresis). Two
 * candidates are then scored by how much of their perimeter lies on edges and the better one wins: the best quadrilateral
 * made of two roughly parallel Hough line pairs, and a region candidate (paper is bright and unsaturated: Otsu on
 * brightness minus saturation, the largest blob in the centre, its convex hull reduced to four corners). For bitmaps (stills,
 * imports) each side is then refined on the bitmap itself: a gradient-weighted line fit in a narrow band, corners
 * re-intersected. A few milliseconds on a phone; the work buffers are reused, so one instance must not be used by two
 * threads at once.
 *
 * Result: 8 floats, normalised (0..1) corner coordinates in the order top-left, top-right, bottom-right, bottom-left; or null.
 * {@link #detectImport} is the stricter variant for imported pictures, which are often cropped already.
 */
public final class QuadDetector {
    private static final int TARGET = 360;
    private static final int THETAS = 180;
    private static final float[] COS = new float[THETAS], SIN = new float[THETAS];
    private static final int[] ICOS = new int[THETAS], ISIN = new int[THETAS];   // × 1024, for the voting loop
    static {
        for (int t = 0; t < THETAS; t++) {
            double a = Math.PI * t / THETAS;
            COS[t] = (float) Math.cos(a); SIN[t] = (float) Math.sin(a);
            ICOS[t] = (int) Math.round(Math.cos(a) * 1024); ISIN[t] = (int) Math.round(Math.sin(a) * 1024);
        }
    }

    private int w, h;
    private int[] img, tmp, mag;
    private byte[] dir, edge, near;
    private int[] acc, stack;
    private float conf;
    private boolean imported;
    // strong-edge threshold cap: dense text or tables must not lift it above a faint page edge (light desk); detectImport
    // retries lower it further
    private static final int SEED_MAX = 128;
    private int seedCap = SEED_MAX;
    private int[] cu, cv, pap;                 // chroma (≈ U, V around 0) and "paperness"; cu/cv only valid when colour
    private boolean colour;
    private byte[] mask;
    private int[] hx, hy, hist256 = new int[256];
    private final float[] bestQ = new float[8], cand = new float[8];
    private float best;
    private boolean found;
    private static final int CHROMA_K = 2;

    // imported pictures (see detectImport); tuned on synthetic test pages (dark / grey / wood / light desks, cropped pages)
    private static final float MIN_CONF = 0.70f, MIN_AREA = 0.25f;
    private static final float STRIP = 0.03f;
    private static final int PAPER_TOL = 40, STEP_TOL = 18, PAPER_MIN = 110, STEP = 12;
    private static final int[] SEED_CAPS = {96, 64};

    /** Mean edge coverage (0..1) of the perimeter of the last quad found; 0 when none was found. */
    public float confidence() { return conf; }

    /** Luma plane (e.g. a camera Y plane with {@code stride}); the frame is analysed in its own orientation. */
    public float[] detect(byte[] luma, int stride, int width, int height) {
        load(luma, stride, width, height);
        return run();
    }

    /**
     * Camera YUV_420_888 frame with colour: U/V planes share {@code uvRowStride} / {@code uvPixelStride} and must hold the
     * whole (half-resolution) frame; with {@code u} or {@code v} null this is {@link #detect(byte[], int, int, int)}.
     */
    public float[] detect(byte[] luma, int stride, byte[] u, byte[] v, int uvRowStride, int uvPixelStride, int width, int height) {
        load(luma, stride, width, height);
        if (u != null && v != null) loadChroma(u, v, uvRowStride, uvPixelStride, width, height);
        return run();
    }

    /** Any bitmap (e.g. an imported photo or a captured still); a small copy is searched, the sides refined on the bitmap. */
    public float[] detect(Bitmap b) {
        load(b);
        float[] q = run();
        return q != null ? refine(b, q) : null;
    }

    /**
     * Imported picture: the page outline only when it is convincing, otherwise the whole picture ({@link #full}); never null.
     * A picture whose border already looks like paper counts as cropped. A found outline must be well supported by edges,
     * cover a plausible part of the picture and be darker outside than inside along its sides (a page on a background,
     * not a table, box or photo printed on the page).
     */
    public float[] detectImport(Bitmap b) { load(b); float[] q = importRun(); return q != null ? refine(b, q) : full(); }

    /** {@link #detectImport(Bitmap)} for a luma plane. */
    public float[] detectImport(byte[] luma, int stride, int width, int height) {
        load(luma, stride, width, height);
        float[] q = importRun();
        return q != null ? q : full();
    }

    /** @return the convincing outline, or null (cropped already, or nothing convincing). */
    private float[] importRun() {
        conf = 0;
        if (looksCropped()) return null;
        prepare();
        imported = true;
        float[] q;
        try {
            q = search();
            for (int i = 0; q == null && i < SEED_CAPS.length; i++) { seedCap = SEED_CAPS[i]; q = search(); }
        } finally { imported = false; seedCap = SEED_MAX; }
        return q;
    }

    private void load(byte[] luma, int stride, int width, int height) {
        int f = Math.max(1, (Math.max(width, height) + TARGET - 1) / TARGET);
        alloc(width / f, height / f);
        colour = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0;
                for (int dy = 0; dy < f; dy++) {
                    int base = (y * f + dy) * stride + x * f;
                    for (int dx = 0; dx < f; dx++) s += luma[base + dx] & 0xFF;
                }
                img[y * w + x] = s / (f * f);
            }
        }
    }

    /** Point-samples the half-resolution chroma planes at the centre of each downscaled pixel (after the luma load). */
    private void loadChroma(byte[] u, byte[] v, int rs, int ps, int width, int height) {
        int f = Math.max(1, (Math.max(width, height) + TARGET - 1) / TARGET);
        int last = ((h * f - 1) / 2) * rs + ((w * f - 1) / 2) * ps;
        if (last >= u.length || last >= v.length || h * f > height || w * f > width) return;
        for (int y = 0; y < h; y++) {
            int r = ((y * f + f / 2) / 2) * rs;
            for (int x = 0; x < w; x++) {
                int o = r + ((x * f + f / 2) / 2) * ps, i = y * w + x;
                cu[i] = (u[o] & 0xFF) - 128; cv[i] = (v[o] & 0xFF) - 128;
            }
        }
        colour = true;
    }

    private void load(Bitmap b) {
        int bw = b.getWidth(), bh = b.getHeight();
        float k = Math.min(1f, TARGET / (float) Math.max(bw, bh));
        int sw = Math.max(8, Math.round(bw * k)), sh = Math.max(8, Math.round(bh * k));
        Bitmap s = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] px = new int[sw * sh];
        s.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (s != b) s.recycle();
        alloc(sw, sh);
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            int y = (r * 77 + g * 150 + bl * 29) >> 8;
            img[i] = y; cu[i] = ((bl - y) * 144) >> 8; cv[i] = ((r - y) * 183) >> 8;   // ≈ YUV scaling
        }
        colour = true;
    }

    private void alloc(int nw, int nh) {
        w = Math.max(8, nw); h = Math.max(8, nh);
        int n = w * h;
        if (img == null || img.length < n) {
            img = new int[n]; tmp = new int[n]; mag = new int[n]; cu = new int[n]; cv = new int[n]; pap = new int[n];
            dir = new byte[n]; edge = new byte[n]; near = new byte[n]; mask = new byte[n]; stack = new int[n];
        }
    }

    private float[] run() {
        conf = 0;
        prepare();
        return search();
    }

    /** Blurs the loaded channels in place and derives the paperness map (brightness minus saturation). */
    private void prepare() {
        blur(img);
        int n = w * h;
        if (colour) {
            blur(cu); blur(cv);
            for (int i = 0; i < n; i++) pap[i] = Math.max(0, img[i] - 2 * (Math.abs(cu[i]) + Math.abs(cv[i])));
        } else System.arraycopy(img, 0, pap, 0, n);
    }

    /** Edges, then both candidates; the better scored one in normalised, ordered form, or null. */
    private float[] search() {
        conf = 0;
        int edges = canny();
        if (edges < 30) return null;
        best = 0; found = false;
        hough();
        region();
        if (!found) return null;
        float per = 0;
        for (int i = 0; i < 4; i++) { int a = i * 2, b = ((i + 1) % 4) * 2; per += (float) Math.hypot(bestQ[b] - bestQ[a], bestQ[b + 1] - bestQ[a + 1]); }
        conf = per > 0 ? best / per : 0;
        float[] out = order(bestQ);
        for (int i = 0; i < 8; i += 2) {
            out[i] = clamp01((out[i] + 0.5f) / w);
            out[i + 1] = clamp01((out[i + 1] + 0.5f) / h);
        }
        return out;
    }

    /** Keeps a candidate (pixel corners, cyclic) when it beats the best so far. */
    private void offer(float[] q, float s) {
        if (s > best && (!imported || plausible(q, s))) { best = s; System.arraycopy(q, 0, bestQ, 0, 8); found = true; }
    }

    /** Separable [1 4 6 4 1] / 16 kernel, clamped borders. */
    private void blur(int[] img) {
        for (int y = 0; y < h; y++) {
            int r = y * w;
            for (int x = 0; x < w; x++) {
                int a = img[r + Math.max(0, x - 2)], b = img[r + Math.max(0, x - 1)], c = img[r + x];
                int d = img[r + Math.min(w - 1, x + 1)], e = img[r + Math.min(w - 1, x + 2)];
                tmp[r + x] = (a + 4 * b + 6 * c + 4 * d + e) >> 4;
            }
        }
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - 2) * w, yb = Math.max(0, y - 1) * w, yc = y * w, yd = Math.min(h - 1, y + 1) * w, ye = Math.min(h - 1, y + 2) * w;
            for (int x = 0; x < w; x++)
                img[yc + x] = (tmp[ya + x] + 4 * tmp[yb + x] + 6 * tmp[yc + x] + 4 * tmp[yd + x] + tmp[ye + x]) >> 4;
        }
    }

    /** @return number of edge pixels in {@link #edge}; {@link #near} gets the edges dilated by one pixel. */
    private int canny() {
        int n = w * h;
        java.util.Arrays.fill(mag, 0, n, 0);
        int[] hist = new int[2048];
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int gx = sx(img, i), gy = sy(img, i);
                int m = Math.abs(gx) + Math.abs(gy);
                if (colour) {   // the strongest channel gives magnitude and direction
                    int ux = sx(cu, i), uy = sy(cu, i), mu = (Math.abs(ux) + Math.abs(uy)) * CHROMA_K;
                    if (mu > m) { m = mu; gx = ux; gy = uy; }
                    int vx = sx(cv, i), vy = sy(cv, i), mv = (Math.abs(vx) + Math.abs(vy)) * CHROMA_K;
                    if (mv > m) { m = mv; gx = vx; gy = vy; }
                }
                int ax = Math.abs(gx), ay = Math.abs(gy);
                mag[i] = m;
                // direction of the gradient, quantised: 0 = horizontal, 1 = 45°, 2 = vertical, 3 = 135°
                byte d;
                if (ay * 5 < ax * 2) d = 0;                 // < ~22°
                else if (ax * 5 < ay * 2) d = 2;            // > ~68°
                else d = (byte) ((gx > 0) == (gy > 0) ? 1 : 3);
                dir[i] = d;
                if (m > 0) { hist[Math.min(2047, m)]++; count++; }
            }
        }
        if (count == 0) return 0;
        // thresholds from the magnitude distribution: strong = top 8 %, with an absolute floor against sensor noise
        int target = (int) (count * 0.92), run = 0, high = 2047;
        for (int v = 0; v < 2048; v++) { run += hist[v]; if (run >= target) { high = v; break; } }
        high = Math.max(Math.min(high, seedCap), 64);
        int low = Math.max(24, high * 2 / 5);

        // non-maximum suppression → candidates in edge[] (1 = weak, 2 = strong)
        java.util.Arrays.fill(edge, 0, n, (byte) 0);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x, m = mag[i];
                if (m < low) continue;
                int a, b;
                switch (dir[i]) {
                    case 0: a = mag[i - 1]; b = mag[i + 1]; break;
                    case 2: a = mag[i - w]; b = mag[i + w]; break;
                    case 1: a = mag[i - w - 1]; b = mag[i + w + 1]; break;
                    default: a = mag[i - w + 1]; b = mag[i + w - 1]; break;
                }
                if (m >= a && m > b) edge[i] = (byte) (m >= high ? 2 : 1);
            }
        }
        // hysteresis: weak pixels survive only when connected to a strong one
        int sp = 0;
        for (int i = 0; i < n; i++) if (edge[i] == 2) { edge[i] = 3; stack[sp++] = i; }
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy; if (yy < 0 || yy >= h) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx; if (xx < 0 || xx >= w) continue;
                    int j = yy * w + xx;
                    if (edge[j] == 1) { edge[j] = 3; stack[sp++] = j; }
                }
            }
        }
        int edges = 0;
        java.util.Arrays.fill(near, 0, n, (byte) 0);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (edge[i] != 3) { edge[i] = 0; continue; }
                edge[i] = 1; edges++;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) { int xx = x + dx; if (xx >= 0 && xx < w) near[yy * w + xx] = 1; }
                }
            }
        }
        return edges;
    }

    private int sx(int[] a, int i) { return (a[i - w + 1] + 2 * a[i + 1] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - 1] + a[i + w - 1]); }

    private int sy(int[] a, int i) { return (a[i + w - 1] + 2 * a[i + w] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - w] + a[i - w + 1]); }

    private void hough() {
        int diag = (int) Math.ceil(Math.sqrt((double) w * w + (double) h * h));
        int rhos = 2 * diag + 1;
        int size = THETAS * rhos;
        if (acc == null || acc.length < size) acc = new int[size];
        java.util.Arrays.fill(acc, 0, size, 0);
        int icx = w / 2, icy = h / 2;      // rho measured from the centre keeps the range small
        float cx = icx, cy = icy;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (edge[y * w + x] == 0) continue;
                int fx = x - icx, fy = y - icy;
                for (int t = 0, o = diag; t < THETAS; t++, o += rhos)
                    acc[o + ((fx * ICOS[t] + fy * ISIN[t] + 512) >> 10)]++;
            }
        }
        // peaks: local maxima in a ±3 θ / ±4 ρ window, at least a sixth of the shorter side
        int minVotes = Math.max(12, Math.min(w, h) / 6);
        final int MAXL = 24;
        int[] lt = new int[MAXL], lr = new int[MAXL], lv = new int[MAXL];
        int nl = 0;
        for (int t = 0; t < THETAS; t++) {
            for (int r = 0; r < rhos; r++) {
                int v = acc[t * rhos + r];
                if (v < minVotes) continue;
                boolean max = true;
                for (int dt = -3; dt <= 3 && max; dt++) {
                    int tt = t + dt, rr0 = r;
                    if (tt < 0) { tt += THETAS; rr0 = 2 * diag - r; } else if (tt >= THETAS) { tt -= THETAS; rr0 = 2 * diag - r; }
                    for (int dr = -4; dr <= 4; dr++) {
                        int rr = rr0 + dr;
                        if (rr < 0 || rr >= rhos || (dt == 0 && dr == 0)) continue;
                        int o = acc[tt * rhos + rr];
                        if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { max = false; break; }
                    }
                }
                if (!max) continue;
                // keep the strongest MAXL
                int pos = nl < MAXL ? nl++ : MAXL - 1;
                if (pos == MAXL - 1 && nl == MAXL && lv[pos] >= v) continue;
                lt[pos] = t; lr[pos] = r - diag; lv[pos] = v;
                for (int k = pos; k > 0 && lv[k] > lv[k - 1]; k--) {
                    int a = lt[k]; lt[k] = lt[k - 1]; lt[k - 1] = a;
                    a = lr[k]; lr[k] = lr[k - 1]; lr[k - 1] = a;
                    a = lv[k]; lv[k] = lv[k - 1]; lv[k - 1] = a;
                }
            }
        }
        if (nl < 4) return;

        // split into two families by angle relative to the strongest line
        int t0 = lt[0];
        int[] ga = new int[nl], gb = new int[nl];
        int na = 0, nb = 0;
        for (int i = 0; i < nl; i++) {
            int d = Math.abs(lt[i] - t0); d = Math.min(d, THETAS - d);
            if (d < 45) ga[na++] = i; else gb[nb++] = i;
        }
        if (na < 2 || nb < 2) return;

        float minSide = Math.min(w, h) * 0.15f, minArea = w * h * 0.10f;
        float[] q = cand;
        for (int a1 = 0; a1 < na; a1++) for (int a2 = a1 + 1; a2 < na; a2++) {
            int i1 = ga[a1], i2 = ga[a2];
            if (!separated(lt[i1], lr[i1], lt[i2], lr[i2], minSide)) continue;
            for (int b1 = 0; b1 < nb; b1++) for (int b2 = b1 + 1; b2 < nb; b2++) {
                int j1 = gb[b1], j2 = gb[b2];
                if (!separated(lt[j1], lr[j1], lt[j2], lr[j2], minSide)) continue;
                if (!cross(lt[i1], lr[i1], lt[j1], lr[j1], q, 0, cx, cy)) continue;
                if (!cross(lt[i1], lr[i1], lt[j2], lr[j2], q, 2, cx, cy)) continue;
                if (!cross(lt[i2], lr[i2], lt[j2], lr[j2], q, 4, cx, cy)) continue;
                if (!cross(lt[i2], lr[i2], lt[j1], lr[j1], q, 6, cx, cy)) continue;
                offer(q, score(q, minSide, minArea));
            }
        }
    }

    /**
     * Region candidates: Otsu split of the paperness map, and once more within its bright class (text can be the dark
     * class, leaving page and light desk together). For each: the largest bright blob reaching into the centre ninth, its
     * convex hull (row extremes, monotone chain), the hull thinned to at most 12 vertices (flattest first), and of those the
     * four spanning the largest area.
     */
    private void region() {
        int n = w * h;
        int[] hist = hist256;
        java.util.Arrays.fill(hist, 0);
        for (int i = 0; i < n; i++) hist[Math.min(255, pap[i])]++;
        int t1 = otsu(hist, 0);
        if (t1 < 0) return;
        blob(t1);
        int t2 = otsu(hist, t1 + 1);
        if (t2 >= 0) blob(t2);
    }

    /** Otsu threshold over the bins above {@code lo}; -1 when the two classes are not clearly apart. */
    private static int otsu(int[] hist, int lo) {
        long sumAll = 0; int total = 0;
        for (int t = lo; t < 256; t++) { sumAll += (long) t * hist[t]; total += hist[t]; }
        long sumB = 0; int wB = 0, thr = -1; double bestVar = 0, gap = 0;
        for (int t = lo; t < 255; t++) {
            wB += hist[t]; sumB += (long) t * hist[t];
            int wF = total - wB;
            if (wB == 0) continue;
            if (wF == 0) break;
            double mB = sumB / (double) wB, mF = (sumAll - sumB) / (double) wF, var = (double) wB * wF * (mF - mB) * (mF - mB);
            if (var > bestVar) { bestVar = var; thr = t; gap = mF - mB; }
        }
        return gap >= 24 ? thr : -1;
    }

    private void blob(int thr) {
        int n = w * h;
        for (int i = 0; i < n; i++) mask[i] = (byte) (pap[i] > thr ? 1 : 0);
        int bestSize = 0, seed = -1;
        for (int y = h / 3; y < h - h / 3; y++) for (int x = w / 3; x < w - w / 3; x++) {
            int i = y * w + x;
            if (mask[i] != 1) continue;
            int size = fill(i, (byte) 1, (byte) 2);
            if (size > bestSize) { bestSize = size; seed = i; }
        }
        if (seed < 0 || bestSize < n / 10) return;
        fill(seed, (byte) 2, (byte) 3);
        if (hx == null || hx.length < 4 * h + 2) { hx = new int[4 * h + 2]; hy = new int[4 * h + 2]; }
        // row extremes, already in (y, x) order; the monotone chain then gives the hull in hx/hy from index 2h + 1 on
        int np = 0;
        for (int y = 0; y < h; y++) {
            int l = -1, r = -1;
            for (int x = 0, o = y * w; x < w; x++) if (mask[o + x] == 3) { if (l < 0) l = x; r = x; }
            if (l < 0) continue;
            hx[np] = l; hy[np++] = y;
            if (r != l) { hx[np] = r; hy[np++] = y; }
        }
        int base = 2 * h + 1, k = 0;
        for (int i = 0; i < np; i++) {                                   // one half
            while (k >= 2 && turn(base + k - 2, base + k - 1, hx[i], hy[i]) <= 0) k--;
            hx[base + k] = hx[i]; hy[base + k++] = hy[i];
        }
        for (int i = np - 2, lo = k + 1; i >= 0; i--) {                  // the other half
            while (k >= lo && turn(base + k - 2, base + k - 1, hx[i], hy[i]) <= 0) k--;
            hx[base + k] = hx[i]; hy[base + k++] = hy[i];
        }
        int m = k - 1;                                                   // last point repeats the first
        if (m < 4) return;
        while (m > 12) {                                                 // drop the vertex spanning the smallest triangle
            int drop = 0; long min = Long.MAX_VALUE;
            for (int i = 0; i < m; i++) {
                long t = Math.abs(area2(base + (i + m - 1) % m, base + i, base + (i + 1) % m));
                if (t < min) { min = t; drop = i; }
            }
            System.arraycopy(hx, base + drop + 1, hx, base + drop, m - drop - 1);
            System.arraycopy(hy, base + drop + 1, hy, base + drop, m - drop - 1);
            m--;
        }
        long bestA = 0; int b0 = 0, b1 = 1, b2 = 2, b3 = 3;              // largest quadrilateral on the hull vertices
        for (int a = 0; a < m; a++) for (int b = a + 1; b < m; b++) for (int c = b + 1; c < m; c++) for (int d = c + 1; d < m; d++) {
            long ar = Math.abs(area2(base + a, base + b, base + c) + area2(base + a, base + c, base + d));
            if (ar > bestA) { bestA = ar; b0 = a; b1 = b; b2 = c; b3 = d; }
        }
        float[] q = cand;
        q[0] = hx[base + b0]; q[1] = hy[base + b0]; q[2] = hx[base + b1]; q[3] = hy[base + b1];
        q[4] = hx[base + b2]; q[5] = hy[base + b2]; q[6] = hx[base + b3]; q[7] = hy[base + b3];
        offer(q, score(q, Math.min(w, h) * 0.15f, w * h * 0.10f));
    }

    /** Twice the signed area of the triangle of hull points a, b, c (indices into hx/hy). */
    private long area2(int a, int b, int c) {
        return (long) (hx[b] - hx[a]) * (hy[c] - hy[a]) - (long) (hy[b] - hy[a]) * (hx[c] - hx[a]);
    }

    /** Cross product of (b − a) × (p − a) for hull points a, b (indices into hx/hy). */
    private long turn(int a, int b, int px, int py) {
        return (long) (hx[b] - hx[a]) * (py - hy[a]) - (long) (hy[b] - hy[a]) * (px - hx[a]);
    }

    /** 4-connected flood fill of {@code from} pixels in {@link #mask}, relabelled {@code to}; returns the size. */
    private int fill(int seed, byte from, byte to) {
        int sp = 0, size = 0;
        mask[seed] = to; stack[sp++] = seed;
        while (sp > 0) {
            int i = stack[--sp], x = i % w;
            size++;
            if (x > 0 && mask[i - 1] == from) { mask[i - 1] = to; stack[sp++] = i - 1; }
            if (x < w - 1 && mask[i + 1] == from) { mask[i + 1] = to; stack[sp++] = i + 1; }
            if (i >= w && mask[i - w] == from) { mask[i - w] = to; stack[sp++] = i - w; }
            if (i + w < w * h && mask[i + w] == from) { mask[i + w] = to; stack[sp++] = i + w; }
        }
        return size;
    }

    /**
     * Refines a found outline (normalised, ordered) on the bitmap it came from: per side, at up to {@link #REFINE_SAMPLES}
     * points between 10 % and 90 % of its length, the strongest luma/chroma step on a short axis-aligned profile across the
     * side (± two detection pixels); a weighted total-least-squares line through those steps (one outlier pass), then the
     * corners re-intersected. A side without enough steps, or a corner moving too far, keeps the coarse outline.
     */
    private float[] refine(Bitmap b, float[] qn) {
        int bw = b.getWidth(), bh = b.getHeight();
        float s = Math.max(bw / (float) w, bh / (float) h);
        if (s < 1.5f) return qn;
        int r = (int) Math.ceil(2 * s), len = 2 * r + 1;
        int[] prof = new int[len + 2];
        float[] px = new float[REFINE_SAMPLES], py = new float[REFINE_SAMPLES], pw = new float[REFINE_SAMPLES];
        float[] c = new float[8];
        for (int i = 0; i < 8; i += 2) { c[i] = qn[i] * bw; c[i + 1] = qn[i + 1] * bh; }
        float[] lines = new float[16];
        for (int side = 0; side < 4; side++) {
            int a = side * 2, e = ((side + 1) % 4) * 2;
            float ax = c[a], ay = c[a + 1], dx = c[e] - ax, dy = c[e + 1] - ay;
            float l = (float) Math.hypot(dx, dy);
            boolean vert = Math.abs(dx) >= Math.abs(dy);   // profile runs along y for a mostly horizontal side
            int k = 0;
            for (int j = 0; j < REFINE_SAMPLES; j++) {
                float t = 0.1f + 0.8f * (j + 0.5f) / REFINE_SAMPLES, x = ax + dx * t, y = ay + dy * t;
                int x0 = Math.round(x), y0 = Math.round(y);
                int sx0 = vert ? x0 : x0 - r - 1, sy0 = vert ? y0 - r - 1 : y0;
                if (sx0 < 0 || sy0 < 0 || (vert ? sx0 >= bw || sy0 + len + 2 > bh : sx0 + len + 2 > bw || sy0 >= bh)) continue;
                if (vert) b.getPixels(prof, 0, 1, sx0, sy0, 1, len + 2); else b.getPixels(prof, 0, len + 2, sx0, sy0, len + 2, 1);
                int bestG = 0, at = -1;
                for (int p = 1; p <= len; p++) {
                    int g = step(prof[p - 1], prof[p + 1]);
                    if (g > bestG) { bestG = g; at = p; }
                }
                if (at < 0 || bestG < 24) continue;
                float off = at;
                if (at > 1 && at < len) {   // parabolic sub-pixel peak
                    float g0 = step(prof[at - 2], prof[at]), g2 = step(prof[at], prof[at + 2]), den = g0 - 2 * bestG + g2;
                    if (den < 0) off += 0.5f * (g0 - g2) / den;
                }
                float o = off - r - 1;
                px[k] = vert ? x0 : x0 + o; py[k] = vert ? y0 + o : y0; pw[k] = bestG; k++;
            }
            if (k < REFINE_SAMPLES / 2 || !fitLine(px, py, pw, k, s, lines, side * 4)) {
                lines[side * 4] = ax; lines[side * 4 + 1] = ay; lines[side * 4 + 2] = dx / l; lines[side * 4 + 3] = dy / l;
            }
        }
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {   // corner i lies on side i − 1 and side i
            int p = ((i + 3) % 4) * 4, q = i * 4;
            float d1x = lines[p + 2], d1y = lines[p + 3], d2x = lines[q + 2], d2y = lines[q + 3];
            float det = d1x * d2y - d1y * d2x;
            if (Math.abs(det) < 0.25f) return qn;
            float t = ((lines[q] - lines[p]) * d2y - (lines[q + 1] - lines[p + 1]) * d2x) / det;
            float x = lines[p] + d1x * t, y = lines[p + 1] + d1y * t;
            if (Math.hypot(x - c[i * 2], y - c[i * 2 + 1]) > 3 * s) return qn;
            out[i * 2] = clamp01(x / bw); out[i * 2 + 1] = clamp01(y / bh);
        }
        return out;
    }

    private static final int REFINE_SAMPLES = 64;

    /** Colour step between two ARGB pixels: the largest of |ΔY|, k·|ΔU|, k·|ΔV| (same scaling as the detection). */
    private static int step(int p, int q) {
        int r1 = (p >> 16) & 0xFF, g1 = (p >> 8) & 0xFF, b1 = p & 0xFF, r2 = (q >> 16) & 0xFF, g2 = (q >> 8) & 0xFF, b2 = q & 0xFF;
        int y1 = (r1 * 77 + g1 * 150 + b1 * 29) >> 8, y2 = (r2 * 77 + g2 * 150 + b2 * 29) >> 8;
        int dy = Math.abs(y1 - y2);
        int du = Math.abs(((b1 - y1) - (b2 - y2)) * 144 >> 8) * CHROMA_K, dv = Math.abs(((r1 - y1) - (r2 - y2)) * 183 >> 8) * CHROMA_K;
        return Math.max(dy, Math.max(du, dv));
    }

    /** Weighted total-least-squares line into out[o..o+3] (point, unit direction); a second pass drops far points. */
    private static boolean fitLine(float[] x, float[] y, float[] wt, int n, float s, float[] out, int o) {
        float tol = Math.max(1.5f, 0.5f * s);
        for (int pass = 0; pass < 2; pass++) {
            double sw = 0, mx = 0, my = 0;
            for (int i = 0; i < n; i++) if (wt[i] > 0) { sw += wt[i]; mx += wt[i] * x[i]; my += wt[i] * y[i]; }
            if (sw <= 0) return false;
            mx /= sw; my /= sw;
            double sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < n; i++) if (wt[i] > 0) {
                double ex = x[i] - mx, ey = y[i] - my;
                sxx += wt[i] * ex * ex; syy += wt[i] * ey * ey; sxy += wt[i] * ex * ey;
            }
            double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            out[o] = (float) mx; out[o + 1] = (float) my; out[o + 2] = (float) Math.cos(ang); out[o + 3] = (float) Math.sin(ang);
            if (pass == 1) break;
            int kept = 0;
            for (int i = 0; i < n; i++) {
                float dist = Math.abs((x[i] - out[o]) * out[o + 3] - (y[i] - out[o + 1]) * out[o + 2]);
                if (dist > tol) wt[i] = 0; else kept++;
            }
            if (kept < n / 2) return false;
        }
        return true;
    }

    /** Two lines of one family must be far enough apart to be opposite sides. */
    private static boolean separated(int t1, int r1, int t2, int r2, float min) {
        int d = Math.abs(t1 - t2);
        if (d > THETAS / 2) return Math.abs(r1 + r2) >= min;   // nearly parallel across the 0/180 wrap: ρ flips sign
        return Math.abs(r1 - r2) >= min;
    }

    /** Intersection of two (θ, ρ) lines into q[o], q[o+1]; false when parallel or far outside the frame. */
    private boolean cross(int ta, int ra, int tb, int rb, float[] q, int o, float cx, float cy) {
        float a1 = COS[ta], b1 = SIN[ta], a2 = COS[tb], b2 = SIN[tb];
        float det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < 0.25f) return false;   // < ~15° apart
        float x = (ra * b2 - rb * b1) / det + cx, y = (a1 * rb - a2 * ra) / det + cy;
        float mx = w * 0.04f, my = h * 0.04f;
        if (x < -mx || y < -my || x > w - 1 + mx || y > h - 1 + my) return false;
        q[o] = Math.max(0, Math.min(w - 1, x)); q[o + 1] = Math.max(0, Math.min(h - 1, y));
        return true;
    }

    /** Edge-supported perimeter (0 when the shape is implausible). Corners arrive in cyclic order. */
    private float score(float[] q, float minSide, float minArea) {
        // convex, sensible angles
        float sign = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2, c = ((i + 2) % 4) * 2;
            float ux = q[b] - q[a], uy = q[b + 1] - q[a + 1], vx = q[c] - q[b], vy = q[c + 1] - q[b + 1];
            float cr = ux * vy - uy * vx;
            if (sign == 0) sign = Math.signum(cr); else if (Math.signum(cr) != sign || cr == 0) return 0;
            float lu = (float) Math.hypot(ux, uy), lv = (float) Math.hypot(vx, vy);
            if (lu < minSide) return 0;
            float cos = (ux * vx + uy * vy) / (lu * lv);
            if (Math.abs(cos) > 0.72f) return 0;   // interior angle outside ~44°..136°
        }
        float area = 0;
        for (int i = 0; i < 4; i++) { int a = i * 2, b = ((i + 1) % 4) * 2; area += q[a] * q[b + 1] - q[b] * q[a + 1]; }
        if (Math.abs(area) / 2 < minArea) return 0;
        float total = 0, minCov = 1, sumCov = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            float len = (float) Math.hypot(q[b] - q[a], q[b + 1] - q[a + 1]);
            int steps = Math.max(2, (int) len), hit = 0;
            for (int s = 0; s <= steps; s++) {
                float t = s / (float) steps;
                int x = Math.round(q[a] + (q[b] - q[a]) * t), y = Math.round(q[a + 1] + (q[b + 1] - q[a + 1]) * t);
                if (near[y * w + x] != 0) hit++;
            }
            float cov = hit / (float) (steps + 1);
            minCov = Math.min(minCov, cov); sumCov += cov;
            total += cov * len;
        }
        if (minCov < 0.35f || sumCov / 4 < 0.55f) return 0;
        return total;
    }

    /** Cyclic corners → top-left, top-right, bottom-right, bottom-left (image coordinates, y down). */
    public static float[] order(float[] q) {
        float mx = (q[0] + q[2] + q[4] + q[6]) / 4, my = (q[1] + q[3] + q[5] + q[7]) / 4;
        // make the order clockwise on screen
        float area = 0;
        for (int i = 0; i < 4; i++) { int a = i * 2, b = ((i + 1) % 4) * 2; area += q[a] * q[b + 1] - q[b] * q[a + 1]; }
        float[] c = new float[8];
        for (int i = 0; i < 4; i++) {
            int src = area >= 0 ? i : (4 - i) % 4;
            c[i * 2] = q[src * 2]; c[i * 2 + 1] = q[src * 2 + 1];
        }
        // start at the corner up and to the left of the centre (smallest x + y relative to it)
        int start = 0; float bestV = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float v = (c[i * 2] - mx) + (c[i * 2 + 1] - my);
            if (v < bestV) { bestV = v; start = i; }
        }
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) { int k = (start + i) % 4; out[i * 2] = c[k * 2]; out[i * 2 + 1] = c[k * 2 + 1]; }
        return out;
    }

    /** Normalised quad of a frame that is shown rotated clockwise by {@code deg} (0/90/180/270), re-ordered. */
    public static float[] rotate(float[] q, int deg) {
        if (q == null) return null;
        float[] r = new float[8];
        for (int i = 0; i < 8; i += 2) {
            float u = q[i], v = q[i + 1];
            switch (((deg % 360) + 360) % 360) {
                case 90: r[i] = 1 - v; r[i + 1] = u; break;
                case 180: r[i] = 1 - u; r[i + 1] = 1 - v; break;
                case 270: r[i] = v; r[i + 1] = 1 - u; break;
                default: r[i] = u; r[i + 1] = v;
            }
        }
        return order(r);
    }

    /**
     * Raw (unblurred) image: at least three sides look like the page itself. Per side, the 75th percentile of each row
     * parallel to it (middle 80 % of the side) gives a paper profile from the border inwards; the border strip must be
     * close to the paper level of the picture and show no step up to brighter paper further in (a page lying on a light
     * desk does). Background slivers along a side or a dark bar may spoil one side.
     */
    private boolean looksCropped() {
        int[] hist = new int[256];
        int n = 0;
        for (int y = h / 10; y < h - h / 10; y++) for (int x = w / 10; x < w - w / 10; x++) { hist[Math.min(255, img[y * w + x])]++; n++; }
        int paper = 255;
        for (int v = 255, run = 0; v >= 0; v--) { run += hist[v]; if (run >= n / 10) { paper = v; break; } }
        if (paper < PAPER_MIN) return false;
        int sides = 0;
        for (int side = 0; side < 4; side++) if (paperSide(side, paper)) sides++;
        return sides >= 3;
    }

    /** Side 0 top, 1 bottom, 2 left, 3 right. */
    private boolean paperSide(int side, int paper) {
        boolean horiz = side < 2;
        int len = horiz ? w : h, depth = horiz ? h : w;
        int strip = Math.max(2, Math.round(depth * STRIP)), far = Math.max(strip + 2, depth / 5);
        int[] hist = new int[256];
        float border = 0, inner = 0;
        for (int d = 0; d < far; d++) {
            java.util.Arrays.fill(hist, 0);
            int cnt = 0;
            for (int t = len / 10; t < len - len / 10; t++) {
                int r = side == 1 || side == 3 ? depth - 1 - d : d;
                hist[Math.min(255, img[horiz ? r * w + t : t * w + r])]++; cnt++;
            }
            int v = 255;
            for (int k = 255, run = 0; k >= 0; k--) { run += hist[k]; if (run >= cnt / 4) { v = k; break; } }
            if (d < strip) border += v / (float) strip; else inner = Math.max(inner, v);
        }
        return border >= paper - PAPER_TOL && border >= inner - STEP_TOL;
    }

    /**
     * Import candidate (pixel corners in cyclic order, edge score {@code s}): edge coverage ≥ {@link #MIN_CONF}, area ≥
     * {@link #MIN_AREA} of the picture, and on a background: along each side a little outside is darker than a little
     * inside by {@link #STEP} (on the blurred image; sides on the picture border are not measurable). All measurable sides
     * but at most one, and at least two.
     */
    private boolean plausible(float[] q, float score) {
        float per = 0, area = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            per += (float) Math.hypot(q[b] - q[a], q[b + 1] - q[a + 1]);
            area += q[a] * q[b + 1] - q[b] * q[a + 1];
        }
        if (score < MIN_CONF * per || Math.abs(area) / 2 < MIN_AREA * w * h) return false;
        float d = Math.max(3, Math.min(w, h) * 0.025f);
        float mx = (q[0] + q[2] + q[4] + q[6]) / 4, my = (q[1] + q[3] + q[5] + q[7]) / 4;
        int measurable = 0, edges = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            float ax = q[a], ay = q[a + 1], bx = q[b], by = q[b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 1) continue;
            float nx = (by - ay) / len, ny = -(bx - ax) / len;                       // a normal; turned to point outwards
            if (nx * ((ax + bx) / 2 - mx) + ny * ((ay + by) / 2 - my) < 0) { nx = -nx; ny = -ny; }
            long sin = 0, sout = 0; int k = 0;
            for (int s = 1; s < 20; s++) {
                float t = 0.1f + 0.8f * s / 20f, px = ax + (bx - ax) * t, py = ay + (by - ay) * t;
                int ox = Math.round(px + nx * d), oy = Math.round(py + ny * d), ix = Math.round(px - nx * d), iy = Math.round(py - ny * d);
                if (ox < 1 || oy < 1 || ox >= w - 1 || oy >= h - 1 || ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                sout += img[oy * w + ox]; sin += img[iy * w + ix]; k++;
            }
            if (k < 10) continue;
            measurable++;
            if ((sin - sout) / (float) k >= STEP) edges++;
        }
        return edges >= 2 && edges >= measurable - 1;
    }

    /** The whole image as a quad. */
    public static float[] full() { return new float[]{0, 0, 1, 0, 1, 1, 0, 1}; }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }
}
