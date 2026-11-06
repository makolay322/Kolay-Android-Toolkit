package app.kolay.office;

import android.content.Context;
import android.net.Uri;
import app.kolay.app.viewer.Docx;
import app.kolay.app.viewer.LegacyOffice;
import app.kolay.app.viewer.Sheet;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.HWPFList;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;

/**
 * Kolay's old-Office plugin (downloaded at run time, see docs/ARCHITECTURE.md): reads .doc / .xls / .ppt with
 * Apache POI 4.1.2 and hands back the app's own models ({@link Docx.Block}, {@link Sheet}). Text only: no images, no
 * formula evaluation (cached values), no layout. Never logs anything.
 */
public final class PoiProvider implements LegacyOffice.Provider {
    static final int MAX_BLOCKS = 100_000;
    private static final Locale TR = new Locale("tr", "TR");

    public PoiProvider() {}

    @Override public LegacyOffice.Result extract(Context ctx, Uri uri, String ext, BooleanSupplier cancelled) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("dosya açılamadı");
        try (InputStream b = new BufferedInputStream(in, 65536)) { return read(b, ext, cancelled); }
    }

    /** Pure Java (no Android types), so it can be tested on a desktop JVM. */
    public static LegacyOffice.Result read(InputStream in, String ext, BooleanSupplier cancelled) throws IOException {
        try {
            switch (ext) {
                case "doc": return LegacyOffice.Result.document(doc(in, cancelled));
                case "xls": return LegacyOffice.Result.workbook(xls(in, cancelled));
                case "ppt": return LegacyOffice.Result.document(ppt(in, cancelled));
                default: return LegacyOffice.Result.unavailable("Bilinmeyen biçim: ." + ext);
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {   // POI reports broken/encrypted files with runtime exceptions
            String m = e.getClass().getSimpleName();
            if (m.contains("Encrypted") || m.contains("Password")) throw new IOException("Dosya parola korumalı");
            throw new IOException("Dosya okunamadı (" + m + ")", e);
        }
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedIOException("iptal");
    }

    // ---- .doc ------------------------------------------------------------------------------------

    private static final class Fields {   // Word fields: \u0013 code \u0014 result \u0015 — show only the result
        final ArrayList<Boolean> code = new ArrayList<>();
        boolean hidden() { for (Boolean b : code) if (b) return true; return false; }
    }

    private static List<Docx.Block> doc(InputStream in, BooleanSupplier cancelled) throws IOException {
        HWPFDocument d = new HWPFDocument(in);
        Range r = d.getRange();
        ArrayList<Docx.Block> out = new ArrayList<>();
        Fields f = new Fields();
        Map<String, Integer> counters = new HashMap<>();
        boolean lastEmpty = false;
        int n = r.numParagraphs();
        for (int i = 0; i < n && out.size() < MAX_BLOCKS; ) {
            if ((i & 63) == 0) check(cancelled);
            Paragraph p = r.getParagraph(i);
            if (p.isInTable()) {
                Table t = null;
                try { t = r.getTable(p); } catch (RuntimeException ignored) {}
                if (t != null && t.numParagraphs() > 0) {
                    out.add(table(t, f));
                    lastEmpty = false;
                    i += t.numParagraphs();
                    continue;
                }
            }
            Docx.Para para = para(d, p, f, counters);
            boolean empty = blank(para);
            if (!(empty && lastEmpty)) out.add(para);   // runs of blank lines collapse to one, as in the .docx reader
            lastEmpty = empty;
            i++;
        }
        return out;
    }

    private static Docx.Table table(Table t, Fields f) {
        Docx.Table out = new Docx.Table();
        for (int ri = 0; ri < t.numRows() && ri < Docx.MAX_TABLE_ROWS; ri++) {
            TableRow row = t.getRow(ri);
            ArrayList<ArrayList<Docx.Para>> cells = new ArrayList<>();
            for (int ci = 0; ci < row.numCells(); ci++) {
                TableCell cell = row.getCell(ci);
                ArrayList<Docx.Para> ps = new ArrayList<>();
                for (int k = 0; k < cell.numParagraphs(); k++) {
                    Docx.Para p = para(null, cell.getParagraph(k), f, null);
                    if (!blank(p)) ps.add(p);
                }
                cells.add(ps);
            }
            out.rows.add(cells);
        }
        return out;
    }

    /** @param d null inside tables (no headings/lists there) */
    private static Docx.Para para(HWPFDocument d, Paragraph p, Fields f, Map<String, Integer> counters) {
        Docx.Para out = new Docx.Para();
        for (int j = 0; j < p.numCharacterRuns(); j++) {
            CharacterRun cr = p.getCharacterRun(j);
            if (cr.isMarkedDeleted() || cr.isVanished()) continue;
            String raw = cr.text();
            StringBuilder sb = new StringBuilder(raw.length());
            for (int k = 0; k < raw.length(); k++) {
                char ch = raw.charAt(k);
                if (ch == 0x13) { f.code.add(Boolean.TRUE); continue; }
                if (ch == 0x14) { if (!f.code.isEmpty()) f.code.set(f.code.size() - 1, Boolean.FALSE); continue; }
                if (ch == 0x15) { if (!f.code.isEmpty()) f.code.remove(f.code.size() - 1); continue; }
                if (f.hidden()) continue;
                if (ch == 0x0b) sb.append('\n');
                else if (ch == '\t' || ch >= 0x20) sb.append(ch);
            }
            if (sb.length() == 0) continue;
            Docx.Run run = new Docx.Run();
            run.text = sb.toString();
            run.b = cr.isBold(); run.i = cr.isItalic(); run.u = cr.getUnderlineCode() != 0; run.s = cr.isStrikeThrough();
            run.halfPts = cr.getFontSize();
            out.runs.add(run);
        }
        int j = p.getJustification();
        out.align = j == 1 ? 1 : j == 2 ? 2 : j == 3 ? 3 : 0;
        if (d != null) {
            out.heading = heading(d, p);
            if (out.heading < 0 && p.isInList()) label(p, out, counters);
        }
        return out;
    }

    private static boolean blank(Docx.Para p) {
        if (p.label != null) return false;
        for (Docx.Run r : p.runs) if (!r.text.trim().isEmpty()) return false;
        return true;
    }

    private static int heading(HWPFDocument d, Paragraph p) {
        try {
            StyleDescription sd = d.getStyleSheet().getStyleDescription(p.getStyleIndex());
            String name = sd == null || sd.getName() == null ? "" : sd.getName().toLowerCase(Locale.ROOT).replace(" ", "");
            if (name.equals("title") || name.equals("konubaşlığı") || name.equals("titel")) return 0;
            for (String pre : new String[]{"heading", "başlık", "überschrift"})
                if (name.startsWith(pre)) {
                    try { int l = Integer.parseInt(name.substring(pre.length())); if (l >= 1 && l <= 9) return l; } catch (NumberFormatException ignored) {}
                }
            int lvl = p.getLvl();
            if (lvl >= 0 && lvl < 9) return lvl + 1;
        } catch (RuntimeException ignored) {}
        return -1;
    }

    private static void label(Paragraph p, Docx.Para out, Map<String, Integer> counters) {
        try {
            int lvl = Math.max(0, Math.min(8, p.getIlvl()));
            out.level = lvl;
            HWPFList list = p.getList();
            if (list.getNumberFormat((char) lvl) == 0x17) { out.label = BULLETS[lvl % 3]; return; }
            String key = p.getIlfo() + ":" + lvl;
            int v = counters.getOrDefault(key, list.getStartAt((char) lvl) - 1) + 1;
            counters.put(key, v);
            for (int deeper = lvl + 1; deeper < 9; deeper++) counters.remove(p.getIlfo() + ":" + deeper);
            out.label = v + ".";
        } catch (RuntimeException e) {
            out.label = "•";
        }
    }

    private static final String[] BULLETS = {"•", "◦", "▪"};

    // ---- .xls ------------------------------------------------------------------------------------

    private static List<Sheet> xls(InputStream in, BooleanSupplier cancelled) throws IOException {
        HSSFWorkbook wb = new HSSFWorkbook(in);
        DataFormatter fmt = new DataFormatter(TR);
        ArrayList<Sheet> out = new ArrayList<>();
        for (int si = 0; si < wb.getNumberOfSheets(); si++) {
            if (wb.isSheetHidden(si) || wb.isSheetVeryHidden(si)) continue;
            HSSFSheet hs = wb.getSheetAt(si);
            Sheet sh = new Sheet(wb.getSheetName(si));
            out.add(sh);
            int maxCol = 0;
            rows:
            for (int ri = hs.getFirstRowNum(); ri <= hs.getLastRowNum(); ri++) {
                if ((ri & 255) == 0) check(cancelled);
                HSSFRow row = hs.getRow(ri);
                if (row == null || row.getFirstCellNum() < 0) continue;
                for (int ci = row.getFirstCellNum(); ci < row.getLastCellNum(); ci++) {
                    HSSFCell cell = row.getCell(ci);
                    if (cell == null) continue;
                    boolean[] num = {false};
                    String v = value(fmt, cell, num);
                    if (!sh.put(ri, ci, v, num[0])) break rows;
                    if (v != null && !v.isEmpty()) maxCol = Math.max(maxCol, ci + 1);
                }
            }
            float[] w = new float[Math.min(maxCol, Sheet.MAX_COLS)];
            for (int c = 0; c < w.length; c++) w[c] = hs.isColumnHidden(c) ? 0 : hs.getColumnWidth(c) / 256f;
            sh.widths = w;
        }
        return out;
    }

    private static String value(DataFormatter fmt, HSSFCell cell, boolean[] num) {
        CellType t = cell.getCellType();
        if (t == CellType.FORMULA) t = cell.getCachedFormulaResultType();
        switch (t) {
            case NUMERIC:
                num[0] = true;
                short fi = cell.getCellStyle().getDataFormat();
                String fs = fi == 14 ? "dd.mm.yyyy" : fi == 22 ? "dd.mm.yyyy hh:mm" : cell.getCellStyle().getDataFormatString();   // Excel shows these in the system's date style
                try {
                    return fmt.formatRawCellContents(cell.getNumericCellValue(), fi, fs == null ? "General" : fs);
                } catch (RuntimeException | LinkageError e) {   // e.g. a conditional format section needing java.awt
                    return fmt.formatRawCellContents(cell.getNumericCellValue(), 0, "General");
                }
            case STRING: return cell.getRichStringCellValue().getString();
            case BOOLEAN: return cell.getBooleanCellValue() ? "DOĞRU" : "YANLIŞ";
            case ERROR:
                try { return FormulaError.forInt(cell.getErrorCellValue()).getString(); } catch (RuntimeException e) { return "#HATA"; }
            default: return null;
        }
    }

    // ---- .ppt ------------------------------------------------------------------------------------

    private static List<Docx.Block> ppt(InputStream in, BooleanSupplier cancelled) throws IOException {
        HSLFSlideShow ss = new HSLFSlideShow(in);
        ArrayList<Docx.Block> out = new ArrayList<>();
        int no = 0;
        for (HSLFSlide slide : ss.getSlides()) {
            check(cancelled);
            no++;
            Docx.Para h = new Docx.Para();
            Docx.Run hr = new Docx.Run();
            hr.text = "Slayt " + no;
            h.runs.add(hr);
            h.heading = 2;
            out.add(h);
            for (List<HSLFTextParagraph> box : slide.getTextParagraphs()) paras(box, out);
            if (out.size() >= MAX_BLOCKS) break;
        }
        if (no == 0) return Collections.emptyList();
        return out;
    }

    /**
     * The text of one text box / placeholder. Deliberately not via the shape tree (getShapes): that code path needs
     * java.awt.geom, which Android does not have. Consequence: text inside .ppt tables is not shown.
     */
    private static void paras(List<HSLFTextParagraph> box, List<Docx.Block> out) {
        for (HSLFTextParagraph tp : box) {
            Docx.Para p = new Docx.Para();
            for (HSLFTextRun tr : tp.getTextRuns()) {
                String raw = tr.getRawText();
                if (raw == null || raw.isEmpty()) continue;
                StringBuilder sb = new StringBuilder(raw.length());
                for (int k = 0; k < raw.length(); k++) {
                    char ch = raw.charAt(k);
                    if (ch == 0x0b) sb.append('\n'); else if (ch == '\t' || ch >= 0x20) sb.append(ch);
                }
                if (sb.length() == 0) continue;
                Docx.Run run = new Docx.Run();
                run.text = sb.toString();
                try { run.b = tr.isBold(); run.i = tr.isItalic(); run.u = tr.isUnderlined(); } catch (RuntimeException ignored) {}
                p.runs.add(run);
            }
            if (blank(p)) continue;
            try {
                if (tp.isBullet()) { p.level = Math.max(0, tp.getIndentLevel()); p.label = BULLETS[p.level % 3]; }
            } catch (RuntimeException ignored) {}
            out.add(p);
        }
    }
}
