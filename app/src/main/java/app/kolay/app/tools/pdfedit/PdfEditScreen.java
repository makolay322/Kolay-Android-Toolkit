package app.kolay.app.tools.pdfedit;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.MediaOut;
import app.kolay.app.doc.PhotoPick;
import app.kolay.app.ui.*;
import app.kolay.shell.Host;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF page editor: the pages of one PDF as a grid (long-press and drag to reorder, rotate, delete), more pages from other
 * PDFs or pictures, then save as a new file ("…_duzenlenmis.pdf" next to the original, else Documents/Kolay) or over the
 * original after a confirmation. Working copies live in {@code cache/pdfedit/} and go when the screen does.
 */
public final class PdfEditScreen extends Screen {
    private static final int REQ_MERGE = 81, REQ_IMAGES = 82, COLS = 3, THUMB_PX = 300;
    private static final String SUFFIX = "_duzenlenmis";

    /** System picker for one PDF (read, and write where the provider allows it: "Asıl dosyanın üzerine yaz"). */
    public static Intent pickIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private final Uri uri;
    private String name = "Belge.pdf";
    private File dir;
    private final ExecutorService rt = Executors.newSingleThreadExecutor();   // PdfRenderer is not thread-safe: all its calls here
    private final List<PdfEdit.Source> sources = new ArrayList<>();
    private final List<PdfEdit.Pg> pages = new ArrayList<>();
    private PdfEdit.Source main;
    private volatile boolean destroyed;
    private boolean dirty, busy, unlocking;
    private int seq;

    private Header header;
    private FrameLayout body;
    private ScrollView scroll;
    private LinearLayout list, actions;
    private final List<View> tiles = new ArrayList<>();
    private final List<ImageView> thumbs = new ArrayList<>();
    private PdfEdit.Pg confirmDelete, dragging;
    private View dropTarget;
    private float dragX, dragY;
    private int edgeDir;
    private final Runnable edgeTick = new Runnable() {
        @Override public void run() {
            if (edgeDir == 0 || dragging == null) return;
            scroll.scrollBy(0, edgeDir * c.dp(10));
            hover(dragX, dragY);
            c.ui.postDelayed(this, 16);
        }
    };

    public PdfEditScreen(Uri uri) { this.uri = uri; }

