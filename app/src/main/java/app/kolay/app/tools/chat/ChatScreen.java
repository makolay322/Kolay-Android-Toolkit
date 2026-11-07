package app.kolay.app.tools.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.*;
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
        empty = hint();
        list.addView(empty);

        // input bar: growing multi-line field + round send button
        LinearLayout bar = new LinearLayout(c.a);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        input = Ui.input(c, "Bir şey sor veya yapıştır…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false); input.setMaxLines(6); input.setMinHeight(c.dp(Ui.H_BTN));
        input.setPadding(c.dp(18), c.dp(14), c.dp(18), c.dp(14));
        input.setBackground(chatInputBg());
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        send = new FrameLayout(c.a);
        send.setBackground(Ui.oval(c.p.primary));
        Ui.roundOutline(c, send, 26);
        if (!c.p.dark) Ui.softShadow(c, send, 3, c.p.primary);
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

    /** Pill-shaped field: soft fill, accent outline while focused. */
    private android.graphics.drawable.Drawable chatInputBg() {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, Ui.outlined(c, c.p.surface, c.p.primary, 26));
        d.addState(new int[0], c.p.dark ? Ui.outlined(c, c.p.surface, c.p.line, 26) : Ui.rounded(c, c.p.surface, 26));
        return d;
    }

    /** Bubble shape: round, with the corner nearest the sender's edge tucked in. */
    private android.graphics.drawable.GradientDrawable bubbleBg(boolean mine) {
        float r = c.dp(20), k = c.dp(6);
        android.graphics.drawable.GradientDrawable d = mine
            ? new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{c.p.heroA, c.p.heroMid})
            : new android.graphics.drawable.GradientDrawable();
        if (!mine) { d.setColor(c.p.surface); if (c.p.dark) d.setStroke(Math.max(1, c.dp(1)), c.p.line); }
        d.setCornerRadii(mine ? new float[]{r, r, r, r, k, k, r, r} : new float[]{r, r, r, r, r, r, k, k});
        return d;
    }

    /** Empty-state hint shown until the first message. */
    private View hint() {
        View hint = Ui.state(c, Icon.Kind.CHAT, "Ne sormak istersin?", "Bir şey sor ya da bir metni yapıştırıp ne yapılacağını yaz. "
                + "Konuşma yalnızca bu ekran açıkken tutulur; çıkınca silinir.", null, null);
        hint.setLayoutParams(Ui.lp(-1, -2, 0, c.dp(48), 0, 0));
        return hint;
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
            try { reply = Llm.chat(c.host, window, 0.7); if (reply != null) reply = reply.trim(); }
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
                    if (turns.isEmpty() && empty == null) { empty = hint(); list.addView(empty, 0); }
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
        empty = hint();
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
        TextView t = Ui.text(c, text, 15, mine ? c.p.onHero : c.p.ink, 400);
        t.setLineSpacing(0, 1.25f);
        t.setTextIsSelectable(true);
        t.setMaxWidth(Math.round(c.a.getResources().getDisplayMetrics().widthPixels * 0.82f));
        t.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(11));
        t.setBackground(bubbleBg(mine));
        if (!mine && !c.p.dark) { Ui.roundOutline(c, t, 20); Ui.softShadow(c, t, 1.5f, c.p.shadow); }
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
        b.setBackground(bubbleBg(false));
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

    @Override public void onDestroy() {
        destroyed = true; gen++;
        turns.clear();
    }
}
