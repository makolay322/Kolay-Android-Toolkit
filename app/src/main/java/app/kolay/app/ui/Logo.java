package app.kolay.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;
import java.util.Calendar;

/**
 * The animated "Ko" mark. Like a clock face it follows the time of day: the gradient walks through dawn pink and blue,
 * daytime teal, sunset coral and violet, night indigo. Idle animation while visible: every few seconds the O spins once
 * around its vertical axis like a tossed coin, a soft sheen turns slowly and the dot breathes. No entrance animation.
 */
public final class Logo extends View {
    // Geometry in the 768-unit artboard of the source logo.
    private static final float L = 168, T = 222, R = 600, B = 545;
    private static final float DOT_X = 353, DOT_Y = 284, DOT_R = 12, O_X = 497, O_Y = 388, O_R = 102, O_IN = 43;

    /** Time-of-day keyframes: hour → color. */
    private static final float[] HOURS = {0, 5, 6.5f, 8.5f, 10, 15, 17, 18.5f, 20, 22, 24};
    private static final int[] COLORS = {0xFF5B5BD6, 0xFF7C4DCC, 0xFFD86FA8, 0xFF4C8FD9, 0xFF1596B0, 0xFF1596B0, 0xFF3F7FD0, 0xFFD9607A, 0xFFA64DB8, 0xFF5B5BD6, 0xFF5B5BD6};

    /** Idle cycle: the O spins during the first SPIN_MS of every LOOP_MS, then rests. */
    private static final long LOOP_MS = 6000, SPIN_MS = 1400;

    private final boolean dark;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path k = new Path(), o = new Path();
    private final Matrix shaderM = new Matrix();
    private ValueAnimator loop;
    private float phase;          // 0..1 over LOOP_MS
    private int lastMinute = -1, c1, c2;
    private LinearGradient shader;

    public Logo(Context ctx, boolean dark) {
        super(ctx);
        this.dark = dark;
        o.setFillType(Path.FillType.EVEN_ODD);
        o.addCircle(O_X, O_Y, O_R, Path.Direction.CW);
        o.addCircle(O_X, O_Y, O_IN, Path.Direction.CW);
        k.setFillType(Path.FillType.EVEN_ODD);
        setContentDescription("Kolay-Android-Toolkit");
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        float aspect = (R - L) / (B - T);
        int height = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? Math.round(width / aspect) : MeasureSpec.getSize(h);
        setMeasuredDimension(width, height);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) startLoop(); else stopLoop();
    }

    @Override protected void onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow(); }

    private void startLoop() {
        if (loop != null) return;
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(LOOP_MS);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
        loop.start();
    }

    private void stopLoop() { if (loop != null) { loop.cancel(); loop = null; } }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w / (R - L), h / (B - T));
        cv.save();
        cv.translate((w - (R - L) * s) / 2f - L * s, (h - (B - T) * s) / 2f - T * s);
        cv.scale(s, s);

        updateColors();
        // The sheen: the gradient axis turns once per loop around the mark's center.
        shaderM.setRotate(phase * 360f, (L + R) / 2f, (T + B) / 2f);
        shader.setLocalMatrix(shaderM);
        paint.setShader(shader);

        // K with the breathing dot (two breaths per cycle).
        float breathe = 1f + 0.22f * (float) Math.sin(phase * Math.PI * 4);
        k.rewind();
        k.moveTo(168, 222); k.lineTo(248, 222); k.lineTo(248, 328); k.lineTo(355, 222); k.lineTo(470, 222); k.lineTo(310, 383);
        k.lineTo(470, 545); k.lineTo(355, 545); k.lineTo(248, 440); k.lineTo(248, 545); k.lineTo(168, 545); k.close();
        k.addCircle(DOT_X, DOT_Y, DOT_R * breathe, Path.Direction.CW);
        cv.drawPath(k, paint);

        // O: one full turn around its vertical axis (eased), drawn as a horizontal squeeze; the far side is a little darker.
        float spin = easeInOut(clamp(phase * LOOP_MS / (float) SPIN_MS));
        double a = spin * 2 * Math.PI;
        float sx = (float) Math.cos(a);
        if (Math.abs(sx) < 0.04f) sx = Math.copySign(0.04f, sx == 0 ? 1 : sx);   // stays visible edge-on
        paint.setAlpha(sx < 0 ? 200 : 255);
        cv.save();
        cv.scale(sx, 1f, O_X, O_Y);
        cv.drawPath(o, paint);
        cv.restore();
        paint.setAlpha(255);
        cv.restore();
    }

    /** Recomputes the gradient when the minute changes (the "clock" part). */
    private void updateColors() {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (minute == lastMinute && shader != null) return;
        lastMinute = minute;
        float hour = minute / 60f;
        c1 = lift(at(hour));
        c2 = lift(at((hour + 2f) % 24f));
        shader = new LinearGradient(L, T, R, B, c1, c2, Shader.TileMode.CLAMP);
    }

    private static int at(float hour) {
        int i = 0;
        while (i < HOURS.length - 2 && hour >= HOURS[i + 1]) i++;
        float t = (hour - HOURS[i]) / (HOURS[i + 1] - HOURS[i]);
        return hsvBlend(COLORS[i], COLORS[i + 1], t);
    }

    /** Keyframe blend in HSV along the shorter hue arc, so mixes stay saturated instead of turning grey. */
    private static int hsvBlend(int a, int b, float t) {
        float[] x = new float[3], y = new float[3];
        Color.colorToHSV(a, x); Color.colorToHSV(b, y);
        float dh = y[0] - x[0];
        if (dh > 180) dh -= 360; else if (dh < -180) dh += 360;
        float h = (x[0] + dh * clamp(t) + 360) % 360;
        return Color.HSVToColor(new float[]{h, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t});
    }

    /** Lighter on the dark theme so the mark keeps its contrast. */
    private int lift(int c) { return dark ? blend(c, 0xFFFFFFFF, 0.22f) : c; }

    private static int blend(int a, int b, float t) {
        t = clamp(t);
        return Color.argb(255,
            Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
            Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float easeInOut(float t) { return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }
}
