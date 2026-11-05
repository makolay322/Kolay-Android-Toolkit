package app.kolay.app.doc;

import android.graphics.Bitmap;

/**
 * Finds the outline of a document page in a grayscale image, pure Java: downscale (≤ 360 px) → 5×5 Gaussian → Sobel →
 * Canny (non-maximum suppression + hysteresis) → Hough lines → the best quadrilateral made of two roughly parallel line
 * pairs, scored by how much of its perimeter lies on edges. A few milliseconds on a phone; the work buffers are reused, so one
 * instance must not be used by two threads at once.
 *
 * Result: 8 floats, normalised (0..1) corner coordinates in the order top-left, top-right, bottom-right, bottom-left; or null.
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

    /** Luma plane (e.g. a camera Y plane with {@code stride}); the frame is analysed in its own orientation. */
    public float[] detect(byte[] luma, int stride, int width, int height) {
        int f = Math.max(1, (Math.max(width, height) + TARGET - 1) / TARGET);
        alloc(width / f, height / f);
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
        return run();
    }

    /** Any bitmap (e.g. an imported photo or a captured still); only a small copy is read. */
    public float[] detect(Bitmap b) {
        int bw = b.getWidth(), bh = b.getHeight();
        float k = Math.min(1f, TARGET / (float) Math.max(bw, bh));
        int sw = Math.max(8, Math.round(bw * k)), sh = Math.max(8, Math.round(bh * k));
        Bitmap s = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] px = new int[sw * sh];
        s.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (s != b) s.recycle();
        alloc(sw, sh);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            img[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
        }
        return run();
    }

    private void alloc(int nw, int nh) {
        w = Math.max(8, nw); h = Math.max(8, nh);
        int n = w * h;
        if (img == null || img.length < n) {
            img = new int[n]; tmp = new int[n]; mag = new int[n];
            dir = new byte[n]; edge = new byte[n]; near = new byte[n]; stack = new int[n];
        }
    }

    private float[] run() {
        blur();
        int edges = canny();
        if (edges < 30) return null;
        return hough();
    }

    /** Separable [1 4 6 4 1] / 16 kernel, clamped borders. */
    private void blur() {
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
                int gx = (img[i - w + 1] + 2 * img[i + 1] + img[i + w + 1]) - (img[i - w - 1] + 2 * img[i - 1] + img[i + w - 1]);
                int gy = (img[i + w - 1] + 2 * img[i + w] + img[i + w + 1]) - (img[i - w - 1] + 2 * img[i - w] + img[i - w + 1]);
                int ax = Math.abs(gx), ay = Math.abs(gy);
                int m = ax + ay;
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
        high = Math.max(high, 64);
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

    private float[] hough() {
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
        if (nl < 4) return null;

        // split into two families by angle relative to the strongest line
        int t0 = lt[0];
        int[] ga = new int[nl], gb = new int[nl];
        int na = 0, nb = 0;
        for (int i = 0; i < nl; i++) {
            int d = Math.abs(lt[i] - t0); d = Math.min(d, THETAS - d);
            if (d < 45) ga[na++] = i; else gb[nb++] = i;
        }
        if (na < 2 || nb < 2) return null;

        float best = 0; float[] bestQ = null;
        float minSide = Math.min(w, h) * 0.15f, minArea = w * h * 0.10f;
        float[] q = new float[8];
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
                float s = score(q, minSide, minArea);
                if (s > best) { best = s; bestQ = q.clone(); }
            }
        }
        if (bestQ == null) return null;
        float[] out = order(bestQ);
        for (int i = 0; i < 8; i += 2) {
            out[i] = clamp01((out[i] + 0.5f) / w);
            out[i + 1] = clamp01((out[i + 1] + 0.5f) / h);
        }
        return out;
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

    /** The whole image as a quad. */
    public static float[] full() { return new float[]{0, 0, 1, 0, 1, 1, 0, 1}; }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }
}
