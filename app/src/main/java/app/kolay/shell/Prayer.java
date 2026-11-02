package app.kolay.shell;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Prayer times for the saved location, the home-screen widget and the "time is about to run out" reminder.
 * Lives in the shell because the widget and the alarm must work while the app (and so the payload) is not running.
 * Only coordinates are stored (city-level accuracy is plenty); nothing leaves the device.
 */
final class Prayer {
    private Prayer() {}

    static final String[] NAMES = {"Sabah", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};
    static final int REMIND_MIN = 15;
    static final String ACTION_REMIND = "app.kolay.action.PRAYER_REMIND", ACTION_TICK = "app.kolay.action.PRAYER_TICK";
    private static final String CHANNEL = "prayer";
    private static final int REQ_REMIND = 61, REQ_TICK = 62, NOTIF_ID = 6100;

    // ---- settings -----------------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("prayer", Context.MODE_PRIVATE); }

    static double[] location(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return null;
        return new double[]{Double.longBitsToDouble(p.getLong("lat", 0)), Double.longBitsToDouble(p.getLong("lon", 0))};
    }

    static void setLocation(Context c, double lat, double lon) {
        prefs(c).edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).apply();
        refresh(c);
    }

    /** Bit i set = remind before prayer i's time runs out (0 Sabah, 2 Öğle, 3 İkindi, 4 Akşam, 5 Yatsı; bit 1 is unused). */
    static final int ALL_MASK = 0b111101;

    static int reminderMask(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("mask")) return p.getInt("mask", 0) & ALL_MASK;
        return p.getBoolean("remind", false) ? ALL_MASK : 0;   // older single switch
    }

    static void setReminderMask(Context c, int mask) { prefs(c).edit().putInt("mask", mask & ALL_MASK).apply(); refresh(c); }

    // ---- times --------------------------------------------------------------------------------

    /** @return 6 instants for the given local date, or null without a saved location */
    static long[] times(Context c, int year, int month, int day) {
        double[] l = location(c);
        return l == null ? null : PrayerCalc.times(l[0], l[1], year, month, day, TimeZone.getDefault());
    }

    private static long[] timesAt(Context c, long now, int dayOffset) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        return times(c, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** The next time after {@code now}, sunrise included (it is shown so the end of Sabah is visible): {index, epoch ms}, or null. */
    static long[] next(Context c, long now) {
        for (int d = 0; d <= 1; d++) {
            long[] t = timesAt(c, now, d);
            if (t == null) return null;
            for (int i = 0; i < 6; i++) if (t[i] > now) return new long[]{i, t[i]};
        }
        return null;
    }

    /** The next reminder: {prayer index, reminder instant, instant the prayer time ends}, or null. A time ends when the next one starts. */
    private static long[] nextReminder(Context c, long now) {
        int mask = reminderMask(c);
        long best = Long.MAX_VALUE; long[] out = null;
        for (int d = -1; d <= 1; d++) {
            long[] t = timesAt(c, now, d), n = timesAt(c, now, d + 1);
            if (t == null || n == null) return null;
            long[] ends = {t[1], 0, t[3], t[4], t[5], n[0]};
            for (int i = 0; i < 6; i++) {
                if (i == PrayerCalc.SUNRISE || (mask & (1 << i)) == 0) continue;
                long at = ends[i] - REMIND_MIN * 60000L;
                if (at > now && at < best) { best = at; out = new long[]{i, at, ends[i]}; }
            }
        }
        return out;
    }

    static String clock(long t) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.GERMANY);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(t));
    }

    // ---- alarms, widget -----------------------------------------------------------------------

    /** Re-reads everything: repaints the widgets and sets the next two alarms (widget tick, reminder). Cheap; call after any change. */
    static void refresh(Context c) {
        Context app = c.getApplicationContext();
        try { PrayerWidget.updateAll(app); } catch (Throwable ignored) { }
        try { schedule(app); } catch (Throwable ignored) { }
    }

    private static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        PendingIntent tick = pending(c, REQ_TICK, new Intent(c, PrayerReceiver.class).setAction(ACTION_TICK));
        PendingIntent remind = pending(c, REQ_REMIND, new Intent(c, PrayerReceiver.class).setAction(ACTION_REMIND));
        am.cancel(tick); am.cancel(remind);
        long[] nx = next(c, now);
        if (nx != null) set(am, nx[1] + 1000, tick);     // repaint the widget right after the time changes
        if (reminderMask(c) != 0) {
            long[] r = nextReminder(c, now);
            if (r != null) {
                Intent i = new Intent(c, PrayerReceiver.class).setAction(ACTION_REMIND).putExtra("i", (int) r[0]).putExtra("end", r[2]);
                set(am, r[1], pending(c, REQ_REMIND, i));
            }
        }
    }

    private static PendingIntent pending(Context c, int req, Intent i) {
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Exact when the system allows it, otherwise the nearest the system lets us get (still fires in Doze). */
    private static void set(AlarmManager am, long at, PendingIntent pi) {
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Shows the "time is running out" notification, then arms the next alarm. */
    static void remind(Context c, int index, long end) {
        if (index >= 0 && index < 6 && end > System.currentTimeMillis() && (reminderMask(c) & (1 << index)) != 0) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Namaz vakti hatırlatıcı", NotificationManager.IMPORTANCE_HIGH));
                PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_prayer)
                    .setContentTitle(NAMES[index] + " vakti çıkıyor")
                    .setContentText(REMIND_MIN + " dk kaldı · saat " + clock(end) + "'de çıkıyor")
                    .setContentIntent(open).setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_REMINDER).build();
                try { nm.notify(NOTIF_ID + index, n); } catch (SecurityException ignored) { }
            }
        }
        refresh(c);
    }

    static boolean pinWidget(Context c) {
        AppWidgetManager m = c.getSystemService(AppWidgetManager.class);
        if (m == null || !m.isRequestPinAppWidgetSupported()) return false;
        return m.requestPinAppWidget(new ComponentName(c, PrayerWidget.class), null, null);
    }
}
