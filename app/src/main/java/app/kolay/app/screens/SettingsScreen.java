package app.kolay.app.screens;

import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;
import java.util.ArrayList;
import java.util.List;

/** API key, update status and about. */
public final class SettingsScreen extends Screen {
    public static final String LLM_SECRET = "llm";

    private final List<View> entrance = new ArrayList<>();
    private TextView keyState, updateState, checkBtn;
    private EditText key;
    private LinearLayout updateBox;

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Ayarlar", "Anahtar · güncelleme · sürüm", () -> c.nav.pop(), null).view);

        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(32));

        // ---- API key ----
        col.addView(section("CİLALAMA", true));
        LinearLayout k = card();
        keyState = Ui.text(c, keyLabel(), 13, p.muted, 500);
        k.addView(row(Icon.Kind.KEY, "Gemini API anahtarı", null, keyState));
        key = Ui.input(c, "Anahtarı buraya yapıştır", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        k.addView(key, Ui.lp(-1, -2, 0, c.dp(14), 0, 0));
        TextView note = Ui.text(c, "Yalnızca bu cihazda, şifreli saklanır. Depoda veya uygulama dosyasında yer almaz.", 12, p.muted, 400);
        note.setLineSpacing(0, 1.15f);
        k.addView(note, Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        TextView save = Ui.button(c, "Kaydet", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        TextView clear = Ui.button(c, "Sil", Ui.Style.TONAL, null);
        save.setOnClickListener(v -> saveKey());
        clear.setOnClickListener(v -> {
            c.host.setSecret(LLM_SECRET, "");
            key.setText(""); Ui.swapText(keyState, keyLabel());
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
        });
        LinearLayout btns = new LinearLayout(c.a);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1);
        sl.setMarginEnd(c.dp(Ui.GAP));
        btns.addView(save, sl);
        btns.addView(clear, Ui.lp(c.dp(96), c.dp(Ui.H_GHOST)));
        k.addView(btns, Ui.lp(-1, -2, 0, c.dp(14), 0, 0));
        col.addView(k);

        // ---- updates ----
        col.addView(section("GÜNCELLEME", false));
        updateBox = card();
        updateState = Ui.text(c, c.host.updateStatus(), 13, p.muted, 400);
        updateState.setLineSpacing(0, 1.15f);
        LinearLayout ur = row(Icon.Kind.REFRESH, "Otomatik güncelleme", null, null);
        updateBox.addView(ur);
        updateBox.addView(updateState, Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        checkBtn = Ui.button(c, "Şimdi denetle", Ui.Style.TONAL, null);
        checkBtn.setOnClickListener(v -> checkNow());
        updateBox.addView(checkBtn, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(14), 0, 0));
        renderUpdateButtons();
        col.addView(updateBox);

        // ---- old-Office plugin (only shown once downloaded from the viewer) ----
        if (c.host.officePluginInstalled()) {
            col.addView(section("EKLENTİLER", false));
            LinearLayout pl = card();
            pl.addView(row(Icon.Kind.DOC, "Eski Office eklentisi", ".doc · .xls · .ppt · " + app.kolay.app.doc.Langs.mb(c.host.officePluginSize()), null));
            TextView del = Ui.button(c, "Eski Office eklentisini sil", Ui.Style.TONAL, Icon.Kind.TRASH);
            del.setOnClickListener(v -> {
                if (!c.host.officePluginDelete()) { Ui.toast(c, "Silinemedi"); return; }
                app.kolay.app.viewer.LegacyOffice.install(null);
                Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
                Ui.toast(c, "Eklenti silindi; gerekirse yeniden indirilir.");
                v.setEnabled(false); v.setAlpha(0.45f);
            });
            pl.addView(del, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(14), 0, 0));
            col.addView(pl);
        }

        // ---- about ----
        col.addView(section("HAKKINDA", false));
        LinearLayout ab = card();
        ab.addView(row(Icon.Kind.LAYERS, "Sürüm", c.host.versionName(), null));
        ab.addView(divider());
        LinearLayout gh = row(Icon.Kind.LINK, "Sürüm sayfası", "GitHub", Ui.icon(c, Icon.Kind.CHEVRON, p.muted, 18));
        gh.setClickable(true);
        gh.setPadding(0, c.dp(4), 0, c.dp(4));
        gh.setBackground(Ui.ripple(c, 12));
        Ui.press(gh);
        gh.setOnClickListener(v -> c.a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(c.host.releasesUrl()))));
        ab.addView(gh);
        col.addView(ab);

        for (int i = 0; i < col.getChildCount(); i++) entrance.add(col.getChildAt(i));
        for (View v : entrance) v.setAlpha(0f);

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    @Override public void onShown() {
        long d = 0;
        Ui.stagger(entrance, d);
    }

    private String keyLabel() { return c.host.secret(LLM_SECRET).isEmpty() ? "Ayarlanmadı" : "Kayıtlı"; }

    private void saveKey() {
        String s = key.getText().toString().trim();
        if (s.isEmpty()) { Ui.toast(c, "Önce anahtarı yapıştır"); return; }
        try {
            c.host.setSecret(LLM_SECRET, s);
            key.setText("");
            Ui.swapText(keyState, keyLabel());
            Ui.haptic(key, HapticFeedbackConstants.CONFIRM);
            Ui.toast(c, "Kaydedildi");
        } catch (Exception e) { Ui.toastLong(c, e.getMessage()); }
    }

    private void checkNow() {
        if (c.host.updateReady()) { renderUpdateButtons(); return; }
        if (!checkBtn.isEnabled()) return;   // a check is already running
        checkBtn.setEnabled(false);
        Ui.swapText(checkBtn, "Denetleniyor…");
        c.host.checkForUpdates(status -> {
            checkBtn.setEnabled(true);
            if (c.host.updateReady()) { renderUpdateButtons(); return; }
            Ui.swapText(checkBtn, "Şimdi denetle");
            Ui.swapText(updateState, status);
        });
    }

    /**
     * A downloaded update waits: the button becomes "Şimdi kur" and the status says so. Text is set directly, not through
     * Ui.swapText, whose delayed end action would otherwise overwrite it.
     */
    private void renderUpdateButtons() {
        if (!c.host.updateReady()) return;
        checkBtn.animate().cancel(); checkBtn.setAlpha(1f); checkBtn.setTranslationY(0);
        updateState.animate().cancel(); updateState.setAlpha(1f); updateState.setTranslationY(0);
        updateState.setText("Yeni sürüm (" + c.host.updateName() + ") indirildi ve doğrulandı.");
        checkBtn.setEnabled(true);
        checkBtn.setText("Şimdi kur");
        Ui.style(c, checkBtn, Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
        checkBtn.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            c.host.installUpdate(msg -> updateState.setText(msg));
        });
    }

    // ---- small building blocks -------------------------------------------------------------

    private TextView section(String s, boolean first) {
        TextView t = Ui.label(c, s);
        t.setLayoutParams(Ui.lp(-2, -2, c.dp(4), c.dp(first ? 8 : 24), 0, c.dp(10)));
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16));
        Ui.surface(c, l, c.p.surface, Ui.R_CARD, true);
        l.setLayoutParams(Ui.lp(-1, -2));
        return l;
    }

    private View divider() {
        View v = Ui.divider(c, 54);
        ((LinearLayout.LayoutParams) v.getLayoutParams()).setMargins(c.dp(54), c.dp(12), 0, c.dp(12));
        return v;
    }

    private int rows;   // running index so neighbouring rows get different tile tones

    private LinearLayout row(Icon.Kind icon, String title, String sub, View trailing) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(Ui.toneTile(c, icon, c.p.tone(rows++), 40, 20, 12), new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));
        LinearLayout t = new LinearLayout(c.a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(c, title, 16, c.p.ink, 700));
        if (sub != null) t.addView(Ui.text(c, sub, 13, c.p.muted, 400), Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(c.dp(14)); lp.setMarginEnd(c.dp(8));
        r.addView(t, lp);
        if (trailing != null) r.addView(trailing);
        return r;
    }
}
