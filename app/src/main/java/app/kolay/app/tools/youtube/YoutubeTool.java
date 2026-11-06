package app.kolay.app.tools.youtube;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** A pasted YouTube link: original-language transcript in, Turkish summary out. */
public final class YoutubeTool implements Tool {
    @Override public String id() { return "youtube"; }
    @Override public String title() { return "YouTube Özeti"; }
    @Override public String subtitle() { return "Bağlantıdan Türkçe özet"; }
    @Override public Icon.Kind icon() { return Icon.Kind.PLAY; }
    @Override public Screen open(Ctx c) { return new YoutubeScreen(); }

    /** Opened from the system share sheet ("YouTube Özet"): the shared text is filled in and the summary starts at once. */
    public Screen openShared(Ctx c, String sharedText) { return new YoutubeScreen(sharedText); }
}
