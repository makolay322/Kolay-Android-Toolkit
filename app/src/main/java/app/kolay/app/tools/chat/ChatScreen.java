package app.kolay.app.tools.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import app.kolay.app.screens.SettingsScreen;
import app.kolay.app.tools.Llm;
import app.kolay.app.ui.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat bubbles over a bottom input bar. The conversation lives only in memory while this screen is open: it is never
 * written to disk or logs, and leaving the screen (or "Yeni sohbet") drops it.
 */
final class ChatScreen extends Screen {
    private static final int MAX_TURNS = 24;          // history sent with each request (oldest dropped first)
    private static final String SYSTEM =
        "Sen Kolay adlı kişisel bir Android uygulamasındaki yardımcı sohbet asistanısın. Kullanıcı başka bir dil istemedikçe "
        + "Türkçe yanıt ver. Kısa, net ve doğrudan ol; giriş ve kapanış cümleleri kurma. Düz metin yaz: markdown başlığı, tablo, "
        + "kalın veya italik işareti kullanma; liste gerekiyorsa satır başında '• ' kullan. Çeviri, düzeltme veya yeniden yazma "
        + "istenirse yalnızca sonucu ver. Emin olmadığın bilgide bunu açıkça söyle; uydurma.";
    private static final String[][] STARTERS = {
        {"Metni düzelt", "Yazım ve dil bilgisi hatalarını düzelt, anlamı değiştirme:\n"},
        {"Çevir", "Şunu İngilizceye çevir:\n"},
        {"Özetle", "Şunu birkaç cümlede özetle:\n"},
        {"Mesaj yaz", "Kibar ve kısa bir mesaj taslağı yaz: "},
    };

    private final List<String[]> turns = new ArrayList<>();   // {role, text}
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout list;
    private View empty, typing, errorBubble;
    private EditText input;
    private FrameLayout send;
    private boolean busy, destroyed;
    private int gen;                                           // bumps on "Yeni sohbet" so a late reply is dropped

    @Override public View create() {
        root = new FrameLayout(c.a);
        build();
        return root;
    }

    @Override public void onReturn() {
        if (input == null && Llm.hasKey(c.host)) build();   // key was just entered in Settings
    }

