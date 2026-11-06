package app.kolay.app.tools.files;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.kolay.app.ui.Header;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Ui;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Folder browser (or, with a loader, a flat list such as a media category). Folders open in place; back goes to the parent
 * until the volume root. Long-press starts multi-select. Listing, search and operations run off the UI thread.
 */
final class BrowserScreen extends Screen {
    private static final String[] SORTS = {"Ad", "Tarih", "Boyut", "Tür"};

    private final FileSource src;
    private final String rootLabel;                       // a Samba server's name (its "/" lists the shares); null for the phone
    private final String fixedTitle;                      // non-null in list mode
    private final Function<Context, List<FileItem>> loader;
    private String path;

    private List<Files.Volume> vols;
    private List<FileItem> items = new ArrayList<>();
    private final List<FileItem> shown = new ArrayList<>();
    private final LinkedHashSet<String> sel = new LinkedHashSet<>();
    private final Map<String, Integer> scroll = new HashMap<>();
    private int gen;
    private Integer jumpTo = 0;   // list position to show after the next load (new folder); null keeps the current scroll
    private String error;

    private int sort; private boolean asc, grid, hidden;

    // search
    private boolean searching, recursive;
    private String query = "";
    private AtomicBoolean searchStop;
    private final List<FileItem> hits = new ArrayList<>();

    private LinearLayout page, crumbs, searchBar, bottom;
    private FrameLayout headSlot, content;
    private HorizontalScrollView crumbScroll;
    private GridView list;
    private Adapter adapter;
    private TextView stopBtn;

    /** Browse a folder. */
    BrowserScreen(String path) { this(LocalSource.INSTANCE, path, null); }

    /** Browse {@code path} of {@code src}; {@code rootLabel} names a network source's root. */
    BrowserScreen(FileSource src, String path, String rootLabel) { this.src = src; this.path = path; this.rootLabel = rootLabel; this.fixedTitle = null; this.loader = null; }

    /** A flat list (e.g. "Görseller"), loaded off the UI thread. */
    BrowserScreen(String title, Function<Context, List<FileItem>> loader) { this.src = LocalSource.INSTANCE; this.rootLabel = null; this.path = null; this.fixedTitle = title; this.loader = loader; }

    private boolean dirMode() { return loader == null; }

    private boolean local() { return src == LocalSource.INSTANCE; }

    /** A server's share list: shares cannot be selected, deleted or written into. */
    private boolean atServerRoot() { return !local() && "/".equals(path); }

    /** Local paths among {@code paths} (for the media index; network paths are not indexed). */
    private static List<String> localOnly(FileSource s, List<String> paths) { return s == LocalSource.INSTANCE ? paths : new ArrayList<>(); }

    @Override public View create() {
        SharedPreferences pr = Files.prefs(c.a);
        sort = pr.getInt("sort", 0); asc = pr.getBoolean("asc", true); grid = pr.getBoolean("grid", false); hidden = pr.getBoolean("hidden", false);
        vols = local() ? Files.volumes(c.a) : Collections.singletonList(new Files.Volume(rootLabel, "/", 0, 0));

        page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        headSlot = new FrameLayout(c.a);
        page.addView(headSlot, Ui.lp(-1, -2));

        crumbScroll = new HorizontalScrollView(c.a);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(c.a);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        crumbScroll.addView(crumbs);
        crumbScroll.setVisibility(dirMode() ? View.VISIBLE : View.GONE);
        page.addView(crumbScroll, Ui.lp(-1, c.dp(40), 0, 0, 0, c.dp(4)));

        searchBar = buildSearchBar();
        searchBar.setVisibility(View.GONE);
        page.addView(searchBar, Ui.lp(-1, -2));

        content = new FrameLayout(c.a);
        list = new GridView(c.a);
        list.setClipToPadding(false);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        list.setPadding(c.dp(Ui.GUTTER - 4), c.dp(4), c.dp(Ui.GUTTER - 4), c.dp(24));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> tap(shown.get(i)));
        list.setOnItemLongClickListener((p, v, i, id) -> { Ui.haptic(v, HapticFeedbackConstants.LONG_PRESS); toggle(shown.get(i)); return true; });
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        bottom = Ui.bottomBar(c);
        page.addView(bottom, Ui.lp(-1, -2));

