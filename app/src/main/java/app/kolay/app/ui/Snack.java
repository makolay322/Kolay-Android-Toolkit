package app.kolay.app.ui;

import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Bottom message pill over the current page (inverse colors, rises in, fades out). One at a time; a new one replaces it. */
final class Snack {
    private Snack() {}

    private static final String TAG = "snack";

    static void show(Ctx c, CharSequence msg, boolean longer) {
        if (Looper.myLooper() != Looper.getMainLooper()) { c.ui.post(() -> show(c, msg, longer)); return; }
        if (c.root == null || c.a.isFinishing() || msg == null) return;
        android.view.View old = c.root.findViewWithTag(TAG);
        if (old != null) c.root.removeView(old);
        TextView t = Ui.text(c, msg, 14, c.p.onInverse, 500);
        t.setLineSpacing(0, 1.15f);
        t.setMaxLines(4);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(c.dp(18), c.dp(13), c.dp(18), c.dp(13));
        t.setBackground(Ui.rounded(c, c.p.inverse, 16));
        Ui.roundOutline(c, t, 16);
        Ui.softShadow(c, t, 6, 0xFF000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int side = c.dp(Ui.GUTTER);
        lp.setMargins(side, 0, side, c.insets.bottom + c.dp(24));
        t.setTag(TAG);
        c.root.addView(t, lp);
        t.setAlpha(0f); t.setTranslationY(c.dp(12));
        t.animate().alpha(1f).translationY(0).setDuration(Ui.D_MED).setInterpolator(Ui.EMPH).start();
        t.postDelayed(() -> {
            t.animate().alpha(0f).setDuration(Ui.D_FAST).withEndAction(() -> {
                if (t.getParent() != null) c.root.removeView(t);
            }).start();
        }, longer ? 3500 : 2200);
    }
}
