package app.kolay.app.tools.ocr;

import java.util.*;
import java.util.regex.*;

/** Yerel kişisel veri sansürü (buluta gitmeden önce). Saf Java: Mac'te de test edilebilir. */
final class Mask {
    private static final String TOK = "[A-ZÄÖÜ][A-Za-zÄÖÜäöüß'’&-]{1,}";
    private static final String CAP = "[A-ZÄÖÜ][a-zäöüß]+(?:-[A-ZÄÖÜ][a-zäöüß]+)*";
    /**
     * Birth-date marker up to the date: "*", "geb.", "geb.:", "geb. am", "geboren am", "Geb.Dat.", "Geb.-Datum:", "Geburtsdatum:"
     * (OCR also reads "Geb" as "Geh" before "Dat").
     */
    private static final String BIRTH = "(?:\\*|\\b(?i:ge[bh][.,]?[ \\t]*-?[ \\t]*dat(?:um)?|geb(?:oren|urtsdatum)?)\\.?)[ \\t]*:?[ \\t]*(?:am[ \\t]*:?[ \\t]*)?";
    private static final String DATE = "\\d{1,2}[.,\\s]*\\d{1,2}[.,\\s]*(?:\\d{4}|\\d{2}\\b)";
    private static final String NOT_ROLE = "(?!(?:Patient|Ehe|Sohn|Tochter|Kolleg|Kõll|Oberarzt|Chefarzt|Hausarzt|Facharzt|Dr\\b)\\w*)";

    /** Unvan + isim: "Herr Dr. med. Mustermann", "Frau Kollegin Beispiel", "Prof. Dr. med. R.M. Muster". */
    private static final Pattern TITLE_NAME = Pattern.compile(
        "\\b((?:Herrn?|Frau|Hr\\.|Fr\\.|Dr[.,](?:\\s*(?:med|rer|nat|pol|phil)[.,]?)*|Prof[.,](?:\\s*Dr[.,])?(?:\\s*med[.,]?)?|Dipl\\.-\\w+\\.?|PD|gez\\.)[ \\t]*"
        + "(?:(?:Dr[.,]|med[.,]?|dent\\.|Prof[.,]|PD|[A-Z][.,](?:[A-Z]\\.)?)[ \\t]*)*(?:K[oö]ll?eg\\w*[ \\t]+)?)"
        + NOT_ROLE + "(" + TOK + "(?:[ \\t]+(?:von|van|ven|wan|de|zu|der|den)[ \\t]+" + TOK + "|[ \\t]+" + TOK + "){0,2})");

    /** "Name: ...", "Name, Vorname: ...", "für: ..." (medication plan) gibi etiketli alanlar. */
    private static final String FIELD_LABEL = "(?im)^([ \\t]*(?:(?:Name|Vorname|Nachname)(?:[ \\t]*[,/][ \\t]*(?:Name|Vorname|Nachname))*|für|Patient(?:in)?|Pat\\.?|Versicherte[rn]?|Kind|Mutter|Vater|Absender|Empfänger|Befr\\.|Behandelnde[rn]? Arzt|Arzt|Ärztin|Unterschrift)[ \\t]*[.:;：][ \\t]*)(?:Herrn?|Frau)?[ \\t]*";
    private static final Pattern FIELD = Pattern.compile(FIELD_LABEL + "(.+?)(?=,?[ \\t]*" + BIRTH + "\\d|$)");
    /** A date right after the name in a patient field is the birth date, even without "geb." ("Name, Vorname: Muster, Max 01.02.1950"). */
    private static final Pattern FIELD_DATE = Pattern.compile(
        "(?im)^[ \\t]*(?:(?:Name|Vorname|Nachname)(?:[ \\t]*[,/][ \\t]*(?:Name|Vorname|Nachname))*|für|Patient(?:in)?|Pat\\.?|Versicherte[rn]?)"
        + "[ \\t]*[.:;：][ \\t]*(?:Herrn?|Frau)?[ \\t]*" + TOK + "(?:[ \\t]*,?[ \\t]+" + TOK + "){0,3}[ \\t]*,?[ \\t]*(?:" + BIRTH + ")?(" + DATE + ")");

