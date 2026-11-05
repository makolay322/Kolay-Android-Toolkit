package app.kolay.app.tools.netscan;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Network scanner (eSCL / AirScan): find or enter a scanner, scan from the glass or the feeder, then the same pages → PDF/JPEG flow as the document scanner. */
public final class NetScanTool implements Tool {
    @Override public String id() { return "netscan"; }
    @Override public String title() { return "Ağ Tarayıcı"; }
    @Override public String subtitle() { return "Wi-Fi tarayıcıdan PDF"; }
    @Override public Icon.Kind icon() { return Icon.Kind.NETSCAN; }
    @Override public Screen open(Ctx c) { return new DevicesScreen(); }
}
