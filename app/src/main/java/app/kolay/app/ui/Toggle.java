package app.kolay.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.function.Consumer;

/** Animated on/off switch (track color blends, thumb springs across). */
public final class Toggle extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int off, on;
    private boolean checked;
    private float t;  // 0 = off, 1 = on
    private Consumer<Boolean> listener;

    public Toggle(Context c, int offColor, int onColor, int thumbColor, boolean checked) {
        super(c);
        off = offColor; on = onColor; thumb.setColor(thumbColor);
        this.checked = checked; t = checked ? 1 : 0;
        setClickable(true);
        setOnClickListener(v -> { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); set(!this.checked, true); if (listener != null) listener.accept(this.checked); });
    }

    public void onChange(Consumer<Boolean> l) { listener = l; }

    public void set(boolean v, boolean animate) {
        checked = v;
        if (!animate) { t = v ? 1 : 0; invalidate(); return; }
        ValueAnimator a = ValueAnimator.ofFloat(t, v ? 1 : 0); a.setDuration(Ui.D_MED); a.setInterpolator(Ui.POP);
        a.addUpdateListener(x -> { t = (float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }

    @Override protected void onMeasure(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(Math.round(52 * d), Math.round(32 * d));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = h * 0.14f, rad = (h - 2 * pad) / 2f;
        float k = Math.max(0, Math.min(1, t));
        track.setColor(blend(off, on, k));
        r.set(0, 0, w, h); c.drawRoundRect(r, h / 2f, h / 2f, track);
        float cx = pad + rad + t * (w - 2 * pad - 2 * rad);
        c.drawCircle(cx, h / 2f, rad + (k > 0.5f ? 0 : 0), thumb);
    }

    private static int blend(int a, int b, float k) {
        return Color.argb(255, (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * k), (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * k), (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }
}