    private void build() {
        root.removeAllViews();
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        View fresh = Ui.iconButton(c, Icon.Kind.REFRESH, "Yeni sohbet", this::reset);
        page.addView(new Header(c, "Sohbet", "Konuşma kaydedilmez", () -> c.nav.pop(), fresh).view);

        if (!Llm.hasKey(c.host)) {
            fresh.setVisibility(View.GONE);
            input = null;
            page.addView(Ui.state(c, Icon.Kind.KEY, "Anahtar gerekli",
                    "Sohbet cilalama servisini kullanır. Anahtarı Ayarlar'dan bir kez girmen yeterli.",
                    "Ayarlara git", () -> c.nav.push(new SettingsScreen())), new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(page);
            return;
        }

        scroll = new ScrollView(c.a);
        scroll.setClipToPadding(false);
        list = new LinearLayout(c.a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(12));
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = starters();
        list.addView(empty);

        // input bar: growing multi-line field + round send button
        LinearLayout bar = new LinearLayout(c.a);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        input = Ui.input(c, "Bir şey sor veya yapıştır…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false); input.setMaxLines(6); input.setMinHeight(c.dp(Ui.H_BTN));
        input.setPadding(c.dp(14), c.dp(13), c.dp(14), c.dp(13));
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        send = new FrameLayout(c.a);
        send.setBackground(Ui.rounded(c, c.p.primary, 26));
        send.addView(Ui.icon(c, Icon.Kind.SEND, c.p.onPrimary, 22), new FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER));
        send.setContentDescription("Gönder"); send.setClickable(true);
        send.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); submit(); });
        Ui.press(send);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(c.dp(Ui.H_BTN), c.dp(Ui.H_BTN));
        sl.setMarginStart(c.dp(Ui.GAP));
        bar.addView(send, sl);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(page);
        refreshSend();
    }

    /** Empty-state hint with a few one-tap starters that pre-fill the field. */
    private View starters() {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, c.dp(8), 0, 0);
        TextView hint = Ui.text(c, "Hızlı bir soru sor ya da bir metni yapıştırıp ne yapılacağını yaz. Konuşma yalnızca bu ekran "
                + "açıkken tutulur; çıkınca silinir.", 14, c.p.muted, 400);
        hint.setLineSpacing(0, 1.25f);
        l.addView(hint, Ui.lp(-1, -2, c.dp(4), 0, c.dp(4), c.dp(14)));
        l.addView(Ui.label(c, "HIZLI BAŞLA"), Ui.lp(-2, -2, c.dp(4), 0, 0, c.dp(8)));
        for (int i = 0; i < STARTERS.length; i += 2) {
            TextView a = starter(STARTERS[i]), b = starter(STARTERS[i + 1]);
            l.addView(Ui.pair(c, a, b, Ui.H_GHOST), Ui.lp(-1, -2, 0, i == 0 ? 0 : c.dp(Ui.GAP), 0, 0));
        }
        return l;
    }

    private TextView starter(String[] s) {
        TextView b = Ui.button(c, s[0], Ui.Style.TONAL, null);
        b.setTextSize(15);
        b.setOnClickListener(v -> {
            input.setText(s[1]); input.setSelection(input.length()); input.requestFocus();
            InputMethodManager im = (InputMethodManager) c.a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        return b;
    }

    private void submit() {
        if (busy || input == null) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        clearError();
        if (empty != null) { list.removeView(empty); empty = null; }
        turns.add(new String[]{"user", text});
        View mine = bubble(text, true);
        list.addView(mine);
        ask(mine, text);
    }

    private void ask(View mine, String text) {
        busy = true; refreshSend();
        typing = typingBubble();
        list.addView(typing);
        scrollDown();
        final int my = gen;
        final List<String[]> window = window();
        c.io.execute(() -> {
            String reply = null, err = null;
            try { reply = tidy(Llm.chat(c.host, window, SYSTEM, 0.7)); }
            catch (Exception e) { err = e.getMessage() == null ? "Bağlantı kurulamadı" : e.getMessage(); }
            final String r = reply, e = err;
            c.ui.post(() -> {
                if (destroyed || my != gen) return;
                busy = false; refreshSend();
                list.removeView(typing); typing = null;
                if (r != null && !r.isEmpty()) {
                    turns.add(new String[]{"model", r});
                    list.addView(bubble(r, false));
                } else {
                    // drop the failed question from the history and give it back so it can be sent again
                    if (!turns.isEmpty()) turns.remove(turns.size() - 1);
                    list.removeView(mine);
                    if (input.length() == 0) { input.setText(text); input.setSelection(input.length()); }
                    errorBubble = bubbleError(e == null ? "Boş yanıt döndü" : e);
                    list.addView(errorBubble);
                    if (turns.isEmpty() && empty == null) { empty = starters(); list.addView(empty, 0); }
                }
                scrollDown();
            });
        });
    }

    /** The newest turns that fit {@link #MAX_TURNS}, starting with a user turn as the API expects. */
    private List<String[]> window() {
        int from = Math.max(0, turns.size() - MAX_TURNS);
        while (from < turns.size() && !"user".equals(turns.get(from)[0])) from++;
        return new ArrayList<>(turns.subList(from, turns.size()));
    }

    private void reset() {
        if (input == null) return;
        gen++; busy = false;
        turns.clear();
        list.removeAllViews();
        typing = null; errorBubble = null;
        empty = starters();
        list.addView(empty);
        input.setText("");
        refreshSend();
    }

    private void clearError() {
        if (errorBubble != null) { list.removeView(errorBubble); errorBubble = null; }
    }

    private void refreshSend() {
        if (send == null) return;
        send.setAlpha(busy ? 0.4f : 1f);
        send.setEnabled(!busy);
    }

    private void scrollDown() {
        scroll.post(() -> scroll.smoothScrollTo(0, list.getHeight()));
    }

    // ---- bubbles -------------------------------------------------------------------------------

    private View bubble(String text, boolean mine) {
        LinearLayout row = new LinearLayout(c.a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        TextView t = Ui.text(c, text, 15, mine ? c.p.onPrimary : c.p.ink, 400);
        t.setLineSpacing(0, 1.25f);
        t.setTextIsSelectable(true);
        t.setMaxWidth(Math.round(c.a.getResources().getDisplayMetrics().widthPixels * 0.82f));
        t.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(11));
        t.setBackground(Ui.rounded(c, mine ? c.p.primary : c.p.surface2, 18));
        row.addView(t, new LinearLayout.LayoutParams(-2, -2));
        if (!mine) {
            TextView copy = Ui.text(c, "Kopyala", 13, c.p.muted, 600);
            copy.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6));
            copy.setClickable(true);
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Yanıt", text));
                Ui.swapText(copy, "Kopyalandı");
                copy.postDelayed(() -> Ui.swapText(copy, "Kopyala"), 1400);
            });
            row.addView(copy, Ui.lp(-2, -2, c.dp(2), c.dp(2), 0, 0));
        }
        row.setLayoutParams(Ui.lp(-1, -2, mine ? c.dp(40) : 0, c.dp(10), mine ? 0 : c.dp(24), 0));
        return row;
    }

    private View typingBubble() {
        LinearLayout b = new LinearLayout(c.a);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(c.dp(14), c.dp(11), c.dp(16), c.dp(11));
        b.setBackground(Ui.rounded(c, c.p.surface2, 18));
        b.addView(new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(2.5f)), Ui.lp(c.dp(16), c.dp(16), 0, 0, c.dp(10), 0));
        b.addView(Ui.text(c, "Yazıyor…", 14, c.p.muted, 500));
        LinearLayout row = new LinearLayout(c.a);
        row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        row.setLayoutParams(Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        return row;
    }

    private View bubbleError(String msg) {
        TextView t = Ui.text(c, msg, 14, c.p.danger, 500);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10));
        t.setBackground(Ui.rounded(c, c.p.dangerSoft, 16));
        t.setLayoutParams(Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        return t;
    }

    /** Light clean-up for replies that still use markdown despite the instruction. */
    static String tidy(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.replace("\r", "").split("\n", -1)) {
            String l = line;
            String trimmed = l.replaceFirst("^\\s+", "");
            if (trimmed.startsWith("#")) l = trimmed.replaceFirst("^#+\\s*", "");
            else if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) l = l.substring(0, l.length() - trimmed.length()) + "• " + trimmed.substring(2);
            l = l.replace("**", "").replace("__", "");
            out.append(l).append('\n');
        }
        return out.toString().trim().replaceAll("\n{3,}", "\n\n");
    }

    @Override public void onDestroy() {
        destroyed = true; gen++;
        turns.clear();
    }
}
