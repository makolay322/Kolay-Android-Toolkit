package app.kolay.app.tools.files;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps a long file operation ({@link Jobs}) alive with the screen off or the app in the background: a foreground service
 * (type dataSync) with a progress notification and an "İptal" action, plus a partial wake lock and a Wi-Fi lock while it runs.
 * It does no work itself; the job runs on the Jobs thread. Started only when a job lasts longer than a moment. The service
 * always enters the foreground before it stops, so a job that ends early cannot trip Android's start-foreground deadline.
 */
public final class TransferService extends Service {
    private static final String CH = "files", CANCEL = "app.kolay.files.CANCEL";
    private static final int ID = 4102;

    private static volatile boolean active;
    private static volatile Runnable onCancel;
    private static volatile String title = "", line = "";
    private static volatile int pct = -1;
    private static TransferService instance;   // main thread only

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lastNotify;

    /** A job has started; {@code cancel} runs (any thread) when the notification's İptal is tapped. */
    static void begin(Context c, String t, Runnable cancel) {
        active = true; onCancel = cancel; title = t; line = ""; pct = -1;
        try { c.startForegroundService(new Intent(c, TransferService.class)); }
        catch (RuntimeException ignored) { }   // not allowed right now (e.g. app already in the background): the job still runs
    }

    /** Progress from the UI thread (already throttled by Jobs). */
    static void progress(String name, long done, long total) {
        line = name == null ? "" : name;
        pct = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
        TransferService s = instance;
        if (s != null) s.update(false);
    }

    /** The job has ended (UI thread). */
    static void end() {
        active = false; onCancel = null;
        TransferService s = instance;
        if (s != null) s.finish();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Dosya işlemleri", NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) { wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kolay:files"); wake.acquire(6 * 3600_000L); }
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null) { wifi = wifiLock(wm); wifi.setReferenceCounted(false); wifi.acquire(); }
    }

    @SuppressWarnings("deprecation")   // FULL_HIGH_PERF: still the lock that keeps Wi-Fi awake with the screen off
    private static WifiManager.WifiLock wifiLock(WifiManager wm) { return wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kolay:files"); }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && CANCEL.equals(i.getAction())) {
            Runnable r = onCancel;
            if (r != null) r.run();
            if (!active) finish();
            return START_NOT_STICKY;
        }
        try { startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
        catch (RuntimeException e) { stopSelf(); return START_NOT_STICKY; }
        if (!active) finish();
        return START_NOT_STICKY;
    }

    private Notification build() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder b = new Notification.Builder(this, CH)
            .setSmallIcon(app.kolay.shell.R.drawable.ic_mono)
            .setContentTitle(title)
            .setContentText(line)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, Math.max(0, pct), pct < 0);
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, TransferService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, "İptal", cancel).build());
        return b.build();
    }

    private void update(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 1000) return;   // the system rate-limits notification updates anyway
        lastNotify = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID, build());
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
