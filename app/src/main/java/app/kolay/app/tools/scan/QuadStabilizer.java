package app.kolay.app.tools.scan;

/**
 * Steadies the live page outline (normalised 8-float quads, UI thread only, no allocation per frame): per-coordinate median
 * of the last {@link #N} agreeing frames, shown once {@link #MIN} of them arrived; held for {@link #HOLD_MS} when frames
 * fail or disagree once; restarted when the outline moves by more than {@link #JUMP} in two frames running (a new page).
 */
final class QuadStabilizer {
    private static final int N = 5, MIN = 3;
    private static final long HOLD_MS = 500;
    private static final float JUMP = 0.08f;

    private final float[][] ring = new float[N][8];
    private final float[] out = new float[8], col = new float[N];
    private int count, next, misses;
    private long lastSeen;

    /** One detection result (null = nothing found); returns the outline to show (reused array) or null. */
    float[] update(float[] q, long now) {
        if (q != null && count > 0 && dist(q, out) > JUMP && ++misses < 2) q = null;   // one odd frame: hold
        if (q == null) {
            if (count > 0 && now - lastSeen > HOLD_MS) reset();
            return shown();
        }
        if (misses >= 2) reset();   // the outline moved for good
        misses = 0;
        lastSeen = now;
        System.arraycopy(q, 0, ring[next], 0, 8);
        next = (next + 1) % N;
        if (count < N) count++;
        for (int i = 0; i < 8; i++) {
            for (int k = 0; k < count; k++) {   // insertion sort of this coordinate
                float v = ring[k][i];
                int j = k;
                while (j > 0 && col[j - 1] > v) { col[j] = col[j - 1]; j--; }
                col[j] = v;
            }
            out[i] = (count & 1) == 1 ? col[count / 2] : (col[count / 2 - 1] + col[count / 2]) / 2;
        }
        return shown();
    }

    void reset() { count = 0; next = 0; misses = 0; }

    private float[] shown() { return count >= MIN ? out : null; }

    static float dist(float[] a, float[] b) {
        float m = 0;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }
}
