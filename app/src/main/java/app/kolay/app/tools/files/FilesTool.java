package app.kolay.app.tools.files;

import app.kolay.app.tools.Tool;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;

/** Local file manager (all-files access): browse, copy/move, rename, delete, ZIP, share. */
public final class FilesTool implements Tool {
    @Override public String id() { return "files"; }
    @Override public String title() { return "Dosyalar"; }
    @Override public String subtitle() { return "Klasörler · kopyala · taşı · ZIP"; }
    @Override public Icon.Kind icon() { return Icon.Kind.FOLDER; }
    @Override public Screen open(Ctx c) { return new FilesScreen(); }
    /** "8,9 GB": the same size format as the file manager (the home card shows the free space with it). */
    public static String size(long bytes) { return Files.size(bytes); }
}
