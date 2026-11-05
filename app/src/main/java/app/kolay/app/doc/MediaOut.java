package app.kolay.app.doc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Files the user saves explicitly: images into Pictures/Kolay, PDFs into Documents/Kolay (Download/Kolay where the
 * documents folder is refused). Entries stay pending (invisible to other apps) until {@link #publish}; {@link #discard}
 * removes a half-written one.
 */
public final class MediaOut {
    private MediaOut() {}

    public static Uri newImage(ContentResolver cr, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kolay");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("Dosya oluşturulamadı");
        return u;
    }

    /** @return {uri, folder shown to the user} */
    public static Object[] newPdf(ContentResolver cr, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Kolay");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = null;
        try { u = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v); }
        catch (RuntimeException ignored) { }
        if (u != null) return new Object[]{u, "Documents/Kolay"};
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Kolay");
        u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("Dosya oluşturulamadı");
        return new Object[]{u, "Download/Kolay"};
    }

    public static OutputStream open(ContentResolver cr, Uri u) throws IOException {
        OutputStream os = cr.openOutputStream(u, "w");
        if (os == null) throw new IOException("Dosya açılamadı");
        return os;
    }

    public static void publish(ContentResolver cr, Uri u) {
        ContentValues d = new ContentValues();
        d.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, d, null, null);
    }

    public static void discard(ContentResolver cr, Uri u) {
        if (u == null) return;
        try { cr.delete(u, null, null); } catch (Exception ignored) { }
    }
}
