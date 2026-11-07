package app.kolay.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Thin determinate progress bar whose fill glides to each new value. */
public final class Bar extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float shown, target;
    private ValueAnimator anim;

    public Bar(Context c, int trackColor, int fillColor) { super(c); track.setColor(trackColor); fill.setColor(fillColor); }

    public void set(float v) {
        target = Math.max(0, Math.min(1, v));
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target); anim.setDuration(Ui.D_SLOW); anim.setInterpolator(Ui.STD);
        anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() { if (anim != null) anim.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        float h = getHeight(), rad = h / 2f;
        r.set(0, 0, getWidth(), h); c.drawRoundRect(r, rad, rad, track);
        float w = Math.max(h, getWidth() * shown);
        r.set(0, 0, w, h); c.drawRoundRect(r, rad, rad, fill);
    }
}
