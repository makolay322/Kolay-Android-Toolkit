package app.kolay.app.tools.ocr;

import android.graphics.Bitmap;
import android.net.Uri;
import java.util.*;

/** State shared by the screens of one OCR flow: the recent photos, the selection and the thumbnail cache. */
final class Session {
    final List<Uri> photos = new ArrayList<>();
    final LinkedHashSet<Uri> selected = new LinkedHashSet<>();
    final Map<Uri, Bitmap> thumbs = new HashMap<>();
    /** false = medical mode (default: masking + page ordering + cleanup), true = general mode (local OCR only; cleanup on request). */
    boolean general;
    /** General mode OCR languages (Tesseract combo, remembered); the medical mode always reads German. */
    String langs = "deu";
}
