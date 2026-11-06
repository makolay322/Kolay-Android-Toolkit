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
            Tool t = tools[i];
            View row = Ui.navRow(c, t.icon(), t.title(), t.subtitle(), i, () -> c.nav.push(t.open(c)));
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

    @Override public void onShown() {
        long d = 40;
        for (View v : rows) { Ui.appear(v, d); d += 55; }
    }
}
