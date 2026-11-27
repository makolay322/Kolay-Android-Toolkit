package app.kolay.app.tools.halal;

import app.kolay.app.tools.Llm;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;

/**
 * Does the whole job behind one spinner: barcode -> product lookup -> (photo -> OCR ->) assessment.
 * If the barcode yields no ingredients it returns to the barcode page with a note (OCR is only used when chosen). Back / Cancel aborts.
 */
final class WorkScreen extends Screen {
    private final String barcode;
    private Bitmap photo;               // set when this run starts from a label photo; recycled as soon as OCR is done
    private TextView status;
    private volatile boolean stopped;

    private final boolean fromLabel;   // started in İçindekiler mode: Vazgeç returns there

    WorkScreen(String barcode, Bitmap photo) { this.barcode = barcode; this.photo = photo; this.fromLabel = photo != null; }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(c.dp(32), 0, c.dp(32), c.dp(32));
        col.addView(new Space(c.a), new LinearLayout.LayoutParams(1, 0, 1));
        col.addView(new Arc(c.a, p.primarySoft, p.primary, c.dp(6)), Ui.lp(c.dp(76), c.dp(76)));
        status = Ui.text(c, photo == null ? "Ürün aranıyor…" : "Etiket okunuyor…", 18, p.ink, 600);
        status.setGravity(Gravity.CENTER);
        col.addView(status, Ui.lp(-1, -2, 0, c.dp(28), 0, 0));
        TextView sub = Ui.text(c, "Hanefi mezhebine göre değerlendirme hazırlanıyor", 13, p.muted, 400);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.lp(-1, -2, 0, c.dp(6), 0, 0));
        FrameLayout bottom = new FrameLayout(c.a);
        TextView cancel = Ui.button(c, "Vazgeç", Ui.Style.GHOST, null);
        cancel.setOnClickListener(v -> cancel());
        bottom.addView(cancel, new FrameLayout.LayoutParams(-2, c.dp(48), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        col.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    @Override public void onShown() { if (!stopped) c.io.execute(this::work); }

    private void work() {
        String name = "", ingredients = "", source;
        try {
            Bitmap shot = takePhoto();
            if (shot == null) {
                Off.Product pr;
                try { pr = Off.lookup(barcode); }
                catch (java.io.IOException e) { backToScan("Ürün veritabanına ulaşılamadı. İnterneti kontrol edip tekrar dene ya da «İçindekiler» moduna geç."); return; }
                if (pr == null) { backToScan("Bu barkod veritabanında yok. Tekrar okutabilir ya da «İçindekiler» moduna geçip yazıyı fotoğraflayabilirsin."); return; }
                if (pr.ingredients.isEmpty()) { backToScan("Bu ürün için içindekiler kayıtlı değil. «İçindekiler» moduna geçip yazıyı fotoğraflayabilirsin."); return; }
                name = ((pr.brand.isEmpty() ? "" : pr.brand + " ") + pr.name).trim();
                ingredients = pr.ingredients;
                source = "Ürün veritabanı";
            } else {
                try { ingredients = LabelOcr.read(c.host, shot); } finally { shot.recycle(); }
                if (ingredients.replaceAll("\\s", "").length() < 8) { backToLabel("Yazı okunamadı. Daha yakından, ışığı yansıtmadan tekrar dene"); return; }
                source = "Etiket fotoğrafı";
            }
            if (stopped) return;
            live(() -> Ui.swapText(status, "Değerlendiriliyor…"));
            Halal.Verdict v = Halal.judge(c.host, name, ingredients);
            final String n = name, ing = ingredients, src = source;
            finishWith(() -> c.nav.replace(new VerdictScreen(n, src, ing, v, null)));
        } catch (Exception e) {
            String msg = Llm.describe(e);
            final String n = name, ing = ingredients;
            finishWith(() -> c.nav.replace(new VerdictScreen(n, "", ing, null, msg)));
        }
    }

    /** A barcode lookup that found nothing returns to the barcode page with a note; the OCR mode is never started on its own. */
    private void backToScan(String note) { finishWith(() -> c.nav.replace(new ScanScreen(note))); }
    /** A photo that could not be read stays in the OCR mode. */
    private void backToLabel(String note) { finishWith(() -> c.nav.replace(new LabelScreen(barcode, note))); }

    /** Status updates: skipped once the screen is gone. */
    private void live(Runnable r) { c.ui.post(() -> { if (!stopped) r.run(); }); }

    /** The one navigation away from this screen: runs at most once, and not after cancel. */
    private void finishWith(Runnable r) {
        c.ui.post(() -> { if (stopped) return; stopped = true; r.run(); });
    }

    private void cancel() {
        if (stopped) return;
        stopped = true;
        recyclePhoto();
        c.nav.replace(fromLabel ? new LabelScreen(barcode, null) : new ScanScreen(null));
    }

    private synchronized Bitmap takePhoto() { Bitmap b = photo; photo = null; return b; }
    private void recyclePhoto() { Bitmap b = takePhoto(); if (b != null) b.recycle(); }

    @Override public boolean onBack() { cancel(); return true; }
    @Override public void onDestroy() { stopped = true; recyclePhoto(); }
}
