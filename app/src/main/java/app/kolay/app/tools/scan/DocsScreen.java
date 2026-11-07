package app.kolay.app.tools.scan;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.PhotoPick;
import app.kolay.app.tools.netscan.DevicesScreen;
import app.kolay.app.tools.pdfedit.PdfEditScreen;
import app.kolay.app.ui.*;
import java.util.ArrayList;
import java.util.List;

/**
 * "Belgeler": where the pages come from (camera, gallery, Wi-Fi scanner) and the root of the one document flow. The pages
 * live as long as this screen: sources and outputs come and go above it; leaving it (back, "Bitti") discards them.
 */
public final class DocsScreen extends Screen {
    private static final int REQ_GALLERY = 75, REQ_PDF = 76;

    private List<Uri> shared;
    private ScanSession s;
    private Header header;
    private TextView pagesBtn;
    private final List<View> rows = new ArrayList<>();
    private long backArmedAt;

    public DocsScreen() {}

    /** Pictures shared from another app: copied into the flow right away (the read grant is temporary), then read as text. */
    public DocsScreen(List<Uri> shared) { this.shared = shared; }

    @Override public View create() {
        s = new ScanSession(c);
        s.root = this;
        s.onChange = this::paint;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Belgeler", "", this::back);
        page.addView(header.view);
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(24));
        rows.add(Ui.navRow(c, Icon.Kind.CAMERA, "Kamera", "Belgeyi çek, kenarlar kendiliğinden bulunur", 0, () -> c.nav.push(new CameraScreen(s))));
        rows.add(Ui.navRow(c, Icon.Kind.IMAGE, "Galeri", "Fotoğraflardan sayfa seç", 1, this::gallery));
        rows.add(Ui.navRow(c, Icon.Kind.NETSCAN, "Wi-Fi tarayıcı", "Ağdaki tarayıcıdan tara", 2, () -> c.nav.push(new DevicesScreen(s))));
        rows.add(Ui.navRow(c, Icon.Kind.EDIT, "PDF düzenle", "Sayfaları sırala, döndür, sil, birleştir", 3, this::pickPdf));
        for (View r : rows) { r.setAlpha(0f); col.addView(r, Ui.lp(-1, c.dp(76), 0, 0, 0, c.dp(12))); }
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bb = Ui.bottomBar(c);
        pagesBtn = Ui.button(c, "", Ui.Style.PRIMARY, Icon.Kind.LAYERS);
        pagesBtn.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(new PagesScreen(s)); });
        bb.addView(pagesBtn, Ui.lp(-1, c.dp(Ui.H_BAR)));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        if (shared != null) s.addUris(shared);
        paint();
        return page;
    }

    @Override public void onShown() {
        Ui.stagger(rows, 0);
        if (shared != null) { shared = null; c.nav.push(new PagesScreen(s, true)); }
    }

    @Override public void onReturn() { s.onChange = this::paint; paint(); }

    private void paint() {
        int n = s.count();
        String sub = n == 0 ? "Tara, kaydet, metne çevir" : n + " sayfa · başka kaynaktan da ekleyebilirsin", btn = "Sayfalar (" + n + ")";
        header.subtitle.setText(sub);
        pagesBtn.setText(btn);
        ((View) pagesBtn.getParent()).setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    private void gallery() {
        try { c.a.startActivityForResult(PhotoPick.intent(true), REQ_GALLERY); }
        catch (ActivityNotFoundException e) { Ui.toast(c, "Galeri açılamadı"); }
    }

    private void pickPdf() {
        try { c.a.startActivityForResult(PdfEditScreen.pickIntent(), REQ_PDF); }
        catch (ActivityNotFoundException e) { Ui.toast(c, "Dosya seçici açılamadı"); }
    }

    @Override public void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_PDF && result == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            c.nav.push(new PdfEditScreen(data.getData()));
            return;
        }
        if (req != REQ_GALLERY || result != android.app.Activity.RESULT_OK) return;
        List<Uri> uris = PhotoPick.uris(data);
        if (uris.isEmpty()) return;
        s.addUris(uris);
        c.nav.push(new PagesScreen(s));
    }

    private void back() {
        if (s.count() > 0 && SystemClock.uptimeMillis() - backArmedAt > 3000) {
            backArmedAt = SystemClock.uptimeMillis();
            Ui.toast(c, s.count() + " sayfa silinecek. Çıkmak için tekrar geri dokun.");
            return;
        }
        c.nav.pop();
    }

    @Override public boolean onBack() { back(); return true; }

    /** End of the flow: every page file and preview goes. */
    @Override public void onDestroy() { s.destroy(); }
}