        applyLayoutMode();
        header();
        load();
        return page;
    }

    // ------------------------------------------------------------ chrome

    private void header() {
        View trailing;
        LinearLayout t = new LinearLayout(c.a);
        if (!sel.isEmpty()) {
            t.addView(Ui.iconButton(c, Icon.Kind.CHECK, "Tümünü seç", this::selectAll));
            t.addView(gap(Ui.iconButton(c, Icon.Kind.MORE, "Diğer işlemler", this::selectionMenu)));
        } else {
            t.addView(Ui.iconButton(c, Icon.Kind.SEARCH, "Ara", this::openSearch));
            t.addView(gap(Ui.iconButton(c, Icon.Kind.MORE, "Seçenekler", this::folderMenu)));
        }
        trailing = t;
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        String title = !sel.isEmpty() ? sel.size() + " seçildi" : fixedTitle != null ? fixedTitle : folderTitle();
        Header h = new Header(c, title, subtitle(), () -> { if (!onBack()) c.nav.pop(); }, trailing);
        headSlot.removeAllViews();
        headSlot.addView(h.view);
        bottomBar();
        if (dirMode()) crumbs();
    }

    private View gap(View v) { ((LinearLayout.LayoutParams) v.getLayoutParams()).setMarginStart(c.dp(8)); return v; }

    private String folderTitle() {
        Files.Volume v = Files.volumeOf(vols, path);
        return v != null && v.path.equals(path) ? v.label : FileSource.name(path);
    }

    private String subtitle() {
        if (!sel.isEmpty()) { long b = 0; for (FileItem it : shown) if (sel.contains(it.path) && !it.dir) b += it.size; return b > 0 ? Files.size(b) : "Klasörler seçili"; }
        if (searching && recursive) return (searchStop != null && !searchStop.get() ? "Aranıyor… " : "") + hits.size() + " sonuç";
        if (error != null) return "Okunamadı";
        return shown.size() + " öğe" + (searching && !query.isEmpty() ? " (süzülmüş)" : "");
    }

    private void crumbs() {
        crumbs.removeAllViews();
        Files.Volume v = Files.volumeOf(vols, path);
        String root = v != null ? v.path : "/";
        List<String[]> parts = new ArrayList<>();
        parts.add(new String[]{v != null ? v.label : "/", root});
        if (path.length() > root.length()) {
            String cur = root;
            for (String seg : path.substring(root.length()).split("/")) {
                if (seg.isEmpty()) continue;
                cur = FileSource.child(cur, seg);
                parts.add(new String[]{seg, cur});
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            String target = parts.get(i)[1];
            TextView t = Ui.text(c, parts.get(i)[0], 14, last ? c.p.onPrimarySoft : c.p.muted, last ? 600 : 500);
            t.setPadding(c.dp(12), c.dp(7), c.dp(12), c.dp(7));
            t.setSingleLine(true);
            if (last) t.setBackground(Ui.rounded(c, c.p.primarySoft, 14));
            else { Ui.surface(c, t, c.p.surface2, 14, false); t.setOnClickListener(x -> navigate(target)); }
            crumbs.addView(t);
            if (!last) crumbs.addView(Ui.icon(c, Icon.Kind.CHEVRON, c.p.muted, 14), Ui.lp(c.dp(14), c.dp(14), c.dp(2), 0, c.dp(2), 0));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void bottomBar() {
        bottom.removeAllViews();
        if (!sel.isEmpty()) {
            LinearLayout row = new LinearLayout(c.a);
            row.addView(action(Icon.Kind.COPY, "Kopyala", () -> toClip(false)), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(action(Icon.Kind.CUT, "Taşı", () -> toClip(true)), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(action(Icon.Kind.SHARE, "Paylaş", () -> { if (local()) Files.share(c, selected()); else Files.toast(c, "Ağdaki dosyalar doğrudan paylaşılamaz; önce telefona kopyala"); }), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(action(Icon.Kind.TRASH, "Sil", this::askDelete), new LinearLayout.LayoutParams(0, -2, 1));
            bottom.addView(row);
            bottom.setVisibility(View.VISIBLE);
        } else if (!Files.clip.isEmpty() && dirMode() && !atServerRoot()) {
            TextView info = Ui.text(c, Files.clip.size() + " öğe " + (Files.clipCut ? "taşınacak" : "kopyalanacak") + " — hedef klasörü aç", 13, c.p.muted, 500);
            bottom.addView(info, Ui.lp(-1, -2, c.dp(4), 0, 0, c.dp(8)));
            TextView cancel = Ui.button(c, "Vazgeç", Ui.Style.GHOST, null);
            cancel.setOnClickListener(v -> { Files.clip.clear(); bottomBar(); });
            TextView paste = Ui.button(c, "Buraya yapıştır", Ui.Style.PRIMARY, Icon.Kind.PASTE);
            paste.setOnClickListener(v -> paste());
            bottom.addView(Ui.pair(c, cancel, paste, Ui.H_GHOST));
            bottom.setVisibility(View.VISIBLE);
        } else bottom.setVisibility(View.GONE);
    }

    private View action(Icon.Kind k, String label, Runnable r) {
        LinearLayout b = new LinearLayout(c.a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, c.dp(8), 0, c.dp(6));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(c.p.ripple), null, Ui.rounded(c, 0xFFFFFFFF, 14)));
        b.addView(Ui.icon(c, k, c.p.ink, 22));
        b.addView(Ui.text(c, label, 12, c.p.ink, 500), Ui.lp(-2, -2, 0, c.dp(4), 0, 0));
        b.setClickable(true);
        b.setContentDescription(label);
        b.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        return b;
    }

    private void applyLayoutMode() {
        if (grid) { list.setNumColumns(GridView.AUTO_FIT); list.setColumnWidth(c.dp(104)); list.setVerticalSpacing(c.dp(6)); list.setHorizontalSpacing(c.dp(6)); }
        else { list.setNumColumns(1); list.setVerticalSpacing(0); list.setHorizontalSpacing(0); }
        list.setAdapter(adapter);
    }

    // ------------------------------------------------------------ listing

    private void navigate(String to) {
        if (dirMode() && path != null) scroll.put(path, list.getFirstVisiblePosition());
        closeSearch(false);
        sel.clear();
        path = to;
        Integer back = scroll.remove(to);
        jumpTo = back != null ? back : 0;
        header();
        load();
    }

    private void load() {
        int g = ++gen;
        String p = path;
        c.ui.postDelayed(() -> { if (g == gen && items.isEmpty()) showState(Ui.busy(c, "Yükleniyor…", null)); }, 250);
        c.io.execute(() -> {
            List<FileItem> got; String err = null;
            try { got = dirMode() ? src.list(p) : loader.apply(c.a); }
            catch (IOException | RuntimeException e) { got = new ArrayList<>(); err = e.getMessage() == null ? "" : e.getMessage(); }
            List<FileItem> fg = got; String fe = err;
            c.ui.post(() -> {
                if (g != gen) return;
                items = fg; error = fe;
                apply();
                if (jumpTo != null) { list.setSelection(jumpTo); jumpTo = null; }
            });
        });
    }

    /** Filter + sort into {@link #shown} and refresh. */
    private void apply() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT);
        List<FileItem> base = searching && recursive ? hits : items;
        for (FileItem it : base) {
            if (!hidden && it.hidden()) continue;
            if (searching && !recursive && !q.isEmpty() && !it.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(it);
        }
        Collator col = Collator.getInstance(new Locale("tr"));
        col.setStrength(Collator.SECONDARY);
        Comparator<FileItem> byName = (a, b) -> col.compare(a.name, b.name);
        Comparator<FileItem> key;
        switch (sort) {
            case 1: key = Comparator.<FileItem>comparingLong(i -> i.modified).thenComparing(byName); break;
            case 2: key = Comparator.<FileItem>comparingLong(i -> i.size).thenComparing(byName); break;
            case 3: key = Comparator.<FileItem, String>comparing(i -> Files.ext(i.name)).thenComparing(byName); break;
            default: key = byName;
        }
        if (!asc) key = key.reversed();
        Comparator<FileItem> dirsFirst = (a, b) -> Boolean.compare(b.dir, a.dir);
        Collections.sort(shown, dirsFirst.thenComparing(key));
        sel.retainAll(pathsOf(shown));
        adapter.notifyDataSetChanged();
        if (error != null && shown.isEmpty()) showState(local() ? Ui.state(c, Icon.Kind.FOLDER, "Klasör okunamadı", "Bu klasöre erişim yok ya da artık mevcut değil.", null, null)
            : Ui.state(c, Icon.Kind.NETSCAN, "Sunucuya erişilemedi", error.isEmpty() ? "Bağlantı kurulamadı." : error, "Tekrar dene", this::load));
        else if (shown.isEmpty() && !(searching && recursive && searchStop != null && !searchStop.get()))
            showState(Ui.state(c, searching ? Icon.Kind.SEARCH : Icon.Kind.FOLDER, searching ? "Sonuç yok" : "Boş klasör",
                searching ? "Bu ada uyan dosya bulunamadı." : hidden || items.isEmpty() ? null : "Gizli dosyalar var; Seçenekler → Gizli dosyaları göster.", null, null));
        else showState(null);
        header();
    }

    private void showState(View v) {
        while (content.getChildCount() > 1) content.removeViewAt(1);
        list.setVisibility(v == null ? View.VISIBLE : View.INVISIBLE);
        if (v != null) content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private static List<String> pathsOf(List<FileItem> l) { List<String> o = new ArrayList<>(); for (FileItem i : l) o.add(i.path); return o; }

    // ------------------------------------------------------------ taps and selection

    private void tap(FileItem it) {
        if (!sel.isEmpty()) { toggle(it); return; }
        if (it.dir) {
            if (dirMode()) navigate(it.path); else c.nav.push(new BrowserScreen(it.path));
            return;
        }
        if (Files.isZip(it.name)) {
            Sheet.menu(c, it.name, new String[]{"Buraya çıkart", "Birlikte aç"}, new Icon.Kind[]{Icon.Kind.DOWNLOAD, Icon.Kind.LINK},
                k -> { if (k == 0) extract(it); else Files.openWith(c, src, it); });
            return;
        }
        Files.open(c, src, it);
    }

    private void toggle(FileItem it) {
        if (atServerRoot()) { Files.toast(c, "Paylaşımlar seçilemez; birini açıp içinde çalış"); return; }
        if (!sel.remove(it.path)) sel.add(it.path);
        adapter.notifyDataSetChanged();
        header();
    }

    private void selectAll() {
        boolean all = sel.size() == shown.size();
        sel.clear();
        if (!all) for (FileItem it : shown) sel.add(it.path);   // a second tap clears the selection
        adapter.notifyDataSetChanged();
        header();
    }

    private List<FileItem> selected() { List<FileItem> o = new ArrayList<>(); for (FileItem it : shown) if (sel.contains(it.path)) o.add(it); return o; }

    @Override public boolean onBack() {
        if (!sel.isEmpty()) { sel.clear(); adapter.notifyDataSetChanged(); header(); return true; }
        if (searching) { closeSearch(true); return true; }
        if (!dirMode()) return false;
        Files.Volume v = Files.volumeOf(vols, path);
        if (v == null || v.path.equals(path) || path.equals("/")) return false;
        navigate(FileSource.parent(path));
        return true;
    }

    @Override public void onReturn() { if (!searching || !recursive) load(); }

    @Override public void onResume() { if (!searching || !recursive) load(); }

    @Override public void onDestroy() { gen++; if (searchStop != null) searchStop.set(true); }

    // ------------------------------------------------------------ menus

    private void folderMenu() {
        List<String> labels = new ArrayList<>(); List<Icon.Kind> icons = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
        if (dirMode() && !atServerRoot()) { labels.add("Yeni klasör"); icons.add(Icon.Kind.PLUS); acts.add(this::newFolder); }
        labels.add("Sırala: " + SORTS[sort] + (asc ? " ↑" : " ↓")); icons.add(Icon.Kind.SETTINGS); acts.add(this::sortMenu);
        labels.add(grid ? "Liste görünümü" : "Izgara görünümü"); icons.add(grid ? Icon.Kind.LIST : Icon.Kind.GRID);
        acts.add(() -> { grid = !grid; Files.prefs(c.a).edit().putBoolean("grid", grid).apply(); applyLayoutMode(); });
        labels.add(hidden ? "Gizli dosyaları gizle" : "Gizli dosyaları göster"); icons.add(Icon.Kind.LAYERS);
        acts.add(() -> { hidden = !hidden; Files.prefs(c.a).edit().putBoolean("hidden", hidden).apply(); apply(); });
        if (dirMode() && local()) {
            boolean marked = Files.bookmarks(c.a).contains(path);
            labels.add(marked ? "Yer imlerinden çıkar" : "Yer imlerine ekle"); icons.add(Icon.Kind.STAR);
            acts.add(() -> { Files.setBookmark(c.a, path, !marked); Files.toast(c, marked ? "Yer imi kaldırıldı" : "Yer imlerine eklendi"); });
        }
        if (!shown.isEmpty() && !atServerRoot()) { labels.add("Seç"); icons.add(Icon.Kind.CHECK); acts.add(this::selectAll); }
        Sheet.menu(c, null, labels.toArray(new String[0]), icons.toArray(new Icon.Kind[0]), k -> acts.get(k).run());
    }

    private void sortMenu() {
        Sheet s = new Sheet(c, "Sırala");
        for (int i = 0; i < SORTS.length; i++) {
            final int k = i;
            s.row(SORTS[i], i == sort ? Icon.Kind.CHECK : null, false, () -> { sort = k; saveSort(); apply(); });
        }
        s.toggle("Azalan sıra", !asc, v -> { asc = !v; saveSort(); apply(); });
        s.show();
    }

    private void saveSort() { Files.prefs(c.a).edit().putInt("sort", sort).putBoolean("asc", asc).apply(); }

    private void selectionMenu() {
        List<FileItem> s = selected();
        if (s.isEmpty()) return;
        boolean one = s.size() == 1;
        FileItem f = s.get(0);
        List<String> labels = new ArrayList<>(); List<Icon.Kind> icons = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
        if (one) { labels.add("Yeniden adlandır"); icons.add(Icon.Kind.DOC); acts.add(() -> rename(f)); }
        if (one && !f.dir) { labels.add("Birlikte aç"); icons.add(Icon.Kind.LINK); acts.add(() -> Files.openWith(c, src, f)); }
        labels.add("Sıkıştır (ZIP)"); icons.add(Icon.Kind.LAYERS); acts.add(() -> zip(s));
        if (one && !f.dir && Files.isZip(f.name)) { labels.add("Buraya çıkart"); icons.add(Icon.Kind.DOWNLOAD); acts.add(() -> extract(f)); }
        if (one && f.dir && local()) {
            boolean marked = Files.bookmarks(c.a).contains(f.path);
            labels.add(marked ? "Yer imlerinden çıkar" : "Yer imlerine ekle"); icons.add(Icon.Kind.STAR);
            acts.add(() -> { Files.setBookmark(c.a, f.path, !marked); Files.toast(c, marked ? "Yer imi kaldırıldı" : "Yer imlerine eklendi"); });
        }
        if (!dirMode() || (searching && recursive)) { labels.add("Bulunduğu klasörü aç"); icons.add(Icon.Kind.FOLDER); acts.add(() -> openParent(f)); }
        labels.add("Özellikler"); icons.add(Icon.Kind.SETTINGS); acts.add(() -> properties(s));
        Sheet.menu(c, one ? f.name : s.size() + " öğe", labels.toArray(new String[0]), icons.toArray(new Icon.Kind[0]), k -> acts.get(k).run());
    }

    private void openParent(FileItem f) {
        String dir = FileSource.parent(f.path);
        sel.clear();
        if (dirMode()) navigate(dir); else { header(); c.nav.push(new BrowserScreen(dir)); }
    }

    // ------------------------------------------------------------ operations

    private String workDir() { return dirMode() ? path : FileSource.parent(selected().get(0).path); }

    private void done() { sel.clear(); header(); load(); }

    private void toClip(boolean cut) {
        Files.clip.clear();
        Files.clip.addAll(pathsOf(selected()));
        Files.clipCut = cut;
        Files.clipSrc = src;
        sel.clear();
        adapter.notifyDataSetChanged();
        header();
        Files.toast(c, Files.clip.size() + " öğe seçildi — hedef klasörde “Buraya yapıştır”a dokun");
    }

    private void paste() {
        List<String> what = new ArrayList<>(Files.clip);
        boolean cut = Files.clipCut;
        FileSource from = Files.clipSrc;
        String to = path;
        Files.clip.clear();
        bottomBar();
        Jobs.run(c, cut ? "Taşınıyor" : "Kopyalanıyor", cut ? "taşındı" : "kopyalandı",
            (p, r) -> Ops.transfer(from, what, src, to, cut, r, p),
            () -> {
                List<String> changed = new ArrayList<>(localOnly(from, what));
                changed.addAll(localOnly(src, Collections.singletonList(to)));
                Files.rescan(c.a, changed);
                done();
            });
    }

    private void askDelete() {
        List<String> what = pathsOf(selected());
        String msg = what.size() == 1 ? "“" + FileSource.name(what.get(0)) + "” kalıcı olarak silinecek." : what.size() + " öğe kalıcı olarak silinecek.";
        Sheet.confirm(c, "Silinsin mi?", msg + " Bu işlem geri alınamaz (çöp kutusu yok).", "Sil", true,
            () -> Jobs.run(c, "Siliniyor", "silindi", (p, r) -> Ops.delete(src, what, p), () -> { Files.rescan(c.a, localOnly(src, what)); done(); }));
    }

    private interface NetCall { String run() throws IOException; }

    /**
     * Runs a small file-system step that answers with an error text (null = done). On the phone it runs right here, so the
     * sheet can show the error; on a network source it runs off the UI thread and a failure is shown as a toast.
     * @return the error for the sheet, or null (also when the step was handed to the background)
     */
    private String step(NetCall call, String fail, Runnable ok) {
        if (local()) {
            String err;
            try { err = call.run(); } catch (IOException e) { err = fail; }
            if (err == null) ok.run();
            return err;
        }
        c.io.execute(() -> {
            String err;
            try { err = call.run(); } catch (IOException e) { err = fail + ": " + e.getMessage(); }
            String fe = err;
            c.ui.post(() -> { if (fe != null) Files.toast(c, fe); else ok.run(); });
        });
        return null;
    }

    private void rename(FileItem f) {
        Sheet.input(c, "Yeniden adlandır", f.name, "Kaydet", name -> {
            String bad = Ops.badName(name);
            if (bad != null) return bad;
            if (name.equals(f.name)) return null;
            String to = FileSource.child(FileSource.parent(f.path), name);
            return step(() -> {
                if (src.stat(to) != null) return "Bu adda bir öğe zaten var";
                src.rename(f.path, to);
                return null;
            }, "Yeniden adlandırılamadı", () -> {
                List<String> changed = new ArrayList<>(); changed.add(f.path); changed.add(to);
                Files.rescan(c.a, localOnly(src, changed));
                done();
            });
        });
    }

    private void newFolder() {
        Sheet.input(c, "Yeni klasör", "Yeni klasör", "Oluştur", name -> {
            String bad = Ops.badName(name);
            if (bad != null) return bad;
            String to = FileSource.child(path, name);
            return step(() -> {
                if (src.stat(to) != null) return "Bu adda bir öğe zaten var";
                src.mkdir(to);
                return null;
            }, "Klasör oluşturulamadı", this::load);
        });
    }

    private void zip(List<FileItem> s) {
        String dir = workDir();
        String base = s.size() == 1 ? s.get(0).name.replaceFirst("\\.[^.]+$", "") : (dirMode() ? folderTitle() : "Arşiv");
        List<String> what = pathsOf(s);
        unique(dir, base + ".zip", suggestion -> Sheet.input(c, "ZIP olarak sıkıştır", suggestion, "Sıkıştır", name -> {
            String n = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
            String bad = Ops.badName(n);
            if (bad != null) return bad;
            String to = FileSource.child(dir, n);
            if (local()) { try { if (src.stat(to) != null) return "Bu adda bir dosya zaten var"; } catch (IOException e) { return "Klasör okunamadı"; } }
            // network: the final rename refuses an existing name, so nothing is overwritten
            Jobs.run(c, "Sıkıştırılıyor", "sıkıştırıldı", (p, r) -> Ops.zip(src, what, to, p),
                () -> { Files.rescan(c.a, localOnly(src, Collections.singletonList(to))); done(); });
            return null;
        }));
    }

    /** {@link Ops#uniqueName} off the UI thread for network sources; {@code then} gets the name (or {@code name} on errors) on the UI thread. */
    private void unique(String dir, String name, java.util.function.Consumer<String> then) {
        if (local()) {
            String u;
            try { u = Ops.uniqueName(src, dir, name); } catch (IOException e) { u = name; }
            then.accept(u);
            return;
        }
        c.io.execute(() -> {
            String u;
            try { u = Ops.uniqueName(src, dir, name); } catch (IOException e) { u = name; }
            String fu = u;
            c.ui.post(() -> then.accept(fu));
        });
    }

    private void extract(FileItem f) {
        String dir = FileSource.parent(f.path);
        unique(dir, f.name.replaceFirst("(?i)\\.zip$", ""), name -> {
            String target = FileSource.child(dir, name);
            Jobs.run(c, "Çıkartılıyor", "çıkartıldı", (p, r) -> Ops.unzip(src, f.path, target, p), () -> {
                sel.clear();
                if (dirMode() && path.equals(dir)) navigate(target); else { header(); load(); }
            });
        });
    }

    private void properties(List<FileItem> s) {
        Sheet sh = new Sheet(c, s.size() == 1 ? s.get(0).name : s.size() + " öğe");
        FileItem f = s.get(0);
        boolean anyDir = false; long bytes = 0;
        for (FileItem it : s) { anyDir |= it.dir; bytes += it.size; }
        StringBuilder b = new StringBuilder();
        if (s.size() == 1) {
            b.append("Tür: ").append(f.dir ? "Klasör" : Files.mime(f.name)).append('\n');
            b.append("Konum: ").append(where(FileSource.parent(f.path))).append('\n');
            b.append("Değiştirilme: ").append(Files.date(f.modified)).append('\n');
        } else b.append("Konum: ").append(where(FileSource.parent(f.path))).append('\n');
        String head = b.toString();
        TextView t = sh.text(head + "Boyut: " + (anyDir ? "hesaplanıyor…" : Files.size(bytes) + " (" + bytes + " bayt)"));
        t.setTextIsSelectable(true);
        AtomicBoolean stop = new AtomicBoolean();
        sh.d.setOnDismissListener(d -> stop.set(true));
        if (anyDir) {
            List<String> what = pathsOf(s);
            c.io.execute(() -> {
                long[] tot = new long[3];
                try {
                    for (String p : what) { long[] m = Ops.measure(src, p, stop::get); for (int i = 0; i < 3; i++) tot[i] += m[i]; }
                } catch (IOException e) { return; }
                String txt = head + "Boyut: " + Files.size(tot[0]) + " (" + tot[0] + " bayt)\nİçerik: " + tot[1] + " dosya, " + Math.max(0, tot[2] - countDirs(s)) + " klasör";
                c.ui.post(() -> { if (!stop.get()) t.setText(txt); });
            });
        }
        sh.show();
    }

    private String where(String p) { return src instanceof SmbSource ? "smb://" + ((SmbSource) src).server.host + (p.equals("/") ? "" : p) : p; }

    private static int countDirs(List<FileItem> s) { int n = 0; for (FileItem it : s) if (it.dir) n++; return n; }

    // ------------------------------------------------------------ search

    private LinearLayout buildSearchBar() {
        LinearLayout bar = new LinearLayout(c.a);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(6));
        EditText e = Ui.input(c, "Bu klasörde ara", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        bar.addView(e, Ui.lp(-1, -2));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                if (!recursive) apply();
            }
        });
        e.setOnEditorActionListener((v, id, ev) -> { if (recursive) runSearch(); hideKeyboard(v); return true; });
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = Ui.text(c, "Alt klasörlerde de ara", 14, c.p.ink, 500);
        row.addView(lab, new LinearLayout.LayoutParams(0, -2, 1));
        stopBtn = Ui.button(c, "Durdur", Ui.Style.GHOST, null);
        stopBtn.setTextSize(14);
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(v -> stopSearch());
        row.addView(stopBtn, new LinearLayout.LayoutParams(-2, c.dp(40)));
        app.kolay.app.ui.Toggle tg = new app.kolay.app.ui.Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, false);
        tg.onChange(v -> { recursive = v; stopSearch(); hits.clear(); if (v && !query.isEmpty()) runSearch(); else apply(); });
        row.addView(tg, new LinearLayout.LayoutParams(c.dp(52), c.dp(32)));
        bar.addView(row, Ui.lp(-1, c.dp(44), c.dp(4), c.dp(4), 0, 0));
        bar.setTag(e);
        return bar;
    }

    private void openSearch() {
        if (searching) return;
        searching = true;
        searchBar.setVisibility(View.VISIBLE);
        EditText e = (EditText) searchBar.getTag();
        e.setHint(dirMode() ? "Bu klasörde ara" : "Listede ara");
        searchBar.getChildAt(1).setVisibility(dirMode() ? View.VISIBLE : View.GONE);
        e.requestFocus();
        android.view.inputmethod.InputMethodManager im = c.a.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.showSoftInput(e, 0);
        apply();
    }

    private void closeSearch(boolean refresh) {
        if (!searching) return;
        stopSearch();
        searching = false; query = ""; hits.clear();
        EditText e = (EditText) searchBar.getTag();
        e.setText("");
        hideKeyboard(e);
        searchBar.setVisibility(View.GONE);
        if (refresh) apply();
    }

    private void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = c.a.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void runSearch() {
        stopSearch();
        hits.clear();
        if (query.isEmpty()) { apply(); return; }
        AtomicBoolean stop = new AtomicBoolean();
        searchStop = stop;
        stopBtn.setVisibility(View.VISIBLE);
        String q = query, dir = path;
        boolean hid = hidden;
        List<FileItem> buf = new ArrayList<>();
        long[] lastPost = {0};
        Runnable flush = () -> {
            if (stop.get() && searchStop != stop) return;
            synchronized (buf) { hits.addAll(buf); buf.clear(); }
            apply();
        };
        apply();
        c.io.execute(() -> {
            Ops.search(src, dir, q, true, hid, it -> {
                synchronized (buf) { buf.add(it); }
                long now = System.currentTimeMillis();
                if (now - lastPost[0] > 300) { lastPost[0] = now; c.ui.post(flush); }
            }, () -> stop.get() || hits.size() + buf.size() >= 5000);
            c.ui.post(() -> {
                if (searchStop != stop) return;
                stop.set(true);
                stopBtn.setVisibility(View.GONE);
                flush.run();
            });
        });
    }

    private void stopSearch() {
        if (searchStop != null) searchStop.set(true);
        searchStop = null;
        if (stopBtn != null) stopBtn.setVisibility(View.GONE);
        header();
    }

    // ------------------------------------------------------------ rows / cells

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return grid ? 1 : 0; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            Item h = v instanceof Item && ((Item) v).grid == grid ? (Item) v : new Item(grid);
            h.bind(shown.get(i));
            return h;
        }
    }

    /** One entry: a row (list) or a tile (grid). */
    private final class Item extends LinearLayout {
        final boolean grid;
        final FrameLayout badge;
        final ImageView icon, thumb;
        final TextView name, sub;

        Item(boolean grid) {
            super(c.a);
            this.grid = grid;
            setOrientation(grid ? VERTICAL : HORIZONTAL);
            setGravity(grid ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            setPadding(c.dp(grid ? 6 : 10), c.dp(grid ? 8 : 8), c.dp(grid ? 6 : 10), c.dp(8));
            badge = new FrameLayout(c.a);
            icon = new ImageView(c.a);
            thumb = new ImageView(c.a);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            int s = grid ? 88 : 44;
            badge.addView(icon, new FrameLayout.LayoutParams(c.dp(grid ? 34 : 22), c.dp(grid ? 34 : 22), Gravity.CENTER));
            badge.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            badge.setClipToOutline(true);
            addView(badge, new LayoutParams(c.dp(s), c.dp(s)));
            LinearLayout txt = new LinearLayout(c.a);
            txt.setOrientation(VERTICAL);
            name = Ui.text(c, "", grid ? 13 : 15, c.p.ink, 500);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            if (grid) { name.setMaxLines(2); name.setGravity(Gravity.CENTER); } else name.setSingleLine(true);
            txt.addView(name, new LayoutParams(-1, -2));
            sub = Ui.text(c, "", 12, c.p.muted, 400);
            sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.START);
            if (grid) sub.setVisibility(GONE);
            txt.addView(sub, Ui.lp(-1, -2, 0, c.dp(3), 0, 0));
            LayoutParams tl = grid ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1);
            if (grid) tl.topMargin = c.dp(6); else tl.setMarginStart(c.dp(14));
            addView(txt, tl);
        }

        void bind(FileItem it) {
            boolean on = sel.contains(it.path);
            setBackground(on ? Ui.rounded(c, c.p.primarySoft, 16) : null);
            badge.setBackground(Ui.rounded(c, it.dir ? c.p.softBg(0) : c.p.surface2, grid ? 18 : 12));
            icon.setImageDrawable(new Icon(on ? Icon.Kind.CHECK : Files.icon(it), it.dir ? c.p.softFg(0) : c.p.muted));
            icon.setVisibility(VISIBLE);
            name.setText(it.name);
            name.setAlpha(it.hidden() ? 0.6f : 1f);
            String where = !dirMode() || (searching && recursive) ? shortPath(FileSource.parent(it.path)) : null;
            String meta = it.dir ? Files.date(it.modified) : Files.size(it.size) + " · " + Files.date(it.modified);
            sub.setText(where != null ? where : meta);
            thumb.setImageDrawable(null);
            thumb.setTag(null);
            if (!on && local() && Files.thumbable(it)) Thumbs.load(thumb, it, c.dp(grid ? 88 : 44), () -> icon.setVisibility(INVISIBLE));
        }
    }

    private String shortPath(String p) {
        Files.Volume v = Files.volumeOf(vols, p);
        return v == null ? p : v.label + p.substring(v.path.length());
    }
}
