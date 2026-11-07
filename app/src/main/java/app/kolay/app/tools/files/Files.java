package app.kolay.app.tools.files;

import app.kolay.app.ui.Ui;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Sheet;
import app.kolay.app.viewer.Viewer;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Android side of the file manager: permission, volumes, types, preferences, the shared clipboard, open/share. */
final class Files {
    private Files() {}

    static final int REQ_STORAGE = 4101;

    // ------------------------------------------------------------ permission

    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 11+: the system page "Tüm dosyalara erişim" for this app; Android 10: the classic storage prompt. */
    static void requestAccess(Ctx c) {
        if (Build.VERSION.SDK_INT < 30) {
            c.a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            c.a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.a.getPackageName())));
        } catch (ActivityNotFoundException e) {
            try { c.a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (ActivityNotFoundException e2) { toast(c, "Ayarlar sayfası açılamadı"); }
        }
    }

    // ------------------------------------------------------------ volumes

    static final class Volume {
        final String label, path;
        final long total, free;
        Volume(String label, String path, long total, long free) { this.label = label; this.path = path; this.total = total; this.free = free; }
    }

    static List<Volume> volumes(Context c) {
        List<Volume> out = new ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        List<StorageVolume> vols = sm == null ? new ArrayList<>() : sm.getStorageVolumes();
        File[] appDirs = c.getExternalFilesDirs(null);   // one per mounted volume (Android 10 has no StorageVolume.getDirectory)
        for (StorageVolume v : vols) {
            if (!Environment.MEDIA_MOUNTED.equals(v.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
            String path = null;
            if (Build.VERSION.SDK_INT >= 30) { File d = v.getDirectory(); if (d != null) path = d.getPath(); }
            else if (v.isPrimary()) path = Environment.getExternalStorageDirectory().getPath();
            else for (File d : appDirs) {
                if (d == null) continue;
                StorageVolume of = sm.getStorageVolume(d);
                String p = d.getPath();
                int i = p.indexOf("/Android/");
                if (of != null && of.equals(v) && i > 0) path = p.substring(0, i);
            }
            if (path == null) continue;
            try {
                StatFs st = new StatFs(path);
                out.add(new Volume(v.isPrimary() ? "Dahili depolama" : v.getDescription(c), path, st.getTotalBytes(), st.getAvailableBytes()));
            } catch (IllegalArgumentException ignored) { }
        }
        if (out.isEmpty()) {
            String p = Environment.getExternalStorageDirectory().getPath();
            StatFs st = new StatFs(p);
            out.add(new Volume("Dahili depolama", p, st.getTotalBytes(), st.getAvailableBytes()));
        }
        return out;
    }

    /** The volume root that contains {@code path} (for the breadcrumb's first chip), or null. */
    static Volume volumeOf(List<Volume> vols, String path) {
        Volume best = null;
        for (Volume v : vols) if (FileSource.within(path, v.path) && (best == null || v.path.length() > best.path.length())) best = v;
        return best;
    }

    // ------------------------------------------------------------ types and text

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String mime(String name) {
        if (name == null) return "application/octet-stream";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name));
        return m != null ? m : "application/octet-stream";
    }

    static boolean isZip(String name) { return ext(name).equals("zip"); }

    static Icon.Kind icon(FileItem it) {
        if (it.dir) return Icon.Kind.FOLDER;
        String m = mime(it.name), e = ext(it.name);
        if (m.startsWith("image/")) return Icon.Kind.IMAGE;
        if (m.startsWith("video/")) return Icon.Kind.PLAY;
        if (m.startsWith("audio/")) return Icon.Kind.MUSIC;
        if (e.equals("apk")) return Icon.Kind.APPS;
        if (Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(e)) return Icon.Kind.LAYERS;
        return Icon.Kind.DOC;
    }

    /** Color family of a file type (tile behind its icon), or null for "other" (neutral tile). */
    static app.kolay.app.ui.Palette.Tone tone(app.kolay.app.ui.Palette p, FileItem it) {
        if (it.dir) return p.blue;
        String m = mime(it.name), e = ext(it.name);
        if (m.startsWith("image/")) return p.rose;
        if (m.startsWith("video/")) return p.red;
        if (m.startsWith("audio/")) return p.amber;
        if (e.equals("apk")) return p.teal;
        if (Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(e)) return p.violet;
        if (e.equals("pdf")) return p.red;
        if (Arrays.asList("xls", "xlsx", "ods", "csv").contains(e)) return p.green;          // office colors: sheets green,
        if (Arrays.asList("ppt", "pptx", "odp").contains(e)) return p.amber;                // slides orange,
        if (Arrays.asList("doc", "docx", "odt", "rtf", "txt", "md").contains(e)) return p.sky;  // text blue
        return null;
    }

    static boolean thumbable(FileItem it) {
        if (it.dir) return false;
        String m = mime(it.name);
        return m.startsWith("image/") || m.startsWith("video/");
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(new Locale("tr"), v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }

    static String date(long ms) { return ms <= 0 ? "" : new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date(ms)); }

    // ------------------------------------------------------------ preferences

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("files", Context.MODE_PRIVATE); }

    static List<String> bookmarks(Context c) {
        String s = prefs(c).getString("bookmarks", "");
        List<String> out = new ArrayList<>();
        for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setBookmark(Context c, String path, boolean on) {
        List<String> b = bookmarks(c);
        b.remove(path);
        if (on) b.add(path);
        prefs(c).edit().putString("bookmarks", String.join("\n", b)).apply();
    }

    // ------------------------------------------------------------ clipboard (shared by every browser page)

    static final List<String> clip = new ArrayList<>();
    static boolean clipCut;
    static FileSource clipSrc = LocalSource.INSTANCE;   // where the clipboard's paths live (a Samba server or the phone)

    // ------------------------------------------------------------ open / share

    /** Tap on a file: documents the built-in viewer can show open there, everything else through the system chooser. */
    static void open(Ctx c, FileSource src, FileItem it) { open(c, src, it, false); }

    /** "Birlikte aç": always the system chooser. */
    static void openWith(Ctx c, FileSource src, FileItem it) { open(c, src, it, true); }

    private static void open(Ctx c, FileSource src, FileItem it, boolean chooser) {
        if (src instanceof SmbSource) openRemote(c, (SmbSource) src, it, chooser);
        else show(c, it.path, it.name, chooser);
    }

    private static void show(Ctx c, String path, String name, boolean chooser) {
        if (!chooser && Viewer.canOpen(name)) {
            String m = mime(name);
            Viewer.open(c, Uri.fromFile(new File(path)), m.equals("application/octet-stream") ? null : m);
        } else openLocal(c, path, name);
    }

    private static final long OPEN_ASK = 100L << 20;   // bigger network files need a yes before they are fetched

    /**
     * A file on a Samba server: copied (verified, temporary name first) into the app's cache under {@code smb-open/}, then
     * opened from there. Copies older than an hour are removed on the next open.
     */
    private static void openRemote(Ctx c, SmbSource s, FileItem it, boolean chooser) {
        File root = new File(c.a.getCacheDir(), "smb-open");
        Runnable go = () -> {
            long now = System.currentTimeMillis();
            File[] old = root.listFiles();
            if (old != null) for (File d : old) if (now - d.lastModified() > 3600_000L) deleteTree(d);
            if (it.size > c.a.getCacheDir().getUsableSpace() - (64L << 20)) { toast(c, "Telefonda yeterli boş yer yok"); return; }
            File dir = new File(root, Long.toString(now, 36));
            if (!dir.mkdirs()) { toast(c, "Geçici klasör oluşturulamadı"); return; }
            Jobs.run(c, "İndiriliyor", "indirildi",
                (p, r) -> Ops.transfer(s, java.util.Collections.singletonList(it.path), LocalSource.INSTANCE, dir.getPath(), false, r, p),
                () -> { File f = new File(dir, it.name); if (f.isFile()) show(c, f.getPath(), it.name, chooser); else deleteTree(dir); }, true);
        };
        if (it.size > OPEN_ASK) Sheet.confirm(c, "Büyük dosya", "“" + it.name + "” " + size(it.size) + ". Açmak için önce telefona geçici olarak indirilir.", "İndir ve aç", false, go);
        else go.run();
    }

    private static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void openLocal(Ctx c, String path, String name) {
        Uri u = ShareProvider.uri(path);
        String m = mime(name);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { c.a.startActivity(Intent.createChooser(i, "Birlikte aç")); }
        catch (ActivityNotFoundException e) { toast(c, "Bu dosyayı açabilecek uygulama yok"); }
    }

    static void share(Ctx c, List<FileItem> items) {
        ArrayList<Uri> uris = new ArrayList<>();
        String type = null;
        for (FileItem it : items) {
            if (it.dir) continue;
            uris.add(ShareProvider.uri(it.path));
            String m = mime(it.name);
            type = type == null ? m : type.equals(m) ? type : type.split("/")[0].equals(m.split("/")[0]) ? m.split("/")[0] + "/*" : "*/*";
        }
        if (uris.isEmpty()) { toast(c, "Klasörler paylaşılamaz (önce sıkıştır)"); return; }
        Intent i = uris.size() == 1
            ? new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.get(0))
            : new Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { c.a.startActivity(Intent.createChooser(i, "Paylaş")); }
        catch (ActivityNotFoundException e) { toast(c, "Paylaşacak uygulama yok"); }
    }

    // ------------------------------------------------------------ media index (categories, recent files)

    static final String[] DOC_MIMES = {"application/pdf", "text/plain", "application/rtf", "text/csv", "application/msword", "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet", "application/epub+zip"};

    /** Files from the system media index (newest first); entries already gone from the disk and hidden paths are left out. */
    @SuppressWarnings("deprecation")   // DATA: real paths are what this file manager works with (all-files access)
    static List<FileItem> query(Context c, String sel, String[] args, String order, int limit) {
        List<FileItem> out = new ArrayList<>();
        android.os.Bundle q = new android.os.Bundle();
        q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
        q.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
        String[] proj = {android.provider.MediaStore.MediaColumns.DATA, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED};
        try (android.database.Cursor cur = c.getContentResolver().query(android.provider.MediaStore.Files.getContentUri("external"), proj, q, null)) {
            while (cur != null && cur.moveToNext() && out.size() < limit) {
                String p = cur.getString(0);
                if (p == null || p.contains("/.")) continue;
                File f = new File(p);
                if (!f.isFile()) continue;
                out.add(new FileItem(p, false, cur.getLong(1), cur.getLong(2) * 1000));
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Tells the media index about changed paths (a missing path drops its stale entry). */
    static void rescan(Context c, List<String> paths) {
        if (!paths.isEmpty()) android.media.MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null);
    }

    static void toast(Ctx c, String s) { Ui.toast(c, s); }
}
