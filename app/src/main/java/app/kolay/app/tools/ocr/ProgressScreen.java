package app.kolay.app.tools.ocr;

import android.view.*;
import android.widget.*;
import app.kolay.app.doc.Langs;
import app.kolay.app.doc.TessScreen;
import app.kolay.app.tools.scan.ScanSession;
import app.kolay.app.ui.*;
import java.util.List;

/**
 * Reads the processed pages of a document flow (as cropped, rotated and filtered there); shows live status, a gliding bar
 * and elapsed time. Back / Cancel aborts and returns to the pages. The flow and its pages stay: they end with its root.
 */
public final class ProgressScreen extends Screen {
    private final ScanSession s;
    private final boolean general;
    private final String langs;
    private List<ScanSession.Job> pages;
    private TextView status, elapsed;
    private Bar bar;
    private Ocr ocr;
    private Runnable waiting;   // the flow's change listener while pages are still being outlined
    private volatile boolean stopped;
    private long t0;
    private float shown;

    private ProgressScreen(ScanSession s, boolean general, String langs) { this.s = s; this.general = general; this.langs = langs; }

    /**
     * Reads the pages of {@code s}: {@code general} = the remembered languages, on the device only; otherwise the medical
     * mode (German, masked, one cleanup request). Missing language data is offered first. {@code replace}: this takes the
     * place of the calling screen (a result being read again) instead of going on top of it.
     */
    public static void open(Ctx c, ScanSession s, boolean general, boolean replace) {
        String langs = general ? Langs.remembered(c.a, "ocr", "deu") : "deu";
        Runnable go = () -> { Screen p = new ProgressScreen(s, general, langs); if (replace) c.nav.replace(p); else c.nav.push(p); };
        if (Langs.ready(c.host, langs)) go.run();
        else c.nav.push(new TessScreen(langs, go));   // language data first; "Devam" then starts reading
    }

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
        TextView sub = Ui.text(c, s.count() + (general ? " sayfa · " + Langs.names(langs) + " · yalnızca cihazda okuma" : " sayfa · okuma, isim maskeleme, cilalama"), 13, p.muted, 400);
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
        if (s.allReady()) { begin(); return; }
        Ui.swapText(status, "Sayfalar hazırlanıyor…");   // e.g. shared pictures still being copied and outlined
        waiting = () -> { if (s.allReady()) begin(); };
        s.onChange = waiting;
    }

    private void begin() {
        if (stopped || ocr != null) return;
        if (s.onChange == waiting) s.onChange = null;
        pages = s.jobs();
        ocr = new Ocr(c.host, langs);
        c.io.execute(this::work);
    }

    private void work() {
        String result, pending = null; boolean ok = true;
        try {
            Ocr.Progress pr = (msg, f) -> c.ui.post(() -> {
                if (stopped) return;
                Ui.swapText(status, msg); shown = Math.max(shown, f); bar.set(shown);
            });
            if (pages.isEmpty()) { result = "Okunabilir sayfa yok."; ok = false; }
            else if (general) result = ocr.runPlain(pages, pr);
            else { Ocr.Out o = ocr.run(pages, pr); result = o.text; pending = o.pending; }
            if (result.isEmpty()) result = "Metin bulunamadı.";
        } catch (Exception | OutOfMemoryError e) {
            result = e instanceof OutOfMemoryError ? "Hata: bellek yetmedi; daha az sayfayla dene"
                : "Hata: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            ok = false;
        } finally { ocr.close(); }
        final String text = result, marked = pending; final boolean good = ok;
        c.ui.post(() -> {
            if (stopped) return;
            stopped = true;
            bar.set(1f);
            c.nav.replace(new ResultScreen(s, general, langs, text, (System.currentTimeMillis() - t0) / 1000, pages.size(), good, marked));
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
        c.nav.pop();
    }

    @Override public boolean onBack() { cancel(); return true; }

    @Override public void onDestroy() {
        stopped = true;
        if (ocr != null) ocr.cancel();
        if (waiting != null && s.onChange == waiting) s.onChange = null;
    }
}
