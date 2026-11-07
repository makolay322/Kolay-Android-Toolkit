package app.kolay.app.tools.namaz;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.*;
import app.kolay.app.ui.*;
import java.util.Locale;

/**
 * "Widget görünümü": background colour, transparency and text colour of the prayer widget. The live preview (the real widget
 * layout, at a size picked below it) stays on top while the options under it change it; every change is applied to the placed
 * widgets at once, so there is nothing to save. Opened from the prayer-times screen, or by the launcher as the widget's
 * configure page ({@code configure}: leaving closes the activity). Text colour "Otomatik" picks dark or light text from the
 * background the widget ends up on.
 */
public final class WidgetStyleScreen extends Screen {
    /** 0 = Otomatik (follows the wallpaper); the rest are opaque colours. */
    private static final int[] COLORS = {0, 0xFFF5F2EA, 0xFF1A1A1E, 0xFF1F3A5F, 0xFF2E6B4F, 0xFF7A2E3A, 0xFF4B3B8F, 0xFFE9D9B4, 0xFFCFE3F5};
    private static final String[] NAMES = {"Otomatik", "Kırık beyaz", "Hafif siyah", "Lacivert", "Yeşil", "Bordo", "Mor", "Kum", "Buz mavisi"};
    /** 0 = Otomatik (dark or light by the background). */
    private static final int[] INKS = {0, 0xFFFFFFFF, 0xFFF5ECD7, 0xFFD4AF37, 0xFF111111, 0xFF0E2340, 0xFF0B5D3B, 0xFF6D1A2E};
    private static final String[] INK_NAMES = {"Otomatik", "Beyaz", "Krem", "Altın", "Siyah", "Lacivert", "Yeşil", "Bordo"};
    /** Default opacity; matches PrayerWidget's. */
    private static final int DEFAULT_OPACITY = 88;
    /** Transparency quick picks (%). */
    private static final int[] PRESETS = {0, 25, 50, 75, 100};
    /** Preview sizes: label and widget size in dp (picks the same layout the launcher would at about that cell size). */
    private static final String[] SIZES = {"1×1", "5×1", "1×3", "3×2"};
    private static final int[][] SIZE_DP = {{80, 80}, {360, 72}, {84, 184}, {260, 172}};

    private final boolean configure;
    private int bg, opacity, ink, size = 3, shownSize = -1;
    private FrameLayout previewBox, sizeBox;
    private View preview;
    private TextView bgName, alphaName, inkName;
    private SeekBar bar;
    private final FrameLayout[] swatches = new FrameLayout[COLORS.length], inkSwatches = new FrameLayout[INKS.length];
    private final TextView[] presets = new TextView[PRESETS.length];

    public WidgetStyleScreen(boolean configure) { this.configure = configure; }

