package app.kolay.app.doc;

import android.graphics.Rect;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal streaming PDF 1.4 writer: one JPEG per page (embedded as-is, DCTDecode, so files stay small) and, optionally, the
 * OCR words as invisible text (render mode 3) on top so the PDF is searchable and its text can be selected.
 *
 * android.graphics.pdf.PdfDocument is not used: it re-encodes bitmaps losslessly (very large files) and drops fully
 * transparent text, so it can neither keep JPEG pages nor carry a hidden text layer.
 *
 * Invisible text uses non-embedded Helvetica with a per-document single-byte encoding (ASCII + up to 128 other characters,
 * e.g. ç ğ ı ö ş ü ä ß) and a ToUnicode map, so search and copy return the real characters. Nothing is buffered on disk:
 * everything goes straight into the given stream (the user's chosen file).
 */
public final class PdfWriter {
    private static final float LONG_SIDE_PT = 842f;   // A4 long side; the short side follows the page's aspect

    private final OutputStream out;
    private long pos;
    private final List<Long> offsets = new ArrayList<>();   // index = object number - 1
    private final List<Integer> pages = new ArrayList<>();
    private final Map<Integer, Integer> codes = new LinkedHashMap<>();   // code point → byte code (non-ASCII only)
    private int nextCode = 128;

    private static final int CATALOG = 1, PAGES = 2, FONT = 3, ENCODING = 4, TOUNICODE = 5, INFO = 6;

    public PdfWriter(OutputStream out) throws IOException {
        this.out = out;
        for (int i = 0; i < 6; i++) offsets.add(0L);
        raw("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    private void raw(byte[] b) throws IOException { out.write(b); pos += b.length; }
    private void str(String s) throws IOException { raw(s.getBytes(StandardCharsets.ISO_8859_1)); }

    private int alloc() { offsets.add(0L); return offsets.size(); }
    private void begin(int id) throws IOException { offsets.set(id - 1, pos); str(id + " 0 obj\n"); }

    private static String f(float v) { return String.format(Locale.ROOT, "%.2f", v); }

    /**
     * @param jpeg page image (baseline JPEG, RGB), {@code w} × {@code h} pixels
     * @param text OCR result for this page (boxes in {@code text.width × text.height} pixels of the same picture), or null
     */
    public void addPage(byte[] jpeg, int w, int h, OcrEngine.Page text) throws IOException {
        float k = LONG_SIDE_PT / Math.max(w, h);
        float pw = w * k, ph = h * k;
        int page = alloc(), img = alloc(), content = alloc();
        pages.add(page);

        begin(img);
        str("<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
            + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
        raw(jpeg);
        str("\nendstream\nendobj\n");

        StringBuilder cs = new StringBuilder();
        cs.append("q ").append(f(pw)).append(" 0 0 ").append(f(ph)).append(" 0 0 cm /Im0 Do Q\n");
        if (text != null && !text.words.isEmpty() && text.width > 0 && text.height > 0) {
            float sx = pw / text.width, sy = ph / text.height;
            cs.append("BT 3 Tr /F1 1 Tf\n");
            for (OcrEngine.Word wd : text.words) {
                String hex = encode(wd.text + " ");
                if (hex.isEmpty()) continue;
                Rect r = wd.box;
                int n = hex.length() / 2 - 1;   // glyphs without the trailing space
                if (n <= 0) continue;
                float bw = r.width() * sx, bh = r.height() * sy;
                float x = r.left * sx, y = ph - r.bottom * sy + bh * 0.15f;
                float hs = bw / (n * 0.5f);          // every glyph is 500/1000 em wide (see /Widths)
                cs.append(f(hs)).append(" 0 0 ").append(f(bh)).append(' ').append(f(x)).append(' ').append(f(y))
                  .append(" Tm <").append(hex).append("> Tj\n");
            }
            cs.append("ET\n");
        }
        byte[] c = cs.toString().getBytes(StandardCharsets.ISO_8859_1);
        begin(content);
        str("<< /Length " + c.length + " >>\nstream\n");
        raw(c);
        str("\nendstream\nendobj\n");

        begin(page);
        str("<< /Type /Page /Parent " + PAGES + " 0 R /MediaBox [0 0 " + f(pw) + " " + f(ph) + "] /Resources << /XObject << /Im0 "
            + img + " 0 R >>" + (text != null ? " /Font << /F1 " + FONT + " 0 R >>" : "") + " >> /Contents " + content + " 0 R >>\nendobj\n");
    }

    /** Hex string of single-byte codes; characters without a free code become '?'. */
    private String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int code;
            if (cp >= 32 && cp <= 126) code = cp;
            else if (Character.isWhitespace(cp)) code = 32;
            else {
                Integer known = codes.get(cp);
                if (known != null) code = known;
                else if (nextCode <= 255 && cp <= 0xFFFF) { code = nextCode++; codes.put(cp, code); }
                else code = '?';
            }
            b.append(String.format(Locale.ROOT, "%02X", code));
        }
        return b.toString();
    }

    /** Writes the shared objects, cross-reference table and trailer. The stream is left open. */
    public void finish() throws IOException {
        if (pages.isEmpty()) throw new IOException("sayfa yok");
        begin(CATALOG);
        str("<< /Type /Catalog /Pages " + PAGES + " 0 R >>\nendobj\n");
        StringBuilder kids = new StringBuilder();
        for (int p : pages) kids.append(p).append(" 0 R ");
        begin(PAGES);
        str("<< /Type /Pages /Count " + pages.size() + " /Kids [" + kids + "] >>\nendobj\n");

        StringBuilder widths = new StringBuilder();
        for (int i = 0; i < 256; i++) widths.append(i % 32 == 31 ? "500\n" : "500 ");
        begin(FONT);
        str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 0 /LastChar 255 /Widths [" + widths
            + "] /Encoding " + ENCODING + " 0 R /ToUnicode " + TOUNICODE + " 0 R >>\nendobj\n");

        StringBuilder diff = new StringBuilder();
        if (!codes.isEmpty()) {
            diff.append(" /Differences [");
            int expect = -1;
            for (Map.Entry<Integer, Integer> e : codes.entrySet()) {
                if (e.getValue() != expect) diff.append(' ').append(e.getValue());
                diff.append(String.format(Locale.ROOT, " /uni%04X", e.getKey()));
                expect = e.getValue() + 1;
            }
            diff.append(" ]");
        }
        begin(ENCODING);
        str("<< /Type /Encoding /BaseEncoding /WinAnsiEncoding" + diff + " >>\nendobj\n");

        List<String> map = new ArrayList<>();
        for (int c = 32; c <= 126; c++) map.add(String.format(Locale.ROOT, "<%02X> <%04X>", c, c));
        for (Map.Entry<Integer, Integer> e : codes.entrySet()) map.add(String.format(Locale.ROOT, "<%02X> <%04X>", e.getValue(), e.getKey()));
        StringBuilder cm = new StringBuilder();
        cm.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
          .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
          .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        for (int i = 0; i < map.size(); i += 100) {
            List<String> part = map.subList(i, Math.min(map.size(), i + 100));
            cm.append(part.size()).append(" beginbfchar\n");
            for (String s : part) cm.append(s).append('\n');
            cm.append("endbfchar\n");
        }
        cm.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        byte[] cmb = cm.toString().getBytes(StandardCharsets.ISO_8859_1);
        begin(TOUNICODE);
        str("<< /Length " + cmb.length + " >>\nstream\n");
        raw(cmb);
        str("\nendstream\nendobj\n");

        begin(INFO);
        str("<< /Producer (Kolay) >>\nendobj\n");

        long xref = pos;
        StringBuilder x = new StringBuilder();
        x.append("xref\n0 ").append(offsets.size() + 1).append("\n0000000000 65535 f \n");
        for (long o : offsets) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", o));
        x.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root ").append(CATALOG).append(" 0 R /Info ").append(INFO)
         .append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        str(x.toString());
        out.flush();
    }
}
