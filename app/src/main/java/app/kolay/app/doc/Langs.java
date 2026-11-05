package app.kolay.app.doc;

import android.content.Context;
import app.kolay.shell.Host;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR language combinations as Tesseract expects them ("deu", "deu+tur", …), their Turkish labels and the remembered choice
 * per place of use. Codes are always kept in the canonical order of {@link #CODES}.
 */
public final class Langs {
    private Langs() {}

    public static final String[] CODES = {"deu", "tur", "eng"};
    private static final String[] NAMES = {"Almanca", "Türkçe", "İngilizce"};

    public static String name(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code;
    }

    /** "deu+tur" → "Almanca + Türkçe". */
    public static String names(String combo) {
        StringBuilder b = new StringBuilder();
        for (String l : split(combo)) { if (b.length() > 0) b.append(" + "); b.append(name(l)); }
        return b.toString();
    }

    /** Known codes of a combo, canonical order, no duplicates; unknown parts are dropped. */
    public static List<String> split(String combo) {
        List<String> out = new ArrayList<>();
        if (combo == null) return out;
        for (String code : CODES)
            for (String part : combo.split("\\+")) if (part.trim().equals(code)) { out.add(code); break; }
        return out;
    }

    public static String join(List<String> langs) {
        StringBuilder b = new StringBuilder();
        for (String code : CODES) if (langs.contains(code)) { if (b.length() > 0) b.append('+'); b.append(code); }
        return b.toString();
    }

    public static List<String> missing(Host h, String combo) {
        List<String> out = new ArrayList<>();
        for (String l : split(combo)) if (!h.tessInstalled(l)) out.add(l);
        return out;
    }

    public static boolean ready(Host h, String combo) { return !split(combo).isEmpty() && missing(h, combo).isEmpty(); }

    /** The last combo chosen at {@code place} ("ocr", "scan"), or {@code def}. */
    public static String remembered(Context c, String place, String def) {
        String v = c.getSharedPreferences("ocr_langs", Context.MODE_PRIVATE).getString(place, def);
        String clean = join(split(v));
        return clean.isEmpty() ? def : clean;
    }

    public static void remember(Context c, String place, String combo) {
        c.getSharedPreferences("ocr_langs", Context.MODE_PRIVATE).edit().putString(place, combo).apply();
    }

    /** "1,5 MB" */
    public static String mb(long bytes) {
        return String.format(java.util.Locale.forLanguageTag("tr"), "%.1f MB", bytes / 1_048_576.0);
    }
}
