package app.kolay.app.tools.friday;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hadiths for the Friday card, read once from {@code assets/hadiths.tsv}: one hadith per line, "text<TAB>source".
 *
 * Source: Diyanet İşleri Başkanlığı, "Hadislerle İslâm" (7 volumes, modern Turkish), as published at
 * https://hadislerleislam.diyanet.gov.tr: the hadiths quoted on the hadith page at the start of each chapter. Only the
 * Prophet's own words are kept (no narrator chain or story), verbatim apart from typographic quotes and apostrophes
 * (the site prints both as a straight "), spacing around brackets, and a closing omission mark that becomes a full
 * stop; the source line is the Buhârî or Müslim reference when the book cites one, otherwise its first reference,
 * with book and chapter. Only hadiths that carry advice for the reader remain: prophecies, descriptions of the
 * hereafter, praise of companions, narrations of practice, bare supplication texts and technical rulings were left
 * out, as were repeats, entries over 300 characters, and entries whose translation reads stilted (slash alternatives,
 * brackets inside words): those were dropped, not reworded.
 */
final class Hadiths {
    private Hadiths() {}

    private static List<String[]> all;

    static synchronized List<String[]> all(Context ctx) {
        if (all != null) return all;
        List<String[]> out = new ArrayList<>(300);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("hadiths.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add(new String[]{"Ameller niyetlere göredir.", "Buhârî, Bed'ü'l-Vahy 1"});
        return all = out;
    }
}
