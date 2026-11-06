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

/**
 * Read-only content:// links for "Paylaş" / "Birlikte aç" (a minimal stand-in for AndroidX's FileProvider).
 * Not exported: other apps can read only the single links the app hands them with a read grant. App-private files are never served.
 */
public final class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "app.kolay.files";

    public static Uri uri(String path) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f").appendEncodedPath(Uri.encode(path, "/").replaceFirst("^/", "")).build(); }

    private File file(Uri u) throws FileNotFoundException {
        String p = u.getPath();
        if (p == null || !p.startsWith("/f/")) throw new FileNotFoundException();
        File f = new File(p.substring(2));
        try {
            String c = f.getCanonicalPath(), own = new File(getContext().getApplicationInfo().dataDir).getCanonicalPath();
            if (c.startsWith(own + "/") || (c.startsWith("/data/") && !c.startsWith("/data/media/")) || !f.isFile()) throw new FileNotFoundException();
        } catch (IOException e) { throw new FileNotFoundException(); }
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri u) { return Files.mime(u.getLastPathSegment()); }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        File f;
        try { f = file(u); } catch (FileNotFoundException e) { return null; }
        if (proj == null) proj = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor m = new MatrixCursor(proj, 1);
        Object[] row = new Object[proj.length];
        for (int i = 0; i < proj.length; i++)
            row[i] = OpenableColumns.DISPLAY_NAME.equals(proj[i]) ? f.getName() : OpenableColumns.SIZE.equals(proj[i]) ? f.length() : null;
        m.addRow(row);
        return m;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
