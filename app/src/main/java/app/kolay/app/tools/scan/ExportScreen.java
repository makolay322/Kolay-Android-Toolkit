package app.kolay.app.tools.scan;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.*;
import app.kolay.app.ui.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Format (PDF / JPEG), file name and optional OCR, then the save itself with progress. PDFs go to Documents/Kolay (with OCR:
 * searchable, an invisible text layer), images to Pictures/Kolay. Files are written only here, on the user's explicit
 * save; recognised text otherwise stays in memory (copy / share) and is gone when the screen closes.
 */
final class ExportScreen extends Screen {
    private final ScanSession s;
    private boolean pdf = true, ocr;
    private String langs;
    private FrameLayout body;
    private LinearLayout form, ocrBox;
    private LangBar langBar;
    private EditText name;
    private TextView ocrNote, save, status;
    private Bar bar;
    private volatile boolean cancelled, destroyed;
    private boolean running;
    // result (main thread)
    private final List<Uri> outputs = new ArrayList<>();
    private String text = "", folder = "", fileName = "";

    ExportScreen(ScanSession s) { this.s = s; }

    @Override public View create() {
        Palette p = c.p;
        android.content.SharedPreferences pr = c.a.getSharedPreferences("scan", 0);
        pdf = pr.getBoolean("pdf", true);
        ocr = pr.getBoolean("ocr", false);
        langs = Langs.remembered(c.a, "scan", "deu+tur");

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Kaydet", s.pages.size() + " sayfa", this::back).view);
        body = new FrameLayout(c.a);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        form = new LinearLayout(c.a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        form.addView(Ui.label(c, "BİÇİM"), Ui.lp(-2, -2, c.dp(4), c.dp(4), 0, c.dp(8)));
        FrameLayout fmt = new FrameLayout(c.a);
        form.addView(fmt, Ui.lp(-1, c.dp(44)));
        paintFormat(fmt);

        form.addView(Ui.label(c, "DOSYA ADI"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
        name = Ui.input(c, "Tarama", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setText(defaultName());
        form.addView(name, Ui.lp(-1, -2));

        form.addView(Ui.label(c, "METİN TANIMA (OCR)"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
        LinearLayout card = Ui.card(c, p.surface, 14);
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(c.a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, "Metni tanı", 16, p.ink, 600));
        ocrNote = Ui.text(c, "", 13, p.muted, 400);
        ocrNote.setLineSpacing(0, 1.15f);
        texts.addView(ocrNote, Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Toggle t = new Toggle(c.a, p.track, p.primary, p.thumb, ocr);
        t.onChange(on -> { ocr = on; paintOcr(); });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.setMarginStart(c.dp(12));
        row.addView(t, tl);
        card.addView(row);
        ocrBox = new LinearLayout(c.a);
        ocrBox.setOrientation(LinearLayout.VERTICAL);
        langBar = new LangBar(c, langs, v -> { langs = v; Langs.remember(c.a, "scan", v); });
        ocrBox.addView(langBar.view, Ui.lp(-1, -2, 0, c.dp(14), 0, 0));
        TextView manage = Ui.button(c, "Dil verisini yönet", Ui.Style.GHOST, Icon.Kind.DOWNLOAD);
        manage.setTextSize(14);
        manage.setOnClickListener(v -> c.nav.push(new TessScreen()));
        ocrBox.addView(manage, Ui.lp(-2, c.dp(Ui.H_GHOST), 0, c.dp(6), 0, 0));
        card.addView(ocrBox);
        form.addView(card);
        sv.addView(form);
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        paintOcr();

        LinearLayout bb = Ui.bottomBar(c);
        save = Ui.button(c, "Kaydet", Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> start());
        bb.addView(save, Ui.lp(-1, c.dp(Ui.H_BAR)));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private static String defaultName() { return "Tarama_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date()); }

    private void paintFormat(FrameLayout fmt) {
        fmt.removeAllViews();
        fmt.addView(Ui.segmented(c, new String[]{"PDF", "JPEG"}, new Icon.Kind[]{Icon.Kind.DOC, Icon.Kind.IMAGE}, pdf ? 0 : 1, i -> {
            pdf = i == 0;
            paintFormat(fmt);
            paintOcr();
        }), new FrameLayout.LayoutParams(-1, -1));
    }

    private void paintOcr() {
        ocrNote.setText(!ocr ? "Kapalı" : pdf ? "Aranabilir PDF: görünmez metin katmanı eklenir; metni ayrıca kopyalayabilirsin"
            : "Tanınan metin kopyalanabilir / paylaşılabilir (dosyaya yazılmaz)");
        ocrBox.setVisibility(ocr ? View.VISIBLE : View.GONE);
    }

    @Override public void onReturn() { if (langBar != null) langBar.refresh(); }

    // ---- saving -----------------------------------------------------------------------------------------------

    private void start() {
        if (running || s.pages.isEmpty()) return;
        if (ocr && !Langs.ready(c.host, langs)) {
            c.nav.push(new TessScreen(langs, this::start));
            return;
        }
        boolean any = false;
        for (ScanSession.Page pg : s.pages) any |= !pg.broken;
        if (!any) { Toast.makeText(c.a, "Kaydedilecek okunabilir sayfa yok", Toast.LENGTH_SHORT).show(); return; }
        String n = clean(name.getText().toString());
        name.setText(n);
        c.a.getSharedPreferences("scan", 0).edit().putBoolean("pdf", pdf).putBoolean("ocr", ocr).apply();
        Ui.haptic(save, HapticFeedbackConstants.CONFIRM);
        android.view.inputmethod.InputMethodManager im =
            (android.view.inputmethod.InputMethodManager) c.a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(name.getWindowToken(), 0);
        running = true; cancelled = false;
        fileName = n;

        body.removeAllViews();
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(c.dp(32), 0, c.dp(32), c.dp(32));
        col.addView(new Space(c.a), new LinearLayout.LayoutParams(1, 0, 1));
        col.addView(new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(6)), Ui.lp(c.dp(76), c.dp(76)));
        status = Ui.text(c, "Hazırlanıyor…", 18, c.p.ink, 600);
        status.setGravity(Gravity.CENTER);
        col.addView(status, Ui.lp(-1, -2, 0, c.dp(28), 0, 0));
        bar = new Bar(c.a, c.p.surface2, c.p.primary);
        col.addView(bar, Ui.lp(c.dp(240), c.dp(6), 0, c.dp(24), 0, 0));
        col.addView(new Space(c.a), new LinearLayout.LayoutParams(1, 0, 1));
        body.addView(col, new FrameLayout.LayoutParams(-1, -1));
        save.setText("Vazgeç");
        Ui.style(c, save, Ui.Style.GHOST, null);
        save.setOnClickListener(v -> cancel());

        List<Job> jobs = new ArrayList<>();
        for (ScanSession.Page pg : s.pages) if (!pg.broken) jobs.add(new Job(pg.file, pg.quad.clone(), pg.rotation, pg.filter));
        final boolean asPdf = pdf, withOcr = ocr; final String combo = langs;
        c.io.execute(() -> work(jobs, asPdf, withOcr, combo, n));
    }

    private static final class Job {
        final File file; final float[] quad; final int rotation; final Imaging.Filter filter;
        Job(File f, float[] q, int r, Imaging.Filter fl) { file = f; quad = q; rotation = r; filter = fl; }
    }

    /** PDF pages: A4 at 300 dpi (long side 3508 px), JPEG quality 90; plain JPEG export keeps the full picture. */
    private static final int PDF_LONG_PX = 3508, PDF_QUALITY = 90;

    /** Downscales (never upscales) to {@link #PDF_LONG_PX}; recycles {@code b} when a smaller copy is made. */
    private static Bitmap forPdf(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), m = Math.max(w, h);
        if (m <= PDF_LONG_PX) return b;
        float k = PDF_LONG_PX / (float) m;
        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        if (s != b) b.recycle();
        return s;
    }

    /** Background thread. Writes straight into the MediaStore entries; on failure or cancel they are removed again. */
    private void work(List<Job> jobs, boolean asPdf, boolean withOcr, String combo, String base) {
        ContentResolver cr = c.a.getContentResolver();
        List<Uri> made = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        String err = null, where = asPdf ? "" : "Pictures/Kolay";
        OcrEngine engine = null;
        OutputStream pdfOut = null;
        try {
            if (withOcr) engine = new OcrEngine(c.host, combo);
            PdfWriter pw = null;
            if (asPdf) {
                Object[] r = MediaOut.newPdf(cr, base + ".pdf");
                made.add((Uri) r[0]); where = (String) r[1];
                pdfOut = new java.io.BufferedOutputStream(MediaOut.open(cr, (Uri) r[0]), 1 << 16);
                pw = new PdfWriter(pdfOut);
            }
            int n = jobs.size();
            for (int i = 0; i < n; i++) {
                if (cancelled) throw new InterruptedIOException();
                progress("Sayfa " + (i + 1) + "/" + n + " hazırlanıyor…", i / (float) n);
                Job j = jobs.get(i);
                Bitmap bmp = Imaging.render(j.file, j.quad, j.rotation, j.filter, Imaging.FULL);
                try {
                    OcrEngine.Page words = null;
                    if (engine != null) {
                        if (cancelled) throw new InterruptedIOException();
                        progress("Sayfa " + (i + 1) + "/" + n + " okunuyor…", (i + 0.4f) / n);
                        Bitmap prep = OcrEngine.prepare(bmp);
                        String pageText;
                        try {
                            if (asPdf) { words = engine.words(prep); pageText = words.text; }
                            else pageText = engine.text(prep);
                        } finally { prep.recycle(); }
                        if (!pageText.isEmpty()) all.append(pageText).append("\n\n");
                    }
                    if (cancelled) throw new InterruptedIOException();
                    if (asPdf) {
                        bmp = forPdf(bmp);
                        ByteArrayOutputStream jb = new ByteArrayOutputStream(1 << 20);
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, PDF_QUALITY, jb)) throw new java.io.IOException("Sayfa sıkıştırılamadı");
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        bmp.recycle();
                        pw.addPage(jb.toByteArray(), w, h, words);
                    } else {
                        Uri u = MediaOut.newImage(cr, n == 1 ? base + ".jpg" : base + "_" + (i + 1) + ".jpg");
                        made.add(u);
                        try (OutputStream os = MediaOut.open(cr, u)) {
                            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)) throw new java.io.IOException("Kaydedilemedi");
                        }
                    }
                } finally { if (!bmp.isRecycled()) bmp.recycle(); }
            }
            if (asPdf) {
                progress("PDF yazılıyor…", 0.97f);
                pw.finish();
                pdfOut.close(); pdfOut = null;
            }
            for (Uri u : made) MediaOut.publish(cr, u);
        } catch (InterruptedIOException e) {
            err = "";
        } catch (Throwable t) {
            err = t instanceof OutOfMemoryError ? "Bellek yetmedi; daha az sayfayla dene" : t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            if (pdfOut != null) try { pdfOut.close(); } catch (Exception ignored) { }
            if (engine != null) engine.close();
        }
        if (err != null) for (Uri u : made) MediaOut.discard(cr, u);
        final String e = err, ocrText = all.toString().trim(), dir = where;
        c.ui.post(() -> {
            if (destroyed) return;
            running = false;
            if (e != null) {
                if (!e.isEmpty()) Toast.makeText(c.a, "Kaydedilemedi: " + e, Toast.LENGTH_LONG).show();
                c.nav.replace(new ExportScreen(s));   // back to a fresh form
                return;
            }
            outputs.clear(); outputs.addAll(made);
            text = ocrText; folder = dir;
            showDone(asPdf, withOcr);
        });
    }

    private void progress(String msg, float f) {
        c.ui.post(() -> { if (!destroyed && status != null && running) { Ui.swapText(status, msg); bar.set(f); } });
    }

    private void cancel() {
        if (!running) return;
        cancelled = true;
        save.setEnabled(false);
        Ui.swapText(status, "İptal ediliyor…");
    }

    private void showDone(boolean asPdf, boolean withOcr) {
        Palette p = c.p;
        body.removeAllViews();
        int n = outputs.size();
        String where = asPdf ? folder + "/" + fileName + ".pdf" : n == 1 ? folder + "/" + fileName + ".jpg" : n + " görüntü · " + folder;
        LinearLayout col = Ui.state(c, Icon.Kind.CHECK, "Kaydedildi", where, null, null);
        LinearLayout acts = new LinearLayout(c.a);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        TextView share = Ui.button(c, "Paylaş", Ui.Style.PRIMARY, Icon.Kind.SHARE);
        share.setOnClickListener(v -> share(asPdf));
        acts.addView(share, Ui.lp(-1, c.dp(Ui.H_BTN)));
        if (withOcr) {
            if (text.isEmpty()) {
                TextView none = Ui.text(c, "Sayfalarda metin bulunamadı.", 13, p.muted, 400);
                none.setGravity(Gravity.CENTER);
                acts.addView(none, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));
            } else {
                TextView copy = Ui.button(c, "Metni kopyala", Ui.Style.TONAL, Icon.Kind.COPY);
                copy.setOnClickListener(v -> copy(copy));
                TextView sendText = Ui.button(c, "Metni paylaş", Ui.Style.TONAL, Icon.Kind.SHARE);
                sendText.setOnClickListener(v -> c.a.startActivity(Intent.createChooser(
                    new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)));
                acts.addView(Ui.pair(c, copy, sendText, Ui.H_GHOST), Ui.lp(-1, -2, 0, c.dp(Ui.GAP), 0, 0));
            }
        }
        col.addView(acts, Ui.lp(-1, -2, 0, c.dp(22), 0, 0));
        body.addView(col, new FrameLayout.LayoutParams(-1, -1));
        Ui.appear(col, 0);

        TextView fresh = Ui.button(c, "Yeni tarama", Ui.Style.TONAL, s.camera ? Icon.Kind.CAMERA : Icon.Kind.NETSCAN);
        fresh.setOnClickListener(v -> {
            for (ScanSession.Page pg : new ArrayList<>(s.pages)) s.remove(pg);
            s.defaultFilter = Imaging.Filter.AUTO;
            c.nav.popTo(s.root);
        });
        TextView close = Ui.button(c, "Bitti", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        close.setOnClickListener(v -> close());
        ViewGroup bb = (ViewGroup) save.getParent();
        bb.removeAllViews();
        bb.addView(Ui.pair(c, fresh, close, Ui.H_BAR));
        Ui.haptic(col, HapticFeedbackConstants.CONFIRM);
    }

    /** Leaves the whole scanner: back to the root of the flow (camera or network scanner), which is popped right after. */
    private void close() {
        for (ScanSession.Page pg : new ArrayList<>(s.pages)) s.remove(pg);
        s.closing = true;
        c.nav.popTo(s.root);
        c.nav.pop();   // queued behind popTo: leaves the camera screen
    }

    private void share(boolean asPdf) {
        if (outputs.isEmpty()) return;
        Intent i;
        if (outputs.size() == 1) i = new Intent(Intent.ACTION_SEND).setType(asPdf ? "application/pdf" : "image/jpeg").putExtra(Intent.EXTRA_STREAM, outputs.get(0));
        else i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(outputs));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { c.a.startActivity(Intent.createChooser(i, null)); }
        catch (Exception e) { Toast.makeText(c.a, "Paylaşılamadı", Toast.LENGTH_SHORT).show(); }
    }

    private void copy(TextView btn) {
        ClipData clip = ClipData.newPlainText("OCR", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        Ui.flashCopied(c, btn, Ui.Style.TONAL);
    }

    /** Safe file name: letters (incl. Turkish/German), digits, space, - and _; at most 80 characters. */
    private static String clean(String in) {
        String low = in.trim().toLowerCase(Locale.ROOT);
        if (low.endsWith(".pdf") || low.endsWith(".jpg")) in = in.trim().substring(0, in.trim().length() - 4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < in.length() && b.length() < 80; i++) {
            char ch = in.charAt(i);
            b.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == ' ' ? ch : '_');
        }
        String s = b.toString().trim();
        return s.isEmpty() ? defaultName() : s;
    }

    private void back() {
        if (running) { cancel(); return; }
        c.nav.pop();
    }

    @Override public boolean onBack() { back(); return true; }

    @Override public void onDestroy() { destroyed = true; cancelled = true; text = ""; }
}
