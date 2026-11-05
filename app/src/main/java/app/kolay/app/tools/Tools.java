package app.kolay.app.tools;

import app.kolay.app.tools.apps.AppsTool;
import app.kolay.app.tools.chat.ChatTool;
import app.kolay.app.tools.compress.CompressTool;
import app.kolay.app.tools.friday.FridayTool;
import app.kolay.app.tools.halal.HelalTool;
import app.kolay.app.tools.kible.KibleTool;
import app.kolay.app.tools.namaz.NamazTool;
import app.kolay.app.tools.ocr.OcrTool;
import app.kolay.app.tools.qr.QrTool;
import app.kolay.app.tools.scan.ScanTool;
import app.kolay.app.tools.youtube.YoutubeTool;

/** The toolbox contents, in display order. Add a line here to add a tool. */
public final class Tools {
    private Tools() {}

    public static final Tool[] ALL = {
        new OcrTool(),
        new ScanTool(),
        new ChatTool(),
        new YoutubeTool(),
        new CompressTool(),
        new QrTool(),
        new HelalTool(),
        new FridayTool(),
        new AppsTool(),
        new NamazTool(),
        new KibleTool(),
    };
}
