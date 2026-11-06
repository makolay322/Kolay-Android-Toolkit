package app.kolay.app.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * PowerPoint (.pptx) reader: slide size and order up front, each slide parsed on demand into positioned text boxes,
 * pictures and solid fills (theme colours ignored). Placeholders without their own position take it from the layout or
 * master. Pure Java.
 */
public final class Pptx {
    public static final int MAX_SLIDES = 2000;

    public long cx = 9144000, cy = 5143500;   // EMU; default 16:9
    public final List<String> slides = new ArrayList<>();
    private final Zip z;
    private final Map<String, Map<String, long[]>> phCache = new HashMap<>();

    public static final class TPara {
        public final ArrayList<Docx.Run> runs = new ArrayList<>();
        public int align;          // 0 start, 1 center, 2 end
        public String bullet;      // null = none
        public int level;
    }

    public static final class Shape {
        public long x, y, w, h;
        public int fill;           // 0 = none
        public String image;       // picture part
        public int anchor;         // 0 top, 1 middle, 2 bottom
        public float fontScale = 1f;
        public final ArrayList<TPara> paras = new ArrayList<>();
    }

    public static final class Slide {
        public int bg = 0xFFFFFFFF;
        public final ArrayList<Shape> shapes = new ArrayList<>();
    }

    private Pptx(Zip z) { this.z = z; }

