package app.kolay.app.doc;

import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.*;
import app.kolay.app.ui.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR language data: per language installed / size, download with progress, delete. Opened from the scanner and from the
 * OCR tools when a needed language is missing; with {@code required} set, "Devam" appears once all of it is on the phone.
 * Leaving the screen cancels a running download (the partial file is removed).
 */
public final class TessScreen extends Screen {
    private final String required;
    private final Runnable onReady;
    private final Map<String, Row> rows = new HashMap<>();
    private TextView go;
    private volatile boolean destroyed;

    /** Plain manager. */
    public TessScreen() { this(null, null); }

    /** @param required combo that must be installed to continue; {@code onReady} runs after this screen was popped */
    public TessScreen(String required, Runnable onReady) { this.required = required; this.onReady = onReady; }

    private final class Row {
        final String lang;
        TextView status, action;
        Bar bar;
        volatile boolean cancel;
        boolean running, confirmDelete;
        Row(String lang) { this.lang = lang; }
    }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        String sub = required == null ? "Metin tanıma için dil verisi" : "Gerekli: " + Langs.names(required);
        page.addView(new Header(c, "OCR dilleri", sub, () -> c.nav.pop()).view);

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        LinearLayout card = Ui.card(c, p.surface, 6);
        List<String> need = Langs.split(required);
        int i = 0;
        for (String l : c.host.tessLanguages()) {
            if (i > 0) {
                View sep = new View(c.a); sep.setBackgroundColor(p.line);
                card.addView(sep, Ui.lp(-1, Math.max(1, c.dp(1) / 2), c.dp(12), 0, c.dp(12), 0));
            }
            card.addView(row(l, i++, need.contains(l)));
        }
        col.addView(card);
        TextView note = Ui.text(c, "Dil verisi GitHub'daki tesseract-ocr/tessdata_fast deposundan (sabit sürüm) indirilir ve "
            + "boyutu ile SHA-256 özeti doğrulanmadan kullanılmaz. Metin tanıma tamamen cihazda yapılır.", 12, p.muted, 400);
        note.setLineSpacing(0, 1.2f);
        col.addView(note, Ui.lp(-1, -2, c.dp(6), c.dp(14), c.dp(6), 0));
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        if (required != null) {
            LinearLayout bb = Ui.bottomBar(c);
            go = Ui.button(c, "Devam", Ui.Style.PRIMARY, Icon.Kind.CHECK);
            go.setOnClickListener(v -> {
                if (!Langs.ready(c.host, required)) return;
                Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
                c.nav.pop();
                if (onReady != null) onReady.run();   // queued behind the pop
            });
            bb.addView(go, Ui.lp(-1, c.dp(Ui.H_BAR)));
            page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        }
        refreshAll();
        return page;
    }

    private View row(String lang, int idx, boolean needed) {
        Palette p = c.p;
        Row r = new Row(lang);
        rows.put(lang, r);
        LinearLayout line = new LinearLayout(c.a);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(c.dp(10), c.dp(12), c.dp(10), c.dp(12));
        LinearLayout top = new LinearLayout(c.a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout box = new FrameLayout(c.a);
        box.setBackground(Ui.rounded(c, p.softBg(idx), 12));
        TextView code = Ui.text(c, lang.substring(0, 2).toUpperCase(java.util.Locale.ROOT), 13, p.softFg(idx), 700);
        box.addView(code, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        top.addView(box, new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));
        LinearLayout texts = new LinearLayout(c.a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, Langs.name(lang) + (needed ? " · gerekli" : ""), 16, p.ink, 600));
        r.status = Ui.text(c, "", 13, p.muted, 400);
        texts.addView(r.status, Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(12));
        top.addView(texts, tl);
        r.action = Ui.button(c, "", Ui.Style.TONAL, null);
        r.action.setTextSize(14);
        r.action.setPadding(c.dp(14), 0, c.dp(14), 0);
        r.action.setOnClickListener(v -> act(r));
        top.addView(r.action, new LinearLayout.LayoutParams(-2, c.dp(40)));
        line.addView(top);
        r.bar = new Bar(c.a, p.surface2, p.primary);
        r.bar.setVisibility(View.GONE);
        line.addView(r.bar, Ui.lp(-1, c.dp(5), 0, c.dp(10), 0, 0));
        return line;
    }

    private void refreshAll() {
        for (Row r : rows.values()) paint(r);
        if (go != null) {
            boolean ok = Langs.ready(c.host, required);
            go.setEnabled(ok);
            go.setAlpha(ok ? 1f : 0.45f);
            go.setText(ok ? "Devam" : "Önce gerekli dilleri indir");
        }
    }

    private void paint(Row r) {
        boolean have = c.host.tessInstalled(r.lang);
        if (r.running) {
            r.action.setText("İptal");
            Ui.style(c, r.action, Ui.Style.GHOST, null);
            return;
        }
        r.bar.setVisibility(View.GONE);
        r.status.setText(have ? "Yüklü · " + Langs.mb(c.host.tessInstalledSize(r.lang)) : "İndirilmedi · " + Langs.mb(c.host.tessDownloadSize(r.lang)));
        r.action.setText(have ? (r.confirmDelete ? "Silinsin mi?" : "Sil") : "İndir");
        Ui.style(c, r.action, have ? Ui.Style.GHOST : Ui.Style.TONAL, have ? Icon.Kind.TRASH : Icon.Kind.DOWNLOAD);
        if (have && r.confirmDelete) r.action.setTextColor(c.p.danger);
    }

    private void act(Row r) {
        if (r.running) { r.cancel = true; r.action.setEnabled(false); return; }
        if (c.host.tessInstalled(r.lang)) {
            if (!r.confirmDelete) {
                r.confirmDelete = true; paint(r);
                c.ui.postDelayed(() -> { if (r.confirmDelete && !destroyed) { r.confirmDelete = false; paint(r); } }, 3000);
                return;
            }
            r.confirmDelete = false;
            if (!c.host.tessDelete(r.lang)) Ui.toast(c, "Silinemedi");
            Ui.haptic(r.action, HapticFeedbackConstants.CONFIRM);
            refreshAll();
            return;
        }
        start(r);
    }

    private void start(Row r) {
        r.running = true; r.cancel = false;
        r.bar.setVisibility(View.VISIBLE); r.bar.set(0);
        final long total = Math.max(1, c.host.tessDownloadSize(r.lang));
        r.status.setText("İndiriliyor… 0 / " + Langs.mb(total));
        paint(r);
        c.io.execute(() -> {
            final long[] last = {0};
            String err = null;
            try {
                c.host.tessDownload(r.lang, done -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 120 && done < total) return;
                    last[0] = now;
                    c.ui.post(() -> {
                        if (destroyed || !r.running) return;
                        r.bar.set(done / (float) total);
                        r.status.setText("İndiriliyor… " + Langs.mb(done) + " / " + Langs.mb(total));
                    });
                }, () -> r.cancel || destroyed);
            } catch (java.io.InterruptedIOException e) {
                err = "";
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String e2 = err;
            c.ui.post(() -> {
                if (destroyed) return;
                r.running = false;
                r.action.setEnabled(true);
                if (e2 != null && !e2.isEmpty()) Ui.toastLong(c, "İndirilemedi: " + e2);
                else if (e2 == null) Ui.haptic(r.action, HapticFeedbackConstants.CONFIRM);
                refreshAll();
            });
        });
    }

    @Override public boolean onBack() {
        for (Row r : rows.values()) r.cancel = true;
        return false;
    }

    @Override public void onDestroy() {
        destroyed = true;
        for (Row r : rows.values()) r.cancel = true;
    }
}
