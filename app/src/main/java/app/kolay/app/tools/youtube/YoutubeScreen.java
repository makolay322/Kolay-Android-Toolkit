package app.kolay.app.tools.youtube;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import app.kolay.app.tools.Llm;
import app.kolay.app.ui.*;

/** Link field -> transcript (original language) -> Turkish summary, all on one screen. Nothing is stored. */
final class YoutubeScreen extends Screen {
    private static final int MAX_CHARS = 400_000;
    private static final String PROMPT =
        "Aşağıda bir YouTube videosunun transkripti var (dil: %s). Videoyu TÜRKÇE özetle. "
        + "Önce 1-2 cümlelik genel özet, ardından ana noktaları madde madde yaz; önemli sayı, isim ve sonuçları koru. "
        + "Transkriptte olmayan hiçbir bilgi ekleme. Çıktı düz metin olsun, markdown başlığı veya kod bloğu kullanma.\n\nTRANSKRİPT:\n";

    private EditText link;
    private TextView go, status, result;
    private LinearLayout statusRow;
    private Arc spinner;
    private View resultCard, actions;
    private volatile boolean destroyed;
    private boolean busy;
    private String summary = "";
    private final String shared;   // text received from the share sheet, or null

    YoutubeScreen() { this(null); }
    YoutubeScreen(String shared) { this.shared = shared; }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "YouTube Özeti", "Bağlantıyı yapıştır", () -> c.nav.pop()).view);

        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(24));

        link = Ui.input(c, "https://youtu.be/…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        link.setOnEditorActionListener((v, action, ev) -> { start(); return true; });
        col.addView(link, Ui.lp(-1, -2));

        TextView paste = Ui.button(c, "Yapıştır", Ui.Style.TONAL, Icon.Kind.PASTE);
        go = Ui.button(c, "Özetle", Ui.Style.PRIMARY, Icon.Kind.SPARKLE);
        paste.setOnClickListener(v -> pasteFromClipboard());
        go.setOnClickListener(v -> start());
        col.addView(Ui.pair(c, paste, go, Ui.H_BTN), Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        // status line: a small loader while working, red text on error
        statusRow = new LinearLayout(c.a);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new Arc(c.a, p.primarySoft, p.primary, c.dp(2.5f));
        statusRow.addView(spinner, Ui.lp(c.dp(18), c.dp(18), 0, 0, c.dp(10), 0));
        status = Ui.text(c, "", 14, p.muted, 500);
        status.setLineSpacing(0, 1.2f);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        statusRow.setVisibility(View.GONE);
        col.addView(statusRow, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), 0));

        result = Ui.text(c, "", 15, p.ink, 400);
        result.setLineSpacing(0, 1.3f);
        LinearLayout card = Ui.card(c, p.surface, 18);
        card.addView(result);
        card.setVisibility(View.GONE);
        resultCard = card;
        col.addView(card, Ui.lp(-1, -2, 0, c.dp(16), 0, 0));

        TextView copy = Ui.button(c, "Kopyala", Ui.Style.TONAL, Icon.Kind.COPY);
        TextView share = Ui.button(c, "Paylaş", Ui.Style.TONAL, Icon.Kind.SHARE);
        copy.setOnClickListener(v -> copy(copy));
        share.setOnClickListener(v -> c.a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary), null)));
        LinearLayout act = Ui.pair(c, copy, share, Ui.H_BTN);
        act.setVisibility(View.GONE);
        actions = act;
        col.addView(act, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void pasteFromClipboard() {
        ClipData d = ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
        if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) {
            link.setText(d.getItemAt(0).getText().toString().trim());
            link.setSelection(link.length());
        }
    }

    private void start() {
        if (busy) return;
        String id = Transcript.videoId(link.getText().toString());
        if (id == null) { Ui.toast(c, "Geçerli bir YouTube bağlantısı yapıştır"); return; }
        busy = true;
        Ui.haptic(go, HapticFeedbackConstants.VIRTUAL_KEY);
        go.setEnabled(false);
        resultCard.setVisibility(View.GONE); actions.setVisibility(View.GONE);
        ((android.view.inputmethod.InputMethodManager) c.a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(link.getWindowToken(), 0);
        link.clearFocus();
        statusRow.setVisibility(View.VISIBLE); spinner.setVisibility(View.VISIBLE); status.setTextColor(c.p.muted);
        Ui.swapText(status, "Transkript alınıyor…");
        c.io.execute(() -> {
            String out = null, err = null;
            try {
                Transcript.Result t = Transcript.fetch(id);
                post(() -> Ui.swapText(status, "Özetleniyor… (" + t.lang + " transkript)"));
                String text = t.text.length() > MAX_CHARS ? t.text.substring(0, MAX_CHARS) : t.text;
                out = Llm.generate(c.host, String.format(PROMPT, t.lang) + text);
                if (out.isEmpty()) err = "Özet üretilemedi";
            } catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String o = out, e = err;
            post(() -> done(o, e));
        });
    }

    private void done(String out, String err) {
        busy = false; go.setEnabled(true);
        if (err != null) { spinner.setVisibility(View.GONE); status.setTextColor(c.p.danger); Ui.swapText(status, err); return; }
        summary = out;
        statusRow.setVisibility(View.GONE);
        result.setText(out);
        resultCard.setVisibility(View.VISIBLE); actions.setVisibility(View.VISIBLE);
        Ui.appear(resultCard, 0); Ui.appear(actions, 80);
        Ui.haptic(go, HapticFeedbackConstants.CONFIRM);
    }

    private void post(Runnable r) { c.ui.post(() -> { if (!destroyed) r.run(); }); }

    private void copy(TextView btn) {
        ClipData clip = ClipData.newPlainText("Özet", summary);
        ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        Ui.flashCopied(c, btn, Ui.Style.TONAL);
    }

    @Override public void onShown() {
        if (shared == null) return;
        link.setText(shared.trim());
        link.setSelection(link.length());
        if (Transcript.videoId(shared) != null) start();
    }

    @Override public void onDestroy() { destroyed = true; }
}
