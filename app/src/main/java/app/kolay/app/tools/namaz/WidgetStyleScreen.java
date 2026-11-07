package app.kolay.app.tools.namaz;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.kolay.app.ui.*;
import java.util.Locale;

/**
 * "Widget görünümü": background colour, transparency and text colour of the prayer widget, with a live preview (the real widget layout).
 * Opened from the prayer-times screen, or by the launcher as the widget's configure page ({@code configure}: leaving closes
 * the activity). Text colour "Otomatik" picks dark or light text from the background the widget ends up on.
 */
public final class WidgetStyleScreen extends Screen {
    /** 0 = Otomatik (follows the wallpaper); the rest are opaque colours. */
    private static final int[] COLORS = {0, 0xFFF5F2EA, 0xFF1A1A1E, 0xFF1F3A5F, 0xFF2E6B4F, 0xFF7A2E3A, 0xFF4B3B8F, 0xFFE9D9B4, 0xFFCFE3F5};
    private static final String[] NAMES = {"Otomatik", "Kırık beyaz", "Hafif siyah", "Lacivert", "Yeşil", "Bordo", "Mor", "Kum", "Buz mavisi"};
    /** 0 = Otomatik (dark or light by the background). */
    private static final int[] INKS = {0, 0xFFFFFFFF, 0xFFF5ECD7, 0xFFD4AF37, 0xFF111111, 0xFF0E2340, 0xFF0B5D3B, 0xFF6D1A2E};
    private static final String[] INK_NAMES = {"Otomatik", "Beyaz", "Krem", "Altın", "Siyah", "Lacivert", "Yeşil", "Bordo"};

    private final boolean configure;
    private int bg, opacity, ink;
    private FrameLayout previewBox;
    private View preview;
    private TextView bgLabel, alphaLabel, inkLabel;
    private final FrameLayout[] swatches = new FrameLayout[COLORS.length], inkSwatches = new FrameLayout[INKS.length];

    public WidgetStyleScreen(boolean configure) { this.configure = configure; }

