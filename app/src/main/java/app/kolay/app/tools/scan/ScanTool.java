package app.kolay.app.tools.scan;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Document scanner: camera or gallery → page outline → straightened, filtered pages → PDF (optionally searchable) or JPEG. */
public final class ScanTool implements Tool {
    @Override public String id() { return "scan"; }
    @Override public String title() { return "Belge Tarayıcı"; }
    @Override public String subtitle() { return "Tara, kırp, PDF olarak kaydet"; }
    @Override public Icon.Kind icon() { return Icon.Kind.DOCSCAN; }
    @Override public Screen open(Ctx c) { return new CameraScreen(new ScanSession(c)); }
}
