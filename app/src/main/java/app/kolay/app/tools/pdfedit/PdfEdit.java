package app.kolay.app.tools.pdfedit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDPageTree;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.io.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The page editor's engine. Pages are references (source PDF + page index, or a picture file) plus an extra rotation;
 * nothing is written until {@link #save}. Thumbnails come from the platform {@link PdfRenderer}; saving uses PdfBox on the
 * original objects (text and vectors stay as they are, rotation is the page's /Rotate), with temp-file buffers.
 */
final class PdfEdit {
    private PdfEdit() {}

    /** A4 in points, for picture pages when the document has no page to take the size from. */
    static final float A4_W = 595.28f, A4_H = 841.89f;

    /** One opened PDF: a readable file (the original, or a private copy / decrypted copy) and its renderer (render thread only). */
    static final class Source {
        final File file;
        PdfRenderer renderer;
        ParcelFileDescriptor fd;
        int count;
        Source(File f) { file = f; }
    }

    /** One page of the result. Fields are touched on the main thread; the thumbnail is rendered on the render thread. */
    static final class Pg {
        final Source src;   // null for a picture page
        final int index;
        final File image;
        int rot;            // extra clockwise rotation, 0/90/180/270
        Bitmap thumb; int thumbRot = -1; boolean queued, broken;
        Pg(Source s, int i) { src = s; index = i; image = null; }
        Pg(File img) { src = null; index = -1; image = img; }
    }

    /** Thrown by {@link #open} when the PDF needs a password. */
    static final class Locked extends IOException { Locked() { super("password"); } }

    static void init(Context ctx) { if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(ctx.getApplicationContext()); }

    static MemoryUsageSetting mem(File tmpDir) { return MemoryUsageSetting.setupTempFileOnly().setTempDir(tmpDir); }

    /** Render thread: opens the renderer; {@link Locked} when the file is password protected. */
    static Source open(File f) throws IOException {
        Source s = new Source(f);
        s.fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        try { s.renderer = new PdfRenderer(s.fd); }
        catch (SecurityException e) { s.fd.close(); throw new Locked(); }
        catch (IOException | RuntimeException e) { s.fd.close(); throw e; }
        s.count = s.renderer.getPageCount();
        return s;
    }

    /** Render thread. */
    static void close(Source s) {
        try { if (s.renderer != null) s.renderer.close(); } catch (RuntimeException ignored) { }
        try { if (s.fd != null) s.fd.close(); } catch (IOException ignored) { }
        s.renderer = null; s.fd = null;
    }

    /** Background: writes an unencrypted copy of a password-protected PDF; InvalidPasswordException for a wrong password. */
    static void decrypt(File in, String password, File out, File tmpDir) throws IOException {
        try (PDDocument d = PDDocument.load(in, password, mem(tmpDir))) {
            d.setAllSecurityToBeRemoved(true);
            d.save(out);
        }
    }

    /** Render thread: a preview whose long side is about {@code longPx}, including the page's extra rotation. */
    static Bitmap thumb(Pg p, int rot, int longPx) throws IOException {
        Bitmap b;
        int turn = rot;
        if (p.src != null) {
            if (p.src.renderer == null) throw new IOException("closed");
            try (PdfRenderer.Page pg = p.src.renderer.openPage(p.index)) {
                float w = pg.getWidth(), h = pg.getHeight(), s = longPx / Math.max(w, h);
                b = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(p.image.getPath(), o);
            if (o.outWidth <= 0) throw new IOException("image");
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= longPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeFile(p.image.getPath(), o);
            if (b == null) throw new IOException("image");
            turn = (turn + exifRotation(p.image)) % 360;
        }
        if (turn == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(turn);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** Clockwise rotation the picture's EXIF orientation asks for (mirrored orientations: their rotation part). */
    static int exifRotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: case ExifInterface.ORIENTATION_TRANSPOSE: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: case ExifInterface.ORIENTATION_FLIP_VERTICAL: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: case ExifInterface.ORIENTATION_TRANSVERSE: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) { return 0; }
    }

    /** A page of the result as it is when saving starts (copied on the main thread). */
    static final class Job {
        final Source src; final int index; final File image; final int rot;
        Job(Pg p) { src = p.src; index = p.index; image = p.image; rot = p.rot; }
    }

    /**
     * Background: writes the result to {@code out}. {@code main} (the opened document) is the base, so its document-level
     * parts (metadata, outline, forms) stay; its pages are re-ordered in place, pages of other PDFs are imported, picture
     * pages get the size of the nearest PDF page (A4 if there is none), turned to match the picture. Security is removed
     * (password-protected sources were opened with their password). {@code step} gets the number of pages done.
     */
    static void save(Source main, List<Job> jobs, OutputStream out, File tmpDir, IntConsumer step) throws IOException {
        Map<Source, PDDocument> docs = new IdentityHashMap<>();
        try {
            PDDocument base = PDDocument.load(main.file, mem(tmpDir));
            docs.put(main, base);
            base.setAllSecurityToBeRemoved(true);
            PDPageTree tree = base.getPages();
            List<PDPage> own = new ArrayList<>();
            for (PDPage p : tree) own.add(p);
            // Pull inherited attributes into each page: they are lost once the page leaves its parent node.
            for (PDPage p : own) {
                p.setMediaBox(p.getMediaBox());
                p.setCropBox(p.getCropBox());
                p.setRotation(p.getRotation());
                PDResources r = p.getResources();
                if (r != null) p.setResources(r);
            }
            for (PDPage p : own) tree.remove(p);

            // Sizes of the PDF pages in result order, for the picture pages next to them.
            int n = jobs.size();
            PDRectangle[] size = new PDRectangle[n];
            for (int i = 0; i < n; i++) {
                Job j = jobs.get(i);
                if (j.src == null) continue;
                PDDocument d = docs.get(j.src);
                if (d == null) { d = PDDocument.load(j.src.file, mem(tmpDir)); docs.put(j.src, d); }
                size[i] = (d == base ? own.get(j.index) : d.getPage(j.index)).getMediaBox();
            }
            for (int i = 0; i < n; i++) {
                Job j = jobs.get(i);
                PDPage p;
                if (j.src == null) {
                    p = picturePage(base, j.image, neighbour(size, i));
                    p.setRotation(j.rot);
                } else {
                    PDDocument d = docs.get(j.src);
                    if (d == base) { p = own.get(j.index); tree.add(p); }
                    else p = base.importPage(d.getPage(j.index));
                    p.setRotation(((p.getRotation() + j.rot) % 360 + 360) % 360);
                }
                step.accept(i + 1);
            }
            base.save(out);
        } finally {
            for (PDDocument d : docs.values()) try { d.close(); } catch (IOException ignored) { }
        }
    }

    /** Size of the nearest PDF page before {@code i}, else after it; A4 when there is none. */
    private static PDRectangle neighbour(PDRectangle[] size, int i) {
        for (int k = i - 1; k >= 0; k--) if (size[k] != null) return size[k];
        for (int k = i + 1; k < size.length; k++) if (size[k] != null) return size[k];
        return new PDRectangle(A4_W, A4_H);
    }

    /**
     * A new page in {@code d} showing the picture whole, aspect kept, centred, at full resolution. The page takes the
     * neighbour's paper size (never the picture's pixel size), landscape when the upright picture is wider than tall.
     * JPEG goes in as it is (no re-compression; EXIF orientation applied by the drawing transform), PNG losslessly,
     * other formats as a high-quality JPEG.
     */
    private static PDPage picturePage(PDDocument d, File f, PDRectangle like) throws IOException {
        PDImageXObject img = image(d, f);
        int exif = exifRotation(f);
        boolean turned = exif == 90 || exif == 270;
        float dw = turned ? img.getHeight() : img.getWidth(), dh = turned ? img.getWidth() : img.getHeight();
        float shortSide = Math.min(like.getWidth(), like.getHeight()), longSide = Math.max(like.getWidth(), like.getHeight());
        float pw = dw > dh ? longSide : shortSide, ph = dw > dh ? shortSide : longSide;
        PDPage page = new PDPage(new PDRectangle(pw, ph));
        d.addPage(page);
        float s = Math.min(pw / dw, ph / dh), w = dw * s, h = dh * s, x = (pw - w) / 2, y = (ph - h) / 2;
        com.tom_roush.pdfbox.util.Matrix m;
        switch (exif) {   // maps the image's unit square onto the upright placement (PDF y axis points up)
            case 90: m = new com.tom_roush.pdfbox.util.Matrix(0, -h, w, 0, x, y + h); break;
            case 180: m = new com.tom_roush.pdfbox.util.Matrix(-w, 0, 0, -h, x + w, y + h); break;
            case 270: m = new com.tom_roush.pdfbox.util.Matrix(0, h, -w, 0, x + w, y); break;
            default: m = new com.tom_roush.pdfbox.util.Matrix(w, 0, 0, h, x, y); break;
        }
        try (PDPageContentStream cs = new PDPageContentStream(d, page)) { cs.drawImage(img, m); }
        return page;
    }

    private static PDImageXObject image(PDDocument d, File f) throws IOException {
        byte[] head = new byte[8];
        int n;
        try (InputStream in = new FileInputStream(f)) { n = in.read(head); }
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { return JPEGFactory.createFromStream(d, in); }
        }
        boolean png = n >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) throw new IOException("image");
        try { return png ? LosslessFactory.createFromImage(d, b) : JPEGFactory.createFromImage(d, b, 0.95f); }
        finally { b.recycle(); }
    }
}