    @Override public View create() {
        File root = new File(c.a.getCacheDir(), "pdfedit");
        File[] old = root.listFiles();   // leftovers of a killed process
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) Host.deleteTree(f);
        dir = new File(root, Long.toString(System.nanoTime()));
        dir.mkdirs();

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "PDF düzenle", "Açılıyor…", this::leave);
        page.addView(header.view);
        body = new FrameLayout(c.a);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll = new ScrollView(c.a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnDragListener(this::onDrag);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> bind());
        list = new LinearLayout(c.a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        scroll.addView(list);

        actions = Ui.bottomBar(c);
        TextView merge = action("PDF ekle", Ui.Style.TONAL, Icon.Kind.PLUS);
        merge.setOnClickListener(v -> pick(pickIntent(), REQ_MERGE));
        TextView img = action("Görüntü ekle", Ui.Style.TONAL, Icon.Kind.IMAGE);
        img.setOnClickListener(v -> pick(PhotoPick.intent(true), REQ_IMAGES));
        TextView save = action("Kaydet", Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> saveMenu());
        LinearLayout row = new LinearLayout(c.a);
        TextView[] all = {merge, img, save};
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.setMarginStart(c.dp(6));
            row.addView(all[i], lp);
        }
        actions.addView(row, new LinearLayout.LayoutParams(-1, -2));
        actions.setVisibility(View.GONE);
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        show(Ui.busy(c, "Açılıyor…", null));
        c.io.execute(() -> {
            PdfEdit.init(c.a);
            String n = displayName(uri);
            post(() -> { name = n; ((TextView) ((LinearLayout) header.view.getChildAt(1)).getChildAt(0)).setText(n); });
            File f;
            try { f = local(uri); }
            catch (IOException | RuntimeException e) { post(() -> failOpen(e)); return; }
            openSource(f, true, null);
        });
        return page;
    }

    /** Compact bottom action, as on the scanner's page grid: icon above a short label. */
    private TextView action(String label, Ui.Style st, Icon.Kind k) {
        TextView b = Ui.button(c, label, st, k);
        b.setTextSize(12);
        b.setMaxLines(2);
        b.setMinHeight(c.dp(58));
        b.setPadding(c.dp(2), c.dp(8), c.dp(2), c.dp(8));
        b.setCompoundDrawablesRelative(null, b.getCompoundDrawablesRelative()[0], null, null);
        b.setCompoundDrawablePadding(c.dp(4));
        return b;
    }

    private void show(View v) { body.removeAllViews(); body.addView(v, new FrameLayout.LayoutParams(-1, -1)); }

    private void post(Runnable r) { c.ui.post(() -> { if (!destroyed) r.run(); }); }

    private void failOpen(Throwable e) {
        header.subtitle.setText("Açılamadı");
        String body = e instanceof SecurityException ? "Dosyayı paylaşan uygulama okuma izni vermedi."
            : e instanceof FileNotFoundException ? "Dosya taşınmış, silinmiş ya da artık erişilemiyor olabilir."
            : "Dosya bozuk ya da desteklenmeyen bir biçimde olabilir.";
        show(Ui.state(c, Icon.Kind.DOC, "PDF açılamadı", body, null, null));
    }

    // ---- opening (background) -----------------------------------------------------------------

    private String displayName(Uri u) {
        if ("file".equals(u.getScheme())) return new File(u.getPath()).getName();
        try (Cursor cur = c.a.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (RuntimeException ignored) { }
        return "Belge.pdf";
    }

    /** The original itself for file:// (read in place), else a private copy (PdfBox and the renderer need a file). */
    private File local(Uri u) throws IOException {
        if ("file".equals(u.getScheme())) {
            File f = new File(u.getPath());
            if (!f.canRead()) throw new FileNotFoundException();
            return f;
        }
        if (!"content".equals(u.getScheme())) throw new FileNotFoundException("scheme");
        File f = newFile(".pdf");
        try (InputStream in = c.a.getContentResolver().openInputStream(u)) {
            if (in == null) throw new FileNotFoundException();
            copy(in, f);
        }
        return f;
    }

    private synchronized File newFile(String ext) { return new File(dir, "f" + (++seq) + ext); }

    private void copy(InputStream in, File f) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[1 << 16];
            for (int r; (r = in.read(b)) > 0; ) { if (destroyed) throw new IOException("closed"); o.write(b, 0, r); }
        }
    }

    /**
     * Any thread: opens {@code f} on the render thread and adds its pages (the first source is the edited document).
     * A password-protected file asks for the password and continues from an unlocked private copy.
     */
    private void openSource(File f, boolean first, String password) {
        rt.execute(() -> {
            PdfEdit.Source s;
            try { s = PdfEdit.open(f); }
            catch (PdfEdit.Locked e) { post(() -> askPassword(f, first, false)); return; }
            catch (IOException | RuntimeException e) { post(() -> { if (first) failOpen(e); else Ui.toastLong(c, "PDF açılamadı: dosya bozuk ya da desteklenmiyor"); }); return; }
            if (destroyed) { PdfEdit.close(s); return; }
            post(() -> {
                sources.add(s);
                if (first) main = s;
                for (int i = 0; i < s.count; i++) pages.add(new PdfEdit.Pg(s, i));
                if (!first) { dirty = true; Ui.toast(c, s.count + " sayfa eklendi"); }
                else if (password != null) Ui.toastLong(c, "Kaydedilen dosya parolasız olur");
                actions.setVisibility(View.VISIBLE);
                refresh();
            });
        });
    }

    private void askPassword(File f, boolean first, boolean wrong) {
        if (first) show(Ui.state(c, Icon.Kind.KEY, "Parolalı PDF", "Bu belge parolayla korunuyor.", "Parola gir", () -> askPassword(f, true, false)));
        if (first) header.subtitle.setText("Parola gerekiyor");
        Sheet.input(c, wrong ? "Parola yanlış, yeniden dene" : "PDF parolası", "Parola", "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "Aç", pw -> {
                if (pw.isEmpty()) return "Parolayı yaz";
                if (unlocking) return null;   // the editor action can fire twice (key down and up)
                unlocking = true;
                if (first) show(Ui.busy(c, "Açılıyor…", null));
                c.io.execute(() -> {
                    File open = newFile(".pdf");
                    try { PdfEdit.decrypt(f, pw, open, dir); }
                    catch (InvalidPasswordException e) { open.delete(); post(() -> { unlocking = false; askPassword(f, first, true); }); return; }
                    catch (IOException | RuntimeException | OutOfMemoryError e) {
                        open.delete();
                        post(() -> { unlocking = false; if (first) failOpen(new IOException(e)); else Ui.toastLong(c, "PDF açılamadı"); });
                        return;
                    }
                    post(() -> unlocking = false);
                    openSource(open, first, pw);
                });
                return null;
            });
    }

    // ---- picking more pages -------------------------------------------------------------------

    private void pick(Intent i, int req) {
        if (busy) return;
        try { c.a.startActivityForResult(i, req); }
        catch (ActivityNotFoundException e) { Ui.toast(c, "Seçici açılamadı"); }
    }

    @Override public void onActivityResult(int req, int result, Intent data) {
        if (result != android.app.Activity.RESULT_OK || data == null) return;
        if (req == REQ_MERGE && data.getData() != null) {
            Uri u = data.getData();
            c.io.execute(() -> {
                File f;
                try { f = local(u); }
                catch (IOException | RuntimeException e) { post(() -> Ui.toastLong(c, "PDF okunamadı")); return; }
                openSource(f, false, null);
            });
        } else if (req == REQ_IMAGES) {
            List<Uri> uris = PhotoPick.uris(data);
            if (uris.isEmpty()) return;
            c.io.execute(() -> {
                List<File> got = new ArrayList<>();
                for (Uri u : uris) {
                    File f = newFile(".img");
                    try (InputStream in = c.a.getContentResolver().openInputStream(u)) {
                        if (in == null) throw new IOException();
                        copy(in, f);
                        got.add(f);
                    } catch (IOException | RuntimeException e) { f.delete(); }
                }
                int failed = uris.size() - got.size();
                post(() -> {
                    for (File f : got) pages.add(new PdfEdit.Pg(f));
                    if (!got.isEmpty()) dirty = true;
                    Ui.toast(c, failed > 0 ? failed + " görüntü okunamadı" : got.size() + " görüntü eklendi");
                    refresh();
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                });
            });
        }
    }

    // ---- grid ---------------------------------------------------------------------------------

    private void refresh() {
        if (dragging != null || main == null) return;   // views stay put while a page is dragged
        int n = pages.size();
        header.subtitle.setText(n == 0 ? "Sayfa kalmadı" : n + " sayfa · sıralamak için basılı tutup sürükle");
        int y = scroll.getScrollY();
        list.removeAllViews();
        tiles.clear();
        thumbs.clear();
        if (n == 0) {
            show(Ui.state(c, Icon.Kind.DOC, "Sayfa kalmadı", "PDF ya da görüntü ekleyebilirsin.", null, null));
            return;
        }
        if (scroll.getParent() == null) show(scroll);
        for (int i = 0; i < n; i += COLS) {
            LinearLayout row = new LinearLayout(c.a);
            for (int j = 0; j < COLS; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (j > 0) lp.setMarginStart(c.dp(8));
                View v = i + j < n ? tile(i + j) : new Space(c.a);
                if (i + j < n) tiles.add(v);
                row.addView(v, lp);
            }
            list.addView(row, Ui.lp(-1, -2, 0, i == 0 ? 0 : c.dp(8), 0, 0));
        }
        scroll.post(() -> { scroll.scrollTo(0, y); bind(); });
    }

    private View tile(int i) {
        Palette p = c.p;
        PdfEdit.Pg pg = pages.get(i);
        LinearLayout card = new LinearLayout(c.a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6));
        Ui.surface(c, card, p.surface, 16, true);
        FrameLayout th = new FrameLayout(c.a) {   // 3:4, like a page
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 4 / 3, MeasureSpec.EXACTLY));
            }
        };
        th.setBackground(Ui.rounded(c, p.surface2, 10));
        final float r = c.dp(10);
        th.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        th.setClipToOutline(true);
        ImageView iv = new ImageView(c.a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (pg.thumb != null) iv.setImageBitmap(pg.thumb);
        th.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (pg.broken) th.addView(Ui.icon(c, Icon.Kind.IMAGE, p.danger, 24), new FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER));
        thumbs.add(iv);
        th.setContentDescription("Sayfa " + (i + 1) + ". Taşımak için basılı tutup sürükle");
        th.setOnLongClickListener(v -> startDrag(card, pg));
        card.addView(th, new LinearLayout.LayoutParams(-1, -2));

        String st = pg.image != null ? "Görüntü" : sources.size() > 1 && pg.src != main ? "Eklenen PDF" : "Sayfa " + (pg.index + 1);
        TextView cap = Ui.text(c, (i + 1) + " · " + st, 12, p.muted, 500);
        cap.setSingleLine(true);
        cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(cap, Ui.lp(-1, -2, c.dp(2), c.dp(6), c.dp(2), c.dp(6)));

        LinearLayout tools = new LinearLayout(c.a);
        tools.addView(tool(Icon.Kind.REFRESH, "Döndür", false, () -> { pg.rot = (pg.rot + 90) % 360; dirty = true; bind(); }));
        View del = tool(Icon.Kind.TRASH, confirmDelete == pg ? "Silmeyi onayla" : "Sil", true, () -> delete(pg));
        if (confirmDelete == pg) del.setBackground(Ui.rounded(c, p.dangerSoft, 10));
        tools.addView(del);
        card.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View tool(Icon.Kind k, String desc, boolean gap, Runnable action) {
        FrameLayout f = new FrameLayout(c.a);
        Ui.surface(c, f, c.p.surface2, 10, false);
        f.addView(Ui.icon(c, k, "Silmeyi onayla".equals(desc) ? c.p.danger : c.p.ink, 18), new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(v -> { if (busy) return; Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); action.run(); });
        Ui.press(f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, c.dp(36), 1);
        if (gap) lp.setMarginStart(c.dp(4));
        f.setLayoutParams(lp);
        return f;
    }

    private void delete(PdfEdit.Pg pg) {
        if (confirmDelete != pg) {
            confirmDelete = pg;
            refresh();
            c.ui.postDelayed(() -> { if (confirmDelete == pg) { confirmDelete = null; if (!destroyed) refresh(); } }, 3000);
            return;
        }
        confirmDelete = null;
        pages.remove(pg);
        pg.thumb = null;
        dirty = true;
        refresh();
    }

    /**
     * Previews only for the tiles on screen (plus a screen above and below); the others drop theirs, so a long PDF keeps
     * a few dozen small bitmaps at most.
     */
    private void bind() {
        if (destroyed || tiles.size() != pages.size()) return;
        int top = scroll.getScrollY(), h = Math.max(scroll.getHeight(), c.dp(600));
        for (int i = 0; i < tiles.size(); i++) {
            PdfEdit.Pg pg = pages.get(i);
            View row = (View) tiles.get(i).getParent();
            boolean near = row.getBottom() >= top - h && row.getTop() <= top + 2 * h;
            ImageView iv = thumbs.get(i);
            if (!near) {
                if (pg.thumb != null) { pg.thumb = null; pg.thumbRot = -1; iv.setImageDrawable(null); }
                continue;
            }
            if (pg.thumb != null && pg.thumbRot == pg.rot) { if (iv.getDrawable() == null) iv.setImageBitmap(pg.thumb); }
            else if (!pg.queued && !pg.broken) render(pg);
        }
    }

    private void render(PdfEdit.Pg pg) {
        pg.queued = true;
        int rot = pg.rot;
        rt.execute(() -> {
            Bitmap b = null;
            boolean bad = false;
            if (!destroyed) {
                try { b = PdfEdit.thumb(pg, rot, THUMB_PX); }
                catch (Exception | OutOfMemoryError e) { bad = true; }
            }
            Bitmap out = b;
            boolean failed = bad;
            post(() -> {
                pg.queued = false;
                if (failed) { pg.broken = true; refresh(); return; }
                if (out == null) return;
                pg.thumb = out; pg.thumbRot = rot;
                int i = pages.indexOf(pg);
                if (i >= 0 && i < thumbs.size()) thumbs.get(i).setImageBitmap(out);
                bind();   // rotated again meanwhile, or scrolled on
            });
        });
    }

    // ---- drag to reorder (as on the scanner's page grid) --------------------------------------

    private boolean startDrag(View card, PdfEdit.Pg pg) {
        if (busy || dragging != null || pages.size() < 2 || !pages.contains(pg)) return false;
        if (!card.startDragAndDrop(null, new View.DragShadowBuilder(card), pg, 0)) return false;
        Ui.haptic(card, HapticFeedbackConstants.LONG_PRESS);
        confirmDelete = null;
        dragging = pg;
        card.setAlpha(0.35f);
        return true;
    }

    private boolean onDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof PdfEdit.Pg)) return false;
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX = e.getX(); dragY = e.getY();
                hover(dragX, dragY);
                int zone = c.dp(64), h = scroll.getHeight();
                int d = dragY < zone ? -1 : dragY > h - zone ? 1 : 0;
                if (d != edgeDir) { edgeDir = d; c.ui.removeCallbacks(edgeTick); if (d != 0) c.ui.post(edgeTick); }
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                edgeDir = 0;
                break;
            case DragEvent.ACTION_DROP:
                int from = pages.indexOf(dragging), to = tiles.indexOf(dropTarget);
                if (from >= 0 && to >= 0 && from != to && to < pages.size()) { pages.add(to, pages.remove(from)); dirty = true; }
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                if (dragging != null) {
                    dragging = null; dropTarget = null; edgeDir = 0;
                    c.ui.removeCallbacks(edgeTick);
                    if (!destroyed) refresh();
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void hover(float x, float y) {
        android.graphics.Rect rc = new android.graphics.Rect();
        int cy = Math.round(y) + scroll.getScrollY();
        View hit = null;
        for (View t : tiles) {
            t.getDrawingRect(rc);
            scroll.offsetDescendantRectToMyCoords(t, rc);
            if (rc.contains(Math.round(x), cy)) { hit = t; break; }
        }
        if (hit == null) return;
        int self = pages.indexOf(dragging);
        if (self >= 0 && self < tiles.size() && tiles.get(self) == hit) hit = null;
        if (hit == dropTarget) return;
        if (dropTarget != null) dropTarget.setForeground(null);
        dropTarget = hit;
        if (hit != null) {
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setCornerRadius(c.dp(16));
            ring.setStroke(c.dp(3), c.p.primary);
            hit.setForeground(ring);
            Ui.haptic(hit, HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    // ---- saving -------------------------------------------------------------------------------

    /** The original as a local file, when it is one outside the app's own folders (else null). */
    private File originalFile() {
        if (!"file".equals(uri.getScheme())) return null;
        File f = new File(uri.getPath());
        String own = c.a.getApplicationInfo().dataDir;
        File ext = c.a.getExternalCacheDir();
        if (f.getPath().startsWith(own + "/") || ext != null && f.getPath().startsWith(ext.getParent() + "/")) return null;
        return f;
    }

    private String outName() {
        String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        return (base.endsWith(SUFFIX) ? base : base + SUFFIX) + ".pdf";
    }

    private void saveMenu() {
        if (busy || main == null) return;
        if (pages.isEmpty()) { Ui.toast(c, "Kaydedilecek sayfa yok"); return; }
        File orig = originalFile();
        boolean nextTo = orig != null && orig.getParentFile() != null && orig.getParentFile().canWrite();
        boolean canOverwrite = nextTo || "content".equals(uri.getScheme());
        Sheet s = new Sheet(c, "Kaydet");
        s.text("Yeni dosya: " + outName() + (nextTo ? " (aynı klasöre)" : " (Documents/Kolay)"));
        s.row("Yeni dosya olarak kaydet", Icon.Kind.DOWNLOAD, false, () -> save(false));
        if (canOverwrite) s.row("Asıl dosyanın üzerine yaz", Icon.Kind.DOC, true, () ->
            Sheet.confirm(c, "Asıl dosyanın üzerine yazılsın mı?", "“" + name + "” değiştirilir. Bu geri alınamaz.", "Üzerine yaz", true, () -> save(true)));
        s.show();
    }

    private void save(boolean overwrite) {
        busy = true;
        List<PdfEdit.Job> jobs = new ArrayList<>();
        for (PdfEdit.Pg p : pages) jobs.add(new PdfEdit.Job(p));
        Sheet prog = new Sheet(c, "Kaydediliyor");
        prog.d.setCancelable(false);
        TextView line = prog.text("0 / " + jobs.size() + " sayfa");
        Bar bar = new Bar(c.a, c.p.track, c.p.primary);
        prog.body.addView(bar, Ui.lp(-1, c.dp(6), 0, 0, 0, c.dp(12)));
        prog.show();
        File orig = originalFile();
        ContentResolver cr = c.a.getContentResolver();
        c.io.execute(() -> {
            Uri result = null;
            String where = null, error = null;
            Uri pending = null;
            File part = null;
            try {
                java.util.function.IntConsumer step = k -> post(() -> {
                    bar.set(k / (float) jobs.size());
                    line.setText(k < jobs.size() ? k + " / " + jobs.size() + " sayfa" : "Dosya yazılıyor…");
                });
                if (orig != null && orig.getParentFile() != null && orig.getParentFile().canWrite()) {
                    File target = overwrite ? orig : unique(orig.getParentFile(), outName());
                    part = new File(orig.getParentFile(), "." + target.getName() + ".part");
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(part), 1 << 16)) { PdfEdit.save(main, jobs, o, dir, step); }
                    if (!part.renameTo(target)) throw new IOException("rename");
                    part = null;
                    android.media.MediaScannerConnection.scanFile(c.a, new String[]{target.getPath()}, null, null);
                    result = Uri.fromFile(target);
                    where = target.getParent().replaceFirst("^/storage/emulated/0/?", "") + "/" + target.getName();
                } else if (overwrite) {
                    part = newFile(".pdf");
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(part), 1 << 16)) { PdfEdit.save(main, jobs, o, dir, step); }
                    try (InputStream in = new FileInputStream(part); OutputStream o = cr.openOutputStream(uri, "wt")) {
                        if (o == null) throw new FileNotFoundException();
                        byte[] b = new byte[1 << 16];
                        for (int r; (r = in.read(b)) > 0; ) o.write(b, 0, r);
                    }
                    result = uri;
                    where = name;
                } else {
                    Object[] r = MediaOut.newPdf(cr, outName());
                    pending = (Uri) r[0];
                    try (OutputStream o = new BufferedOutputStream(MediaOut.open(cr, pending), 1 << 16)) { PdfEdit.save(main, jobs, o, dir, step); }
                    MediaOut.publish(cr, pending);
                    result = pending;
                    pending = null;
                    where = r[1] + "/" + displayName(result);
                }
            } catch (SecurityException | FileNotFoundException e) {
                error = overwrite ? "Asıl dosyaya yazma izni yok. Yeni dosya olarak kaydedebilirsin." : "Dosya yazılamadı.";
            } catch (OutOfMemoryError e) {
                error = "Bellek yetmedi. Daha az sayfayla ya da daha küçük görüntülerle dene.";
            } catch (IOException | RuntimeException e) {
                error = "Kaydedilemedi: dosya yazılamadı ya da bir sayfa okunamadı.";
            } finally {
                if (part != null) part.delete();
                MediaOut.discard(cr, pending);
            }
            Uri done = result;
            String msg = error != null ? error : "Kaydedildi: " + where;
            c.ui.post(() -> {
                prog.dismiss();
                busy = false;
                if (destroyed) return;
                Ui.toastLong(c, msg);
                if (done == null) return;
                dirty = false;
                c.nav.replace(new PdfEditScreen(done));   // continue on the saved file
            });
        });
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        String base = name.substring(0, name.length() - 4);
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ").pdf");
        return f;
    }

    // ---- leaving ------------------------------------------------------------------------------

    private void leave() {
        if (busy) return;
        if (!dirty) { c.nav.pop(); return; }
        Sheet.confirm(c, "Değişiklikler kaydedilmedi", "Çıkarsan sayfalarda yaptığın değişiklikler kaybolur.", "Çık", true, () -> { dirty = false; c.nav.pop(); });
    }

    @Override public boolean onBack() { leave(); return true; }

    @Override public void onDestroy() {
        destroyed = true;
        c.ui.removeCallbacks(edgeTick);
        for (PdfEdit.Pg p : pages) p.thumb = null;
        List<PdfEdit.Source> open = new ArrayList<>(sources);
        File d = dir;
        rt.execute(() -> {
            for (PdfEdit.Source s : open) PdfEdit.close(s);
            Host.deleteTree(d);
        });
        rt.shutdown();
    }
}
