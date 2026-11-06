package app.kolay.app.tools.qr;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Live camera QR reader plus a QR maker (text, Wi-Fi, contact). Also the target of the shell's quick-settings tile (see {@code QrTileService}). */
public final class QrTool implements Tool {
    /** Must match QrTileService.ACTION_QR in the shell. */
    public static final String ACTION_QR = "app.kolay.action.QR_SCAN";

    @Override public String id() { return "qr"; }
    @Override public String title() { return "QR Kod"; }
    @Override public String subtitle() { return "Oku · oluştur"; }
    @Override public Icon.Kind icon() { return Icon.Kind.QR; }
    @Override public Screen open(Ctx c) { return new QrScreen(); }
}
