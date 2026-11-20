package app.kolay.app.tools.ocr;

import java.util.*;
import java.util.regex.*;

/**
 * Deterministic, conservative cleanup of masked OCR pages before the polish request: drops page numbers (top or bottom of
 * a page), contact/bank/legal lines and "Für Rückfragen …" sentences, greeting and closing formulas, signature role lines,
 * identity-only header lines, case numbers and headers/footers repeated on later pages. When in doubt a line is kept; medical
 * text is never rewritten. Pure Java: can be tried on a desktop JVM.
 */
final class PreClean {
    private PreClean() {}

    /** "4/6", "Seite 2", "Seite 2 von 3", "- 3 -" alone on a line. */
    private static final Pattern PAGE_NO = Pattern.compile(
        "(?i)^[ \\t]*(?:(?:Seite|S\\.)[ \\t]*(\\d{1,2})(?:[ \\t]*(?:von|/|v\\.)[ \\t]*(\\d{1,2}))?|(\\d{1,2})[ \\t]*/[ \\t]*(\\d{1,2})|-[ \\t]*(\\d{1,2})[ \\t]*-)[ \\t]*$");

    /** Parts of a line that only carry contact, bank, legal or case-number data. */
    private static final Pattern[] CONTACT = {
        // phone/fax: labelled ("T: 0123 456-7890", "Fax 0123/456-7891") or with "+49" / "(0123)"; a bare digit run never counts (lab rows)
        Pattern.compile("(?:\\b(?i:Tel(?:efon)?|Telefax|Fax|Fon|Mobil|Durchwahl)\\b\\.?[ \\t]*:?|\\b[TFM][.:])[ \\t]*\\+?\\(?\\d[\\d \\t()/-]{5,}\\d"
            + "|(?:\\+\\d{2}|\\(0\\d{2,5}\\))[\\d \\t()/-]{4,}\\d"),
        Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"),
        Pattern.compile("(?i)(?:https?://|www\\.)\\S+"),
        Pattern.compile("(?i)\\b(?:E-?Mail|Internet|Homepage)\\b[ \\t]*:?"),
        Pattern.compile("(?i)\\b(?:IBAN|BIC|SWIFT|Bankverbindung\\w*|Bankleitzahl|BLZ|Konto(?:-?Nr\\.?|nummer)?|Sparkasse|Volksbank|Commerzbank|"
            + "USt\\.?[ \\t-]*Id\\.?[ \\t-]*Nr\\.?|USt\\.?-?IdNr\\.?|Umsatzsteuer\\w*|Steuer-?Nr\\.?|Steuernummer|Geschäftsführ\\w*|Amtsgericht|"
            + "Registergericht|Handelsregister|HRB|Aufsichtsrat\\w*|Vorsitzende[rn]?|Institutionskennzeichen|IK-?Nr\\.?|"
            + "Fall-?(?:nummer|Nr\\.?)|Aufnahme-?(?:nummer|Nr\\.?)|Pat(?:ienten)?\\.?-?Nr\\.?)"),
        Pattern.compile("\\bDE[ \\t]?\\d{9}\\b"),   // VAT ID
        Pattern.compile("\\[IBAN\\]"),
    };
    /** A matched line is dropped only when this many letters (or fewer) remain beside the contact parts. */
    private static final int CONTACT_REST = 24;

    /** Case/admission number with its value ("Aufnahme-Nr. P 12345678", "Fallnummer: 1234567"); the number is group 1. */
    private static final Pattern CASE_NO = Pattern.compile(
        "(?i)\\b(?:Fall|Aufnahme|Pat(?:ienten)?\\.?)[ \\t]*-?[ \\t]*(?:nummer|Nr\\.?)[ \\t]*:?[ \\t]*(?:[A-Z][ \\t]?)?(\\d{5,})\\b");
    /** A whole line that only offers contact: "Für Rückfragen stehen wir Ihnen gerne telefonisch zur Verfügung." */
    private static final Pattern QUERIES = Pattern.compile(
        "(?i)^(?=.*\\b(?:wir|uns)\\b)[ \\t]*(?:Für|Bei)[ \\t]+(?:\\p{L}+[ \\t]+)?(?:Rück)?fragen\\b[\\p{L} \\t,.]{0,80}?\\b(?:zur[ \\t]+Verfügung|erreichbar|an[ \\t]+uns)[ \\t.!]*$");
    /** Role-only lines of a signature block ("Chefarzt", "Leitende Ärztin   Physician Assistant"). */
    private static final Pattern ROLE_ONLY = Pattern.compile("^[ \\t]*" + Mask.ROLE + "(?:[ \\t]+" + Mask.ROLE + ")*[ \\t]*$");

