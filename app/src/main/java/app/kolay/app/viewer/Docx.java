package app.kolay.app.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Word (.docx) reader: streams word/document.xml into blocks (paragraphs with formatted runs, simple tables, pictures).
 * Styles give heading levels, numbering.xml gives list labels. Pure Java (XmlPullParser only).
 */
public final class Docx {
    private Docx() {}

    public static final int MAX_IMAGES = 300, MAX_TABLE_ROWS = 3000;

    public static final class Run {
        public String text;
        public boolean b, i, u, s;
        public int halfPts;     // 0 = inherit
        public int color;       // 0 = automatic, else 0xFFRRGGBB
        boolean same(Run o) { return b == o.b && i == o.i && u == o.u && s == o.s && halfPts == o.halfPts && color == o.color; }
    }

    public abstract static class Block {}

    public static final class Para extends Block {
        public final ArrayList<Run> runs = new ArrayList<>();
        /** -1 body, 0 title, 1..6 heading level. */
        public int heading = -1;
        public String label;    // list bullet/number, or null
        public int level;       // list level (indent)
        public int align;       // 0 start, 1 center, 2 end, 3 justify
        public Object cache;    // rendered text, set by the UI
        public String text() { StringBuilder sb = new StringBuilder(); for (Run r : runs) sb.append(r.text); return sb.toString(); }
        boolean empty() { for (Run r : runs) if (!r.text.trim().isEmpty()) return false; return label == null; }
    }

    public static final class Table extends Block {
        /** rows → cells → paragraphs */
        public final ArrayList<ArrayList<ArrayList<Para>>> rows = new ArrayList<>();
    }

    public static final class Image extends Block {
        public final String part;
        public final long cx, cy;   // EMU, 0 if unknown
        Image(String part, long cx, long cy) { this.part = part; this.cx = cx; this.cy = cy; }
    }

    public interface Sink {
        /** Called once before the first block; {@code baseHalfPts} is the document's default font size. */
        default void begin(int baseHalfPts) {}
        void block(Block b);
    }

    // ---- parsing ---------------------------------------------------------------------------------

    private static final class Style { String basedOn; int heading = -2, numId = -1, ilvl = -1; boolean resolving; }
    private static final class Lvl { String fmt = "decimal", text = "%1."; int start = 1; }

    private final static class State {
        Zip z;
        Map<String, String> rels;
        final Map<String, Style> styles = new HashMap<>();
        final Map<Integer, Integer> numToAbs = new HashMap<>();
        final Map<Integer, Lvl[]> abs = new HashMap<>();
        final Map<Integer, int[]> counters = new HashMap<>();
        int baseHalfPts = 22, images;
        BooleanSupplier cancelled;
    }

    public static void parse(Zip z, Sink sink, BooleanSupplier cancelled) throws IOException {
        State st = new State();
        st.z = z; st.cancelled = cancelled;
        String main = "word/document.xml";
        if (!z.has(main)) {
            for (String v : Px.rels(z, "").values()) if (v.endsWith(".xml") && !v.startsWith("docProps")) { main = v; break; }
            if (!z.has(main)) throw new IOException("no document part");
        }
        st.rels = Px.rels(z, main);
        int slash = main.lastIndexOf('/');
        String dir = slash < 0 ? "" : main.substring(0, slash + 1);
        try {
            readStyles(st, dir + "styles.xml");
            readNumbering(st, dir + "numbering.xml");
            sink.begin(st.baseHalfPts);
            try (InputStream in = z.open(main, Zip.MAX_XML)) {
                XmlPullParser p = Px.open(in);
                boolean lastEmpty = false;
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                    if (e != XmlPullParser.START_TAG) continue;
                    if ("body".equals(Px.ln(p.getName()))) { body(st, p, sink, new boolean[]{lastEmpty}); break; }
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
    }

    /** Children of body / sdtContent / customXml: paragraphs and tables. */
    private static void body(State st, XmlPullParser p, Sink sink, boolean[] lastEmpty) throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            if (st.cancelled != null && st.cancelled.getAsBoolean()) throw new IOException("cancelled");
            String n = Px.ln(p.getName());
            switch (n) {
                case "p": {
                    ArrayList<Image> imgs = new ArrayList<>();
                    Para para = para(st, p, imgs);
                    boolean empty = para.empty();
                    if (!(empty && (lastEmpty[0] || !imgs.isEmpty()))) sink.block(para);
                    lastEmpty[0] = empty && imgs.isEmpty();
                    for (Image im : imgs) sink.block(im);
                    break;
                }
                case "tbl": sink.block(table(st, p)); lastEmpty[0] = false; break;
                case "sdt": case "sdtContent": case "customXml": body(st, p, sink, lastEmpty); break;
                default: Px.skip(p);
            }
        }
    }

