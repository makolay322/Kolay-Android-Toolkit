package app.kolay.shell;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/**
 * Self-update from GitHub Releases: manifest.json → Kolay.apk, checked, kept in files/update/ until installed.
 *
 * Trust chain: the manifest names the APK + SHA-256 + ECDSA signature; the signature is checked against the public key
 * compiled into the app, and the archive must be this package, the announced version and signed with this app's own
 * certificate (Android re-checks the certificate itself). A hijacked repository therefore cannot ship code.
 *
 * Installing: one PackageInstaller session asking for no user action (Android 12+ lets an app update itself silently;
 * otherwise Android shows its confirmation). The background job installs silently only while the app is not on screen.
 */
final class Updater {
    private Updater() {}

    static final String ACTION_RESULT = "app.kolay.shell.INSTALL_RESULT";
    static final String EXTRA_BACKGROUND = "background";

    /** Set by the UI; called on the main thread after an update was downloaded. */
    static volatile Runnable onReady;

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE); }

    static String status(Context c) { return prefs(c).getString("status", "Henüz denetlenmedi"); }
    static void setStatus(Context c, String s) { prefs(c).edit().putString("status", s).apply(); }

    static File staged(Context c) { return new File(new File(c.getFilesDir(), "update"), "Kolay.apk"); }
    static String stagedName(Context c) { return ready(c) ? prefs(c).getString("staged_name", "") : ""; }

    /** A verified newer APK is on disk. Stale ones (already installed) are removed here. */
    static boolean ready(Context c) {
        long vc = prefs(c).getLong("staged_vc", 0);
        if (vc > installedVersion(c) && staged(c).isFile()) return true;
        if (vc != 0 || staged(c).exists()) clearStaged(c);
        return false;
    }

    static void clearStaged(Context c) {
        deleteTree(staged(c).getParentFile());
        prefs(c).edit().remove("staged_vc").remove("staged_name").apply();
    }

    /** Leftovers of the old two-APK design (downloaded modules and their bookkeeping). */
    static void cleanupLegacy(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("legacy_cleaned", false)) return;
        deleteTree(new File(c.getFilesDir(), "payloads"));
        c.deleteSharedPreferences("registry");
        new File(c.getCacheDir(), "payload.download").delete();
        new File(c.getCacheDir(), "shell.download").delete();
        p.edit().putBoolean("legacy_cleaned", true).apply();
    }

    static void checkAsync(Context c, boolean force) {
        Thread t = new Thread(() -> check(c, force), "updater");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** @return a short human-readable result, also stored for the settings screen. */
    static synchronized String check(Context ctx, boolean force) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        if (!force && now < p.getLong("next_check", 0)) return status(ctx);
        String result;
        try {
            result = run(ctx);
            p.edit().putLong("next_check", now + Config.CHECK_INTERVAL_MS).apply();
        } catch (Exception e) {
            result = "Denetim başarısız: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            p.edit().putLong("next_check", now + Config.RETRY_AFTER_FAIL_MS).apply();
        }
        setStatus(ctx, result);
        return result;
    }

    private static String run(Context ctx) throws Exception {
        JSONObject mf = new JSONObject(new String(fetch(Config.MANIFEST_URL, 64 << 10), StandardCharsets.UTF_8));
        long vc = mf.getLong("versionCode");
        String name = mf.optString("versionName", String.valueOf(vc));
        String url = mf.getString("url"), sha = mf.getString("sha256").toLowerCase(), sig = mf.getString("signature");
        long size = mf.getLong("size");
        if (!url.startsWith(Config.REPO_BASE + "releases/download/")) throw new SecurityException("beklenmeyen indirme adresi");
        if (size <= 0 || size > Config.MAX_APK_BYTES) throw new IOException("geçersiz boyut");
        if (vc <= installedVersion(ctx)) { clearStaged(ctx); return "Güncel (" + installedName(ctx) + ")"; }
        if (ready(ctx) && prefs(ctx).getLong("staged_vc", 0) == vc) return "Yeni sürüm hazır: " + name;

        File tmp = new File(ctx.getCacheDir(), "update.download");
        tmp.delete();
        try {
            download(url, tmp, size);
            if (!Verify.sha256(tmp).equals(sha)) throw new SecurityException("özet (SHA-256) uyuşmuyor");
            if (!Verify.signature(tmp, Base64.getDecoder().decode(sig), Config.UPDATE_PUBLIC_KEYS)) throw new SecurityException("imza doğrulanamadı");
            checkArchive(ctx, tmp, vc);
            clearStaged(ctx);
            File dest = staged(ctx);
            dest.getParentFile().mkdirs();
            if (!tmp.renameTo(dest)) copy(tmp, dest);
            prefs(ctx).edit().putLong("staged_vc", vc).putString("staged_name", name).apply();
            Runnable r = onReady;
            if (r != null) new Handler(Looper.getMainLooper()).post(r);
            return "Yeni sürüm hazır: " + name;
        } finally { tmp.delete(); }
    }

    /** The file must be this app: same package, the announced version, same signing certificate. */
    private static void checkArchive(Context ctx, File apk, long vc) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo n = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (n == null || !ctx.getPackageName().equals(n.packageName)) throw new SecurityException("paket adı uyuşmuyor");
        if (n.getLongVersionCode() != vc) throw new SecurityException("sürüm numarası manifest ile uyuşmuyor");
        PackageInfo mine;
        try { mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException e) { throw new SecurityException("kendi imzam okunamadı"); }
        if (n.signingInfo == null || mine.signingInfo == null) throw new SecurityException("APK imzası okunamadı");
        for (Signature a : n.signingInfo.getApkContentsSigners())
            for (Signature b : mine.signingInfo.getApkContentsSigners())
                if (a.equals(b)) return;
        throw new SecurityException("APK imzası bu uygulamayla aynı değil");
    }

    /**
     * Hands the downloaded APK to Android. The result (and a confirmation request, if Android wants one) arrives at
     * {@link InstallResultReceiver}. On success Android replaces the app, which closes it.
     */
    static void install(Context ctx, boolean background) throws IOException {
        Context app = ctx.getApplicationContext();
        File apk = staged(app);
        if (!ready(app)) throw new IOException("indirilmiş güncelleme yok");
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(app.getPackageName());
        sp.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("kolay", 0, apk.length())) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent cb = new Intent(app, InstallResultReceiver.class).setAction(ACTION_RESULT).setPackage(app.getPackageName())
                .putExtra(EXTRA_BACKGROUND, background);
            // Mutable on purpose: the system fills in the status extras.
            PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    static long installedVersion(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return Long.MAX_VALUE; }   // unknown: never offer
    }

    static String installedName(Context ctx) {
        try { String n = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; return n == null ? "" : n; }
        catch (Exception e) { return ""; }
    }

    private static HttpURLConnection open(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "kolay");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    /** After redirects the final endpoint must still be GitHub over HTTPS. */
    private static void requireTrusted(HttpURLConnection c) {
        URL u = c.getURL();
        if (!"https".equals(u.getProtocol()) || !Config.trustedHost(u.getHost())) throw new SecurityException("güvenilmeyen sunucu: " + u.getHost());
    }

    static byte[] fetch(String u, int max) throws IOException {
        HttpURLConnection c = open(u);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            requireTrusted(c);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new IOException("yanıt çok büyük"); }
                return o.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    static void download(String u, File to, long expected) throws IOException {
        HttpURLConnection c = open(u);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            requireTrusted(c);
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(to)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) { total += n; if (total > expected) throw new IOException("dosya beklenenden büyük"); o.write(b, 0, n); }
            }
            if (total != expected) throw new IOException("indirme eksik (" + total + "/" + expected + ")");
        } finally { c.disconnect(); }
    }

    private static void copy(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