    @Override public View create() {
        int[] s = c.host.prayerWidgetStyle();
        bg = s[0]; opacity = s[1]; ink = s[2];

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Widget görünümü", "Namaz vakti widget'ı", this::close).view);
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c.a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // Preview: the widget on the wallpaper's main colour, so transparency and text contrast look as on the home screen.
        previewBox = new FrameLayout(c.a);
        previewBox.setPadding(c.dp(24), c.dp(22), c.dp(24), c.dp(22));
        int wall = c.host.prayerWallpaperColor();
        previewBox.setBackground(Ui.outlined(c, wall, c.p.line, Ui.R_CARD));
        body.addView(previewBox, Ui.lp(-1, c.dp(200)));
        refreshPreview();

        bgLabel = Ui.label(c, "");
        body.addView(bgLabel, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), c.dp(8)));
        body.addView(swatchCard(NAMES, swatches, k -> bg = COLORS[k]), Ui.lp(-1, -2));

        alphaLabel = Ui.label(c, "");
        body.addView(alphaLabel, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), c.dp(8)));
        LinearLayout card2 = Ui.card(c, c.p.surface, 12);
        SeekBar bar = new SeekBar(c.a);
        bar.setMax(100);
        bar.setProgress(100 - opacity);   // the slider is transparency: 0 = solid card, 100 = no card
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(c.p.primary);
        bar.setProgressTintList(tint); bar.setThumbTintList(tint);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(c.p.track));
        bar.setContentDescription("Saydamlık");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) { opacity = 100 - p; refresh(); } }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        card2.addView(bar, Ui.lp(-1, c.dp(40)));
        body.addView(card2, Ui.lp(-1, -2));

        inkLabel = Ui.label(c, "");
        body.addView(inkLabel, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), c.dp(8)));
        body.addView(swatchCard(INK_NAMES, inkSwatches, k -> ink = INKS[k]), Ui.lp(-1, -2));

        TextView note = Ui.text(c, "Otomatik yazı rengi arka plana göre seçilir. Ayar tüm Kolay widget'larına uygulanır.", 12, c.p.muted, 400);
        note.setLineSpacing(0, 1.15f);
        note.setGravity(Gravity.CENTER);
        body.addView(note, Ui.lp(-1, -2, c.dp(8), c.dp(12), c.dp(8), 0));

        LinearLayout bb = Ui.bottomBar(c);
        TextView save = Ui.button(c, "Kaydet", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        save.setOnClickListener(v -> {
            c.host.prayerSetWidgetStyle(bg, opacity, ink);
            Ui.haptic(v, android.view.HapticFeedbackConstants.CONFIRM);
            if (configure) c.a.finish();
            else { Ui.toast(c, "Widget güncellendi"); c.nav.pop(); }
        });
        bb.addView(save, Ui.lp(-1, c.dp(Ui.H_BAR)));
        page.addView(bb, Ui.lp(-1, -2));
        refresh();
        return page;
    }

    @Override public boolean onBack() { if (configure) { c.a.finish(); return true; } return false; }

    private void close() { if (configure) c.a.finish(); else c.nav.pop(); }

    /** A card of round colour swatches, five per row; a tap runs {@code pick} with the index and refreshes. */
    private LinearLayout swatchCard(String[] names, FrameLayout[] out, java.util.function.IntConsumer pick) {
        LinearLayout card = Ui.card(c, c.p.surface, 12);
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(c.a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(row, Ui.lp(-1, c.dp(56)));
            }
            final int k = i;
            FrameLayout sw = new FrameLayout(c.a);
            sw.setContentDescription(names[i]);
            sw.setClickable(true);
            sw.setOnClickListener(v -> { Ui.haptic(v, android.view.HapticFeedbackConstants.CLOCK_TICK); pick.accept(k); refresh(); });
            Ui.press(sw);
            out[i] = sw;
            row.addView(sw, new LinearLayout.LayoutParams(0, c.dp(48), 1));
        }
        for (int i = names.length; i % 5 != 0; i++) row.addView(new Space(c.a), new LinearLayout.LayoutParams(0, 1, 1));
        return card;
    }

    private void refresh() {
        Locale tr = new Locale("tr", "TR");
        bgLabel.setText("ARKA PLAN · " + NAMES[paint(swatches, COLORS, bg)].toUpperCase(tr));
        alphaLabel.setText(String.format(Locale.US, "SAYDAMLIK · %%%d", 100 - opacity));
        inkLabel.setText("YAZI RENGİ · " + INK_NAMES[paint(inkSwatches, INKS, ink)].toUpperCase(tr));
        refreshPreview();
    }

    /** Redraws the swatches (index 0 = Otomatik, a half light / half dark dot) and marks {@code value}; returns its index. */
    private int paint(FrameLayout[] swatches, int[] colors, int value) {
        int sel = 0;
        for (int i = 0; i < colors.length; i++) if (colors[i] == value) sel = i;
        for (int i = 0; i < swatches.length; i++) {
            FrameLayout sw = swatches[i];
            sw.removeAllViews();
            View dot = new View(c.a);
            GradientDrawable d = i == 0
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{COLORS[1], COLORS[1], COLORS[2], COLORS[2]})
                : new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (i != 0) d.setColor(colors[i]);
            d.setStroke(c.dp(i == sel ? 3 : 1), i == sel ? c.p.primary : c.p.line);
            dot.setBackground(d);
            sw.addView(dot, new FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.CENTER));
            if (i == sel) {
                int fg = i == 0 ? c.p.primary : android.graphics.Color.luminance(colors[i]) > 0.179f ? 0xFF24221F : 0xFFFFFFFF;
                sw.addView(Ui.icon(c, Icon.Kind.CHECK, fg, 20), new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
            }
        }
        return sel;
    }

    private void refreshPreview() {
        android.widget.RemoteViews rv = c.host.prayerWidgetPreview(bg, opacity, ink);
        try {
            if (preview == null) { preview = rv.apply(c.a, previewBox); previewBox.addView(preview, new FrameLayout.LayoutParams(-1, -1)); }
            else rv.reapply(c.a, preview);
        } catch (RuntimeException e) {   // preview only; never block the settings
            previewBox.removeAllViews(); preview = null;
        }
    }
}
