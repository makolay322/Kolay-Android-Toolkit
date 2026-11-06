package app.kolay.app.viewer;

import android.net.Uri;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Screen;
import java.util.Locale;

/**
 * Public entry of the read-only document viewer (PDF, Word, Excel, PowerPoint, text, CSV; old .doc/.xls/.ppt through
 * {@link LegacyOffice}). Used by the "Birlikte aç" intent filter and by other tools, e.g. a file manager:
 * <pre>
 *   if (Viewer.canOpen(name)) Viewer.open(c, uri, mime);   // mime may be null; content:// or file:// uris
 * </pre>
 */
public final class Viewer {
    private Viewer() {}

    public enum Kind { PDF, DOCX, XLSX, PPTX, TXT, CSV, DOC, XLS, PPT }

    /** Pushes the viewer screen for {@code uri}. {@code mime} may be null (then the name / content decides). */
    public static void open(Ctx c, Uri uri, String mime) { c.nav.push(new ViewerScreen(uri, mime, false)); }

    /** Screen for a file opened from another app ("Birlikte aç"); Back finishes the activity. */
    public static Screen external(Uri uri, String mime) { return new ViewerScreen(uri, mime, true); }

    /** True when a file name ("rapor.docx"), extension ("docx") or MIME type can be shown. */
    public static boolean canOpen(String nameOrMime) { return kind(nameOrMime, null) != null || kind(null, nameOrMime) != null; }

    /** Kind from the name's extension, else from the MIME type; null when neither is known. */
    public static Kind kind(String name, String mime) {
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            String ext = dot >= 0 ? n.substring(dot + 1) : n;
            switch (ext) {
                case "pdf": return Kind.PDF;
                case "docx": case "docm": case "dotx": return Kind.DOCX;
                case "xlsx": case "xlsm": case "xltx": return Kind.XLSX;
                case "pptx": case "pptm": case "ppsx": case "potx": return Kind.PPTX;
                case "txt": case "text": case "log": case "md": return Kind.TXT;
                case "csv": case "tsv": return Kind.CSV;
                case "doc": case "dot": return Kind.DOC;
                case "xls": case "xlt": return Kind.XLS;
                case "ppt": case "pps": case "pot": return Kind.PPT;
                default: break;
            }
        }
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            int semi = m.indexOf(';');
            if (semi >= 0) m = m.substring(0, semi).trim();
            switch (m) {
                case "application/pdf": return Kind.PDF;
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return Kind.DOCX;
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return Kind.XLSX;
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                case "application/vnd.openxmlformats-officedocument.presentationml.slideshow": return Kind.PPTX;
                case "text/plain": return Kind.TXT;
                case "text/csv": case "text/comma-separated-values": case "application/csv": case "text/tab-separated-values": return Kind.CSV;
                case "application/msword": return Kind.DOC;
                case "application/vnd.ms-excel": return Kind.XLS;
                case "application/vnd.ms-powerpoint": return Kind.PPT;
                default: break;
            }
        }
        return null;
    }

    static String label(Kind k) {
        switch (k) {
            case PDF: return "PDF";
            case DOCX: case DOC: return "Word belgesi";
            case XLSX: case XLS: return "Excel tablosu";
            case PPTX: case PPT: return "PowerPoint sunusu";
            case CSV: return "CSV tablosu";
            default: return "Metin";
        }
    }
}
