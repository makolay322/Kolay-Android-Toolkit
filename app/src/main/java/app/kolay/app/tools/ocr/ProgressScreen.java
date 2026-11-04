package app.kolay.app.tools.ocr;

import android.net.Uri;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;
import java.util.List;

/** Reads the selected pages; shows live status, a gliding bar and elapsed time. Back / Cancel aborts. */
final class ProgressScreen extends Screen {
    private final Session s;
    private final List<Uri> pages;
    private TextView status, elapsed;
    private Bar bar;
    private Ocr ocr;
    private volatile boolean stopped;
    private long t0;
    private float shown;

    ProgressScreen(Session s, List<Uri> pages) { this.s = s; this.pages = pages; }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(c.dp(32), 0, c.dp(32), c.dp(32));

        col.addView(new Space(c.a), new LinearLayout.LayoutParams(1, 0, 1));

        Arc sp = new Arc(c.a, p.primarySoft, p.primary, c.dp(6));
        col.addView(sp, Ui.lp(c.dp(76), c.dp(76)));
        status = Ui.text(c, "Başlıyor…", 18, p.ink, 600);
        status.setGravity(Gravity.CENTER);
        col.addView(status, Ui.lp(-1, -2, 0, c.dp(28), 0, 0));
        TextView sub = Ui.text(c, pages.size() + (s.general ? " sayfa · yalnızca cihazda okuma" : " sayfa · okuma, isim maskeleme, cilalama"), 13, p.muted, 400);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.lp(-1, -2, 0, c.dp(6), 0, 0));
        bar = new Bar(c.a, p.surface2, p.primary);
        col.addView(bar, Ui.lp(c.dp(240), c.dp(6), 0, c.dp(28), 0, 0));
        elapsed = Ui.text(c, "0 sn", 13, p.muted, 500);
        col.addView(elapsed, Ui.lp(-2, -2, 0, c.dp(12), 0, 0));

        FrameLayout bottom = new FrameLayout(c.a);
        TextView cancel = Ui.button(c, "Vazgeç", Ui.Style.GHOST, null);
        cancel.setOnClickListener(v -> cancel());
        bottom.addView(cancel, new FrameLayout.LayoutParams(-2, c.dp(48), Gravity.CENTER));
        col.addView(bottom, Ui.lp(-1, 0, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) bottom.getLayoutParams()).weight = 1;
        ((FrameLayout.LayoutParams) cancel.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        return col;
    }

    @Override public void onShown() {
        if (stopped) return;
        t0 = System.currentTimeMillis();
        tick();
        ocr = new Ocr(c.host);
        c.io.execute(this::work);
    }

    private void work() {
        String result, pending = null; boolean ok = true;
        try {
            Ocr.Progress pr = (msg, f) -> c.ui.post(() -> {
                if (stopped) return;
                Ui.swapText(status, msg); shown = Math.max(shown, f); bar.set(shown);
            });
            if (s.general) result = ocr.runPlain(pages, pr);
            else { Ocr.Out o = ocr.run(pages, pr); result = o.text; pending = o.pending; }
            if (result.isEmpty()) result = "Metin bulunamadı.";
        } catch (Exception e) {
            result = "Hata: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); ok = false;
        } finally { ocr.close(); }
        final String text = result, marked = pending; final boolean good = ok;
        c.ui.post(() -> {
            if (stopped) return;
            stopped = true;
            bar.set(1f);
            c.nav.replace(new ResultScreen(s, text, (System.currentTimeMillis() - t0) / 1000, pages.size(), good, marked));
        });
    }

    /** Elapsed counter; also nudges the bar while the (unpredictable) cleanup request is running. */
    private void tick() {
        if (stopped) return;
        long sec = (System.currentTimeMillis() - t0) / 1000;
        elapsed.setText(sec + " sn");
        if (shown >= 0.78f && shown < 0.95f) { shown += 0.015f; bar.set(shown); }
        c.ui.postDelayed(this::tick, 1000);
    }

    private void cancel() {
        if (stopped) return;
        stopped = true;
        if (ocr != null) ocr.cancel();
        c.nav.replace(new PickerScreen(s));
    }

    @Override public boolean onBack() { cancel(); return true; }

    @Override public void onDestroy() { stopped = true; }
}
