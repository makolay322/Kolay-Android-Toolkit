package app.kolay.app.tools.halal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import app.kolay.shell.Host;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.IOException;

/** One ingredient-label photo -> plain text with the downloaded Tesseract data (deu, + tur when installed). The text is returned to the caller and kept nowhere else. */
final class LabelOcr {
    private static final int MAX_SIDE = 2400;
    private LabelOcr() {}

    static String read(Host host, Bitmap photo) throws IOException {
        Bitmap img = prepare(photo);
        TessBaseAPI api = new TessBaseAPI();
        try {
            // German is required (LabelScreen offers the download); Turkish is added when present, for ş ğ ı on Turkish labels
            String lang = host.tessInstalled("tur") ? "deu+tur" : "deu";
            if (!host.tessInstalled("deu")) throw new IOException("OCR dil verisi (Almanca) yüklü değil");
            if (!api.init(host.tessDataParent().getAbsolutePath(), lang)) throw new IOException("Tesseract başlatılamadı");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setVariable("user_defined_dpi", "300");
            api.setImage(img);
            String t = api.getUTF8Text();
            return t == null ? "" : t.trim();
        } finally {
            api.recycle();
            if (img != photo) img.recycle();
        }
    }

    /** Downscale to the OCR limit and stretch the contrast a little (labels are often glossy). */
    private static Bitmap prepare(Bitmap src) {
        int m = Math.max(src.getWidth(), src.getHeight());
        float k = m > MAX_SIDE ? MAX_SIDE / (float) m : 1f;
        int w = Math.round(src.getWidth() * k), h = Math.round(src.getHeight() * k);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float c = 1.3f, off = -(0.15f * 255);
        ColorMatrix cm = new ColorMatrix(new float[]{c,0,0,0,off, 0,c,0,0,off, 0,0,c,0,off, 0,0,0,1,0});
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG); p.setColorFilter(new ColorMatrixColorFilter(cm));
        Canvas cv = new Canvas(out);
        cv.scale(k, k);
        cv.drawBitmap(src, 0, 0, p);
        return out;
    }
}
