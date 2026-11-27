package app.kolay.app.tools.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;

/**
 * Read-only content:// links for "Paylaş" / "Birlikte aç" (a minimal stand-in for AndroidX's FileProvider).
 * Not exported: other apps can read only the single links the app hands them with a read grant. App-private files are never served, except the
 * temporary copies of network files in {@code cache/smb-open/} (see {@code Files.openRemote}) and installed apps' APKs under {@code /data/app/}
 * (world-readable anyway; "Uygulama APK'ları"), which may carry a display name ({@code ?n=}) instead of "base.apk".
 * Network files are streamed: {@code /r/<token>/<name>} maps an unguessable in-memory token (made by {@link #stream}) to a Samba server + path;
 * {@code openFile} returns a proxy file descriptor whose reads go to the server on demand (nothing is downloaded first). Tokens live only
 * as long as the process; no credentials or paths are in the URI.
 */
public final class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "app.kolay.files";

    private static final class Remote {
        final SmbSource src; final String path, name; final long size;
        Remote(SmbSource src, String path, String name, long size) { this.src = src; this.path = path; this.name = name; this.size = size; }
    }

    private static final int MAX_TOKENS = 64;
    private static final int READ_AHEAD = 2 << 20;
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static final Map<String, Remote> REMOTES = new LinkedHashMap<String, Remote>(16, 0.75f, true) {   // access order: a link still being read is not evicted first
        @Override protected boolean removeEldestEntry(Map.Entry<String, Remote> e) { return size() > MAX_TOKENS; }
    };
    private static HandlerThread thread;
    private static Handler handler;

    /** A streaming link for a file on a Samba server (read on demand over the network). */
    static Uri stream(SmbSource src, String path, String name, long size) {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x));
        synchronized (REMOTES) { REMOTES.put(t.toString(), new Remote(src, path, name, size)); }
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("r").appendPath(t.toString()).appendPath(name).build();
    }

    private static Remote remote(Uri u) {
        java.util.List<String> s = u.getPathSegments();
        if (s.size() < 2 || !"r".equals(s.get(0))) return null;
        synchronized (REMOTES) { return REMOTES.get(s.get(1)); }
    }

    private static synchronized Handler handler() {
        if (handler == null) { thread = new HandlerThread("smb-stream"); thread.start(); handler = new Handler(thread.getLooper()); }
        return handler;
    }

    /** Serves one open remote file to the proxy descriptor; all calls arrive on the {@code smb-stream} thread. */
    private static final class Proxy extends ProxyFileDescriptorCallback {
        private final SmbSource.RandomRead r;
        private final long size;
        private final byte[] buf = new byte[READ_AHEAD];
        private long bufPos = -1;
        private int bufLen;

        Proxy(SmbSource.RandomRead r, long size) { this.r = r; this.size = size; }

        @Override public long onGetSize() { return size; }

        @Override public int onRead(long off, int len, byte[] data) throws ErrnoException {
            try {
                if (off >= size) return 0;
                len = (int) Math.min(len, size - off);
                if (len >= buf.length) return r.read(off, data, 0, len);   // large request: straight through
                if (bufPos < 0 || off < bufPos || off + len > bufPos + bufLen) {
                    bufLen = r.read(off, buf, 0, (int) Math.min(buf.length, size - off));
                    bufPos = off;
                    if (bufLen <= 0) { bufPos = -1; return 0; }
                }
                int n = (int) Math.min(len, bufPos + bufLen - off);
                System.arraycopy(buf, (int) (off - bufPos), data, 0, n);
                return n;
            } catch (IOException | RuntimeException e) { throw new ErrnoException("read", OsConstants.EIO); }
        }

        @Override public void onRelease() { r.close(); }
    }

    /** A link whose display name is {@code name} instead of the file's own name. */
    static Uri uri(String path, String name) { return uri(path).buildUpon().appendQueryParameter("n", name).build(); }

    public static Uri uri(String path) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f").appendEncodedPath(Uri.encode(path, "/").replaceFirst("^/", "")).build(); }

    private File file(Uri u) throws FileNotFoundException {
        String p = u.getPath();
        if (p == null || !p.startsWith("/f/")) throw new FileNotFoundException();
        File f = new File(p.substring(2));
        try {
            String c = f.getCanonicalPath(), own = new File(getContext().getApplicationInfo().dataDir).getCanonicalPath();
            String fetched = new File(getContext().getCacheDir(), "smb-open").getCanonicalPath() + "/";   // network files copied for "open"
            if (c.startsWith(fetched) && f.isFile()) return f;
            if (c.startsWith("/data/app/") && c.endsWith(".apk") && f.isFile()) return f;   // an installed app's (split) APK
            if (c.startsWith(own + "/") || (c.startsWith("/data/") && !c.startsWith("/data/media/")) || !f.isFile()) throw new FileNotFoundException();
        } catch (IOException e) { throw new FileNotFoundException(); }
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri u) { return Files.mime(u.getLastPathSegment()); }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        Remote rm = remote(u);
        if (rm != null) {
            if (proj == null) proj = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor m = new MatrixCursor(proj, 1);
            Object[] row = new Object[proj.length];
            for (int i = 0; i < proj.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(proj[i]) ? rm.name : OpenableColumns.SIZE.equals(proj[i]) ? (Object) rm.size : null;
            m.addRow(row);
            return m;
        }
        File f;
        try { f = file(u); } catch (FileNotFoundException e) { return null; }
        if (proj == null) proj = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor m = new MatrixCursor(proj, 1);
        Object[] row = new Object[proj.length];
        for (int i = 0; i < proj.length; i++)
            row[i] = OpenableColumns.DISPLAY_NAME.equals(proj[i]) ? (u.getQueryParameter("n") != null ? u.getQueryParameter("n") : f.getName()) : OpenableColumns.SIZE.equals(proj[i]) ? f.length() : null;
        m.addRow(row);
        return m;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        Remote rm = remote(u);
        if (rm != null) {
            SmbSource.RandomRead r;
            try { r = rm.src.openRandom(rm.path); } catch (IOException e) { throw new FileNotFoundException(); }
            try {
                return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, new Proxy(r, rm.size), handler());
            } catch (IOException | RuntimeException e) { r.close(); throw new FileNotFoundException(); }
        }
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