    /** Doğum işaretinden hemen önceki isim: "Muster, Erika, *01.02.1950", "Max Beispiel (* 03.04.1960", "Muster Erika Maria * 05.06.1970". */
    private static final Pattern BIRTH_NAME = Pattern.compile(
        "(" + TOK + "(?:[ \\t]*,?[ \\t]+" + TOK + "){0,3})(?=[ \\t]*[,(]?[ \\t]*" + BIRTH + "\\d)");

    /** "Patientin Erika Muster," / "Patient Max Beispiel wurde": exactly two capitalised words, then a comma, a bracket or a common verb. */
    private static final Pattern PATIENT_NAME = Pattern.compile(
        "(?m)\\b(Patient(?:in|en)?[ \\t]+)(?!(?:Herrn?|Frau)\\b)" + NOT_ROLE + "(" + CAP + "[ \\t]+" + CAP + ")(?=[ \\t]*[,(]|[ \\t]+(?:wurde|war|ist|hat|kam|befand|stellte|zeigte|berichtete|klagte|erhielt|wird)\\b)");

    /** Role line of a signature block: "Oberarzt", "Leitende Ärztin", "Physician Assistant", "Sektionsleiter", ... */
    static final String ROLE = "(?:(?:Leitende[rn]?|Ltd\\.)[ \\t]+)?(?:(?:Funktionsober|Ober|Chef|Assistenz|Stations|Fach|Haus)?(?:ärztin|arzt|Ärztin|Arzt)"
        + "|Physician[ \\t]+Assistant|(?:Sektions|Bereichs|Abteilungs)leiter(?:in)?)\\b";
    /** Signature line "C. Muster" (or "C. Muster   D. Beispiel" side by side) directly above a role line. */
    private static final Pattern SIGN_NAME = Pattern.compile(
        "(?m)^([ \\t]*)((?:[A-ZÄÖÜ][.,][ \\t]*" + TOK + "[ \\t]*){1,3})(?=\\n[ \\t]*" + ROLE + ")");

