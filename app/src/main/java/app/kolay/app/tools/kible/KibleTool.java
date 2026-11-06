package app.kolay.app.tools.kible;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Qibla compass: the saved prayer location gives the bearing, the phone's rotation sensors give the heading. */
public final class KibleTool implements Tool {
    @Override public String id() { return "kible"; }
    @Override public String title() { return "Kıble"; }
    @Override public String subtitle() { return "Pusulayla kıble yönü"; }
    @Override public Icon.Kind icon() { return Icon.Kind.COMPASS; }
    @Override public Screen open(Ctx c) { return new KibleScreen(); }
}
