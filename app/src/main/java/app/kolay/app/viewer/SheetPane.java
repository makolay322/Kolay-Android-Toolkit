package app.kolay.app.viewer;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Ui;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/** Sheet tabs + {@link CellGrid} + a strip showing the tapped cell's full value. Sheets load on first selection. */
final class SheetPane extends LinearLayout {
    interface Loader { Sheet load(int index, Consumer<Sheet> early) throws Exception; }

    private final Ctx c;
    private final List<String> names;
    private final Loader loader;
    private final HashMap<Integer, Sheet> loaded = new HashMap<>();
    private final CellGrid grid;
    private final TextView info;
    private final FrameLayout body;
    private final LinearLayout tabs;
    private int current = -1, generation;
    volatile boolean closed;

    SheetPane(Ctx c, List<String> names, Loader loader) {
        super(c.a);
        this.c = c; this.names = names; this.loader = loader;
        setOrientation(VERTICAL);
        tabs = new LinearLayout(c.a);
        tabs.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(8));
        if (names.size() > 1) {
            HorizontalScrollView hs = new HorizontalScrollView(c.a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(tabs);
            addView(hs, new LayoutParams(-1, -2));
            for (int i = 0; i < names.size(); i++) {
                int idx = i;
                TextView t = Ui.text(c, names.get(i), 14, c.p.ink, 600);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); t.setMaxWidth(c.dp(200));
                t.setPadding(c.dp(14), 0, c.dp(14), 0);
                t.setOnClickListener(v -> show(idx));
                tabs.addView(t, Ui.lp(-2, c.dp(36), 0, 0, c.dp(8), 0));
            }
        }
        info = Ui.text(c, "Hücreye dokun: tam değeri burada görünür.", 13, c.p.muted, 400);
        info.setMaxLines(3); info.setEllipsize(TextUtils.TruncateAt.END);
        info.setPadding(c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(10));
        info.setTextIsSelectable(true);
        grid = new CellGrid(c);
        grid.onCell = (r, col) -> {
            Sheet s = loaded.get(current);
            String v = s == null ? null : s.get(r, col);
            info.setTextColor(v == null ? c.p.muted : c.p.ink);
            info.setText(Sheet.colName(col) + (r + 1) + "   " + (v == null ? "(boş)" : v));
        };
        body = new FrameLayout(c.a);
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        addView(body, new LayoutParams(-1, 0, 1));
        addView(info, new LayoutParams(-1, -2));
        show(0);
    }

    private void styleTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            boolean sel = i == current;
            t.setBackground(Ui.rounded(c, sel ? c.p.primary : c.p.surface2, 12));
            t.setTextColor(sel ? c.p.onPrimary : c.p.ink);
        }
    }

    private void show(int i) {
        if (i == current) return;
        current = i;
        styleTabs();
        int gen = ++generation;
        Sheet s = loaded.get(i);
        setBusy(s == null);
        if (s != null) { grid.setSheet(s); return; }
        grid.setSheet(null);
        c.io.execute(() -> {
            try {
                Sheet full = loader.load(i, early -> c.ui.post(() -> { if (gen == generation && !closed) { setBusy(false); grid.setSheet(early); tick(gen, early); } }));
                c.ui.post(() -> {
                    if (closed) return;
                    loaded.put(i, full);
                    if (gen != generation) return;
                    setBusy(false);
                    if (full.rowCount() == 0) { message("Bu sayfa boş."); return; }
                    grid.measureColumns(); grid.invalidate();
                    if (full.truncated) Ui.toastLong(c, "Çok büyük sayfa: yalnızca ilk kısmı gösteriliyor.");
                    if (grid.getTag() != full) grid.setSheet(full);
                });
            } catch (Exception | OutOfMemoryError e) {
                c.ui.post(() -> { if (gen == generation && !closed) message("Bu sayfa okunamadı."); });
            }
        });
    }

    /** While a sheet is still parsing, the grid redraws now and then so new rows show up in the scroll range. */
    private void tick(int gen, Sheet s) {
        grid.setTag(s);
        c.ui.postDelayed(() -> {
            if (gen != generation || closed || loaded.containsKey(current)) return;
            grid.invalidate();
            tick(gen, s);
        }, 400);
    }

    private View overlay;

    private void setBusy(boolean busy) {
        if (overlay != null) { body.removeView(overlay); overlay = null; }
        if (busy) { overlay = Ui.busy(c, "Açılıyor…", null); overlay.setBackgroundColor(c.p.bg); body.addView(overlay, new FrameLayout.LayoutParams(-1, -1)); }
    }

    private void message(String m) {
        if (overlay != null) body.removeView(overlay);
        overlay = Ui.state(c, Icon.Kind.DOC, m, null, null, null);
        overlay.setBackgroundColor(c.p.bg);
        body.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }
}
