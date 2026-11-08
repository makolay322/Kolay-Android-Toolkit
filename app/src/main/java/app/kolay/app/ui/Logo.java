package app.kolay.app.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** The plain "Ko" mark in a single color, no background and no animation (same shape as the launcher icon). */
public final class Logo extends View {
    // Geometry in the 768-unit artboard of the source logo.
    private static final float L = 168, T = 222, R = 600, B = 545;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mark = new Path();

    public Logo(Context ctx, int color) {
        super(ctx);
        paint.setColor(color);
        mark.setFillType(Path.FillType.EVEN_ODD);
        mark.moveTo(168, 222); mark.lineTo(248, 222); mark.lineTo(248, 328); mark.lineTo(355, 222); mark.lineTo(470, 222); mark.lineTo(310, 383);
        mark.lineTo(470, 545); mark.lineTo(355, 545); mark.lineTo(248, 440); mark.lineTo(248, 545); mark.lineTo(168, 545); mark.close();
        mark.addCircle(353, 284, 12, Path.Direction.CW);
        mark.addCircle(497, 388, 102, Path.Direction.CW);
        mark.addCircle(497, 388, 43, Path.Direction.CW);
        setContentDescription("Kolay-Android-Toolkit");
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w / (R - L), h / (B - T));
        cv.save();
        cv.translate((w - (R - L) * s) / 2f - L * s, (h - (B - T) * s) / 2f - T * s);
        cv.scale(s, s);
        cv.drawPath(mark, paint);
        cv.restore();
    }
}
