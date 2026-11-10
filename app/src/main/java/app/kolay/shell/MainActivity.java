package app.kolay.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import app.kolay.app.Entry;
import java.io.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * The only activity (plus {@link PrayerWidgetConfig}, the same UI opened on the widget appearance page). Prepares the platform pieces (key store, OCR data, updater) and hands the window to the UI
 * ({@link Entry}). The class name stays {@code app.kolay.shell.MainActivity} on purpose: launcher shortcuts point at it.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Kolay";
    /** True while the activity is resumed; the background updater installs only when this is false. */
    static volatile boolean foreground;

    private SecretStore secrets;
    private Entry ui;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        secrets = new SecretStore(this);
        Updater.cleanupLegacy(this);
        try { ensureTessdata(); } catch (RuntimeException e) { Log.e(TAG, "tessdata", e); }
        ui = new Entry();
        ui.onCreate(new HostImpl(), saved);
        if (this instanceof YoutubePopup) return;   // the share popup does none of the app's housekeeping
        UpdateJob.schedule(this);
        Updater.checkAsync(this, false);
        Context app = getApplicationContext();   // the widget follows the wallpaper: repaint whenever the app opens
        new Thread(() -> { try { PrayerWidget.updateAll(app); } catch (Throwable ignored) { } }).start();
    }

    /**
     * OCR language data is downloaded on demand ({@link TessData}). Until 1.028 deu.traineddata was copied here from the APK;
     * that copy is the same file and stays (it counts as installed). Only certainly incomplete leftovers are removed.
     */
    private void ensureTessdata() {
        TessData.cleanup(this);
        deleteSharedPreferences("tessdata");   // bookkeeping of the old copy step
    }

    private final class HostImpl implements Host {
        @Override public Activity activity() { return MainActivity.this; }
        @Override public String secret(String n) { return secrets.get(n); }
        @Override public void setSecret(String n, String v) { secrets.set(n, v); }
        @Override public File tessDataParent() { return getFilesDir(); }
        @Override public String[] tessLanguages() { return TessData.LANGS.clone(); }
        @Override public boolean tessInstalled(String l) { return TessData.installed(MainActivity.this, l); }
        @Override public long tessInstalledSize(String l) { return TessData.installedSize(MainActivity.this, l); }
        @Override public long tessDownloadSize(String l) { return TessData.downloadSize(l); }
        @Override public void tessDownload(String l, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
            TessData.download(getApplicationContext(), l, progress, cancelled);
        }
        @Override public boolean tessDelete(String l) { return TessData.delete(MainActivity.this, l); }
        @Override public boolean officePluginInstalled() { return OfficePlugin.installed(MainActivity.this); }
        @Override public long officePluginSize() { return OfficePlugin.SIZE; }
        @Override public void officePluginDownload(LongConsumer progress, BooleanSupplier cancelled) throws IOException {
            OfficePlugin.download(getApplicationContext(), progress, cancelled);
        }
        @Override public java.nio.ByteBuffer officePluginDex() throws IOException { return OfficePlugin.dex(getApplicationContext()); }
        @Override public boolean officePluginDelete() { return OfficePlugin.delete(MainActivity.this); }
        @Override public String versionName() { return Updater.installedName(MainActivity.this); }
        @Override public String updateStatus() { return Updater.status(MainActivity.this); }
        @Override public boolean updateReady() { return Updater.ready(MainActivity.this); }
        @Override public String updateName() { return Updater.stagedName(MainActivity.this); }
        @Override public void checkForUpdates(Consumer<String> done) {
            new Thread(() -> {
                String s = Updater.check(getApplicationContext(), true);
                runOnUiThread(() -> done.accept(s));
            }, "update-check").start();
        }
        @Override public void onUpdateReady(Runnable r) { Updater.onReady = r; }
        @Override public void installUpdate(Consumer<String> progress) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                progress.accept("Önce Kolay için “Bu kaynaktan yüklemeye izin ver”i aç, sonra geri dönüp tekrar dokun.");
                return;
            }
            progress.accept("Kuruluyor… Uygulama birazdan kapanır; yeniden açınca yeni sürüm çalışır.");
            new Thread(() -> {
                try { Updater.install(getApplicationContext(), false); }
                catch (Exception e) {
                    String m = "Güncelleme kurulamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    runOnUiThread(() -> progress.accept(m));
                }
            }, "update-install").start();
        }
        @Override public String releasesUrl() { return Config.RELEASES_PAGE; }
        @Override public double[] prayerLocation() { return Prayer.location(MainActivity.this); }
        @Override public void prayerSetLocation(double lat, double lon) { Prayer.setLocation(MainActivity.this, lat, lon); }
        @Override public long[] prayerTimes(int y, int m, int d) { return Prayer.times(MainActivity.this, y, m, d); }
        @Override public int prayerReminderMask() { return Prayer.reminderMask(MainActivity.this); }
        @Override public void prayerSetReminderMask(int mask) { Prayer.setReminderMask(MainActivity.this, mask); }
        @Override public boolean prayerPinWidget() { return Prayer.pinWidget(MainActivity.this); }
        @Override public int[] prayerWidgetStyle() { return PrayerWidget.style(MainActivity.this); }
        @Override public void prayerSetWidgetStyle(int bg, int opacity, int ink) { PrayerWidget.setStyle(MainActivity.this, bg, opacity, ink); }
        @Override public android.widget.RemoteViews prayerWidgetPreview(int bg, int opacity, int ink, int w, int h) { return PrayerWidget.preview(MainActivity.this, bg, opacity, ink, w, h); }
        @Override public int prayerWallpaperColor() { return PrayerWidget.wallpaperColor(MainActivity.this); }
    }

    // ---- lifecycle forwarding ------------------------------------------------------------------

    @Override protected void onResume() { super.onResume(); foreground = true; ui.onResume(); }

    @Override protected void onPause() { foreground = false; ui.onPause(); super.onPause(); }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        ui.onPermissionResult(req, p, r);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        ui.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() { if (!ui.onBackPressed()) super.onBackPressed(); }

    @Override protected void onDestroy() {
        Updater.onReady = null;
        ui.onDestroy();
        super.onDestroy();
    }
}
