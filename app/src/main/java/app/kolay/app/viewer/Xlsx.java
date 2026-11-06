package app.kolay.app.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Excel (.xlsx) reader: workbook, shared strings, number formats, then one sheet at a time on demand. Only cached
 * values are shown (no formula evaluation). Also parses CSV into the same {@link Sheet}. Pure Java.
 */
public final class Xlsx {
    public static final int MAX_SHARED = 1_500_000;
    private static final Locale TR = new Locale("tr", "TR");

    public final List<String> names = new ArrayList<>();
    private final List<String> parts = new ArrayList<>();
    private final Zip z;
    private String[] shared = new String[0];
    /** Per cell style (xf index): 0 general, 1 date, 2 time, 3 date+time, 4 percent, 5 fixed decimals (see dec). */
    private byte[] xfKind = new byte[0];
    private byte[] xfDec = new byte[0];
    private boolean date1904;

    private Xlsx(Zip z) { this.z = z; }

    public static Xlsx open(Zip z, BooleanSupplier cancelled) throws IOException {
        Xlsx x = new Xlsx(z);
        String wb = "xl/workbook.xml";
        if (!z.has(wb)) {
            for (String v : Px.rels(z, "").values()) if (v.endsWith(".xml") && !v.startsWith("docProps")) { wb = v; break; }
        }
        try {
            Map<String, String> rels = Px.rels(z, wb);
            try (InputStream in = z.open(wb, Zip.MAX_XML)) {
                if (in == null) throw new IOException("no workbook");
                XmlPullParser p = Px.open(in);
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                    if (e != XmlPullParser.START_TAG) continue;
                    String n = Px.ln(p.getName());
                    if (n.equals("workbookPr")) x.date1904 = "1".equals(Px.attr(p, "date1904")) || "true".equals(Px.attr(p, "date1904"));
                    else if (n.equals("sheet")) {
                        String state = Px.attr(p, "state");
                        String part = rels.get(Px.rid(p));
                        if (part == null || (state != null && !state.equals("visible"))) continue;
                        x.names.add(Px.attr(p, "name") == null ? "Sayfa " + (x.names.size() + 1) : Px.attr(p, "name"));
                        x.parts.add(part);
                    }
                }
            }
            String sst = null, sty = null;
            for (String v : rels.values()) {
                if (v.endsWith("sharedStrings.xml")) sst = v;
                else if (v.endsWith("styles.xml")) sty = v;
            }
            if (sty != null) x.styles(sty);
            if (sst != null) x.shared(sst, cancelled);
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
        return x;
    }