    @Override public View create() {
        int[] s = c.host.prayerWidgetStyle();
        bg = s[0]; opacity = s[1]; ink = s[2];

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Widget görünümü", "Değişiklikler hemen uygulanır", this::close).view);

        // Sticky preview: the widget on the wallpaper's main colour, so transparency and text contrast look as on the home screen.
        previewBox = new FrameLayout(c.a);
        previewBox.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8));
        previewBox.setBackground(Ui.outlined(c, c.host.prayerWallpaperColor(), c.p.line, Ui.R_CARD));
        page.addView(previewBox, Ui.lp(-1, c.dp(200), c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0));
        sizeBox = new FrameLayout(c.a);
        page.addView(sizeBox, Ui.lp(-1, c.dp(44), c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(4)));

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c.a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bgCard = section("Arka plan");
        bgName = (TextView) bgCard.getTag();
        bgCard.addView(swatchRow(NAMES, swatches, k -> bg = COLORS[k]));
        body.addView(bgCard, Ui.lp(-1, -2));

        LinearLayout alphaCard = section("Saydamlık");
        alphaName = (TextView) alphaCard.getTag();
        bar = new SeekBar(c.a);
        bar.setMax(100);
        bar.setProgress(100 - opacity);   // the slider is transparency: 0 = solid card, 100 = no card
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(c.p.primary);
        bar.setProgressTintList(tint); bar.setThumbTintList(tint);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(c.p.track));
        bar.setContentDescription("Saydamlık");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) { opacity = 100 - p; refresh(false); } }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { apply(); }   // the placed widgets only once the finger lifts
        });
        alphaCard.addView(bar, Ui.lp(-1, c.dp(36)));
        LinearLayout chips = new LinearLayout(c.a);
        for (int i = 0; i < PRESETS.length; i++) {
            final int k = i;
            TextView chip = Ui.text(c, "%" + PRESETS[i], Ui.T_SUB, c.p.muted, 600);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); opacity = 100 - PRESETS[k]; bar.setProgress(PRESETS[k]); refresh(true); });
            Ui.press(chip);
            presets[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, c.dp(36), 1);
            if (i > 0) lp.setMarginStart(c.dp(6));
            chips.addView(chip, lp);
        }
        alphaCard.addView(chips, Ui.lp(-1, -2, 0, c.dp(4), 0, 0));
        body.addView(alphaCard, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        LinearLayout inkCard = section("Yazı rengi");
        inkName = (TextView) inkCard.getTag();
        inkCard.addView(swatchRow(INK_NAMES, inkSwatches, k -> ink = INKS[k]));
        body.addView(inkCard, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        TextView note = Ui.text(c, "Otomatik: arka plan duvar kâğıdına, yazı rengi arka plana göre seçilir. Ayar tüm Namaz widget'larına uygulanır.", 12, c.p.muted, 400);
        note.setLineSpacing(0, 1.15f);
        note.setGravity(Gravity.CENTER);
        body.addView(note, Ui.lp(-1, -2, c.dp(8), c.dp(12), c.dp(8), 0));

        LinearLayout bb = Ui.bottomBar(c);
        TextView reset = Ui.button(c, "Varsayılan", Ui.Style.GHOST, Icon.Kind.REFRESH);
        reset.setOnClickListener(v -> {
            bg = 0; opacity = DEFAULT_OPACITY; ink = 0;
            bar.setProgress(100 - opacity);
            Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            refresh(true);
            Ui.toast(c, "Varsayılan görünüm");
        });
        TextView done = Ui.button(c, "Tamam", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        done.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CONFIRM); close(); });
        bb.addView(Ui.pair(c, reset, done, Ui.H_BAR), Ui.lp(-1, -2));
        page.addView(bb, Ui.lp(-1, -2));

        sizePicker();
        refresh(false);
        return page;
    }

    @Override public boolean onBack() { if (configure) { c.a.finish(); return true; } return false; }

    private void close() { if (configure) c.a.finish(); else c.nav.pop(); }

    /** Saves the current choice and repaints the placed widgets. */
    private void apply() { c.host.prayerSetWidgetStyle(bg, opacity, ink); }

    /** A card with a title on the left and the current choice (returned as the card's tag) on the right. */
    private LinearLayout section(String title) {
        LinearLayout card = Ui.card(c, c.p.surface, 12);
        LinearLayout head = new LinearLayout(c.a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(c.dp(4), 0, c.dp(4), 0);
        head.addView(Ui.text(c, title, Ui.T_BODY, c.p.ink, 700), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = Ui.text(c, "", Ui.T_SUB, c.p.primary, 600);
        head.addView(value, Ui.lp(-2, -2));
        card.addView(head, Ui.lp(-1, -2, 0, c.dp(2), 0, c.dp(6)));
        card.setTag(value);
        return card;
    }

    /** One horizontally scrolling row of round colour swatches; a tap runs {@code pick} with the index and applies it. */
    private HorizontalScrollView swatchRow(String[] names, FrameLayout[] out, java.util.function.IntConsumer pick) {
        HorizontalScrollView hs = new HorizontalScrollView(c.a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            FrameLayout sw = new FrameLayout(c.a);
            sw.setClickable(true);
            sw.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); pick.accept(k); refresh(true); });
            Ui.press(sw);
            out[i] = sw;
            row.addView(sw, new LinearLayout.LayoutParams(c.dp(48), c.dp(48)));
        }
        hs.addView(row, new FrameLayout.LayoutParams(-2, -2));
        return hs;
    }

    /** The preview size picker (rebuilt on a pick: the segmented control is stateless). */
    private void sizePicker() {
        sizeBox.removeAllViews();
        sizeBox.addView(Ui.segmented(c, SIZES, null, size, k -> { size = k; sizePicker(); refreshPreview(); }), new FrameLayout.LayoutParams(-1, -1));
    }

    /** Redraws the controls and the preview; {@code save} also applies the choice to the placed widgets. */
    private void refresh(boolean save) {
        String[] names = {NAMES[paint(swatches, COLORS, bg, NAMES)], INK_NAMES[paint(inkSwatches, INKS, ink, INK_NAMES)]};
        bgName.setText(names[0]);
        inkName.setText(names[1]);
        int t = 100 - opacity;
        alphaName.setText(String.format(Locale.US, "%%%d", t));
        for (int i = 0; i < presets.length; i++) {
            boolean on = PRESETS[i] == t;
            presets[i].setTextColor(on ? c.p.onPrimary : c.p.muted);
            presets[i].setBackground(Ui.rounded(c, on ? c.p.primary : c.p.surface2, Ui.R_CHIP));
            presets[i].setContentDescription("Saydamlık %" + PRESETS[i] + (on ? ", seçili" : ""));
        }
        refreshPreview();
        if (save) apply();
    }

    /** Redraws the swatches (index 0 = Otomatik, a half light / half dark dot) and marks {@code value}; returns its index. */
    private int paint(FrameLayout[] swatches, int[] colors, int value, String[] names) {
        int sel = 0;
        for (int i = 0; i < colors.length; i++) if (colors[i] == value) sel = i;
        for (int i = 0; i < swatches.length; i++) {
            FrameLayout sw = swatches[i];
            sw.removeAllViews();
            sw.setContentDescription(names[i] + (i == sel ? ", seçili" : ""));
            View dot = new View(c.a);
            GradientDrawable d = i == 0
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{COLORS[1], COLORS[1], COLORS[2], COLORS[2]})
                : new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (i != 0) d.setColor(colors[i]);
            d.setStroke(c.dp(i == sel ? 3 : 1), i == sel ? c.p.primary : c.p.line);
            dot.setBackground(d);
            sw.addView(dot, new FrameLayout.LayoutParams(c.dp(38), c.dp(38), Gravity.CENTER));
            if (i == sel) {
                int fg = i == 0 ? c.p.primary : Color.luminance(colors[i]) > 0.179f ? 0xFF24221F : 0xFFFFFFFF;
                sw.addView(Ui.icon(c, Icon.Kind.CHECK, fg, 18), new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
            }
        }
        return sel;
    }

    private void refreshPreview() {
        int[] d = SIZE_DP[size];
        RemoteViews rv = c.host.prayerWidgetPreview(bg, opacity, ink, d[0], d[1]);
        try {
            if (preview == null || shownSize != size) {   // another size may be another layout: inflate afresh
                previewBox.removeAllViews();
                preview = rv.apply(c.a, previewBox);
                shownSize = size;
                previewBox.addView(preview, new FrameLayout.LayoutParams(size == 1 ? -1 : c.dp(d[0]), c.dp(d[1]), Gravity.CENTER));
            } else rv.reapply(c.a, preview);
        } catch (RuntimeException e) {   // preview only; never block the settings
            previewBox.removeAllViews(); preview = null;
        }
    }
}
