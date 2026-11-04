package app.kolay.app.tools.ocr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;

/** The cleaned text with Copy and Share. Never stored: it only exists in memory while this screen is open. */
final class ResultScreen extends Screen {
    private final Session s;
    private final String raw;
    private String text;            // what is shown / copied: the local OCR text, or its cleaned version after a polish
    private boolean cleaned, cleaning;
    private TextView out, clean;
    private final long secs;
    private final int pages;
    private final boolean ok;
    private final String marked;   // medical mode: masked, page-marked text still waiting for a polish (null when none)
    private View body, bar;
    private TextView copy;
    private static final String CLEAN_LABEL = "Cilala (maskelenip buluta gider)";

    ResultScreen(Session s, String text, long secs, int pages, boolean ok, String marked) { this.s = s; this.marked = marked; this.raw = text; this.text = text; this.secs = secs; this.pages = pages; this.ok = ok; }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Sonuç", secs + " sn · " + pages + " sayfa", this::back).view);

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        TextView t = Ui.text(c, text, 15, ok ? p.ink : p.danger, 400);
        out = t;
        t.setLineSpacing(0, 1.3f);
        LinearLayout card = new LinearLayout(c.a);
        card.setPadding(c.dp(18), c.dp(18), c.dp(18), c.dp(18));
        Ui.surface(c, card, p.surface, 22, true);
        card.addView(t);
        FrameLayout wrap = new FrameLayout(c.a);
        wrap.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        wrap.addView(card);
        sv.addView(wrap);
        body = sv;
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = Ui.bottomBar(c);
        if (ok && (s.general || marked != null)) {   // the text stays on the device unless this button is pressed
            clean = Ui.button(c, CLEAN_LABEL, Ui.Style.TONAL, Icon.Kind.SPARKLE);
            clean.setOnClickListener(v -> cleanUp());
            bottom.addView(clean, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, 0, 0, c.dp(Ui.GAP)));
        }
        copy = Ui.button(c, "Kopyala", Ui.Style.PRIMARY, Icon.Kind.COPY);
        TextView share = Ui.button(c, "Paylaş", Ui.Style.TONAL, Icon.Kind.SHARE);
        copy.setOnClickListener(v -> copy());
        share.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            c.a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null));
        });
        if (ok) bottom.addView(Ui.pair(c, copy, share, Ui.H_BAR));
        else {   // nothing worth copying: offer to try again instead
            TextView retry = Ui.button(c, "Başka sayfa seç", Ui.Style.PRIMARY, Icon.Kind.IMAGE);
            retry.setOnClickListener(v -> back());
            bottom.addView(retry, Ui.lp(-1, c.dp(Ui.H_BAR)));
        }
        bar = bottom;
        page.addView(bottom);
        body.setAlpha(0f); bar.setAlpha(0f);
        return page;
    }

    @Override public void onShown() {
        body.setTranslationY(c.dp(20));
        body.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(Ui.EMPH).start();
        bar.setTranslationY(c.dp(40));
        bar.animate().alpha(1f).translationY(0).setStartDelay(120).setDuration(420).setInterpolator(Ui.EMPH).start();
    }

    /** Sends the local text out for cleanup (loose prompt in general mode, page order + fixes in medical mode); pressing again shows the local text. */
    private void cleanUp() {
        if (cleaning) return;
        if (cleaned) {   // toggle back to the raw text
            cleaned = false; text = raw; out.setText(raw);
            Ui.style(c, clean, Ui.Style.TONAL, Icon.Kind.SPARKLE); clean.setText(CLEAN_LABEL);
            return;
        }
        cleaning = true;
        clean.setText("Cilalanıyor…"); clean.setEnabled(false);
        c.io.execute(() -> {
            String res = null, err = null;
            try { res = s.general ? Ocr.cleanGeneral(c.host, raw) : Ocr.polish(c.host, marked); if (res.isEmpty()) err = "Boş yanıt"; }
            catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String r = res, e = err;
            c.ui.post(() -> {
                cleaning = false;
                if (!clean.isAttachedToWindow()) return;
                clean.setEnabled(true);
                if (e != null) { clean.setText(CLEAN_LABEL); android.widget.Toast.makeText(c.a, e, android.widget.Toast.LENGTH_LONG).show(); return; }
                cleaned = true; text = r; out.setText(r);
                clean.setText("Yerel metni göster");
                Ui.style(c, clean, Ui.Style.TONAL, Icon.Kind.DOC);
            });
        });
    }

    private void copy() {
        ClipData clip = ClipData.newPlainText("OCR", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);  // keeps the text out of the keyboard/clipboard preview
        clip.getDescription().setExtras(extras);
        ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        Ui.flashCopied(c, copy, Ui.Style.PRIMARY);
    }

    private void back() { s.selected.clear(); c.nav.replace(new PickerScreen(s)); }

    @Override public boolean onBack() { back(); return true; }
}
