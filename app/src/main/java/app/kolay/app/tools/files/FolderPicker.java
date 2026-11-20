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
import java.util.function.Consumer;

/** Picks a local folder (folders only; volumes and bookmarks as chips, breadcrumb, "Yeni klasör"). Used by "Kolay'a kaydet". */
final class FolderPicker extends Screen {
    private final String title, okLabel;
    private final Consumer<String> onPick;
    private String path;

    private List<Files.Volume> vols;
    private final List<FileItem> shown = new ArrayList<>();
    private LinearLayout page, chips, crumbs;
    private LinearLayout headSlot;
    private HorizontalScrollView crumbScroll;
    private TextView empty;
    private BaseAdapter adapter;
    private int gen;
    private boolean destroyed;

    FolderPicker(String title, String okLabel, String startDir, Consumer<String> onPick) {
        this.title = title; this.okLabel = okLabel; this.path = startDir; this.onPick = onPick;
    }

    @Override public View create() {
        vols = Files.volumes(c.a);
        if (path == null || Files.volumeOf(vols, path) == null || !new File(path).isDirectory()) path = vols.get(0).path;

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
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) { return row(shown.get(i)); }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> go(shown.get(i).path));
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
        Files.Volume v = Files.volumeOf(vols, path);
        if (v == null || v.path.equals(path)) return false;
        go(FileSource.parent(path));
        return true;
    }

    private void go(String dir) { path = dir; refresh(); }

    private void refresh() {
        Header h = new Header(c, title, FileSource.name(path), () -> { if (!onBack()) c.nav.pop(); });
        headSlot.removeAllViews();
        headSlot.addView(h.view, new LinearLayout.LayoutParams(-1, -2));
        chips();
        crumbs();
        boolean hidden = Files.prefs(c.a).getBoolean("hidden", false);
        String dir = path;
        int g = ++gen;
        c.io.execute(() -> {
            List<FileItem> out = new ArrayList<>();
            try {
                for (FileItem it : LocalSource.INSTANCE.list(dir)) {
                    if (!it.dir || (!hidden && it.hidden()) || protectedDir(it.path)) continue;
                    out.add(it);
                }
            } catch (IOException ignored) { }   // unreadable folder: shown as empty
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            c.ui.post(() -> {
                if (destroyed || g != gen) return;
                shown.clear(); shown.addAll(out);
                adapter.notifyDataSetChanged();
                empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** App-private data folders of other apps: unreadable on Android 11+ and never a sensible target. */
    private static boolean protectedDir(String p) { return p.endsWith("/Android/data") || p.endsWith("/Android/obb"); }

    private View row(FileItem it) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(c.dp(12), 0, c.dp(12), 0);
        r.setBackground(Ui.ripple(c, 14));
        r.addView(Ui.icon(c, Icon.Kind.FOLDER, c.p.primary, 22), new LinearLayout.LayoutParams(c.dp(22), c.dp(22)));
        TextView t = Ui.text(c, it.name, 16, c.p.ink, 500);
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(t, Ui.lp(0, -2, c.dp(16), 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        r.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, c.dp(52)));
        return r;
    }

    private void chips() {
        chips.removeAllViews();
        Files.Volume cur = Files.volumeOf(vols, path);
        for (Files.Volume v : vols) chip(v.label, v.path, v == cur && v.path.equals(path));
        for (String b : Files.bookmarks(c.a)) if (new File(b).isDirectory() && Files.volumeOf(vols, b) != null) chip(FileSource.name(b), b, b.equals(path));
    }

    private void chip(String label, String target, boolean on) {
        TextView t = Ui.text(c, label, 14, on ? c.p.onPrimarySoft : c.p.muted, on ? 600 : 500);
        t.setPadding(c.dp(12), c.dp(7), c.dp(12), c.dp(7));
        t.setSingleLine(true);
        if (on) t.setBackground(Ui.rounded(c, c.p.primarySoft, Ui.R_CHIP));
        else { Ui.surface(c, t, c.p.surface2, Ui.R_CHIP, false); t.setOnClickListener(x -> go(target)); }
        chips.addView(t, Ui.lp(-2, -2, 0, 0, c.dp(8), 0));
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
            else { Ui.surface(c, t, c.p.surface2, 14, false); t.setOnClickListener(x -> go(target)); }
            crumbs.addView(t);
            if (!last) crumbs.addView(Ui.icon(c, Icon.Kind.CHEVRON, c.p.muted, 14), Ui.lp(c.dp(14), c.dp(14), c.dp(2), 0, c.dp(2), 0));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void newFolder() {
        Sheet.input(c, "Yeni klasör", "Yeni klasör", "Oluştur", name -> {
            String bad = Ops.badName(name);
            if (bad != null) return bad;
            String to = FileSource.child(path, name);
            try {
                if (LocalSource.INSTANCE.stat(to) != null) return "Bu adda bir öğe zaten var";
                LocalSource.INSTANCE.mkdir(to);
            } catch (IOException e) { return "Klasör oluşturulamadı"; }
            go(to);
            return null;
        });
    }

    private void pick() {
        if (!new File(path).canWrite()) { Ui.toast(c, "Bu klasöre yazılamıyor"); return; }
        String dir = path;
        c.nav.pop();
        onPick.accept(dir);
    }
}