    private static Para para(State st, XmlPullParser p, List<Image> imgs) throws XmlPullParserException, IOException {
        Para para = new Para();
        int[] num = {-1, -1};
        String[] style = {null};
        inline(st, p, para, imgs, num, style);
        Style s = style[0] == null ? null : st.styles.get(style[0]);
        if (s != null) {
            para.heading = heading(st, style[0]);
            if (num[0] < 0) { int[] sn = styleNum(st, style[0]); num[0] = sn[0]; if (num[1] < 0) num[1] = sn[1]; }
        }
        if (num[0] > 0) label(st, para, num[0], Math.max(0, num[1]));
        return para;
    }

    /** Paragraph content: properties, runs and run containers (hyperlink, ins, smartTag, sdt …). */
    private static void inline(State st, XmlPullParser p, Para para, List<Image> imgs, int[] num, String[] style)
            throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "pPr": pPr(p, para, num, style); break;
                case "r": run(st, p, para, imgs); break;
                case "hyperlink": case "ins": case "smartTag": case "sdt": case "sdtContent": case "fldSimple": case "customXml":
                case "moveTo":
                    inline(st, p, para, imgs, num, style); break;
                default: Px.skip(p);
            }
        }
    }

    private static void pPr(XmlPullParser p, Para para, int[] num, String[] style) throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "pStyle": style[0] = Px.attr(p, "val"); break;
                case "numId": num[0] = Px.attrInt(p, "val", -1); break;
                case "ilvl": num[1] = Px.attrInt(p, "val", 0); break;
                case "jc": {
                    String v = Px.attr(p, "val");
                    para.align = "center".equals(v) ? 1 : ("right".equals(v) || "end".equals(v)) ? 2 : "both".equals(v) ? 3 : 0;
                    break;
                }
                case "numPr": break;   // descend into numId / ilvl
                default: Px.skip(p);
            }
        }
    }

    private static void run(State st, XmlPullParser p, Para para, List<Image> imgs) throws XmlPullParserException, IOException {
        Run fmt = new Run();
        StringBuilder sb = new StringBuilder();
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "rPr": rPr(p, fmt); break;
                case "t": sb.append(p.nextText()); break;
                case "tab": sb.append('\t'); break;
                case "br": case "cr": sb.append('\n'); break;
                case "noBreakHyphen": sb.append('-'); break;
                case "drawing": case "pict": case "object": case "AlternateContent": picture(st, p, imgs); break;
                default: Px.skip(p);
            }
        }
        if (sb.length() == 0) return;
        fmt.text = sb.toString();
        Run last = para.runs.isEmpty() ? null : para.runs.get(para.runs.size() - 1);
        if (last != null && last.same(fmt)) last.text += fmt.text;
        else para.runs.add(fmt);
    }

    private static void rPr(XmlPullParser p, Run r) throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "b": r.b = Px.on(p); break;
                case "i": r.i = Px.on(p); break;
                case "u": r.u = Px.on(p); break;
                case "strike": case "dstrike": r.s = Px.on(p); break;
                case "sz": r.halfPts = Px.attrInt(p, "val", 0); break;
                case "color": r.color = color(Px.attr(p, "val")); break;
                default: break;
            }
            Px.skip(p);
        }
    }

    static int color(String hex) {
        if (hex == null || hex.length() != 6) return 0;
        try { int v = Integer.parseInt(hex, 16); return v == 0 ? 0 : 0xFF000000 | v; } catch (NumberFormatException e) { return 0; }
    }

    /** Finds the picture (blip embed or VML imagedata) and its extent inside a drawing; mc:Fallback is ignored. */
    private static void picture(State st, XmlPullParser p, List<Image> imgs) throws XmlPullParserException, IOException {
        String id = null; long cx = 0, cy = 0;
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            if (n.equals("Fallback")) { Px.skip(p); continue; }
            if (n.equals("extent") && cx == 0) { cx = Px.attrLong(p, "cx", 0); cy = Px.attrLong(p, "cy", 0); }
            else if (n.equals("blip") && id == null) id = Px.attr(p, "embed");
            else if (n.equals("imagedata") && id == null) id = Px.attr(p, "id");
        }
        String part = id == null ? null : st.rels.get(id);
        if (part != null && st.images < MAX_IMAGES) { st.images++; imgs.add(new Image(part, cx, cy)); }
    }

    private static Table table(State st, XmlPullParser p) throws XmlPullParserException, IOException {
        Table t = new Table();
        int depth = p.getDepth();
        ArrayList<ArrayList<Para>> row = null;
        ArrayList<Para> cell = null;
        ArrayList<Image> ignore = new ArrayList<>();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "tr":
                    if (t.rows.size() >= MAX_TABLE_ROWS) { Px.skip(p); break; }
                    row = new ArrayList<>(); t.rows.add(row); cell = null; break;
                case "tc": if (row == null) { Px.skip(p); break; } cell = new ArrayList<>(); row.add(cell); break;
                case "p":
                    if (cell == null) { Px.skip(p); break; }
                    ignore.clear();
                    cell.add(para(st, p, ignore));
                    break;
                case "tbl": {   // nested table: its paragraphs are flattened into the current cell
                    if (cell == null) { Px.skip(p); break; }
                    for (ArrayList<ArrayList<Para>> r : table(st, p).rows) for (ArrayList<Para> c : r) cell.addAll(c);
                    break;
                }
                case "sdt": case "sdtContent": case "customXml": break;
                default: Px.skip(p);
            }
        }
        return t;
    }

    // ---- styles and numbering ----------------------------------------------------------------

    private static void readStyles(State st, String part) throws IOException, XmlPullParserException {
        try (InputStream in = st.z.open(part, Zip.MAX_XML)) {
            if (in == null) return;
            XmlPullParser p = Px.open(in);
            Style cur = null; String curId = null, curName = null;
            boolean defaults = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.END_TAG) {
                    String n = Px.ln(p.getName());
                    if (n.equals("style") && cur != null) {
                        if (cur.heading == -2) cur.heading = headingFromName(curName, curId);
                        st.styles.put(curId, cur); cur = null;
                    } else if (n.equals("docDefaults")) defaults = false;
                    continue;
                }
                if (e != XmlPullParser.START_TAG) continue;
                switch (Px.ln(p.getName())) {
                    case "docDefaults": defaults = true; break;
                    case "style":
                        curId = Px.attr(p, "styleId"); curName = null;
                        cur = curId != null && "paragraph".equals(Px.attr(p, "type")) ? new Style() : null;
                        break;
                    case "name": curName = Px.attr(p, "val"); break;
                    case "basedOn": if (cur != null) cur.basedOn = Px.attr(p, "val"); break;
                    case "outlineLvl": if (cur != null) { int l = Px.attrInt(p, "val", 9); if (l < 9) cur.heading = l + 1; } break;
                    case "numId": if (cur != null) cur.numId = Px.attrInt(p, "val", -1); break;
                    case "ilvl": if (cur != null) cur.ilvl = Px.attrInt(p, "val", -1); break;
                    case "sz": if (defaults) { int v = Px.attrInt(p, "val", 0); if (v > 0) st.baseHalfPts = v; } break;
                    default: break;
                }
            }
        }
    }

    private static int headingFromName(String name, String id) {
        String s = (name != null ? name : id == null ? "" : id).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (s.equals("title") || s.equals("konubaşlığı") || s.equals("başlık")) return 0;
        for (String pre : new String[]{"heading", "başlık"}) {
            if (s.startsWith(pre) && s.length() == pre.length() + 1) {
                char d = s.charAt(pre.length());
                if (d >= '1' && d <= '9') return d - '0';
            }
        }
        return -2;
    }

    private static int heading(State st, String id) {
        for (int guard = 0; id != null && guard < 20; guard++) {
            Style s = st.styles.get(id);
            if (s == null) return -1;
            if (s.heading != -2) return Math.min(s.heading, 6);
            id = s.basedOn;
        }
        return -1;
    }

    private static int[] styleNum(State st, String id) {
        for (int guard = 0; id != null && guard < 20; guard++) {
            Style s = st.styles.get(id);
            if (s == null) break;
            if (s.numId >= 0) return new int[]{s.numId, s.ilvl};
            id = s.basedOn;
        }
        return new int[]{-1, -1};
    }

    private static void readNumbering(State st, String part) throws IOException, XmlPullParserException {
        try (InputStream in = st.z.open(part, Zip.MAX_XML)) {
            if (in == null) return;
            XmlPullParser p = Px.open(in);
            Lvl[] curAbs = null; Lvl lvl = null; int curNum = -1;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.END_TAG) {
                    String n = Px.ln(p.getName());
                    if (n.equals("abstractNum")) curAbs = null;
                    else if (n.equals("lvl")) lvl = null;
                    else if (n.equals("num")) curNum = -1;
                    continue;
                }
                if (e != XmlPullParser.START_TAG) continue;
                switch (Px.ln(p.getName())) {
                    case "abstractNum": curAbs = new Lvl[9]; st.abs.put(Px.attrInt(p, "abstractNumId", -1), curAbs); break;
                    case "lvl": {
                        int i = Px.attrInt(p, "ilvl", -1);
                        if (curAbs != null && i >= 0 && i < 9) { lvl = new Lvl(); curAbs[i] = lvl; }
                        break;
                    }
                    case "numFmt": if (lvl != null) lvl.fmt = Px.attr(p, "val"); break;
                    case "lvlText": if (lvl != null) lvl.text = Px.attr(p, "val"); break;
                    case "start": if (lvl != null) lvl.start = Px.attrInt(p, "val", 1); break;
                    case "num": curNum = Px.attrInt(p, "numId", -1); break;
                    case "abstractNumId": if (curNum >= 0) st.numToAbs.put(curNum, Px.attrInt(p, "val", -1)); break;
                    default: break;
                }
            }
        }
    }

    private static final String[] BULLETS = {"•", "◦", "▪"};

    private static void label(State st, Para para, int numId, int ilvl) {
        Integer a = st.numToAbs.get(numId);
        Lvl[] lv = a == null ? null : st.abs.get(a);
        if (lv == null || ilvl > 8) return;
        Lvl l = lv[ilvl] != null ? lv[ilvl] : new Lvl();
        para.level = ilvl;
        if ("none".equals(l.fmt)) return;
        if ("bullet".equals(l.fmt)) { para.label = BULLETS[ilvl % 3]; return; }
        int[] cnt = st.counters.computeIfAbsent(numId, k -> new int[9]);
        cnt[ilvl] = cnt[ilvl] == 0 ? l.start : cnt[ilvl] + 1;
        for (int i = ilvl + 1; i < 9; i++) cnt[i] = 0;
        String t = l.text == null ? "%" + (ilvl + 1) + "." : l.text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '%' && i + 1 < t.length() && t.charAt(i + 1) >= '1' && t.charAt(i + 1) <= '9') {
                int k = t.charAt(++i) - '1';
                Lvl kl = lv[k] != null ? lv[k] : l;
                int v = cnt[k] == 0 ? kl.start : cnt[k];
                sb.append(format(v, k == ilvl ? l.fmt : kl.fmt));
            } else sb.append(ch);
        }
        para.label = sb.toString();
    }

    static String format(int v, String fmt) {
        if (fmt == null) fmt = "decimal";
        switch (fmt) {
            case "lowerLetter": return letters(v, 'a');
            case "upperLetter": return letters(v, 'A');
            case "lowerRoman": return roman(v).toLowerCase(java.util.Locale.ROOT);
            case "upperRoman": return roman(v);
            case "decimalZero": return v < 10 ? "0" + v : String.valueOf(v);
            default: return String.valueOf(v);
        }
    }

    private static String letters(int v, char base) {
        if (v <= 0) return String.valueOf(v);
        char ch = (char) (base + (v - 1) % 26);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= (v - 1) / 26; i++) sb.append(ch);
        return sb.toString();
    }

    private static String roman(int v) {
        if (v <= 0 || v >= 4000) return String.valueOf(v);
        int[] n = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) while (v >= n[i]) { v -= n[i]; sb.append(s[i]); }
        return sb.toString();
    }
}
