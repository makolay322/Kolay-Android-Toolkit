package app.kolay.app.tools.files;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.kolay.app.ui.Header;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Sheet;
import app.kolay.app.ui.Ui;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Picks a folder (folders only; volumes and bookmarks as chips, breadcrumb, "Yeni klasör"). Used by "Kolay'a kaydet" and the
 * paste/extract flows. With network mode an "Ağ" chip lists the saved Samba servers and browses their shares through
 * {@link SmbSource}; the result is then a (source, folder) pair. All network calls run off the UI thread.
 */
final class FolderPicker extends Screen {
    private final String title, okLabel;
    private final BiConsumer<FileSource, String> onPick;
    private final boolean network;
    private String path;
    private SmbSource smb;           // browsing this server (null: local)
    private boolean netRoot;         // the "Ağ" chip: the saved servers are listed
    private List<SmbSource.Server> servers = new ArrayList<>();

    private List<Files.Volume> vols;
    private final List<FileItem> shown = new ArrayList<>();
    private LinearLayout page, chips, crumbs;
    private LinearLayout headSlot;
    private HorizontalScrollView crumbScroll;
    private TextView empty;
    private BaseAdapter adapter;
    private int gen;
    private boolean destroyed;

    /** Local folders only. */
    FolderPicker(String title, String okLabel, String startDir, Consumer<String> onPick) {
        this.title = title; this.okLabel = okLabel; this.path = startDir; this.network = false;
        this.onPick = (src, d) -> onPick.accept(d);
    }

    /** With the "Ağ" chip; {@code startSrc} is {@link LocalSource#INSTANCE} or a {@link SmbSource}, {@code startDir} a folder of it. */
    FolderPicker(String title, String okLabel, FileSource startSrc, String startDir, BiConsumer<FileSource, String> onPick) {
        this.title = title; this.okLabel = okLabel; this.path = startDir; this.network = true; this.onPick = onPick;
        if (startSrc instanceof SmbSource) smb = (SmbSource) startSrc;
    }

    @Override public View create() {
        vols = Files.volumes(c.a);
        if (smb != null) { if (path == null) path = "/"; }
        else if (path == null || Files.volumeOf(vols, path) == null || !new File(path).isDirectory()) path = vols.get(0).path;
        if (network && smb == null && !Files.hasAccess(c.a)) { netRoot = true; path = "/"; }   // no local access: the network is the only target

        page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        headSlot = new LinearLayout(c.a);
        page.addView(headSlot, Ui.lp(-1, -2));

        HorizontalScrollView chipScroll = new HorizontalScrollView(c.a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(c.a);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        chipScroll.addView(chips);
        page.addView(chipScroll, Ui.lp(-1, c.dp(40), 0, 0, 0, c.dp(4)));

        crumbScroll = new HorizontalScrollView(c.a);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(c.a);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        crumbScroll.addView(crumbs);
        page.addView(crumbScroll, Ui.lp(-1, c.dp(40), 0, 0, 0, c.dp(4)));

        android.widget.FrameLayout content = new android.widget.FrameLayout(c.a);
        ListView list = new ListView(c.a);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(c.dp(Ui.GUTTER - 4), c.dp(4), c.dp(Ui.GUTTER - 4), c.dp(24));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return netRoot ? servers.size() : shown.size(); }
            @Override public Object getItem(int i) { return netRoot ? servers.get(i) : shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                return netRoot ? row(servers.get(i).name, Icon.Kind.NETSCAN) : row(shown.get(i).name, Icon.Kind.FOLDER);
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> { if (netRoot) goServer(servers.get(i)); else go(shown.get(i).path); });
        content.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(c, "Alt klasör yok", 14, c.p.muted, 400);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        content.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = Ui.bottomBar(c);
        TextView mk = Ui.button(c, "Yeni klasör", Ui.Style.TONAL, Icon.Kind.PLUS);
        mk.setOnClickListener(v -> newFolder());
        TextView ok = Ui.button(c, okLabel, Ui.Style.PRIMARY, Icon.Kind.CHECK);
        ok.setOnClickListener(v -> pick());
        bar.addView(Ui.pair(c, mk, ok, Ui.H_BTN));
        page.addView(bar, Ui.lp(-1, -2));

        chips();
        refresh();
        return page;
    }

