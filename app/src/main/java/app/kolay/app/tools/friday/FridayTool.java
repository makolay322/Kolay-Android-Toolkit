package app.kolay.app.tools.friday;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Random Islamic-style 2:1 Friday card with a well-known hadith; half of the backgrounds are black themes. */
public final class FridayTool implements Tool {
    @Override public String id() { return "cuma"; }
    @Override public String title() { return "Cuma Mesajı"; }
    @Override public String subtitle() { return "Hadisli 2:1 görsel"; }
    @Override public Icon.Kind icon() { return Icon.Kind.CRESCENT; }
    @Override public Screen open(Ctx c) { return new FridayScreen(); }
}
