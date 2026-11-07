package app.kolay.app.viewer;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.kolay.app.doc.Langs;
import app.kolay.app.ui.*;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Read-only document viewer. The file is parsed on a background thread and shown as soon as the first part is ready
 * (first PDF page, first paragraphs, first rows, first slide). Nothing leaves the device; document content is never logged.
 */
final class ViewerScreen extends Screen {
    private static final int TEXT_CHUNK_LINES = 60;
    private static final long TEXT_MAX_CHARS = 40L << 20;

    private final Uri uri;
    private final String mime;
    private final boolean external;
    private Header header;
    private FrameLayout content;
    private volatile boolean destroyed;
    private volatile Runnable closer;
    private Zip zip;

    /** {@code external}: opened from another app ("Birlikte aç"); Back then returns to that app. */
    ViewerScreen(Uri uri, String mime, boolean external) { this.uri = uri; this.mime = mime; this.external = external; }

    @Override public View create() {
        Px.source = android.util.Xml::newPullParser;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Belge", "Açılıyor…", this::leave);
        page.addView(header.view);
        content = new FrameLayout(c.a);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        show(Ui.busy(c, "Açılıyor…", null));
        c.io.execute(this::load);
        return page;
    }

    private void leave() { if (external) c.a.finish(); else c.nav.pop(); }

    @Override public boolean onBack() { if (external) { c.a.finish(); return true; } return false; }

    @Override public void onDestroy() {
        destroyed = true;
        Runnable r = closer; closer = null;
        if (r != null) r.run();
        Zip z = zip; zip = null;
        if (z != null) c.io.execute(z::close);   // after queued picture reads
    }

