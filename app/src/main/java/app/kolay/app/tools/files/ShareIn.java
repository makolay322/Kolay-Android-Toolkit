package app.kolay.app.tools.files;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns a share intent ("Kolay'a kaydet") into {@link Ops.Incoming} files, and writes them to Download/Kolay without the all-files permission. */
final class ShareIn {
    private ShareIn() {}

    private static final String FALLBACK_NAME = "Paylaşılan dosya";

    /** The streams of a SEND / SEND_MULTIPLE intent (ClipData first, then EXTRA_STREAM). Only content:// from other apps is accepted. */
    @SuppressWarnings("deprecation")
    static List<Ops.Incoming> from(Context c, Intent in) {
        Set<Uri> uris = new LinkedHashSet<>();
        if (in == null) return new ArrayList<>();
        ClipData cd = in.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) { Uri u = cd.getItemAt(i).getUri(); if (u != null) uris.add(u); }
        try {
            Uri one = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) uris.add(one);
            ArrayList<Uri> many = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) for (Uri u : many) if (u != null) uris.add(u);
        } catch (RuntimeException ignored) { }   // a malformed extra from the sender: use what the ClipData gave
        List<Ops.Incoming> out = new ArrayList<>();
        for (Uri u : uris) if (acceptable(u)) out.add(new Stream(c.getApplicationContext(), u));
        return out;
    }

    /** content:// only, and never Kolay's own provider (otherwise another app could make Kolay copy its own files out). */
    static boolean acceptable(Uri u) {
        if (!"content".equalsIgnoreCase(u.getScheme())) return false;
        String a = u.getAuthority();
        if (a == null) return false;
        a = a.substring(a.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);   // "0@authority" addresses the same provider
        return !a.isEmpty() && !a.equals(ShareProvider.AUTHORITY);
    }

    /** A shared piece of text as a UTF-8 file named after the subject (or "Paylaşılan metin") and the time. */
    static Ops.Incoming text(String text, String subject) {
        String s = Ops.cleanName(subject == null ? null : subject.replace('.', ' '));
        if (s == null) s = "Paylaşılan metin";
        else if (s.length() > 60) s = s.substring(0, 60).trim();
        String name = s + " " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(new Date()) + ".txt";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return new Ops.Incoming() {
            @Override public String name() { return name; }
            @Override public long size() { return data.length; }
            @Override public InputStream open() { return new ByteArrayInputStream(data); }
        };
    }

    private static final class Stream implements Ops.Incoming {
        private final Context c;
        private final Uri uri;
        private final String name;
        private final long size;

        Stream(Context c, Uri uri) {
            this.c = c; this.uri = uri;
            String n = null;
            long sz = -1;
            try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (q != null && q.moveToFirst()) {
                    int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME), j = q.getColumnIndex(OpenableColumns.SIZE);
                    if (i >= 0 && !q.isNull(i)) n = Ops.cleanName(q.getString(i));
                    if (j >= 0 && !q.isNull(j)) sz = q.getLong(j);
                }
            } catch (RuntimeException ignored) { }   // provider refused the query: name and size stay unknown
            if (n == null) {
                String ext = null;
                try { String t = c.getContentResolver().getType(uri); if (t != null) ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(t); } catch (RuntimeException ignored) { }
                n = ext == null ? FALLBACK_NAME : FALLBACK_NAME + "." + ext;
            }
            this.name = n; this.size = sz < 0 ? -1 : sz;
        }

        @Override public String name() { return name; }
        @Override public long size() { return size; }
        @Override public InputStream open() throws IOException {
            try {
                InputStream is = c.getContentResolver().openInputStream(uri);
                if (is == null) throw new IOException("dosya açılamadı");
                return is;
            } catch (SecurityException | java.io.FileNotFoundException e) {
                throw new IOException("dosya açılamadı");
            }
        }
    }

    /**
     * Fallback without the all-files permission: Download/Kolay through MediaStore (pending until complete).
     * A failed or cancelled file leaves no row behind. Runs on a worker thread.
     */
    static Ops.Result saveToDownloads(Context c, List<Ops.Incoming> in, Ops.Progress p) {
        Ops.Result res = new Ops.Result();
        ContentResolver cr = c.getContentResolver();
        long total = 0;
        boolean known = true;
        for (Ops.Incoming i : in) { if (i.size() < 0) known = false; else total += i.size(); }
        if (!known) total = 0;
        long done = 0;
        byte[] buf = new byte[256 * 1024];
        for (Ops.Incoming i : in) {
            if (p.cancelled()) { res.cancelled = true; break; }
            String name = Ops.cleanName(i.name());
            if (name == null) name = FALLBACK_NAME;
            String ext = Files.ext(name), mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Kolay");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri row = null;
            boolean ok = false;
            try {
                row = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (row == null) throw new IOException("kayıt oluşturulamadı");
                long n = 0;
                try (InputStream is = i.open(); OutputStream out = cr.openOutputStream(row)) {
                    if (out == null) throw new IOException("kayıt açılamadı");
                    for (int k; (k = is.read(buf)) > 0; ) {
                        if (p.cancelled()) { res.cancelled = true; break; }
                        out.write(buf, 0, k);
                        n += k; done += k;
                        p.update(name, done, total);
                    }
                }
                if (!res.cancelled) {
                    if (i.size() >= 0 && i.size() != n) throw new IOException("kopya doğrulanamadı (boyut farklı)");
                    ContentValues f = new ContentValues();
                    f.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(row, f, null, null);
                    ok = true;
                    res.done++;
                }
            } catch (IOException | RuntimeException e) {
                res.errors.add(name + ": " + e.getMessage());
            } finally {
                if (!ok && row != null) { try { cr.delete(row, null, null); } catch (RuntimeException ignored) { } }
            }
            if (res.cancelled) break;
        }
        return res;
    }
}