    private void shared(String part, BooleanSupplier cancelled) throws IOException, XmlPullParserException {
        try (InputStream in = z.open(part, Zip.MAX_XML)) {
            if (in == null) return;
            XmlPullParser p = Px.open(in);
            ArrayList<String> out = new ArrayList<>();
            StringBuilder sb = null;
            int phonetic = 0;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String n = Px.ln(p.getName());
                    if (n.equals("si")) {
                        sb = new StringBuilder();
                        if ((out.size() & 4095) == 0 && cancelled != null && cancelled.getAsBoolean()) throw new IOException("cancelled");
                    }
                    else if (n.equals("rPh")) phonetic++;
                    else if (n.equals("t") && sb != null && phonetic == 0) sb.append(p.nextText());
                } else if (e == XmlPullParser.END_TAG) {
                    String n = Px.ln(p.getName());
                    if (n.equals("rPh")) phonetic--;
                    else if (n.equals("si") && sb != null) {
                        out.add(sb.toString()); sb = null;
                        if (out.size() >= MAX_SHARED) break;
                    }
                }
            }
            shared = out.toArray(new String[0]);
        }
    }

    private void styles(String part) throws IOException, XmlPullParserException {
        java.util.HashMap<Integer, String> custom = new java.util.HashMap<>();
        ArrayList<Integer> xfs = new ArrayList<>();
        try (InputStream in = z.open(part, Zip.MAX_XML)) {
            if (in == null) return;
            XmlPullParser p = Px.open(in);
            boolean inXfs = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.END_TAG && "cellXfs".equals(Px.ln(p.getName()))) inXfs = false;
                if (e != XmlPullParser.START_TAG) continue;
                String n = Px.ln(p.getName());
                if (n.equals("numFmt")) custom.put(Px.attrInt(p, "numFmtId", -1), Px.attr(p, "formatCode"));
                else if (n.equals("cellXfs")) inXfs = true;
                else if (n.equals("xf") && inXfs) xfs.add(Px.attrInt(p, "numFmtId", 0));
            }
        }
        xfKind = new byte[xfs.size()]; xfDec = new byte[xfs.size()];
        for (int i = 0; i < xfs.size(); i++) {
            int id = xfs.get(i);
            String code = custom.get(id);
            int[] kd = code != null ? classify(code) : builtin(id);
            xfKind[i] = (byte) kd[0]; xfDec[i] = (byte) kd[1];
        }
    }

    private static int[] builtin(int id) {
        if (id == 1 || id == 3) return new int[]{5, 0};
        if (id == 2 || id == 4) return new int[]{5, 2};
        if (id == 9) return new int[]{4, 0};
        if (id == 10) return new int[]{4, 2};
        if (id >= 14 && id <= 17) return new int[]{1, 0};
        if ((id >= 18 && id <= 21) || (id >= 45 && id <= 47)) return new int[]{2, 0};
        if (id == 22) return new int[]{3, 0};
        return new int[]{0, 0};
    }

    /** Rough classification of a custom number format code. */
    static int[] classify(String code) {
        String f = code.split(";", -1)[0];
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < f.length(); i++) {
            char ch = f.charAt(i);
            if (ch == '"') { q = !q; continue; }
            if (q) continue;
            if (ch == '\\' || ch == '_' || ch == '*') { i++; continue; }
            if (ch == '[') { int j = f.indexOf(']', i); if (j < 0) break; String b = f.substring(i + 1, j).toLowerCase(Locale.ROOT); if (b.matches("h+|m+|s+")) sb.append(b); i = j; continue; }
            sb.append(Character.toLowerCase(ch));
        }
        String s = sb.toString();
        if (s.equals("general") || s.isEmpty()) return new int[]{0, 0};
        boolean d = s.indexOf('y') >= 0 || s.indexOf('d') >= 0, t = s.indexOf('h') >= 0 || s.indexOf('s') >= 0;
        if (d && t) return new int[]{3, 0};
        if (d) return new int[]{1, 0};
        if (t) return new int[]{2, 0};
        if (s.indexOf('m') >= 0 && s.indexOf('0') < 0 && s.indexOf('#') < 0) return new int[]{1, 0};
        int dot = s.indexOf('.'), dec = 0;
        if (dot >= 0) for (int i = dot + 1; i < s.length() && (s.charAt(i) == '0' || s.charAt(i) == '#'); i++) dec++;
        if (s.indexOf('%') >= 0) return new int[]{4, Math.min(dec, 10)};
        if (s.indexOf('0') >= 0 || s.indexOf('#') >= 0) return new int[]{5, Math.min(dec, 10)};
        return new int[]{0, 0};
    }

    public Sheet sheet(int i, BooleanSupplier cancelled) throws IOException { return sheet(i, cancelled, null); }

    /**
     * Parses sheet {@code i}. {@code early} (may be null) receives the sheet once the first rows are in, so it can be
     * shown while the rest is parsed; it is called at most once, before this method returns.
     */
    public Sheet sheet(int i, BooleanSupplier cancelled, java.util.function.Consumer<Sheet> early) throws IOException {
        Sheet sh = new Sheet(names.get(i));
        try (InputStream in = z.open(parts.get(i), Zip.MAX_XML)) {
            if (in == null) return sh;
            XmlPullParser p = Px.open(in);
            int row = -1, col = -1, style = 0;
            String type = null, value = null;
            StringBuilder inline = null;
            ArrayList<float[]> widths = new ArrayList<>();
            int count = 0;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    switch (Px.ln(p.getName())) {
                        case "row": {
                            row = Px.attrInt(p, "r", row + 2) - 1; col = -1;
                            if ((++count & 1023) == 0 && cancelled != null && cancelled.getAsBoolean()) throw new IOException("cancelled");
                            if (count == 400 && early != null) { early.accept(sh); early = null; }
                            break;
                        }
                        case "c": {
                            String ref = Px.attr(p, "r");
                            int[] rc = ref == null ? null : ref(ref);
                            if (rc != null) { col = rc[1]; if (rc[0] >= 0) row = rc[0]; } else col++;
                            type = Px.attr(p, "t"); style = Px.attrInt(p, "s", 0); value = null; inline = null;
                            break;
                        }
                        case "v": value = p.nextText(); break;
                        case "is": inline = new StringBuilder(); break;
                        case "t": if (inline != null) inline.append(p.nextText()); break;
                        case "col": {
                            String w = Px.attr(p, "width");
                            if (w != null) try {
                                widths.add(new float[]{Px.attrInt(p, "min", 1), Px.attrInt(p, "max", 1), Float.parseFloat(w)});
                            } catch (NumberFormatException ignored) {}
                            break;
                        }
                        case "sheetData": {
                            int max = 0;
                            for (float[] w : widths) max = Math.max(max, (int) Math.min(w[1], Sheet.MAX_COLS));
                            float[] ws = new float[max];
                            for (float[] w : widths) for (int c = (int) w[0]; c <= Math.min(w[1], max); c++) if (c >= 1) ws[c - 1] = w[2];
                            sh.widths = ws;
                            break;
                        }
                        case "f": Px.skip(p); break;
                        default: break;
                    }
                } else if (e == XmlPullParser.END_TAG && "c".equals(Px.ln(p.getName())) && row >= 0 && col >= 0) {
                    String shown; boolean num = false;
                    if (inline != null) shown = inline.toString();
                    else if (value == null) shown = null;
                    else if ("s".equals(type)) {
                        int k = parseInt(value);
                        shown = k >= 0 && k < shared.length ? shared[k] : "";
                    } else if ("b".equals(type)) shown = "1".equals(value.trim()) ? "DOĞRU" : "YANLIŞ";
                    else if ("str".equals(type) || "e".equals(type) || "inlineStr".equals(type)) shown = value;
                    else { shown = number(value, style); num = true; }
                    if (!sh.put(row, col, shown, num)) break;
                }
            }
            if (early != null) early.accept(sh);
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
        return sh;
    }

    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; } }

    /** "BC12" → {11, 54}; row -1 when the reference has no digits. */
    static int[] ref(String r) {
        int c = 0, i = 0, n = r.length();
        while (i < n && Character.isLetter(r.charAt(i))) { c = c * 26 + (Character.toUpperCase(r.charAt(i)) - 'A' + 1); i++; if (c > 20000) return null; }
        if (c == 0) return null;
        int row = 0; boolean any = false;
        while (i < n && Character.isDigit(r.charAt(i))) { row = row * 10 + (r.charAt(i) - '0'); i++; any = true; if (row > 2_000_000) return null; }
        return new int[]{any ? row - 1 : -1, c - 1};
    }

    String number(String raw, int style) {
        double d;
        try { d = Double.parseDouble(raw.trim()); } catch (NumberFormatException e) { return raw; }
        int kind = style >= 0 && style < xfKind.length ? xfKind[style] : 0;
        int dec = style >= 0 && style < xfDec.length ? xfDec[style] : 0;
        return format(d, kind, dec, date1904);
    }

    private final DecimalFormat[] fixedFmt = new DecimalFormat[11];

    String format(double d, int kind, int dec, boolean date1904) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        switch (kind) {
            case 1: case 2: case 3: {
                if (d < 0 || d > 2_958_465) break;
                long secs = Math.round(d * 86400);
                LocalDate day = (date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 30)).plusDays(secs / 86400);
                int t = (int) (secs % 86400);
                StringBuilder sb = new StringBuilder(19);
                if (kind != 2) { two(sb, day.getDayOfMonth()).append('.'); two(sb, day.getMonthValue()).append('.').append(day.getYear()); }
                if (kind == 3) sb.append(' ');
                if (kind != 1) { two(sb, t / 3600).append(':'); two(sb, t / 60 % 60); if (t % 60 != 0) two(sb.append(':'), t % 60); }
                return sb.toString();
            }
            case 4: return fixed(d * 100, dec) + " %";
            case 5: return fixed(d, dec);
            default: break;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        BigDecimal b = new BigDecimal(d).round(new MathContext(11)).stripTrailingZeros();
        String s = Math.abs(d) >= 1e-6 && Math.abs(d) < 1e15 ? b.toPlainString() : b.toString();
        return s.replace('.', ',');
    }

    private static StringBuilder two(StringBuilder sb, int v) { if (v < 10) sb.append('0'); return sb.append(v); }

    private String fixed(double d, int dec) {
        DecimalFormat f = fixedFmt[dec];
        if (f == null) {
            StringBuilder pat = new StringBuilder("#,##0");
            if (dec > 0) { pat.append('.'); for (int i = 0; i < dec; i++) pat.append('0'); }
            fixedFmt[dec] = f = new DecimalFormat(pat.toString(), DecimalFormatSymbols.getInstance(TR));
        }
        return f.format(d);
    }

    // ---- CSV ---------------------------------------------------------------------------------

    /** Parses delimited text (delimiter guessed from the first line: comma, semicolon or tab) into a sheet. */
    public static Sheet csv(Reader r, String name, BooleanSupplier cancelled) throws IOException {
        Sheet sh = new Sheet(name);
        java.io.PushbackReader in = new java.io.PushbackReader(r, 8192);
        char[] head = new char[8192];
        int hn = 0;
        for (int k; hn < head.length && (k = in.read(head, hn, head.length - hn)) > 0; ) hn += k;
        in.unread(head, 0, hn);
        int comma = 0, semi = 0, tab = 0; boolean q = false;
        for (int i = 0; i < hn; i++) {
            char ch = head[i];
            if (ch == '"') q = !q;
            else if (!q && (ch == '\n' || ch == '\r')) break;
            else if (!q) { if (ch == ',') comma++; else if (ch == ';') semi++; else if (ch == '\t') tab++; }
        }
        char delim = tab > comma && tab > semi ? '\t' : semi > comma ? ';' : ',';
        StringBuilder cell = new StringBuilder();
        int row = 0, col = 0; boolean quoted = false, any = false;
        for (int ch; (ch = in.read()) >= 0; ) {
            if (quoted) {
                if (ch == '"') {
                    int nx = in.read();
                    if (nx == '"') cell.append('"');
                    else { quoted = false; if (nx >= 0) in.unread(nx); }
                } else cell.append((char) ch);
                continue;
            }
            if (ch == '"' && cell.length() == 0) { quoted = true; any = true; }
            else if (ch == delim) { if (!put(sh, row, col++, cell)) return sh; any = true; }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') { int nx = in.read(); if (nx != '\n' && nx >= 0) in.unread(nx); }
                if (!put(sh, row, col, cell)) return sh;
                row++; col = 0; any = false;
                if ((row & 1023) == 0 && cancelled != null && cancelled.getAsBoolean()) throw new IOException("cancelled");
            } else { cell.append((char) ch); any = true; }
        }
        if (any || cell.length() > 0) put(sh, row, col, cell);
        return sh;
    }

    /** Looks like a number ("-1.234,5", "12%"): right-aligned in the grid. */
    private static boolean numeric(String v) {
        int n = v.length(), i = 0;
        if (n == 0 || n > 40) return false;
        if (v.charAt(0) == '-' || v.charAt(0) == '+') i++;
        if (i >= n || !Character.isDigit(v.charAt(i))) return false;
        for (; i < n; i++) {
            char ch = v.charAt(i);
            if (!(Character.isDigit(ch) || ch == '.' || ch == ',' || (ch == '%' && i == n - 1))) return false;
        }
        return true;
    }

    private static boolean put(Sheet sh, int r, int c, StringBuilder cell) {
        String v = cell.toString(); cell.setLength(0);
        return sh.put(r, c, v, numeric(v));
    }
}
