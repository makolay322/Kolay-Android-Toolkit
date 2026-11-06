package app.kolay.app.tools.apps;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.webkit.CookieManager;
import app.kolay.shell.Host;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Download → unpack (plain APK, or an XAPK / APKM / APKS bundle of split APKs) → checks → Android's package installer.
 * Downloaded and unpacked files live in the cache and are deleted as soon as the installer has its own copy.
 */
final class Installer {
    private Installer() {}

    static final String ACTION = "app.kolay.app.APP_UPDATE_RESULT";
    private static final long MAX_BYTES = 2_000_000_000L;

    static File workDir(Context ctx) { return new File(ctx.getCacheDir(), "app-updates"); }

    /** Removes anything left over from an earlier run (for example when the app was closed mid-download). */
    static void cleanup(Context ctx) { Host.deleteTree(workDir(ctx)); }

    /** Fetches the file the browser was about to download, with the browser's cookies and user agent. Blocking. */
    static File download(Context ctx, String url, String ua, String referer, String pkg, LongConsumer percent) throws IOException {
        File dir = workDir(ctx); dir.mkdirs();
        File out = new File(dir, pkg + ".download");
        HttpURLConnection h = null;
        for (int redirects = 0; ; redirects++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) throw new IOException("güvensiz (HTTP) indirme adresi");
            h = (HttpURLConnection) u.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20_000); h.setReadTimeout(60_000);
            if (ua != null) h.setRequestProperty("User-Agent", ua);
            if (referer != null && !referer.isEmpty()) h.setRequestProperty("Referer", referer);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) h.setRequestProperty("Cookie", cookie);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null && redirects < 8) {
                url = new URL(u, h.getHeaderField("Location")).toString();
                h.disconnect();
                continue;
            }
            if (code != 200) throw new IOException("sunucu " + code + " döndü");
            break;
        }
        String type = h.getContentType() == null ? "" : h.getContentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html")) throw new IOException("dosya yerine bir sayfa geldi");
        long total = h.getContentLengthLong();
        if (total > MAX_BYTES) throw new IOException("dosya çok büyük");
        try (InputStream in = new BufferedInputStream(h.getInputStream()); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536]; int n; long got = 0; long last = -1;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n); got += n;
                if (got > MAX_BYTES) throw new IOException("dosya çok büyük");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("durduruldu");
                long p = total > 0 ? got * 100 / total : -1;
                if (p != last) { last = p; percent.accept(p); }
            }
        } catch (IOException e) { out.delete(); throw e; }
        finally { h.disconnect(); }
        return out;
    }

    /** What gets installed: one APK, or a base APK plus the split APKs this phone needs. */
    static final class Pack {
        final List<File> apks = new ArrayList<>();
        File dir;
        String version = ""; long code;
    }

    private static final String[] ABIS = {"arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"};

    /** Unpacks the download and checks it belongs to {@code pkg}, is newer, and is signed like the installed app. */
    static Pack prepare(Context ctx, File file, String pkg) throws IOException {
        Pack p = new Pack();
        p.dir = new File(workDir(ctx), pkg + ".parts");
        Host.deleteTree(p.dir); p.dir.mkdirs();
        try (ZipFile z = new ZipFile(file)) {
            if (z.getEntry("AndroidManifest.xml") != null) {   // a plain APK
                File apk = new File(p.dir, "base.apk");
                if (!file.renameTo(apk)) copy(z, null, apk, file);
                p.apks.add(apk);
            } else {
                List<ZipEntry> splits = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.toLowerCase(Locale.ROOT).endsWith(".apk") || n.contains("..")) continue;
                    if (wanted(new File(n).getName())) splits.add(e);
                }
                if (splits.isEmpty()) throw new IOException("dosyada kurulabilir APK yok");
                for (ZipEntry e : splits) {
                    File out = new File(p.dir, new File(e.getName()).getName());
                    copy(z, e, out, null);
                    p.apks.add(out);
                }
            }
        } catch (java.util.zip.ZipException e) { throw new IOException("indirilen dosya bir APK değil"); }
        finally { file.delete(); }

        PackageManager pm = ctx.getPackageManager();
        // The base APK: named base.apk / <package>.apk in bundles, otherwise the largest one Android can read.
        PackageInfo base = null; boolean named = false; long size = -1;
        for (File f : p.apks) {
            PackageInfo i = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (i == null || (i.splitNames != null && i.splitNames.length > 0)) continue;
            boolean n = f.getName().equalsIgnoreCase("base.apk") || f.getName().equalsIgnoreCase(pkg + ".apk");
            if (named && !n) continue;
            if (n && !named || f.length() > size) { base = i; named = n; size = f.length(); }
        }
        if (base == null) throw new IOException("APK okunamadı");
        if (!pkg.equals(base.packageName)) throw new IOException("başka bir uygulamanın dosyası (" + base.packageName + ")");
        p.version = base.versionName == null ? "" : base.versionName;
        p.code = base.getLongVersionCode();

        PackageInfo mine;
        try { mine = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException e) { throw new IOException("uygulama artık yüklü değil"); }
        if (p.code <= mine.getLongVersionCode()) throw new IOException("sitedeki dosya yüklü sürümden yeni değil (" + p.version + ")");
        if (base.signingInfo != null && mine.signingInfo != null && !sameSigner(base.signingInfo.getApkContentsSigners(), mine.signingInfo.getApkContentsSigners()))
            throw new IOException("imza farklı: bu dosya yüklü uygulamanın yerine geçemez (Android reddeder). Kaynağı değiştirmeyi dene.");
        return p;
    }

    /** Split filter: architecture splits only for this phone's processors; densities, languages and features all stay. */
    private static boolean wanted(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String abi : ABIS) {
            if (n.endsWith("config." + abi + ".apk") || n.endsWith("." + abi + ".apk") || n.equals(abi + ".apk")) {
                for (String s : Build.SUPPORTED_ABIS) if (s.replace('-', '_').equals(abi)) return true;
                return false;
            }
        }
        return true;
    }

    private static boolean sameSigner(Signature[] a, Signature[] b) {
        if (a == null || b == null) return true;
        for (Signature x : a) for (Signature y : b) if (x.equals(y)) return true;
        return false;
    }

    /**
     * Hands the files to Android in one session and deletes them right after (the installer keeps its own copy).
     * The result arrives as a broadcast with {@link #ACTION}; see {@link #receiver}.
     */
    static void install(Context ctx, String pkg, Pack p) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(pkg);
        long size = 0; for (File f : p.apks) size += f.length();
        sp.setSize(size);
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);   // silent when Kolay installed it last time
        if (Build.VERSION.SDK_INT >= 33) sp.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            for (File f : p.apks) {
                try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite(f.getName(), 0, f.length())) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    s.fsync(o);
                }
            }
            Intent cb = new Intent(ACTION).setPackage(ctx.getPackageName()).putExtra("pkg", pkg);
            // Mutable on purpose: the system fills in the status extras.
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        } finally { Host.deleteTree(p.dir); }
    }

    /** Install outcome: {@code null} = success, otherwise a short Turkish reason. "Onay bekleniyor" states are handled inside. */
    interface Outcome { void done(String pkg, String error); }

    /** Registers the result receiver; the confirmation screen is opened from it when Android asks for one. */
    static BroadcastReceiver receiver(Context ctx, Consumer<Intent> confirm, Outcome out) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String pkg = i.getStringExtra("pkg");
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent go = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (go != null) confirm.accept(go);
                    return;
                }
                if (st == PackageInstaller.STATUS_SUCCESS) { out.done(pkg, null); return; }
                out.done(pkg, reason(st, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(r, f);
        return r;
    }

    private static String reason(int st, String msg) {
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "kurulum iptal edildi";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "imza veya sürüm çakışması";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "bu telefonla uyumsuz";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "yer yetersiz";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "sistem engelledi";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "dosya geçersiz";
            default: return msg == null || msg.isEmpty() ? "kurulamadı" : "kurulamadı (" + msg + ")";
        }
    }

    private static void copy(ZipFile z, ZipEntry e, File out, File whole) throws IOException {
        try (InputStream in = e != null ? z.getInputStream(e) : new FileInputStream(whole); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }
}