    private void show(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void post(Runnable r) { c.ui.post(() -> { if (!destroyed) r.run(); }); }

    private void fail(String title, String body) {
        post(() -> { header.subtitle.setText("Açılamadı"); show(Ui.state(c, Icon.Kind.DOC, title, body, null, null)); });
    }

    // ---- loading (background) ----------------------------------------------------------------

    private void load() {
        Src.cleanup(c.a);
        String name = Src.name(c.a, uri);
        post(() -> ((TextView) ((LinearLayout) header.view.getChildAt(1)).getChildAt(0)).setText(name));
        Viewer.Kind kind = Viewer.kind(name, null);
        if (kind == null) kind = Viewer.kind(null, mime);
        if (kind == null || kind == Viewer.Kind.TXT && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            Viewer.Kind sniffed = Src.sniff(c.a, uri);
            if (sniffed != null) kind = sniffed;
        }
        if (kind == null) { fail("Bu dosya türü gösterilemiyor", "PDF, Word, Excel, PowerPoint, metin ve CSV dosyaları açılabilir."); return; }
        String label = Viewer.label(kind);
        try {
            switch (kind) {
                case PDF: pdf(); break;
                case DOCX: docx(label); break;
                case XLSX: xlsx(label); break;
                case PPTX: pptx(label); break;
                case CSV: csv(label, name); break;
                case TXT: text(label); break;
                default: legacy(kind, label); break;
            }
        } catch (SecurityException e) {
            fail("Dosyaya erişilemedi", "Dosyayı paylaşan uygulama okuma izni vermedi. Dosyayı oradan yeniden açmayı dene.");
        } catch (java.io.FileNotFoundException e) {
            fail("Dosya bulunamadı", "Dosya taşınmış, silinmiş ya da artık erişilemiyor olabilir.");
        } catch (Exception | OutOfMemoryError e) {
            if (destroyed) return;
            if (closer != null) post(() -> Ui.toastLong(c, "Belgenin bir kısmı okunamadı."));
            else fail("Belge açılamadı", "Dosya bozuk, parolalı ya da desteklenmeyen bir sürümde olabilir.");
        }
    }

    private void pdf() throws IOException {
        ParcelFileDescriptor pfd;
        File[] tmp = {null};
        try {
            pfd = Src.pfd(c.a, uri);
            if (pfd.getStatSize() < 0) { pfd.close(); throw new IOException("not seekable"); }
        } catch (IOException e) {
            if (e instanceof java.io.FileNotFoundException) throw e;
            tmp[0] = Src.copy(c.a, uri, () -> destroyed);
            pfd = ParcelFileDescriptor.open(tmp[0], ParcelFileDescriptor.MODE_READ_ONLY);
        }
        PdfPane pane;
        try {
            pane = PdfPane.open(c, pfd, () -> { if (tmp[0] != null) tmp[0].delete(); });
        } catch (SecurityException e) {
            if (tmp[0] != null) tmp[0].delete();
            fail("Parolalı PDF", "Parolayla korunan PDF'ler bu görüntüleyicide açılamıyor.");
            return;
        } catch (IOException | RuntimeException e) {
            if (tmp[0] != null) tmp[0].delete();
            throw e;
        }
        post(() -> {
            closer = pane::close;
            header.subtitle.setText("PDF · " + pane.count() + " sayfa");
            if (pane.count() == 0) show(Ui.state(c, Icon.Kind.DOC, "Boş PDF", "Bu dosyada sayfa yok.", null, null));
            else show(pane);
        });
        c.ui.post(() -> { if (destroyed) pane.close(); });
    }

    private Zip openZip() throws IOException {
        Zip z = Src.zip(c.a, uri, () -> destroyed);
        if (destroyed) { z.close(); throw new IOException("closed"); }
        zip = z;
        return z;
    }

    private void docx(String label) throws IOException {
        Zip z = openZip();
        DocPane[] pane = {null};
        ArrayList<Docx.Block> batch = new ArrayList<>();
        int[] base = {22};
        long[] lastPost = {0};
        int[] total = {0};
        Docx.parse(z, new Docx.Sink() {
            @Override public void begin(int halfPts) { base[0] = halfPts; }
            @Override public void block(Docx.Block b) {
                batch.add(b); total[0]++;
                long now = System.nanoTime();
                // first screenful right away, then batches at most every 150 ms
                if ((pane[0] == null && batch.size() >= 30) || now - lastPost[0] > 150_000_000L && batch.size() >= 200) {
                    flush(pane, batch, base[0], z, label);
                    lastPost[0] = now;
                }
            }
        }, () -> destroyed);
        flush(pane, batch, base[0], z, label);
        int n = total[0];
        post(() -> { if (n == 0) show(Ui.state(c, Icon.Kind.DOC, "Boş belge", "Bu belgede gösterilecek metin yok.", null, null)); });
    }

    private void flush(DocPane[] pane, ArrayList<Docx.Block> batch, int base, Zip z, String label) {
        List<Docx.Block> part = new ArrayList<>(batch);
        batch.clear();
        boolean first = pane[0] == null;
        if (first) {
            CountDown created = new CountDown();
            post(() -> {
                DocPane p = new DocPane(c, z, false);
                p.setBase(base);
                closer = p::close;
                header.subtitle.setText(label);
                show(p);
                pane[0] = p;
                p.add(part);
                created.done();
            });
            created.await(() -> destroyed);
        } else {
            DocPane p = pane[0];
            post(() -> p.add(part));
        }
    }

    /** Waits (background thread) until the UI thread created the pane, so later batches can reference it. */
    private static final class CountDown {
        private final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        void done() { l.countDown(); }
        void await(java.util.function.BooleanSupplier stop) {
            try { while (!l.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) if (stop.getAsBoolean()) return; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void xlsx(String label) throws IOException {
        Zip z = openZip();
        Xlsx x = Xlsx.open(z, () -> destroyed);
        if (x.names.isEmpty()) { fail("Boş tablo", "Bu dosyada görünür sayfa yok."); return; }
        post(() -> {
            header.subtitle.setText(label + " · " + x.names.size() + " sayfa");
            SheetPane p = new SheetPane(c, x.names, (i, early) -> x.sheet(i, () -> destroyed, early));
            closer = () -> p.closed = true;
            show(p);
        });
    }

    private void csv(String label, String name) throws IOException {
        Sheet s;
        try (Reader r = Plain.reader(Src.stream(c.a, uri))) { s = Xlsx.csv(r, name, () -> destroyed); }
        sheetOnly(label, s);
    }

    private void sheetOnly(String label, Sheet s) { sheets(label, Collections.singletonList(s)); }

    private void sheets(String label, List<Sheet> list) {
        List<String> names = new ArrayList<>();
        for (Sheet s : list) names.add(s.name);
        post(() -> {
            header.subtitle.setText(label + (list.size() > 1 ? " · " + list.size() + " sayfa" : ""));
            SheetPane p = new SheetPane(c, names, (i, early) -> list.get(i));
            closer = () -> p.closed = true;
            show(p);
        });
    }

    private void pptx(String label) throws IOException {
        Zip z = openZip();
        Pptx deck = Pptx.open(z);
        if (deck.slides.isEmpty()) { fail("Boş sunu", "Bu dosyada slayt yok."); return; }
        post(() -> {
            header.subtitle.setText(label + " · " + deck.slides.size() + " slayt");
            SlidePane p = new SlidePane(c, deck, z);
            closer = p::close;
            show(p);
        });
    }

    /** Text in chunks of lines; the first chunk is shown at once, the rest streams in. */
    private void text(String label) throws IOException {
        DocPane[] pane = {null};
        ArrayList<Docx.Block> batch = new ArrayList<>();
        long chars = 0;
        boolean cut = false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(Plain.reader(Src.stream(c.a, uri)))) {
            StringBuilder sb = new StringBuilder();
            int lines = 0;
            long lastPost = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (destroyed) return;
                if (lines > 0) sb.append('\n');
                sb.append(line);
                chars += line.length() + 1;
                if (++lines >= TEXT_CHUNK_LINES || sb.length() > 16000) {
                    batch.add(plainPara(sb)); sb.setLength(0); lines = 0;
                    long now = System.nanoTime();
                    if (pane[0] == null || now - lastPost > 150_000_000L && batch.size() >= 20) { flushText(pane, batch, label); lastPost = now; }
                }
                if (chars > TEXT_MAX_CHARS) { cut = true; break; }
            }
            if (sb.length() > 0) batch.add(plainPara(sb));
        }
        if (cut) { StringBuilder sb = new StringBuilder("… (dosya çok büyük; yalnızca ilk kısmı gösteriliyor)"); batch.add(plainPara(sb)); }
        if (pane[0] == null && batch.isEmpty()) { fail("Boş dosya", "Bu dosyada metin yok."); return; }
        flushText(pane, batch, label);
    }

    private static Docx.Para plainPara(StringBuilder sb) {
        Docx.Para p = new Docx.Para();
        Docx.Run r = new Docx.Run();
        r.text = sb.toString();
        p.runs.add(r);
        return p;
    }

    private void flushText(DocPane[] pane, ArrayList<Docx.Block> batch, String label) {
        List<Docx.Block> part = new ArrayList<>(batch);
        batch.clear();
        if (pane[0] == null) {
            CountDown created = new CountDown();
            post(() -> {
                DocPane p = new DocPane(c, null, true);
                closer = p::close;
                header.subtitle.setText(label);
                show(p);
                pane[0] = p;
                p.add(part);
                created.done();
            });
            created.await(() -> destroyed);
        } else {
            DocPane p = pane[0];
            post(() -> p.add(part));
        }
    }

    private void legacy(Viewer.Kind kind, String label) throws IOException {
        String ext = kind.name().toLowerCase(java.util.Locale.ROOT);
        if (!LegacyOffice.available() && c.host.officePluginInstalled()) {
            try {
                LegacyOffice.load(c.host.officePluginDex());
            } catch (SecurityException e) {   // file changed on disk: already deleted, offer a fresh download
                post(() -> offerPlugin(label, ext, "Kayıtlı eklenti doğrulanamadı ve silindi. Yeniden indirebilirsin."));
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                fail("Eklenti yüklenemedi", "Eski Office eklentisi bu cihazda çalıştırılamadı. Ayarlar'dan silip yeniden indirmeyi dene.");
                return;
            }
        }
        if (!LegacyOffice.available()) { post(() -> offerPlugin(label, ext, null)); return; }
        LegacyOffice.Result r = LegacyOffice.extract(c.a, uri, ext, () -> destroyed);
        if (r.sheets != null && !r.sheets.isEmpty()) { sheets(label, r.sheets); return; }
        if (r.blocks != null) {
            if (r.blocks.isEmpty()) { fail("Boş belge", "Bu dosyada gösterilecek metin yok."); return; }
            List<Docx.Block> blocks = r.blocks;
            post(() -> {
                DocPane p = new DocPane(c, null, false);
                closer = p::close;
                header.subtitle.setText(label + " · eski biçim, yalnızca metin");
                show(p);
                p.add(blocks);
            });
            return;
        }
        post(() -> {
            header.subtitle.setText(label + " · eski biçim");
            show(Ui.state(c, Icon.Kind.DOC, "Eski Office biçimi", r.reason, null, null));
        });
    }

    // ---- old-Office plugin: one-time download on request ----------------------------------------

    private volatile boolean pluginCancel;

    private void offerPlugin(String label, String ext, String error) {
        header.subtitle.setText(label + " · eski biçim");
        String size = Langs.mb(c.host.officePluginSize());
        String body = (error != null ? error + "\n\n" : "")
            + "Eski ." + ext + " dosyalarını açmak için bir kerelik bir eklenti (" + size + ") indirilir. "
            + "Eklenti GitHub'daki Kolay sürüm sayfasından gelir, SHA-256 özeti doğrulanmadan kullanılmaz ve yalnızca bu "
            + "telefonda çalışır; belgen hiçbir yere gönderilmez. Yalnızca metin gösterilir (resim ve düzen yok).";
        show(Ui.state(c, Icon.Kind.DOWNLOAD, "Eski Office biçimi", body, error != null ? "Yeniden dene" : "İndir · " + size,
            () -> downloadPlugin(label, ext)));
    }

    private void downloadPlugin(String label, String ext) {
        Palette p = c.p;
        final long total = Math.max(1, c.host.officePluginSize());
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(c.dp(36), 0, c.dp(36), c.dp(48));
        TextView title = Ui.text(c, "Eklenti indiriliyor…", 18, p.ink, 600);
        title.setGravity(Gravity.CENTER);
        l.addView(title);
        Bar bar = new Bar(c.a, p.surface2, p.primary);
        l.addView(bar, Ui.lp(-1, c.dp(6), 0, c.dp(18), 0, 0));
        TextView status = Ui.text(c, "0 / " + Langs.mb(total), 13, p.muted, 400);
        status.setGravity(Gravity.CENTER);
        l.addView(status, Ui.lp(-2, -2, 0, c.dp(10), 0, 0));
        TextView cancel = Ui.button(c, "İptal", Ui.Style.GHOST, null);
        cancel.setMinWidth(c.dp(140));
        cancel.setOnClickListener(v -> { pluginCancel = true; v.setEnabled(false); });
        l.addView(cancel, Ui.lp(-2, c.dp(Ui.H_GHOST), 0, c.dp(20), 0, 0));
        show(l);
        pluginCancel = false;
        c.io.execute(() -> {
            final long[] last = {0};
            String err = null;
            try {
                c.host.officePluginDownload(done -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 120 && done < total) return;
                    last[0] = now;
                    post(() -> { bar.set(done / (float) total); status.setText(Langs.mb(done) + " / " + Langs.mb(total)); });
                }, () -> pluginCancel || destroyed);
            } catch (java.io.InterruptedIOException e) {
                err = "";
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (destroyed) return;
            if (err == null) { post(() -> show(Ui.busy(c, "Açılıyor…", null))); load(); return; }
            String e2 = err;
            post(() -> {
                if (e2.isEmpty()) offerPlugin(label, ext, null);
                else offerPlugin(label, ext, "İndirilemedi: " + e2);
            });
        });
    }

    // ---- shared bits for the panes ------------------------------------------------------------

    /** Floating "3 / 12" pill; tap to jump. */
    static TextView pill(Ctx c, Runnable onTap) {
        TextView t = Ui.text(c, "", 13, c.p.onPrimarySoft, 600);
        t.setGravity(Gravity.CENTER);
        t.setPadding(c.dp(14), c.dp(7), c.dp(14), c.dp(7));
        t.setBackground(Ui.rounded(c, c.p.primarySoft, 16));
        t.setElevation(c.dp(3));
        t.setContentDescription("Sayfaya git");
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    /** Small number dialog (1…max). */
    static void askNumber(Ctx c, String title, int max, IntConsumer go) {
        app.kolay.app.ui.Sheet.input(c, title, "1 – " + max, "", InputType.TYPE_CLASS_NUMBER, "Git", t -> {
            try { go.accept(Math.max(1, Math.min(max, Integer.parseInt(t)))); return null; }
            catch (NumberFormatException e) { return "1 ile " + max + " arasında bir sayı yaz"; }
        });
    }
}
