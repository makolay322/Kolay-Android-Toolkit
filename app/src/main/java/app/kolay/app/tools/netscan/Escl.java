package app.kolay.app.tools.netscan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * eSCL (AirScan / Mopria) documents: ScannerCapabilities and ScannerStatus parsing, ScanSettings building. Elements are
 * matched by local name (namespace-aware parser, prefixes and namespace URIs vary between vendors). Pure Java.
 */
final class Escl {
    private Escl() {}

    static final String JPEG = "image/jpeg", PDF = "application/pdf";
    static final String RGB = "RGB24", GRAY = "Grayscale8";

    /** One input source (platen, feeder, feeder duplex) as offered by the scanner. */
    static final class Input {
        final Set<String> modes = new LinkedHashSet<>(), formats = new LinkedHashSet<>();
        /** The scanner lists formats as {@code scan:DocumentFormatExt} too (then the settings carry it as well). */
        boolean formatExt;
        final List<Integer> dpis = new ArrayList<>();
        final List<int[]> ranges = new ArrayList<>();   // {min, max, step}
        /** In 300ths of an inch; 0 = not given. */
        int maxW, maxH;

        boolean dpi(int d) {
            if (dpis.contains(d)) return true;
            for (int[] r : ranges) if (d >= r[0] && d <= r[1] && (r[2] <= 1 || (d - r[0]) % r[2] == 0)) return true;
            return false;
        }

        /** JPEG when offered (or when nothing is listed), else PDF; null when neither. */
        String format() {
            if (formats.isEmpty() || formats.contains(JPEG)) return JPEG;
            return formats.contains(PDF) ? PDF : null;
        }
    }

    static final class Caps {
        String version = "2.0", model = "";
        Input platen, adf, duplex;
        /** {@code AdfOption Duplex} without separate duplex caps: duplex uses the simplex ones. */
        boolean duplexOption;

        Input duplexInput() { return duplex != null ? duplex : duplexOption ? adf : null; }
    }

    static final class Status {
        String state = "", adf = "";
        boolean adfEmpty() { return adf.equals("ScannerAdfEmpty"); }
    }

