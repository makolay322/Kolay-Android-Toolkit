package app.kolay.app.doc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import app.kolay.shell.Host;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tesseract with a chosen language combo ("deu", "deu+tur", …), automatic page layout. One instance per job; not thread
 * safe. Text and word boxes are returned to the caller only, never stored or logged.
 */
public final class OcrEngine implements AutoCloseable {
    /** Longest side handed to Tesseract (same limit as the OCR tool). */
    public static final int MAX_SIDE = 2400;

    private TessBaseAPI api;

    public OcrEngine(Host host, String combo) throws IOException {
        String langs = Langs.join(Langs.split(combo));
        if (langs.isEmpty() || !Langs.missing(host, langs).isEmpty()) throw new IOException("OCR dil verisi eksik");
        TessBaseAPI a = new TessBaseAPI();
        if (!a.init(host.tessDataParent().getAbsolutePath(), langs)) { a.recycle(); throw new IOException("Tesseract başlatılamadı"); }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
        a.setVariable("user_defined_dpi", "300");
        api = a;
    }

    /** One recognised word; box in the coordinates of the bitmap given to {@link #words}. */
    public static final class Word {
        public final String text;
        public final Rect box;
        Word(String text, Rect box) { this.text = text; this.box = box; }
    }

    public static final class Page {
        public final String text;
        public final List<Word> words;
        public final int width, height;
        Page(String text, List<Word> words, int w, int h) { this.text = text; this.words = words; width = w; height = h; }
    }

    /**
     * Grayscale + mild contrast stretch, longest side ≤ {@link #MAX_SIDE} (the preprocessing of the OCR tool). Returns a new
     * bitmap; the caller recycles both.
     */
    public static Bitmap prepare(Bitmap src) {
        int m = Math.max(src.getWidth(), src.getHeight());
        float k = m > MAX_SIDE ? MAX_SIDE / (float) m : 1f;
        int w = Math.max(1, Math.round(src.getWidth() * k)), h = Math.max(1, Math.round(src.getHeight() * k));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0);
        float c = 1.25f, off = -(0.125f * 255);
        cm.postConcat(new ColorMatrix(new float[]{c,0,0,0,off, 0,c,0,0,off, 0,0,c,0,off, 0,0,0,1,0}));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG); p.setColorFilter(new ColorMatrixColorFilter(cm));
        Canvas cv = new Canvas(out);
        cv.scale(w / (float) src.getWidth(), h / (float) src.getHeight());
        cv.drawBitmap(src, 0, 0, p);
        return out;
    }

    public String text(Bitmap prepared) {
        api.setImage(prepared);
        String t = api.getUTF8Text();
        api.clear();
        return t == null ? "" : t.trim();
    }

    /** Text plus word boxes (for the invisible text layer of a searchable PDF). */
    public Page words(Bitmap prepared) {
        api.setImage(prepared);
        String t = api.getUTF8Text();
        List<Word> out = new ArrayList<>();
        ResultIterator it = api.getResultIterator();
        if (it != null) {
            try {
                it.begin();
                do {
                    String w = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                    if (w == null || w.trim().isEmpty()) continue;
                    Rect r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                    if (r != null && r.width() > 0 && r.height() > 0) out.add(new Word(w.trim(), r));
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
            } finally { it.delete(); }
        }
        api.clear();
        return new Page(t == null ? "" : t.trim(), out, prepared.getWidth(), prepared.getHeight());
    }

    @Override public void close() { if (api != null) { api.recycle(); api = null; } }
}
