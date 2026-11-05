package app.kolay.app.tools.ocr;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Photos in, plain text out: medical mode (German medical documents, masked and cleaned) or general mode (local OCR, optional cleanup). */
public final class OcrTool implements Tool {
    @Override public String id() { return "ocr"; }
    @Override public String title() { return "OCR"; }
    @Override public String subtitle() { return "Belge ve fotoğraftan metin"; }
    @Override public Icon.Kind icon() { return Icon.Kind.DOC; }
    @Override public Screen open(Ctx c) {
        Session s = new Session();
        s.langs = app.kolay.app.doc.Langs.remembered(c.a, "ocr", "deu");
        return new PickerScreen(s);
    }
}