    @Override public void onDestroy() { destroyed = true; }

    /** Up one folder until the volume root; then the screen closes. */
    @Override public boolean onBack() {
        if (netRoot) return false;
        if (smb != null) {
            if (path.equals("/")) { smb = null; netRoot = true; refresh(); }
            else go(FileSource.parent(path));
            return true;
        }
        Files.Volume v = Files.volumeOf(vols, path);
        if (v == null || v.path.equals(path)) return false;
        go(FileSource.parent(path));
        return true;
    }

    private void go(String dir) { path = dir; netRoot = false; refresh(); }

    private void goLocal(String dir) { smb = null; go(dir); }

    private void goNet() { smb = null; netRoot = true; path = "/"; refresh(); }

    private void goServer(SmbSource.Server sv) { smb = SmbServers.source(sv); netRoot = false; path = "/"; refresh(); }

    private String subtitle() { return netRoot ? "Ağ" : smb != null ? (path.equals("/") ? smb.server.name : FileSource.name(path)) : FileSource.name(path); }

    private void refresh() {
        Header h = new Header(c, title, subtitle(), () -> { if (!onBack()) c.nav.pop(); });
        headSlot.removeAllViews();
        headSlot.addView(h.view, new LinearLayout.LayoutParams(-1, -2));
        chips();
        crumbs();
        boolean hidden = Files.prefs(c.a).getBoolean("hidden", false);
        String dir = path;
        int g = ++gen;
        shown.clear(); servers = new ArrayList<>();
        if (netRoot) {
            servers = SmbServers.all(c);
            adapter.notifyDataSetChanged();
            empty.setText("Kayıtlı sunucu yok. Dosyalar sayfasındaki Ağ bölümünden ekleyebilirsin.");
            empty.setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);
            return;
        }
        FileSource from = smb != null ? smb : LocalSource.INSTANCE;
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.GONE);
        c.io.execute(() -> {
            List<FileItem> out = new ArrayList<>();
            String err = null;
            try {
                for (FileItem it : from.list(dir)) {
                    if (!it.dir || (!hidden && it.hidden()) || protectedDir(it.path)) continue;
                    out.add(it);
                }
            } catch (IOException e) { if (from != LocalSource.INSTANCE) err = e.getMessage() == null ? "Sunucuya bağlanılamadı" : e.getMessage(); }   // local: unreadable folder shown as empty
            catch (RuntimeException e) { if (from != LocalSource.INSTANCE) err = "Sunucuya bağlanılamadı"; }
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            String fe = err;
            c.ui.post(() -> {
                if (destroyed || g != gen) return;
                shown.clear(); shown.addAll(out);
                adapter.notifyDataSetChanged();
                empty.setText(fe != null ? fe : "Alt klasör yok");
                empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** App-private data folders of other apps: unreadable on Android 11+ and never a sensible target. */
    private static boolean protectedDir(String p) { return p.endsWith("/Android/data") || p.endsWith("/Android/obb"); }

    private View row(String label, Icon.Kind icon) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(c.dp(12), 0, c.dp(12), 0);
        r.setBackground(Ui.ripple(c, 14));
        r.addView(Ui.icon(c, icon, c.p.primary, 22), new LinearLayout.LayoutParams(c.dp(22), c.dp(22)));
        TextView t = Ui.text(c, label, 16, c.p.ink, 500);
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(t, Ui.lp(0, -2, c.dp(16), 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        r.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, c.dp(52)));
        return r;
    }

    private void chips() {
        chips.removeAllViews();
        boolean local = smb == null && !netRoot;
        Files.Volume cur = Files.volumeOf(vols, path);
        for (Files.Volume v : vols) chip(v.label, local && v == cur && v.path.equals(path), () -> goLocal(v.path));
        for (String b : Files.bookmarks(c.a)) if (new File(b).isDirectory() && Files.volumeOf(vols, b) != null) chip(FileSource.name(b), local && b.equals(path), () -> goLocal(b));
        if (network) chip("Ağ", !local, this::goNet);
    }

    private void chip(String label, boolean on, Runnable go) {
        TextView t = Ui.text(c, label, 14, on ? c.p.onPrimarySoft : c.p.muted, on ? 600 : 500);
        t.setPadding(c.dp(12), c.dp(7), c.dp(12), c.dp(7));
        t.setSingleLine(true);
        if (on) t.setBackground(Ui.rounded(c, c.p.primarySoft, Ui.R_CHIP));
        else { Ui.surface(c, t, c.p.surface2, Ui.R_CHIP, false); t.setOnClickListener(x -> go.run()); }
        chips.addView(t, Ui.lp(-2, -2, 0, 0, c.dp(8), 0));
    }

    private void crumbs() {
        crumbs.removeAllViews();
        Files.Volume v = smb != null || netRoot ? null : Files.volumeOf(vols, path);
        String root = v != null ? v.path : "/";
        List<String[]> parts = new ArrayList<>();
        parts.add(new String[]{netRoot ? "Ağ" : smb != null ? smb.server.name : v != null ? v.label : "/", root});
        if (netRoot) path = "/";
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
            else { Ui.surface(c, t, c.p.surface2, 14, false); t.setOnClickListener(x -> go(target)); }
            crumbs.addView(t);
            if (!last) crumbs.addView(Ui.icon(c, Icon.Kind.CHEVRON, c.p.muted, 14), Ui.lp(c.dp(14), c.dp(14), c.dp(2), 0, c.dp(2), 0));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void newFolder() {
        if (netRoot) { Ui.toast(c, "Önce bir sunucu seç"); return; }
        if (smb != null && path.equals("/")) { Ui.toast(c, "Önce bir paylaşım aç"); return; }
        Sheet.input(c, "Yeni klasör", "Yeni klasör", "Oluştur", name -> {
            String bad = Ops.badName(name);
            if (bad != null) return bad;
            String to = FileSource.child(path, name);
            if (smb != null) {   // network: stat/mkdir off the UI thread, the outcome comes as a toast
                SmbSource sm = smb;
                c.io.execute(() -> {
                    String err = null;
                    try { if (sm.stat(to) != null) err = "Bu adda bir öğe zaten var"; else sm.mkdir(to); }
                    catch (IOException e) { err = e.getMessage() == null ? "Klasör oluşturulamadı" : e.getMessage(); }
                    catch (RuntimeException e) { err = "Klasör oluşturulamadı"; }
                    String fe = err;
                    c.ui.post(() -> { if (destroyed || smb != sm) return; if (fe != null) Ui.toast(c, fe); else go(to); });
                });
                return null;
            }
            try {
                if (LocalSource.INSTANCE.stat(to) != null) return "Bu adda bir öğe zaten var";
                LocalSource.INSTANCE.mkdir(to);
            } catch (IOException e) { return "Klasör oluşturulamadı"; }
            go(to);
            return null;
        });
    }

    private void pick() {
        if (netRoot) { Ui.toast(c, "Önce bir sunucu seç"); return; }
        if (smb != null) {
            if (path.equals("/")) { Ui.toast(c, "Önce bir paylaşım aç"); return; }   // a share is the smallest target
            String dir = path;
            FileSource sm = smb;
            c.nav.pop();
            onPick.accept(sm, dir);
            return;
        }
        if (!new File(path).canWrite()) { Ui.toast(c, "Bu klasöre yazılamıyor"); return; }
        String dir = path;
        c.nav.pop();
        onPick.accept(LocalSource.INSTANCE, dir);
    }
}
