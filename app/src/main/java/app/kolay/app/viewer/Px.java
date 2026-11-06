package app.kolay.app.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/** Small XmlPullParser helpers shared by the OOXML parsers (namespace processing off; names compared by local part). */
public final class Px {
    private Px() {}

    /** Parser source; the app sets {@code android.util.Xml::newPullParser}, the desktop harness keeps the factory. */
    public static volatile Supplier<XmlPullParser> source = () -> {
        try { return XmlPullParserFactory.newInstance().newPullParser(); }
        catch (XmlPullParserException e) { throw new IllegalStateException(e); }
    };

    static XmlPullParser open(InputStream in) throws XmlPullParserException {
        XmlPullParser p = source.get();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);
        return p;
    }

    /** Local part of a qualified name ("w:p" → "p"). */
    static String ln(String n) { int i = n.indexOf(':'); return i < 0 ? n : n.substring(i + 1); }

    /** Attribute by local name, whatever its prefix. */
    static String attr(XmlPullParser p, String local) {
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            String a = p.getAttributeName(i);
            if (a.equals(local) || (a.endsWith(local) && a.length() > local.length() && a.charAt(a.length() - local.length() - 1) == ':'))
                return p.getAttributeValue(i);
        }
        return null;
    }

    /** Relationship reference ("r:id"); unlike {@link #attr} it ignores an unprefixed "id". */
    static String rid(XmlPullParser p) {
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            String a = p.getAttributeName(i);
            if (a.endsWith(":id")) return p.getAttributeValue(i);
        }
        return null;
    }

    static int attrInt(XmlPullParser p, String local, int def) {
        String v = attr(p, local);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static long attrLong(XmlPullParser p, String local, long def) {
        String v = attr(p, local);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** OOXML on/off property: present without val, or val not 0/false/off. */
    static boolean on(XmlPullParser p) {
        String v = attr(p, "val");
        return v == null || !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none"));
    }

    /** Skips the current element's subtree (parser on START_TAG). */
    static void skip(XmlPullParser p) throws XmlPullParserException, IOException {
        int d = 1;
        while (d > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) d++;
            else if (e == XmlPullParser.END_TAG) d--;
            else if (e == XmlPullParser.END_DOCUMENT) return;
        }
    }

    /** Relationship id → resolved part path for a part (internal targets only). */
    static Map<String, String> rels(Zip z, String part) throws IOException {
        Map<String, String> m = new HashMap<>();
        int slash = part.lastIndexOf('/');
        String rel = (slash < 0 ? "" : part.substring(0, slash + 1)) + "_rels/" + part.substring(slash + 1) + ".rels";
        try (InputStream in = z.open(rel, Zip.MAX_XML)) {
            if (in == null) return m;
            XmlPullParser p = open(in);
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != XmlPullParser.START_TAG || !"Relationship".equals(ln(p.getName()))) continue;
                if ("External".equalsIgnoreCase(attr(p, "TargetMode"))) continue;
                String id = attr(p, "Id"), t = attr(p, "Target");
                if (id != null && t != null) m.put(id, resolve(part, t));
            }
        } catch (XmlPullParserException e) { throw new IOException("bad rels", e); }
        return m;
    }

    /** Resolves a relationship target relative to the folder of {@code part}. */
    static String resolve(String part, String target) {
        String path;
        if (target.startsWith("/")) path = target.substring(1);
        else { int s = part.lastIndexOf('/'); path = (s < 0 ? "" : part.substring(0, s + 1)) + target; }
        java.util.ArrayDeque<String> st = new java.util.ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (!st.isEmpty()) st.removeLast(); }
            else st.addLast(seg);
        }
        return String.join("/", st);
    }
}