    private static XmlPullParser parser(byte[] xml) throws IOException {
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser();
            p.setInput(new ByteArrayInputStream(xml), null);
            return p;
        } catch (XmlPullParserException e) { throw new IOException("XML okunamadı"); }
    }

    static Caps caps(byte[] xml) throws IOException {
        Caps c = new Caps();
        XmlPullParser p = parser(xml);
        Input cur = null;
        int dx = 0, dy = 0;
        int[] range = null;
        boolean any = false;
        try {
            for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (n.equals("PlatenInputCaps") || n.equals("AdfSimplexInputCaps") || n.equals("AdfDuplexInputCaps")) cur = null;
                    else if (n.equals("DiscreteResolution") && cur != null) { if (dx > 0 && dx == dy && !cur.dpis.contains(dx)) cur.dpis.add(dx); }
                    else if (n.equals("XResolutionRange") && cur != null && range != null) { if (range[0] > 0 && range[1] >= range[0]) cur.ranges.add(range); range = null; }
                    continue;
                }
                if (ev != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                int depth = p.getDepth();
                switch (n) {
                    case "ScannerCapabilities": any = true; break;
                    case "PlatenInputCaps": cur = c.platen = new Input(); break;
                    case "AdfSimplexInputCaps": cur = c.adf = new Input(); break;
                    case "AdfDuplexInputCaps": cur = c.duplex = new Input(); break;
                    case "Version": if (depth == 2) c.version = p.nextText().trim(); break;
                    case "MakeAndModel": if (depth == 2) c.model = p.nextText().trim(); break;
                    case "AdfOption": if (p.nextText().trim().equals("Duplex")) c.duplexOption = true; break;
                    case "DiscreteResolution": dx = dy = 0; break;
                    case "XResolutionRange": range = new int[]{0, 0, 1}; break;
                    default:
                        if (cur == null) break;
                        switch (n) {
                            case "ColorMode": cur.modes.add(p.nextText().trim()); break;
                            case "DocumentFormat": cur.formats.add(p.nextText().trim().toLowerCase(java.util.Locale.ROOT)); break;
                            case "DocumentFormatExt": cur.formats.add(p.nextText().trim().toLowerCase(java.util.Locale.ROOT)); cur.formatExt = true; break;
                            case "MaxWidth": cur.maxW = Math.max(cur.maxW, num(p.nextText())); break;
                            case "MaxHeight": cur.maxH = Math.max(cur.maxH, num(p.nextText())); break;
                            case "XResolution": dx = num(p.nextText()); break;
                            case "YResolution": dy = num(p.nextText()); break;
                            case "Min": if (range != null) range[0] = num(p.nextText()); break;
                            case "Max": if (range != null) range[1] = num(p.nextText()); break;
                            case "Step": if (range != null) range[2] = num(p.nextText()); break;
                            default: break;
                        }
                }
            }
        } catch (XmlPullParserException e) { throw new IOException("Tarayıcı yetenekleri okunamadı"); }
        if (!any) throw new IOException("Tarayıcı yetenekleri okunamadı");
        if (!c.version.matches("\\d{1,2}\\.\\d{1,3}")) c.version = "2.0";
        return c;
    }

    static Status status(byte[] xml) throws IOException {
        Status s = new Status();
        XmlPullParser p = parser(xml);
        try {
            for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG || p.getDepth() != 2) continue;
                if (p.getName().equals("State")) s.state = p.nextText().trim();
                else if (p.getName().equals("AdfState")) s.adf = p.nextText().trim();
            }
        } catch (XmlPullParserException e) { throw new IOException("Tarayıcı durumu okunamadı"); }
        return s;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * A ScanSettings request. {@code feeder}: ADF (with {@code duplex}) instead of the platen; {@code w}/{@code h}: region
     * in 300ths of an inch, clamped to the input's maximum.
     */
    static String settings(Caps caps, Input in, boolean feeder, boolean duplex, String mode, int dpi, int w, int h) {
        if (in.maxW > 0) w = Math.min(w, in.maxW);
        if (in.maxH > 0) h = Math.min(h, in.maxH);
        String fmt = in.format();
        StringBuilder b = new StringBuilder(800);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<scan:ScanSettings xmlns:scan=\"http://schemas.hp.com/imaging/escl/2011/05/03\" xmlns:pwg=\"http://www.pwg.org/schemas/2010/12/sm\">\n")
            .append("  <pwg:Version>").append(caps.version).append("</pwg:Version>\n")
            .append("  <pwg:ScanRegions>\n    <pwg:ScanRegion>\n")
            .append("      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>\n")
            .append("      <pwg:XOffset>0</pwg:XOffset>\n      <pwg:YOffset>0</pwg:YOffset>\n")
            .append("      <pwg:Width>").append(w).append("</pwg:Width>\n")
            .append("      <pwg:Height>").append(h).append("</pwg:Height>\n")
            .append("    </pwg:ScanRegion>\n  </pwg:ScanRegions>\n")
            .append("  <pwg:InputSource>").append(feeder ? "Feeder" : "Platen").append("</pwg:InputSource>\n");
        if (feeder) b.append("  <scan:Duplex>").append(duplex).append("</scan:Duplex>\n");
        b.append("  <scan:ColorMode>").append(mode).append("</scan:ColorMode>\n")
            .append("  <scan:XResolution>").append(dpi).append("</scan:XResolution>\n")
            .append("  <scan:YResolution>").append(dpi).append("</scan:YResolution>\n")
            .append("  <pwg:DocumentFormat>").append(fmt).append("</pwg:DocumentFormat>\n");
        if (in.formatExt) b.append("  <scan:DocumentFormatExt>").append(fmt).append("</scan:DocumentFormatExt>\n");
        b.append("</scan:ScanSettings>\n");
        return b.toString();
    }
}