    private static final Pattern STREET = Pattern.compile(
        "\\b[A-ZÄÖÜ][\\wäöüß.-]*(?:str\\.?|straße|strasse|weg|platz|allee|gasse|ring|ufer|damm)[.,]?[ \\t]*\\d+[a-z]?\\b");
    /** "wohnhaft in Musterstadt, Beispielweg 3", "wohnhaft: Am Anger 5": the place and a following "Street 12" part (also on the next line). */
    private static final Pattern RESIDENCE = Pattern.compile(
        "([Ww]ohnhaft\\b[ \\t]*:?[ \\t]*(?:in[ \\t]+)?)[^,\\n.]+(?:,[ \\t]*\\n?[ \\t]*[A-ZÄÖÜ][^,\\n]{0,40}?[ \\t]\\d+[a-z]?\\b)?");
    private static final Pattern PLZ = Pattern.compile("\\b\\d{5}[ \\t]+" + CAP);
    private static final Pattern IBAN = Pattern.compile("\\bDE\\d{2}(?:[ \\t]?\\d{4}){4}[ \\t]?\\d{2}\\b");
    private static final Pattern BDAY = Pattern.compile(BIRTH + "(" + DATE + ")");

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        "Dr", "Prof", "Herr", "Herrn", "Frau", "Sehr", "Mit", "Datum", "Seite", "Patient", "Patientin", "Befund", "Alter", "Name"));

    /** Tüm sayfalardan isim adaylarını topla; sonra her sayfada aynı sözcükleri de sansürle. */
    static Set<String> collect(Collection<String> pages) {
        Set<String> names = new HashSet<>();
        for (String p : pages) {
            for (Pattern pt : new Pattern[]{BDAY, FIELD_DATE}) {
                Matcher d = pt.matcher(p);
                while (d.find()) {
                    String[] f = d.group(1).split("[^0-9]+");
                    if (f.length == 3) names.add(f[0] + "." + f[1] + "." + f[2]);
                }
            }
            for (Pattern pt : new Pattern[]{BIRTH_NAME, TITLE_NAME, FIELD, PATIENT_NAME, SIGN_NAME}) {
                Matcher m = pt.matcher(p);
                while (m.find()) for (String w : m.group(pt == BIRTH_NAME ? 1 : 2).split("[\\s,]+"))
                    if (w.length() >= 4 && Character.isUpperCase(w.charAt(0)) && !STOP.contains(w)) names.add(w);
            }
        }
        return names;
    }

    private static boolean lev1(String a, String b) {
        if (a.length() < 6 || b.length() < 6 || Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, d = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++d > 1) return false;
            if (a.length() > b.length()) i++; else if (a.length() < b.length()) j++; else { i++; j++; }
        }
        return d + (a.length() - i) + (b.length() - j) <= 1;
    }

    static String apply(String t, Set<String> known) {
        t = BIRTH_NAME.matcher(t).replaceAll("[NAME]");
        t = BDAY.matcher(t).replaceAll("[GEBURTSDATUM]");
        t = FIELD.matcher(t).replaceAll("$1[NAME]");
        t = TITLE_NAME.matcher(t).replaceAll("$1[NAME]");
        t = PATIENT_NAME.matcher(t).replaceAll("$1[NAME]");
        t = SIGN_NAME.matcher(t).replaceAll("$1[NAME]");
        t = STREET.matcher(t).replaceAll("[ADRESSE]");   // before RESIDENCE: "Musterstr. 5" must not stop it at the dot
        t = RESIDENCE.matcher(t).replaceAll("$1[ADRESSE]");
        t = PLZ.matcher(t).replaceAll("[ORT]");
        t = IBAN.matcher(t).replaceAll("[IBAN]");
        for (String n : known) {   // a birth date found anywhere is masked wherever else it appears
            if (!Character.isDigit(n.charAt(0))) continue;
            String[] f = n.split("\\.");
            String date = "(?<!\\d)0?" + Integer.parseInt(f[0]) + "[.,][ \\t]?0?" + Integer.parseInt(f[1]) + "[.,][ \\t]?" + f[2] + "(?!\\d)";
            // names right before the birth date ("Muster, Max, 01.02.1950" page header without "geb."), also short first names
            t = t.replaceAll("(?<![\\p{L}\\[])" + TOK + "(?:[ \\t]*,?[ \\t]+(?:" + TOK + "|\\[NAME\\])){0,2}(?=[ \\t]*,?[ \\t]*" + date + ")", "[NAME]");
            t = t.replaceAll(date, "[GEBURTSDATUM]");
        }
        if (known.isEmpty()) return t;
        Matcher m = Pattern.compile("[A-ZÄÖÜ][A-Za-zÄÖÜäöüß'’-]{3,}").matcher(t);
        StringBuffer sb = new StringBuffer();   // the StringBuilder overloads of appendReplacement/appendTail need API 34
        while (m.find()) {
            String w = m.group();
            boolean hit = false;
            for (String n : known) if (!Character.isDigit(n.charAt(0)) && (w.equals(n) || (n.length() >= 6 && w.length() >= 6 && (w.startsWith(n) || n.startsWith(w))) || lev1(w, n))) { hit = true; break; }
            m.appendReplacement(sb, hit ? "[NAME]" : Matcher.quoteReplacement(w));
        }
        m.appendTail(sb);
        return sb.toString().replace("[NAME],[NAME]", "[NAME]").replaceAll("(\\[NAME\\][ \\t]*){2,}", "[NAME] ");
    }

}
