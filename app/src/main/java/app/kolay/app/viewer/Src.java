package app.kolay.app.viewer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.function.BooleanSupplier;

/** Opening the viewed file: content:// through the ContentResolver, file:// directly; never the network. */
final class Src {
    private Src() {}

    static final long MAX_COPY = 512L << 20;

    static String name(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor cur = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (RuntimeException ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "Belge" : last.substring(last.lastIndexOf('/') + 1);
    }

    static InputStream stream(Context ctx, Uri uri) throws IOException {
        checkScheme(uri);
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new FileNotFoundException();
        return in;
    }

    static ParcelFileDescriptor pfd(Context ctx, Uri uri) throws IOException {
        checkScheme(uri);
        if ("file".equals(uri.getScheme())) return ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (p == null) throw new FileNotFoundException();
        return p;
    }

    private static void checkScheme(Uri uri) throws IOException {
        String s = uri.getScheme();
        if (!"content".equals(s) && !"file".equals(s)) throw new FileNotFoundException("scheme");
    }

    /** Opens the file as a zip: directly when it is a real file (also via the descriptor), else from a private copy. */
    static Zip zip(Context ctx, Uri uri, BooleanSupplier cancelled) throws IOException {
        if ("file".equals(uri.getScheme())) return new Zip(new File(uri.getPath()), null);
        ParcelFileDescriptor p = pfd(ctx, uri);
        try {
            return new Zip(new File("/proc/self/fd/" + p.getFd()), p);
        } catch (IOException e) {
            p.close();
        }
        File tmp = copy(ctx, uri, cancelled);
        try { return new Zip(tmp, () -> { if (!tmp.delete()) tmp.deleteOnExit(); }); }
        catch (IOException e) { tmp.delete(); throw e; }
    }

    /** Copies the content to the app cache (non-seekable providers); the caller deletes it. */
    static File copy(Context ctx, Uri uri, BooleanSupplier cancelled) throws IOException {
        File dir = new File(ctx.getCacheDir(), "viewer");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cache");
        File tmp = File.createTempFile("doc", ".bin", dir);
        try (InputStream in = stream(ctx, uri); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            long total = 0;
            for (int r; (r = in.read(b)) > 0; ) {
                total += r;
                if (total > MAX_COPY) throw new IOException("too large");
                if (cancelled.getAsBoolean()) throw new IOException("cancelled");
                out.write(b, 0, r);
            }
        } catch (IOException | RuntimeException e) { tmp.delete(); throw e; }
        return tmp;
    }

    /** Leftovers of an earlier run (process killed while viewing). */
    static void cleanup(Context ctx) {
        File[] old = new File(ctx.getCacheDir(), "viewer").listFiles();
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 60_000) f.delete();
    }

    /** Guesses the kind from the first bytes when neither name nor type tell it. */
    static Viewer.Kind sniff(Context ctx, Uri uri) {
        byte[] h = new byte[8];
        int n = 0;
        try (InputStream in = stream(ctx, uri)) {
            for (int r; n < h.length && (r = in.read(h, n, h.length - n)) > 0; ) n += r;
        } catch (IOException | RuntimeException e) { return null; }
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return Viewer.Kind.PDF;
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) {
            try (Zip z = zip(ctx, uri, () -> false)) {
                if (z.has("word/document.xml")) return Viewer.Kind.DOCX;
                if (z.has("xl/workbook.xml")) return Viewer.Kind.XLSX;
                if (z.has("ppt/presentation.xml")) return Viewer.Kind.PPTX;
            } catch (IOException | RuntimeException e) { return null; }
            return null;
        }
        if (n >= 4 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0) return Viewer.Kind.DOC;
        return null;
    }
}
