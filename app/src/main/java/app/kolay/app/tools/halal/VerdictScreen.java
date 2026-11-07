package app.kolay.app.tools.halal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;

/** The assessment: a colored verdict, the reasons, and the ingredient text it was based on. Only in memory. */
final class VerdictScreen extends Screen {
    private final String name, source, ingredients, error;
    private final Halal.Verdict verdict;
    private final View[] parts = new View[4];

    VerdictScreen(String name, String source, String ingredients, Halal.Verdict verdict, String error) {
        this.name = name; this.source = source; this.ingredients = ingredients; this.verdict = verdict; this.error = error;
    }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Sonuç", source.isEmpty() ? "Hanefi mezhebi" : source + " · Hanefi mezhebi", () -> c.nav.pop()).view);

        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        if (error != null) {
            LinearLayout card = card(p.dangerSoft);
            card.setOrientation(LinearLayout.VERTICAL);
            card.addView(Ui.text(c, "Değerlendirme yapılamadı", 17, p.danger, 700));
            TextView t = Ui.text(c, error, 14, p.ink, 400);
            t.setLineSpacing(0, 1.3f);
            card.addView(t, Ui.lp(-1, -2, 0, c.dp(8), 0, 0));
            col.addView(card, Ui.lp(-1, -2));
            parts[0] = card;
        } else {
            int bg, fg; String label;
            switch (verdict.level) {
                case HELAL: bg = p.okSoft; fg = p.ok; label = "HELAL"; break;
                case HARAM: bg = p.dangerSoft; fg = p.danger; label = "HARAM"; break;
                case SUPHELI: bg = p.warnSoft; fg = p.warn; label = "ŞÜPHELİ"; break;
                default: bg = p.surface2; fg = p.ink; label = "BELİRSİZ"; break;
            }
            LinearLayout badge = new LinearLayout(c.a);
            badge.setOrientation(LinearLayout.VERTICAL); badge.setGravity(Gravity.CENTER);
            badge.setPadding(c.dp(20), c.dp(24), c.dp(20), c.dp(24));
            badge.setBackground(Ui.rounded(c, bg, 22));
            TextView v = Ui.text(c, label, 34, fg, 700);
            v.setLetterSpacing(0.04f);
            badge.addView(v);
            if (!name.isEmpty()) {
                TextView n = Ui.text(c, name, 15, fg, 500);
                n.setGravity(Gravity.CENTER);
                badge.addView(n, Ui.lp(-2, -2, 0, c.dp(8), 0, 0));
            }
            col.addView(badge, Ui.lp(-1, -2));
            parts[0] = badge;

            LinearLayout card = card(p.surface);
            TextView d = Ui.text(c, verdict.detail, 15, p.ink, 400);
            d.setLineSpacing(0, 1.3f);
            card.addView(d);
            col.addView(card, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));
            parts[1] = card;
        }

        if (!ingredients.isEmpty()) {
            TextView lab = Ui.label(c, "DEĞERLENDİRİLEN İÇİNDEKİLER");
            col.addView(lab, Ui.lp(-2, -2, c.dp(4), c.dp(20), 0, c.dp(8)));
            LinearLayout card = card(p.surface2);
            String shown = ingredients.length() > 1200 ? ingredients.substring(0, 1200) + "…" : ingredients;
            TextView t = Ui.text(c, shown, 13, p.muted, 400);
            t.setLineSpacing(0, 1.25f);
            card.addView(t);
            col.addView(card, Ui.lp(-1, -2));
            parts[2] = card;
        }
        TextView note = Ui.text(c, "Otomatik değerlendirmedir, fetva değildir. Şüphede üreticiye veya yetkili bir kuruma danış.", 12, p.muted, 400);
        note.setLineSpacing(0, 1.2f);
        col.addView(note, Ui.lp(-1, -2, c.dp(4), c.dp(16), c.dp(4), 0));

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout row = Ui.bottomBar(c);
        TextView copy = Ui.button(c, "Kopyala", Ui.Style.TONAL, Icon.Kind.COPY);
        TextView again = Ui.button(c, "Yeni sorgu", Ui.Style.PRIMARY, fromLabel() ? Icon.Kind.CAMERA : Icon.Kind.BARCODE);
        copy.setOnClickListener(x -> copy(copy));
        again.setOnClickListener(x -> again());
        if (error != null) row.addView(again, Ui.lp(-1, c.dp(Ui.H_BAR)));
        else row.addView(Ui.pair(c, copy, again, Ui.H_BAR));
        page.addView(row);
        return page;
    }

    private LinearLayout card(int fill) {
        LinearLayout card = new LinearLayout(c.a);
        card.setPadding(c.dp(18), c.dp(18), c.dp(18), c.dp(18));
        Ui.surface(c, card, fill, 22, fill == c.p.surface);
        return card;
    }

    @Override public void onShown() {
        long d = 0;
        for (View v : parts) if (v != null) { Ui.appear(v, d); d += Ui.STAGGER * 2; }
        if (verdict != null) Ui.haptic(parts[0], HapticFeedbackConstants.CONFIRM);
    }

    private void copy(TextView btn) {
        String text = (name.isEmpty() ? "" : name + "\n") + verdict.level + "\n" + verdict.detail;
        ClipData clip = ClipData.newPlainText("Helal", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        Ui.flashCopied(c, btn, Ui.Style.TONAL);
    }

    /** "Yeni sorgu" starts over in the mode this result came from; the header arrow / Back go to the home screen. */
    private void again() { c.nav.replace(fromLabel() ? new LabelScreen(null, null) : new ScanScreen(null)); }

    private boolean fromLabel() { return "Etiket fotoğrafı".equals(source); }
}
