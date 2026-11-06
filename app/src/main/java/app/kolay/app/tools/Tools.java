package app.kolay.app.tools;

import app.kolay.app.tools.apps.AppsTool;
import app.kolay.app.tools.chat.ChatTool;
import app.kolay.app.tools.compress.CompressTool;
import app.kolay.app.tools.friday.FridayTool;
import app.kolay.app.tools.halal.HelalTool;
import app.kolay.app.tools.kible.KibleTool;
import app.kolay.app.tools.namaz.NamazTool;
import app.kolay.app.tools.qr.QrTool;
import app.kolay.app.tools.youtube.YoutubeTool;

/** The toolbox contents: the home screen shows the groups as cards, a group lists its tools in this order (the documents card opens its own flow, tools/scan/DocsScreen). Add a tool to a group here. */
public final class Tools {
    private Tools() {}

    public static final Tool[] FAITH = { new NamazTool(), new KibleTool(), new FridayTool(), new HelalTool() };
    public static final Tool[] UTILITIES = { new QrTool(), new CompressTool(), new YoutubeTool(), new AppsTool() };
    /** Has a home card of its own. */
    public static final Tool CHAT = new ChatTool();
}
