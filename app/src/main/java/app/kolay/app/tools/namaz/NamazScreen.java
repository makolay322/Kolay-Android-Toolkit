package app.kolay.app.tools.namaz;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Today's prayer times for the saved location, with a live countdown, the reminder switch and the widget buttons (add, appearance).
 * The calculation, alarms and widget live in the shell (they must work while the app is closed); this is only the front end.
 */
final class NamazScreen extends Screen {
    private static final int REQ_LOC = 71, REQ_NOTIF = 72;
    private static final String[] NAMES = {"Sabah", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};

    private Header header;
    private LinearLayout body;
    private TextView heroName, heroTime, heroLeft;
    private int pendingBit = -1;       // toggle waiting for the notification permission
    private final Toggle[] toggles = new Toggle[6];
    private long[] today, tomorrow;
    private long nextAt;                // the instant the countdown runs to
    private boolean destroyed, ticking, showingTimes, awaitingSettings;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || !ticking) return;
            if (countdown()) c.ui.postDelayed(this, 1000);   // false: render() already restarted the loop
        }
    };

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Namaz Vakitleri", "Konuma göre", () -> c.nav.pop());
        page.addView(header.view);
        ScrollView sv = new ScrollView(c.a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(c.a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    @Override public void onShown() { start(); }
    /** Back from the system settings (exact alarms, location): rebuild so buttons and times reflect the new state. */
    @Override public void onResume() {
        if (showingTimes) render();
        else if (awaitingSettings && hasLocationPermission()) { awaitingSettings = false; locate(false); }
    }
    @Override public void onPause() { ticking = false; c.ui.removeCallbacks(tick); }
    @Override public void onDestroy() { destroyed = true; ticking = false; c.ui.removeCallbacks(tick); }

    // ---- flow ---------------------------------------------------------------------------------

    private void start() {
        if (hasLocationPermission()) locate(c.host.prayerLocation() != null);
        if (c.host.prayerLocation() != null) render();
        else if (!hasLocationPermission()) askLocation();
    }

    private boolean hasLocationPermission() { return c.a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private void askLocation() {
        state(Icon.Kind.PIN, "Konum gerekli", "Vakitler bulunduğun yere göre hesaplanır. Konum yalnızca cihazında kalır; internete gönderilmez.",
            "Konumu bul", () -> c.a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC));
    }

    @Override public void onPermission(int req, String[] perms, int[] r) {
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_LOC) {
            if (ok) locate(false);
            else if (c.host.prayerLocation() != null) say("Konum izni verilmedi; kayıtlı konum kullanılıyor");
            else state(Icon.Kind.PIN, "Konum izni verilmedi", "Vakitleri hesaplamak için yaklaşık konum izni gerekir.", "Ayarları aç", this::openAppSettings);
        } else if (req == REQ_NOTIF) {
            int bit = pendingBit; pendingBit = -1;
            if (ok && bit >= 0) c.host.prayerSetReminderMask(c.host.prayerReminderMask() | (1 << bit));
            else { if (bit >= 0 && toggles[bit] != null) toggles[bit].set(false, true); say("Bildirim izni olmadan hatırlatıcı gösterilemez"); }
        }
    }

    /** Takes the most recent known position, or asks the system for a fresh one. {@code quiet}: no error message when it fails. */
    private void locate(boolean quiet) {
        if (!showingTimes) {   // nothing on screen yet: show that we are looking
            body.removeAllViews();
            body.setGravity(Gravity.CENTER);
            body.addView(Ui.busy(c, "Konum bulunuyor…", "Vakitler bu konuma göre hesaplanacak"), Ui.lp(-1, -2));
        }
        try {
            LocationManager lm = (LocationManager) c.a.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String pv : lm.getProviders(true)) {
                try {
                    Location l = lm.getLastKnownLocation(pv);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException | IllegalArgumentException ignored) { }   // GPS needs the fine permission
            }
            if (best != null) { apply(best, quiet); return; }
            String pv = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
            if (pv == null || Build.VERSION.SDK_INT < 30) { if (!quiet) fail(); return; }
            lm.getCurrentLocation(pv, null, c.a.getMainExecutor(), loc -> {
                if (destroyed) return;
                if (loc != null) apply(loc, quiet); else if (!quiet) fail();
            });
        } catch (Throwable t) { if (!quiet) fail(); }
    }

    private void fail() {
        if (c.host.prayerLocation() != null) { say("Konum alınamadı; kayıtlı konum kullanılıyor"); return; }
        state(Icon.Kind.PIN, "Konum alınamadı", "Cihazın konum servisini açıp tekrar dene.", "Tekrar dene", () -> locate(false));
    }

    private void openAppSettings() {
        awaitingSettings = true;
        c.a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.a.getPackageName(), null)));
    }

    private void apply(Location l, boolean quiet) {
        double[] old = c.host.prayerLocation();
        if (old == null || Math.abs(old[0] - l.getLatitude()) > 0.05 || Math.abs(old[1] - l.getLongitude()) > 0.05)   // ~5 km: no churn from GPS jitter
            c.host.prayerSetLocation(l.getLatitude(), l.getLongitude());
        boolean refresh = showingTimes;
        render();
        if (!quiet && refresh) say("Konum güncellendi");
    }

    // ---- views --------------------------------------------------------------------------------

    /** Replaces the page with a centered message (no countdown runs behind it). */
    private void state(Icon.Kind icon, String title, String text, String btn, Runnable action) {
        showingTimes = false; ticking = false; c.ui.removeCallbacks(tick);
        heroLeft = null;
        body.removeAllViews();
        body.setGravity(Gravity.CENTER);
        LinearLayout st = Ui.state(c, icon, title, text, btn, action);
        body.addView(st, Ui.lp(-1, -2));
        Ui.appear(st, 0);
    }

    private void load() {
        Calendar now = Calendar.getInstance();
        today = c.host.prayerTimes(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        now.add(Calendar.DAY_OF_YEAR, 1);
        tomorrow = c.host.prayerTimes(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
    }

    /** {index, instant, day} of the next time (sunrise included); day 0 = today, 1 = tomorrow. */
    private long[] next(long now) {
        for (int d = 0; d < 2; d++) {
            long[] t = d == 0 ? today : tomorrow;
            if (t == null) return null;
            for (int i = 0; i < 6; i++) if (t[i] > now) return new long[]{i, t[i], d};   // sunrise included
        }
        return null;
    }

    private void render() {
        if (destroyed) return;
        load();
        if (today == null) { askLocation(); return; }
        double[] loc = c.host.prayerLocation();
        header.subtitle.setText(new SimpleDateFormat("d MMMM EEEE", new Locale("tr", "TR")).format(new Date())
            + String.format(Locale.US, " · %.2f° %s, %.2f° %s", Math.abs(loc[0]), loc[0] >= 0 ? "K" : "G", Math.abs(loc[1]), loc[1] >= 0 ? "D" : "B"));

        showingTimes = true;
        body.removeAllViews();
        body.setGravity(Gravity.NO_GRAVITY);
        long now = System.currentTimeMillis();
        long[] n = next(now);

        // Hero: the next prayer and a live countdown.
        LinearLayout hero = new LinearLayout(c.a);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(c.dp(20), c.dp(16), c.dp(20), c.dp(16));
        // Same gradient as the home hero: the next prayer is this page's headline.
        hero.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{c.p.heroA, c.p.heroMid, c.p.heroB}));
        ((android.graphics.drawable.GradientDrawable) hero.getBackground()).setCornerRadius(c.dp(Ui.R_CARD));
        Ui.roundOutline(c, hero, Ui.R_CARD);
        if (!c.p.dark) Ui.softShadow(c, hero, 4, c.p.heroA);
        hero.addView(Ui.text(c, n != null && n[2] == 1 ? "Sıradaki vakit · yarın" : "Sıradaki vakit", 13, (c.p.onHero & 0x00FFFFFF) | 0xD9000000, 600));
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        heroName = Ui.text(c, "", 26, c.p.onHero, 800);
        heroTime = Ui.text(c, "", 38, c.p.onHero, 800);
        heroTime.setFontFeatureSettings("tnum");
        row.addView(heroName, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(heroTime);
        hero.addView(row, Ui.lp(-1, -2, 0, c.dp(4), 0, 0));
        heroLeft = Ui.text(c, "", 14, (c.p.onHero & 0x00FFFFFF) | 0xD9000000, 600);
        heroLeft.setFontFeatureSettings("tnum");
        hero.addView(heroLeft, Ui.lp(-1, -2, 0, c.dp(2), 0, 0));
        body.addView(hero, Ui.lp(-1, -2));
        if (n != null) {
            heroName.setText(NAMES[(int) n[0]]);
            heroTime.setText(clock(n[1]));
            nextAt = n[1];
        }

        // Today's list; the toggle column is the 15-minute reminder.
        LinearLayout labels = new LinearLayout(c.a);
        labels.setGravity(Gravity.BOTTOM);
        labels.addView(Ui.label(c, "BUGÜN"), new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(Ui.label(c, "15 DK ÖNCE HATIRLAT"));
        body.addView(labels, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), c.dp(8)));

        LinearLayout list = Ui.card(c, c.p.surface, 6);
        int mask = c.host.prayerReminderMask();
        for (int i = 0; i < 6; i++) {
            boolean isNext = n != null && n[2] == 0 && n[0] == i, past = today[i] <= now && !isNext;
            LinearLayout r = new LinearLayout(c.a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(c.dp(12), 0, c.dp(6), 0);
            if (isNext) r.setBackground(Ui.rounded(c, c.p.primarySoft, 16));
            int fg = past ? c.p.muted : c.p.ink;
            TextView nm = Ui.text(c, NAMES[i], 16, isNext ? c.p.onPrimarySoft : fg, isNext ? 700 : 500);
            TextView tm = Ui.text(c, clock(today[i]), 17, isNext ? c.p.onPrimarySoft : fg, isNext ? 700 : 500);
            tm.setFontFeatureSettings("tnum");
            r.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(tm);
            if (i == 1) {   // Güneş ends Sabah; it has no reminder of its own
                r.addView(new Space(c.a), new LinearLayout.LayoutParams(c.dp(52 + 16), 1));
            } else {
                final int bit = i;
                Toggle tg = new Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, (mask & (1 << i)) != 0);
                tg.setContentDescription(NAMES[i] + " hatırlatıcısı");
                tg.onChange(on -> onReminder(bit, on));
                toggles[i] = tg;
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.setMarginStart(c.dp(16));
                r.addView(tg, tl);
            }
            list.addView(r, Ui.lp(-1, c.dp(46)));
        }
        body.addView(list, Ui.lp(-1, -2));

        if (Build.VERSION.SDK_INT >= 31 && !canExact()) {
            TextView exact = Ui.button(c, "Tam zamanlı alarm izni ver", Ui.Style.GHOST, Icon.Kind.CLOCK);
            exact.setOnClickListener(v -> c.a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.a.getPackageName()))));
            body.addView(exact, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(6), 0, 0));
        }

        TextView pin = Ui.button(c, "Widget ekle", Ui.Style.TONAL, Icon.Kind.CLOCK);
        pin.setOnClickListener(v -> { if (!c.host.prayerPinWidget()) say("Ana ekrana uzun bas → Widget'lar → Kolay → Namaz vakti"); });
        TextView loc2 = Ui.button(c, "Konumu yenile", Ui.Style.TONAL, Icon.Kind.PIN);
        loc2.setOnClickListener(v -> { if (hasLocationPermission()) { locate(false); } else c.a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC); });
        body.addView(Ui.pair(c, pin, loc2, Ui.H_BTN), Ui.lp(-1, -2, 0, c.dp(12), 0, 0));
        TextView look = Ui.button(c, "Widget görünümü", Ui.Style.GHOST, Icon.Kind.SETTINGS);
        look.setOnClickListener(v -> c.nav.push(new WidgetStyleScreen(false)));
        body.addView(look, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(6), 0, 0));

        TextView note = Ui.text(c, "Diyanet usulü (imsak 18°, yatsı 17°, ikindi asr-ı evvel) astronomik hesap; resmî takvimle birkaç dakika fark olabilir.", 12, c.p.muted, 400);
        note.setLineSpacing(0, 1.15f);
        note.setGravity(Gravity.CENTER);
        body.addView(note, Ui.lp(-1, -2, c.dp(8), c.dp(10), c.dp(8), 0));

        ticking = true;
        c.ui.removeCallbacks(tick);
        c.ui.post(tick);
    }

    private boolean canExact() {
        AlarmManager am = c.a.getSystemService(AlarmManager.class);
        return am == null || Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();   // API 31; earlier versions always allow exact alarms
    }

    private void onReminder(int bit, boolean on) {
        int mask = c.host.prayerReminderMask();
        if (!on) { c.host.prayerSetReminderMask(mask & ~(1 << bit)); return; }
        if (c.host.prayerLocation() == null) { toggles[bit].set(false, true); say("Önce konum gerekli"); return; }
        if (Build.VERSION.SDK_INT >= 33 && c.a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingBit = bit;
            c.a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        c.host.prayerSetReminderMask(mask | (1 << bit));
    }

    // ---- countdown ----------------------------------------------------------------------------

    /** @return false when the time arrived and render() restarted the tick itself */
    private boolean countdown() {
        if (heroLeft == null) return true;
        long left = nextAt - System.currentTimeMillis();
        if (left <= 0) { render(); return false; }     // the time arrived: pick the following one
        long s = left / 1000;
        heroLeft.setText(String.format(Locale.US, "%d:%02d:%02d kaldı", s / 3600, (s / 60) % 60, s % 60));
        return true;
    }

    private static String clock(long t) { return new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(t)); }

    private void say(String s) { Ui.toastLong(c, s); }
}