    public static Pptx open(Zip z) throws IOException {
        Pptx x = new Pptx(z);
        String pres = "ppt/presentation.xml";
        try {
            Map<String, String> rels = Px.rels(z, pres);
            try (InputStream in = z.open(pres, Zip.MAX_XML)) {
                if (in == null) throw new IOException("no presentation");
                XmlPullParser p = Px.open(in);
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                    if (e != XmlPullParser.START_TAG) continue;
                    String n = Px.ln(p.getName());
                    if (n.equals("sldSz")) {
                        long w = Px.attrLong(p, "cx", 0), h = Px.attrLong(p, "cy", 0);
                        if (w > 0 && h > 0) { x.cx = w; x.cy = h; }
                    } else if (n.equals("sldId") && x.slides.size() < MAX_SLIDES) {
                        String part = rels.get(Px.rid(p));
                        if (part != null) x.slides.add(part);
                    }
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
        return x;
    }

    /** Affine map child → slide coordinates: X = ax*x + bx, Y = ay*y + by. */
    private static final class Tf {
        final double ax, bx, ay, by;
        Tf(double ax, double bx, double ay, double by) { this.ax = ax; this.bx = bx; this.ay = ay; this.by = by; }
    }

    private static final class Ph { String type, idx; }

    public Slide slide(int i) throws IOException {
        String part = slides.get(i);
        Slide s = new Slide();
        Map<String, String> rels = Px.rels(z, part);
        String layout = null;
        for (String v : rels.values()) if (v.contains("slideLayouts/")) layout = v;
        Map<String, long[]> ph = new HashMap<>();
        if (layout != null) {
            String master = null;
            for (String v : Px.rels(z, layout).values()) if (v.contains("slideMasters/")) master = v;
            if (master != null) ph.putAll(placeholders(master));
            ph.putAll(placeholders(layout));
        }
        try (InputStream in = z.open(part, Zip.MAX_XML)) {
            if (in == null) return s;
            XmlPullParser p = Px.open(in);
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != XmlPullParser.START_TAG) continue;
                String n = Px.ln(p.getName());
                if (n.equals("bg")) { int c = firstColor(p); if (c != 0) s.bg = c; }
                else if (n.equals("spTree")) { tree(p, s, rels, ph, new Tf(1, 0, 1, 0)); break; }
            }
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
        return s;
    }

    private void tree(XmlPullParser p, Slide s, Map<String, String> rels, Map<String, long[]> ph, Tf tf)
            throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "sp": case "pic": case "graphicFrame": shape(p, s, rels, ph, tf); break;
                case "grpSp": group(p, s, rels, ph, tf); break;
                case "AlternateContent": case "Choice": break;   // descend
                case "Fallback": default: Px.skip(p);
            }
        }
    }

    private void group(XmlPullParser p, Slide s, Map<String, String> rels, Map<String, long[]> ph, Tf tf)
            throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        Tf inner = tf;
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return;
            if (e != XmlPullParser.START_TAG) continue;
            switch (Px.ln(p.getName())) {
                case "grpSpPr": {
                    long[] x = xfrmIn(p);
                    if (x != null && x[6] > 0 && x[7] > 0) {
                        double sx = (double) x[2] / x[6], sy = (double) x[3] / x[7];
                        double gx = x[0] - x[4] * sx, gy = x[1] - x[5] * sy;
                        inner = new Tf(tf.ax * sx, tf.ax * gx + tf.bx, tf.ay * sy, tf.ay * gy + tf.by);
                    }
                    break;
                }
                case "sp": case "pic": case "graphicFrame": shape(p, s, rels, ph, inner); break;
                case "grpSp": group(p, s, rels, ph, inner); break;
                case "AlternateContent": case "Choice": break;
                default: Px.skip(p);
            }
        }
    }

    /** Reads an xfrm-bearing properties element: {offX, offY, extX, extY, chOffX, chOffY, chExtX, chExtY} or null. */
    private static long[] xfrmIn(XmlPullParser p) throws XmlPullParserException, IOException {
        long[] r = null;
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) return r;
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            if (r == null && n.equals("xfrm")) r = new long[8];
            if (r == null) continue;
            switch (n) {
                case "off": r[0] = Px.attrLong(p, "x", 0); r[1] = Px.attrLong(p, "y", 0); break;
                case "ext": r[2] = Px.attrLong(p, "cx", 0); r[3] = Px.attrLong(p, "cy", 0); break;
                case "chOff": r[4] = Px.attrLong(p, "x", 0); r[5] = Px.attrLong(p, "y", 0); break;
                case "chExt": r[6] = Px.attrLong(p, "cx", 0); r[7] = Px.attrLong(p, "cy", 0); break;
                default: break;
            }
        }
        return r;
    }

    private void shape(XmlPullParser p, Slide s, Map<String, String> rels, Map<String, long[]> phPos, Tf tf)
            throws XmlPullParserException, IOException {
        Shape sh = new Shape();
        long[] xf = null;
        Ph ph = null;
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            switch (n) {
                case "ph": ph = new Ph(); ph.type = Px.attr(p, "type"); ph.idx = Px.attr(p, "idx"); break;
                case "spPr": {
                    int d2 = p.getDepth();
                    for (int e2 = p.next(); e2 != XmlPullParser.END_DOCUMENT; e2 = p.next()) {
                        if (e2 == XmlPullParser.END_TAG && p.getDepth() == d2) break;
                        if (e2 != XmlPullParser.START_TAG) continue;
                        String m = Px.ln(p.getName());
                        if (m.equals("xfrm")) xf = xfrmBody(p);
                        else if (m.equals("solidFill") && p.getDepth() == d2 + 1) sh.fill = firstColor(p);
                        else Px.skip(p);
                    }
                    break;
                }
                case "xfrm": xf = xfrmBody(p); break;   // graphicFrame keeps it outside spPr
                case "blip": { String id = Px.attr(p, "embed"); if (id != null && rels.get(id) != null) sh.image = rels.get(id); break; }
                case "txBody": text(p, sh, ph); break;
                case "tbl": table(p, sh); break;
                case "style": case "extLst": Px.skip(p); break;
                default: break;   // descend: nvSpPr, nvPr, blipFill, graphic, graphicData …
            }
        }
        if (xf == null && ph != null) {
            xf = ph.idx != null ? phPos.get("idx:" + ph.idx) : null;
            if (xf == null) xf = phPos.get("type:" + phType(ph.type));
            if (xf == null) {
                boolean title = "title".equals(phType(ph.type));
                xf = title ? new long[]{cx / 20, cy / 20, cx * 9 / 10, cy / 6} : new long[]{cx / 20, cy / 4, cx * 9 / 10, cy * 2 / 3};
            }
        }
        if (xf == null) return;
        if (sh.image == null && sh.fill == 0 && sh.paras.isEmpty()) return;
        sh.x = Math.round(tf.ax * xf[0] + tf.bx); sh.y = Math.round(tf.ay * xf[1] + tf.by);
        sh.w = Math.round(tf.ax * xf[2]); sh.h = Math.round(tf.ay * xf[3]);
        s.shapes.add(sh);
    }

    private static String phType(String t) {
        if (t == null || t.equals("obj") || t.equals("subTitle")) return "body";
        if (t.equals("ctrTitle")) return "title";
        return t;
    }

    /** Parser on an xfrm start tag: {x, y, cx, cy}. */
    private static long[] xfrmBody(XmlPullParser p) throws XmlPullParserException, IOException {
        long[] r = new long[4];
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            if (n.equals("off")) { r[0] = Px.attrLong(p, "x", 0); r[1] = Px.attrLong(p, "y", 0); }
            else if (n.equals("ext")) { r[2] = Px.attrLong(p, "cx", 0); r[3] = Px.attrLong(p, "cy", 0); }
        }
        return r;
    }

    /** First srgbClr inside the current element (consumes it). 0 when only theme colours are used. */
    private static int firstColor(XmlPullParser p) throws XmlPullParserException, IOException {
        int c = 0, depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e == XmlPullParser.START_TAG && c == 0 && "srgbClr".equals(Px.ln(p.getName()))) c = Docx.color(Px.attr(p, "val"));
        }
        return c;
    }

    private void text(XmlPullParser p, Shape sh, Ph ph) throws XmlPullParserException, IOException {
        String type = ph == null ? null : phType(ph.type);
        boolean bodyPh = "body".equals(type);
        int defHalf = "title".equals(type) ? ("ctrTitle".equals(ph.type) ? 88 : 80) : ph != null && "subTitle".equals(ph.type) ? 48 : bodyPh ? 52 : 36;
        if ("title".equals(type)) sh.anchor = 1;
        int[] autoNum = new int[9];
        int depth = p.getDepth();
        TPara para = null;
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e == XmlPullParser.END_TAG && para != null && "p".equals(Px.ln(p.getName()))) {
                if (para.bullet == null && bodyPh && ph != null && !"subTitle".equals(ph.type) && !para.runs.isEmpty()) para.bullet = "•";
                if ("#".equals(para.bullet)) para.bullet = (++autoNum[Math.min(para.level, 8)]) + ".";
                sh.paras.add(para); para = null; continue;
            }
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            switch (n) {
                case "bodyPr": {
                    String a = Px.attr(p, "anchor");
                    if ("ctr".equals(a)) sh.anchor = 1; else if ("b".equals(a)) sh.anchor = 2; else if ("t".equals(a)) sh.anchor = 0;
                    break;
                }
                case "normAutofit": { int fs = Px.attrInt(p, "fontScale", 100000); if (fs > 10000 && fs < 100000) sh.fontScale = fs / 100000f; break; }
                case "p": para = new TPara(); break;
                case "pPr": {
                    if (para == null) break;
                    String a = Px.attr(p, "algn");
                    para.align = "ctr".equals(a) ? 1 : "r".equals(a) ? 2 : 0;
                    para.level = Math.max(0, Math.min(8, Px.attrInt(p, "lvl", 0)));
                    break;
                }
                case "buNone": if (para != null) para.bullet = ""; break;
                case "buChar": if (para != null) para.bullet = "•"; break;
                case "buAutoNum": if (para != null) para.bullet = "#"; break;
                case "r": case "fld": if (para != null) run(p, para, defHalf - para.level * 4); break;
                case "br": if (para != null) { Docx.Run r = new Docx.Run(); r.text = "\n"; para.runs.add(r); } Px.skip(p); break;
                case "endParaRPr": case "lstStyle": Px.skip(p); break;
                default: break;
            }
        }
        for (TPara tp : sh.paras) if ("".equals(tp.bullet)) tp.bullet = null;
    }

    private static void run(XmlPullParser p, TPara para, int defHalf) throws XmlPullParserException, IOException {
        Docx.Run r = new Docx.Run();
        r.halfPts = Math.max(12, defHalf);
        StringBuilder sb = new StringBuilder();
        int depth = p.getDepth();
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            if (n.equals("rPr")) {
                int sz = Px.attrInt(p, "sz", 0);
                if (sz > 0) r.halfPts = sz / 50;
                String b = Px.attr(p, "b"), i = Px.attr(p, "i"), u = Px.attr(p, "u"), st = Px.attr(p, "strike");
                r.b = "1".equals(b) || "true".equals(b);
                r.i = "1".equals(i) || "true".equals(i);
                r.u = u != null && !"none".equals(u);
                r.s = st != null && !"noStrike".equals(st);
                int d2 = p.getDepth();
                for (int e2 = p.next(); e2 != XmlPullParser.END_DOCUMENT; e2 = p.next()) {
                    if (e2 == XmlPullParser.END_TAG && p.getDepth() == d2) break;
                    if (e2 != XmlPullParser.START_TAG) continue;
                    if ("solidFill".equals(Px.ln(p.getName())) && p.getDepth() == d2 + 1) r.color = firstColor(p);
                    else Px.skip(p);
                }
            } else if (n.equals("t")) sb.append(p.nextText());
            else Px.skip(p);
        }
        if (sb.length() == 0) return;
        r.text = sb.toString();
        para.runs.add(r);
    }

    /** a:tbl in a graphic frame: one paragraph per row, cells joined with " | ". */
    private static void table(XmlPullParser p, Shape sh) throws XmlPullParserException, IOException {
        int depth = p.getDepth();
        TPara row = null;
        StringBuilder cell = null;
        for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            if (e == XmlPullParser.END_TAG) {
                String n = Px.ln(p.getName());
                if (n.equals("tc") && row != null && cell != null) {
                    Docx.Run r = new Docx.Run(); r.halfPts = 28;
                    r.text = (row.runs.isEmpty() ? "" : "  |  ") + cell.toString().trim();
                    row.runs.add(r); cell = null;
                } else if (n.equals("tr") && row != null) { sh.paras.add(row); row = null; }
                continue;
            }
            if (e != XmlPullParser.START_TAG) continue;
            String n = Px.ln(p.getName());
            if (n.equals("tr")) row = new TPara();
            else if (n.equals("tc")) cell = new StringBuilder();
            else if (n.equals("t") && cell != null) cell.append(p.nextText()).append(' ');
        }
    }

    /** Placeholder positions of a layout or master ("idx:N" / "type:T" → {x, y, cx, cy}), cached. */
    private Map<String, long[]> placeholders(String part) throws IOException {
        synchronized (phCache) {
            Map<String, long[]> m = phCache.get(part);
            if (m != null) return m;
        }
        Map<String, long[]> m = new HashMap<>();
        try (InputStream in = z.open(part, Zip.MAX_XML)) {
            if (in != null) {
                XmlPullParser p = Px.open(in);
                Ph ph = null; long[] xf = null; int spDepth = -1;
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                    if (e == XmlPullParser.START_TAG) {
                        String n = Px.ln(p.getName());
                        if (n.equals("sp")) { spDepth = p.getDepth(); ph = null; xf = null; }
                        else if (n.equals("ph") && spDepth > 0) { ph = new Ph(); ph.type = Px.attr(p, "type"); ph.idx = Px.attr(p, "idx"); }
                        else if (n.equals("xfrm") && spDepth > 0 && xf == null) xf = xfrmBody(p);
                        else if (n.equals("txBody")) Px.skip(p);
                    } else if (e == XmlPullParser.END_TAG && p.getDepth() == spDepth && "sp".equals(Px.ln(p.getName()))) {
                        if (ph != null && xf != null && xf[2] > 0) {
                            if (ph.idx != null) m.put("idx:" + ph.idx, xf);
                            m.putIfAbsent("type:" + phType(ph.type), xf);
                        }
                        spDepth = -1;
                    }
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("bad xml", e); }
        synchronized (phCache) { phCache.put(part, m); }
        return m;
    }
}
