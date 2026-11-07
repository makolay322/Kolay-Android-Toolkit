package app.kolay.shell;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.widget.RemoteViews;
import java.util.Calendar;

/**
 * Home-screen widget: a rounded card with the next prayer (name, time, live countdown); from about 3 x 2 cells also all six
 * times of that day. Three layouts by size (Android 12+: one RemoteViews per size, the launcher picks; older: from the
 * widget options). All text auto-sizes, so nothing clips. Background colour and transparency are the user's choice
 * ({@link PrayerWidgetConfig}); "Otomatik" follows the wallpaper (off-white on a dark one, near-black on a light one).
 * The text colour is the user's choice too, or by default picked from the resulting background's luminance. Tap = the app's prayer-times screen.
 */
public class PrayerWidget extends AppWidgetProvider {
    /** Must match Entry.ACTION_PRAYER in the app layer. */
    static final String ACTION_PRAYER = "app.kolay.action.PRAYER";
    /** Background value meaning "follow the wallpaper" (real choices are opaque colours, so 0 is free). */
    static final int AUTO = 0;
    static final int OFF_WHITE = 0xFFF5F2EA, SOFT_BLACK = 0xFF1A1A1E;
    private static final int INK_DARK = 0xFF24221F, INK_LIGHT = 0xFFFFFFFF;
    private static final int DEFAULT_OPACITY = 88;
    /** Layout size thresholds (dp): from these sizes up the taller / full layout fits. */
    private static final float TALL_H = 110, FULL_W = 180, FULL_H = 170;
    private static final int[] N_IDS = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5};
    private static final int[] T_IDS = {R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4, R.id.t5};

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

    // ---- appearance (one setting for all widgets) ------------------------------------------------

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("prayer", Context.MODE_PRIVATE); }

    /** @return {background colour or {@link #AUTO}, opacity 0..100, text colour or {@link #AUTO}} */
    static int[] style(Context c) {
        SharedPreferences p = prefs(c);
        return new int[]{p.getInt("w_bg", AUTO), Math.max(0, Math.min(100, p.getInt("w_alpha", DEFAULT_OPACITY))), p.getInt("w_ink", AUTO)};
    }

    /** Saves the appearance and repaints every placed widget. */
    static void setStyle(Context c, int bg, int opacity, int ink) {
        prefs(c).edit().putInt("w_bg", bg == AUTO ? AUTO : bg | 0xFF000000).putInt("w_alpha", Math.max(0, Math.min(100, opacity)))
                .putInt("w_ink", ink == AUTO ? AUTO : ink | 0xFF000000).apply();
        updateAll(c.getApplicationContext());
    }

    /** The full layout with the given (unsaved) appearance, for the live preview in the settings screen. */
    static RemoteViews preview(Context c, int bg, int opacity, int ink) { return build(c, R.layout.widget_prayer_full, new int[]{bg, opacity, ink}); }

    /** The wallpaper's main colour (what shows through a transparent card); a neutral dark / light grey when unknown. */
    static int wallpaperColor(Context c) {
        try {
            android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(c).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wc != null) {
                int col = wc.getPrimaryColor().toArgb() | 0xFF000000;
                if (Build.VERSION.SDK_INT >= 31 && (wc.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                        && Color.luminance(col) < 0.5f) return 0xFFE8E8E8;   // the system says dark text reads well: treat as light
                return col;
            }
        } catch (RuntimeException ignored) { }
        return 0xFF303030;
    }

    // ---- drawing --------------------------------------------------------------------------------

    private static void paint(Context c, AppWidgetManager m, int id) {
        int[] s = style(c);
        RemoteViews v;
        if (Build.VERSION.SDK_INT >= 31) {
            java.util.Map<SizeF, RemoteViews> bySize = new android.util.ArrayMap<>();
            bySize.put(new SizeF(40, 40), build(c, R.layout.widget_prayer, s));
            bySize.put(new SizeF(40, TALL_H), build(c, R.layout.widget_prayer_tall, s));
            bySize.put(new SizeF(FULL_W, FULL_H), build(c, R.layout.widget_prayer_full, s));
            v = new RemoteViews(bySize);
        } else {
            // Android 10/11: the smaller of the portrait / landscape sizes, so the chosen layout fits in both
            Bundle o = m.getAppWidgetOptions(id);
            int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
            int layout = w >= FULL_W && h >= FULL_H ? R.layout.widget_prayer_full : h >= TALL_H ? R.layout.widget_prayer_tall : R.layout.widget_prayer;
            v = build(c, layout, s);
        }
        m.updateAppWidget(id, v);
    }

    private static RemoteViews build(Context c, int layout, int[] style) {
        long now = System.currentTimeMillis();
        long[] n = Prayer.next(c, now);

        int wall = wallpaperColor(c);
        int base = style[0] != AUTO ? style[0] | 0xFF000000 : Color.luminance(wall) > 0.5f ? SOFT_BLACK : OFF_WHITE;
        float a = style[1] / 100f;
        // what the eye sees: the card blended over the wallpaper; dark or light text, whichever contrasts more
        int seen = Color.rgb(mix(Color.red(base), Color.red(wall), a), mix(Color.green(base), Color.green(wall), a), mix(Color.blue(base), Color.blue(wall), a));
        int ink = style[2] != AUTO ? style[2] | 0xFF000000
                : Color.luminance(seen) > 0.179f ? INK_DARK : INK_LIGHT;   // equal-contrast point against black and white
        int soft = (ink & 0x00FFFFFF) | 0xB3000000, faint = (ink & 0x00FFFFFF) | 0x73000000;

        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setInt(R.id.bg, "setColorFilter", base);
        v.setInt(R.id.bg, "setImageAlpha", Math.round(a * 255));
        Intent open = new Intent(c, MainActivity.class).setAction(ACTION_PRAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        v.setTextColor(R.id.name, soft);
        v.setTextColor(R.id.time, ink);
        v.setTextColor(R.id.left, soft);
        if (n == null) {   // no location saved yet
            v.setTextViewText(R.id.name, "Namaz vakti");
            v.setTextViewText(R.id.time, "--:--");
            v.setChronometer(R.id.left, SystemClock.elapsedRealtime(), "Konum için dokun", false);
            v.setContentDescription(R.id.root, "Namaz vakitleri");
            return v;
        }
        int idx = (int) n[0];
        String t = Prayer.clock(n[1]);
        v.setTextViewText(R.id.name, Prayer.NAMES[idx]);
        v.setTextViewText(R.id.time, t);
        v.setChronometer(R.id.left, SystemClock.elapsedRealtime() + (n[1] - now), "%s kaldı", true);
        v.setChronometerCountDown(R.id.left, true);
        v.setContentDescription(R.id.root, "Sıradaki vakit: " + Prayer.NAMES[idx] + " " + t);

        if (layout == R.layout.widget_prayer_full) {
            // the day the next time belongs to (tomorrow after Yatsı)
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(n[1]);
            long[] day = Prayer.times(c, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            for (int i = 0; i < 6 && day != null; i++) {
                int col = i == idx ? ink : day[i] <= now ? faint : soft;
                v.setTextViewText(N_IDS[i], Prayer.NAMES[i]);
                v.setTextViewText(T_IDS[i], Prayer.clock(day[i]));
                v.setTextColor(N_IDS[i], col);
                v.setTextColor(T_IDS[i], col);
            }
        }
        return v;
    }

    private static int mix(int fg, int bg, float a) { return Math.round(fg * a + bg * (1 - a)); }
}
