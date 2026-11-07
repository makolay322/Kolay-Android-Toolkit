package app.kolay.app.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared page header: back button, title and a (swappable) subtitle. */
public final class Header {
    public final LinearLayout view;
    public final TextView subtitle;

    public Header(Ctx c, String title, String sub, Runnable onBack) { this(c, title, sub, onBack, null); }

    public Header(Ctx c, String title, String sub, Runnable onBack, View trailing) {
        view = new LinearLayout(c.a);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(c.dp(Ui.GUTTER), c.dp(12), c.dp(Ui.GUTTER), c.dp(14));
        view.addView(Ui.iconButton(c, Icon.Kind.BACK, "Geri", onBack));
        LinearLayout t = new LinearLayout(c.a);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView tt = Ui.text(c, title, Ui.T_TITLE, c.p.ink, 800);
        tt.setLetterSpacing(-0.01f);
        tt.setSingleLine(true); tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(tt);
        subtitle = Ui.text(c, sub, 13, c.p.muted, 400);
        subtitle.setMaxLines(2); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(subtitle, Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(c.dp(14));
        view.addView(t, lp);
        if (trailing != null) { view.addView(trailing); ((LinearLayout.LayoutParams) trailing.getLayoutParams()).setMarginStart(c.dp(8)); }
    }
}