    private static final Pattern GREETING = Pattern.compile(
        "^[ \\t]*(?i:Sehr[ \\t]+geehrte[rs]?|Liebe[rs]?)\\b(?:[ \\t,]+(?:(?i:Frau|Herrn?|Dr[.,]?|med[.,]?|Prof[.,]?|Kolleg\\w*|Damen|und|Herren|sehr|"
        + "geehrte[rs]?|liebe[rs]?)|\\[NAME\\]|[A-ZÄÖÜ][\\p{L}.-]*))*[ \\t,.!]*$");   // only salutation words and capitalised names
    private static final Pattern CLOSING = Pattern.compile(
        "(?i)^[ \\t]*(?:Mit[ \\t]+)?(?:(?:freundlich\\w*|kollegial\\w*|besten|herzlich\\w*|vielen)[ \\t]+(?:und[ \\t]+)?)+Grü(?:ß|ss|s)en[ \\t,.!]*$"
        + "|(?i)^[ \\t]*(?:Hochachtungsvoll|MfG|Mit[ \\t]+kollegialem[ \\t]+Gruß)[ \\t,.!]*$");

    /** What an identity-only line ("Pat.: [NAME], Geb.Dat. [GEBURTSDATUM], M", "wohnhaft: [ADRESSE], [ORT]") consists of. */
    private static final Pattern IDENTITY_WORDS = Pattern.compile(
        "(?i)\\[(?:NAME|GEBURTSDATUM|ADRESSE|ORT)\\]|\\b(?:Pat(?:ient(?:in)?)?|Betreff|Betr|Name|Vorname|für|wohnhaft|in|geb|ge[bh][ \\t.,]*dat(?:um)?|Geburtsdatum|am|Herrn?|Frau|"
        + "Dr|med|Prof|[mwd])\\b|\\b(?-i:[A-ZÄÖÜ])\\.|[^\\p{L}\\p{N}]");   // also initials: "Dr. med. C. [NAME]"
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:NAME|GEBURTSDATUM|ADRESSE|ORT)\\]");

    /** Lines at the top and bottom of a page that count as header/footer for the repeat rule. */
    private static final int ZONE = 5;

    /**
     * Cleans every page; the result has the same size and order as {@code pages} (a page may become empty). A line in the
     * header/footer zone of at least half of the pages (and at least two) is a running header/footer: its first occurrence
     * stays, later ones are dropped.
     */
    static List<String> pages(List<String> pages) {
        Set<String> caseNos = new HashSet<>();   // also removed where they stand alone, e.g. in a running header
        for (String p : pages) { Matcher m = CASE_NO.matcher(p); while (m.find()) caseNos.add(m.group(1)); }
        List<List<String>> all = new ArrayList<>();
        List<Set<Integer>> zones = new ArrayList<>();
        Map<String, Integer> pagesWith = new HashMap<>();
        int filledPages = 0;
        for (String p : pages) {
            List<String> lines = new ArrayList<>();
            for (String l : p.split("\n", -1)) {
                l = l.replaceAll("[ \\t]+$", "");
                String s = CASE_NO.matcher(l).replaceAll("");
                for (String n : caseNos) s = s.replaceAll("(?<!\\d)" + n + "(?!\\d)", "");
                if (!s.equals(l)) {   // the line carried a case number: the rest stays ("stationär vom …") unless a rule drops it
                    s = s.replaceAll("^[ \\t,;:]+|[ \\t,;:]+$", "");
                    if (s.isEmpty()) continue;
                }
                if (!drop(s)) lines.add(s);
            }
            List<Integer> filled = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) if (!lines.get(i).trim().isEmpty()) filled.add(i);
            if (!filled.isEmpty()) filledPages++;
            Set<Integer> zone = new HashSet<>();
            for (int k = 0; k < filled.size(); k++) if (k < ZONE || k >= filled.size() - ZONE) zone.add(filled.get(k));
            Set<String> keys = new HashSet<>();
            for (int i : zone) keys.add(key(lines.get(i)));
            for (String k : keys) pagesWith.merge(k, 1, Integer::sum);
            all.add(lines);
            zones.add(zone);
        }
        int need = Math.max(2, (filledPages + 1) / 2);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int p = 0; p < all.size(); p++) {
            List<String> lines = all.get(p);
            Set<String> mine = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            boolean blank = true;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (zones.get(p).contains(i)) {
                    if (pageNumber(l.trim()) > 0) continue;   // only at the top or bottom: a lone "1/2" inside may be a dose
                    String key = key(l);
                    if (key.length() >= 6 && pagesWith.get(key) >= need) {
                        if (seen.contains(key)) continue;
                        mine.add(key);
                    }
                }
                boolean b = l.trim().isEmpty();
                if (b && blank) continue;   // no leading or repeated blank lines
                sb.append(l).append('\n');
                blank = b;
            }
            seen.addAll(mine);
            out.add(sb.toString().trim());
        }
        return out;
    }

    /** True when the line carries no medical content by one of the rules above. */
    static boolean drop(String l) {
        String t = l.trim();
        if (t.isEmpty()) return false;
        if (!t.matches(".*[\\p{L}\\p{N}].*")) return true;   // only symbols: OCR dust
        if (GREETING.matcher(t).matches() || CLOSING.matcher(t).matches() || QUERIES.matcher(t).matches() || ROLE_ONLY.matcher(t).matches()) return true;
        if (PLACEHOLDER.matcher(t).find() && IDENTITY_WORDS.matcher(t).replaceAll("").isEmpty()) return true;
        String rest = t;
        boolean hit = false;
        for (Pattern p : CONTACT) {
            Matcher m = p.matcher(rest);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                // phone-like runs need at least six digits, so dosing schemes ("1-0-1-0") and dates stay
                boolean ok = p != CONTACT[0] || m.group().replaceAll("\\D", "").length() >= 6;
                if (ok) hit = true;
                m.appendReplacement(sb, ok ? " " : Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(sb);
            rest = sb.toString();
        }
        return hit && rest.replaceAll("\\[[A-Z]+\\]", "").replaceAll("[^\\p{L}]", "").length() <= CONTACT_REST;
    }

    /** The page number a page-number line shows (0 when the line is none); "x/y" needs x ≤ y. */
    static int pageNumber(String line) {
        Matcher m = PAGE_NO.matcher(line);
        if (!m.matches()) return 0;
        String x = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5);
        String y = m.group(2) != null ? m.group(2) : m.group(4);
        int n = Integer.parseInt(x);
        if (n == 0 || (y != null && n > Integer.parseInt(y))) return 0;
        return n;
    }

    /**
     * Reading order of the pages: by their printed page numbers only when every non-empty page shows exactly one, all
     * different; otherwise the order as scanned (the user can reorder pages before reading).
     */
    static List<Integer> order(List<String> pages) {
        List<Integer> idx = new ArrayList<>();
        Map<Integer, Integer> num = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < pages.size(); i++) {
            idx.add(i);
            if (pages.get(i).trim().isEmpty()) continue;
            int found = 0, count = 0;
            for (String l : pages.get(i).split("\n")) { int n = pageNumber(l.trim()); if (n > 0) { found = n; count++; } }
            if (count != 1 || !used.add(found)) ok = false;
            num.put(i, found);
        }
        if (!ok || num.size() < 2) return idx;
        idx.sort((a, b) -> {
            Integer na = num.get(a), nb = num.get(b);
            return na == null || nb == null ? Integer.compare(a, b) : Integer.compare(na, nb);
        });
        return idx;
    }

    /** Comparison key for repeated header/footer lines: letters and digits only, lower case. */
    private static String key(String l) { return l.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
}
