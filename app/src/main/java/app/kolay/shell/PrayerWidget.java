package app.kolay.shell;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.*;
import android.widget.RemoteViews;

/**
 * Home-screen widget: only the next prayer's time, 24-hour, light text on a rounded card that contrasts with the wallpaper
 * (off-white card with dark text on a dark wallpaper, translucent near-black card with pastel-gradient text on a light one).
 * It adapts to its size: one line when wide, hours above minutes otherwise, always scaled to fill the widget. A small lowercase
 * letter in the top-right corner names the time it belongs to (s, g, ö, i, a, y; g = sunrise). Tap = the app's prayer-times screen.
 */
public class PrayerWidget extends AppWidgetProvider {
    /** Must match Entry.ACTION_PRAYER in the app layer. */
    static final String ACTION_PRAYER = "app.kolay.action.PRAYER";
    private static final int CARD_LIGHT = 0xEBF5F2EA, INK_DARK = 0xFF2A2824, CARD_DARK = 0xB31A1A1E;
    /** Pastel gradients, three stops each. */
    private static final int[][] PALETTES = {
        {0xFFB4C0FF, 0xFFF5B8E4, 0xFFFFD0A8},   // lavender - pink - peach
        {0xFFA8E6CF, 0xFFA8D8F0, 0xFFC9B8FF},   // mint - sky - lilac
        {0xFFFFC2D1, 0xFFFFDDB5, 0xFFFFF3B0},   // rose - apricot - butter
        {0xFF9FE0E8, 0xFFB5C7FF, 0xFFE3B8FF},   // aqua - periwinkle - orchid
        {0xFFC5F0A4, 0xFFA5E8D0, 0xFF9FD0F5},   // lime - mint - blue
        {0xFFFFB8B8, 0xFFFFC9E8, 0xFFD4C2FF},   // coral - pink - violet
        {0xFFFFD6A5, 0xFFFFB5C8, 0xFFC4B5FF},   // sand - blush - lavender
    };
    /** Corner letter per time index (Prayer.NAMES order; g = Güneş, sunrise). */
    private static final String[] LETTERS = {"s", "g", "ö", "i", "a", "y"};

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Prayer.refresh(c);   // keeps the alarms armed even if the app was never opened after an install
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) { paint(c, m, id); }

    @Override public void onEnabled(Context c) { Prayer.refresh(c); }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, PrayerWidget.class));
        if (ids == null) return;
        for (int id : ids) paint(c, m, id);
    }

    private static void paint(Context c, AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        // size in dp as the launcher currently shows it (min width / max height of the portrait case)
        float w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
        if (w <= 0) w = 110;
        if (h <= 0) h = 110;
        // Stack hours over minutes unless the widget is clearly wide: a narrow widget then gets big digits instead of tiny ones.
        boolean stacked = h >= w * 0.8f;

        long[] n = Prayer.next(c, System.currentTimeMillis());
        String t = n == null ? "--:--" : Prayer.clock(n[1]);
        String letter = n == null ? "" : LETTERS[(int) n[0]];

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_prayer);
        Intent open = new Intent(c, MainActivity.class).setAction(ACTION_PRAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setImageViewBitmap(R.id.img, draw(c, t, letter, w, h, stacked, lightWallpaper(c)));
        v.setContentDescription(R.id.root, n == null ? "Namaz vakitleri" : "Sıradaki vakit: " + Prayer.NAMES[(int) n[0]] + " " + t);
        m.updateAppWidget(id, v);
    }

    /** True if the home-screen wallpaper is light (the system says dark text reads well on it); unknown counts as dark. */
    private static boolean lightWallpaper(Context c) {
        try {
            android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(c).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wc == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 31 && (wc.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0) return true;
            return Color.luminance(wc.getPrimaryColor().toArgb()) > 0.6f;
        } catch (RuntimeException e) { return false; }
    }

    /** The time on a rounded card, text scaled to fill the widget (bitmap kept small: RemoteViews has a size limit). */
    private static Bitmap draw(Context c, String t, String letter, float wDp, float hDp, boolean stacked, boolean lightWall) {
        float d = c.getResources().getDisplayMetrics().density;
        float scale = Math.min(1f, 640f / Math.max(wDp * d, hDp * d));
        int bw = Math.max(32, Math.round(wDp * d * scale)), bh = Math.max(32, Math.round(hDp * d * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
        card.setColor(lightWall ? CARD_DARK : CARD_LIGHT);
        float r = Math.min(Math.min(bw, bh) * 0.24f, 22 * d * scale);
        cv.drawRoundRect(0, 0, bw, bh, r, r, card);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        Typeface base = Typeface.create("sans-serif", Typeface.NORMAL);
        p.setTypeface(Typeface.create(base, 300, false));     // light weight (API 28+, minSdk is 29)
        p.setTextAlign(Paint.Align.CENTER);
        p.setLetterSpacing(stacked ? 0f : 0.02f);

        String[] lines = stacked && t.length() == 5 ? new String[]{t.substring(0, 2), t.substring(3)} : new String[]{t};
        float pad = Math.min(bw, bh) * 0.10f;
        // the corner letter gets its own band above the digits; the time is centred in the space below it
        float letterH = letter.isEmpty() ? 0f : Math.min(bw, bh) * 0.12f, band = letterH * 1.6f;
        float availW = bw - 2 * pad, availH = bh - 2 * pad - band;
        // fit: measure at a reference size, then scale to the tighter of width and height
        p.setTextSize(100f);
        float maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, p.measureText(l));
        Paint.FontMetrics fm = p.getFontMetrics();
        float digitH = -fm.ascent * 0.74f;                    // cap/digit height is about 74% of the ascent
        float gap = 28f;                                       // reference px between stacked lines
        float totalH = lines.length * digitH + (lines.length - 1) * gap;
        float k = Math.min(availW / Math.max(1f, maxW), availH / Math.max(1f, totalH));
        p.setTextSize(100f * k);
        digitH *= k; gap *= k;
        float blockH = lines.length * digitH + (lines.length - 1) * gap;

        // dark card: pastel diagonal gradient (the palette saved earlier); off-white card: plain dark ink
        if (lightWall) p.setShader(new LinearGradient(pad, pad, bw - pad, bh - pad,
            PALETTES[Math.floorMod(c.getSharedPreferences("prayer", Context.MODE_PRIVATE).getInt("pal", 0), PALETTES.length)], new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        else p.setColor(INK_DARK);
        float y = pad + band + (availH - blockH) / 2f + digitH;
        for (String l : lines) { cv.drawText(l, bw / 2f, y, p); y += digitH + gap; }
        if (!letter.isEmpty()) {
            // x-height is about half the text size; size it so the letter's body is letterH tall
            p.setTextSize(letterH / 0.52f);
            p.setLetterSpacing(0f);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(letter, bw - pad, pad + letterH * 1.5f, p);   // room above for the dots of ö and i
        }
        return bmp;
    }
}
