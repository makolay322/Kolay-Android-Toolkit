package app.kolay.app.screens;

import android.view.*;
import android.widget.*;
import app.kolay.app.tools.Tool;
import app.kolay.app.ui.*;
import java.util.ArrayList;
import java.util.List;

/** One home card opened: its tools as full-width rows. Back returns home; back from a tool returns here. */
final class GroupScreen extends Screen {
    private final String title, subtitle;
    private final Tool[] tools;
    private final List<View> rows = new ArrayList<>();

    GroupScreen(String title, String subtitle, Tool[] tools) { this.title = title; this.subtitle = subtitle; this.tools = tools; }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, title, subtitle, () -> c.nav.pop()).view);
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(24));
        for (int i = 0; i < tools.length; i++) {
            View row = row(tools[i], i);
            row.setAlpha(0f);
            rows.add(row);
            col.addView(row, Ui.lp(-1, c.dp(76), 0, 0, 0, c.dp(12)));
        }
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    /** Icon badge, title and subtitle, chevron. */
    private View row(Tool t, int idx) {
        Palette p = c.p;
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(c.dp(14), 0, c.dp(16), 0);
        Ui.surface(c, row, p.surface, 22, true);
        row.setClickable(true);
        FrameLayout badge = new FrameLayout(c.a);
        badge.setBackground(Ui.rounded(c, p.softBg(idx), 14));
        badge.addView(Ui.icon(c, t.icon(), p.softFg(idx), 22), new FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(c.dp(46), c.dp(46)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, t.title(), 16, p.ink, 600);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        TextView sub = Ui.text(c, t.subtitle(), 13, p.muted, 400);
        sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(sub, Ui.lp(-1, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(14));
        row.addView(txt, tl);
        row.addView(Ui.icon(c, Icon.Kind.CHEVRON, p.muted, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        row.setContentDescription(t.title() + ". " + t.subtitle());
        row.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(t.open(c)); });
        Ui.press(row);
        return row;
    }

    @Override public void onShown() {
        long d = 40;
        for (View v : rows) { Ui.appear(v, d); d += 55; }
    }
}
