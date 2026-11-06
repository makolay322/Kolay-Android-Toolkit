package app.kolay.app.tools;

import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** One entry in the toolbox. Register new tools in {@link Tools}. */
public interface Tool {
    /** Stable identifier (never shown); used for preferences and logs. */
    String id();
    String title();
    String subtitle();
    Icon.Kind icon();
    /** The first screen of the tool; its group (or the home screen) pushes it. */
    Screen open(Ctx c);
}
