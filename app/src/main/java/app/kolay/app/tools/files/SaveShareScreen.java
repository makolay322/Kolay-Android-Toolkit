package app.kolay.app.tools.files;

import android.content.Intent;
import android.os.Environment;
import android.os.StatFs;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.kolay.app.ui.Header;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Ui;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Kolay'a kaydet": files (or text) shared from another app are saved into a folder of the phone. The activity stays open while
 * copying: the sender's read grant lives only as long as it does.
 */
public final class SaveShareScreen extends Screen {
    private static final String PREF_DIR = "save_dir", PREF_SRV = "save_srv", PREF_SRV_DIR = "save_srv_dir";

    private final Intent in;
    private List<Ops.Incoming> items;
    private String dir;
    private FileSource src = LocalSource.INSTANCE;   // the destination: the phone or a saved Samba server
    private LinearLayout col, bottom;
    private boolean saved;   // the "Kaydedildi" state is showing
    private int savedCount;

    public SaveShareScreen(Intent in) { this.in = in; }

    @Override public View create() {
        items = ShareIn.from(c.a, in);
        if (items.isEmpty() && in != null) {
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            CharSequence subj = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            if (t != null && t.length() > 0) items.add(ShareIn.text(t.toString(), subj == null ? null : subj.toString()));
        }
        dir = startDir();
        probeNet();

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Kolay'a kaydet", "Paylaşılan öğeleri telefona kaydet", () -> c.a.finish()).view);
        col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(24));
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = Ui.bottomBar(c);
        page.addView(bottom, Ui.lp(-1, -2));
        build();
        return page;
    }

    @Override public boolean onBack() { c.a.finish(); return true; }

    @Override public void onResume() { if (col != null && !saved) build(); }   // back from the system permission page
    @Override public void onPermission(int req, String[] perms, int[] results) { if (req == Files.REQ_STORAGE && !saved) build(); }

    /** A remembered network folder is used only if the server still exists and answers; otherwise Download. */
    private String startDir() {
        String d = Files.prefs(c.a).getString(PREF_DIR, null);
        if (d != null && new File(d).isDirectory()) return d;
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dl.mkdirs();
        return dl.getPath();
    }

    private void probeNet() {
        String id = Files.prefs(c.a).getString(PREF_SRV, null), nd = Files.prefs(c.a).getString(PREF_SRV_DIR, null);
        if (id == null || nd == null) return;
        SmbSource.Server found = null;
        for (SmbSource.Server sv : SmbServers.all(c)) if (sv.id.equals(id)) found = sv;
        if (found == null) { forgetNet(); return; }
        SmbSource sm = SmbServers.source(found);
        src = sm; dir = nd;
        c.io.execute(() -> {   // network: never on the UI thread
            boolean ok, gone;   // gone: the server answered and the folder is not there any more
            try { FileItem it = sm.stat(nd); ok = it != null && it.dir; gone = !ok; } catch (IOException | RuntimeException e) { ok = false; gone = false; }
            boolean fok = ok, fgone = gone;
            c.ui.post(() -> {
                if (fok || src != sm) return;   // reachable, or the user already picked something else
                if (fgone) forgetNet();   // only unreachable right now (Wi-Fi off…): keep the choice for next time
                src = LocalSource.INSTANCE; dir = startDir();
                Ui.toast(c, "Sunucuya ulaşılamadı; İndirilenler'e dönüldü");
                if (col != null && !saved) build();
            });
        });
    }

    private void forgetNet() { Files.prefs(c.a).edit().remove(PREF_SRV).remove(PREF_SRV_DIR).apply(); }

    private boolean net() { return src instanceof SmbSource; }

    private long totalSize() {
        long t = 0;
        for (Ops.Incoming i : items) { if (i.size() < 0) return -1; t += i.size(); }
        return t;
    }

    private String where() {
        if (net()) return "Ağ · " + ((SmbSource) src).server.name + (dir.equals("/") ? "" : dir);
        Files.Volume v = Files.volumeOf(Files.volumes(c.a), dir);
        return v == null ? dir : dir.length() > v.path.length() ? v.label + dir.substring(v.path.length()) : v.label;
    }

    private void build() {
        col.removeAllViews();
        bottom.removeAllViews();
        if (items.isEmpty()) {
            col.addView(Ui.state(c, Icon.Kind.SHARE, "Kaydedilecek bir şey yok",
                "Paylaşılan içerikte kaydedilebilir bir dosya ya da metin bulunamadı.", "Kapat", () -> c.a.finish()), Ui.lp(-1, -2, 0, c.dp(32), 0, 0));
            return;
        }
        if (saved) { showSaved(); return; }

        long total = totalSize();
        col.addView(Ui.label(c, "KAYDEDİLECEK"), Ui.lp(-1, -2, c.dp(4), c.dp(4), 0, c.dp(8)));
        LinearLayout card = Ui.card(c, c.p.surface, 16);
        card.addView(Ui.text(c, items.size() + " dosya" + (total >= 0 ? " · " + Files.size(total) : ""), 16, c.p.ink, 700));
        for (int i = 0; i < items.size() && i < 5; i++) {
            TextView t = Ui.text(c, items.get(i).name(), 14, c.p.muted, 400);
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(t, Ui.lp(-1, -2, 0, c.dp(6), 0, 0));
        }
        if (items.size() > 5) card.addView(Ui.text(c, "… ve " + (items.size() - 5) + " dosya daha", 14, c.p.muted, 400), Ui.lp(-1, -2, 0, c.dp(6), 0, 0));
        col.addView(card, Ui.lp(-1, -2, 0, 0, 0, c.dp(Ui.GAP)));

        if (Files.hasAccess(c.a) || net()) {   // a network target needs no all-files access
            col.addView(Ui.navRow(c, Icon.Kind.FOLDER, "Konum", where(), 1, this::pickDir), Ui.lp(-1, c.dp(70)));
            TextView go = Ui.button(c, "Kaydet", Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
            go.setOnClickListener(v -> save());
            bottom.addView(go, Ui.lp(-1, c.dp(Ui.H_BTN)));
        } else {
            LinearLayout note = Ui.card(c, c.p.surface2, 16);
            note.addView(Ui.text(c, "Dilediğin klasöre kaydetmek için “Tüm dosyalara erişim” iznini ver. İstersen izin vermeden İndirilenler/Kolay klasörüne de kaydedebilirsin.", 14, c.p.ink, 400));
            col.addView(note);
            TextView allow = Ui.button(c, "İzin ver", Ui.Style.PRIMARY, null);
            allow.setOnClickListener(v -> Files.requestAccess(c));
            TextView dl = Ui.button(c, "İndirilenler/Kolay'a kaydet", Ui.Style.TONAL, Icon.Kind.DOWNLOAD);
            dl.setOnClickListener(v -> saveDownloads());
            TextView lan = Ui.button(c, "Ağdaki bir sunucuya kaydet", Ui.Style.TONAL, Icon.Kind.NETSCAN);
            lan.setOnClickListener(v -> pickDir());
            bottom.addView(allow, Ui.lp(-1, c.dp(Ui.H_BTN)));
            bottom.addView(dl, Ui.lp(-1, c.dp(Ui.H_BTN), 0, c.dp(Ui.GAP), 0, 0));
            bottom.addView(lan, Ui.lp(-1, c.dp(Ui.H_BTN), 0, c.dp(Ui.GAP), 0, 0));
        }
    }

    private void showSaved() {
        boolean access = Files.hasAccess(c.a) || net();
        String place = access ? where() : "İndirilenler/Kolay";
        col.addView(Ui.state(c, Icon.Kind.CHECK, "Kaydedildi", savedCount + " dosya " + place + " konumuna kaydedildi.",
            access ? "Klasörü aç" : null, () -> c.nav.push(net() ? new BrowserScreen(src, dir, ((SmbSource) src).server.name) : new BrowserScreen(dir))), Ui.lp(-1, -2, 0, c.dp(32), 0, 0));
        TextView done = Ui.button(c, "Bitti", Ui.Style.TONAL, null);
        done.setOnClickListener(v -> c.a.finish());
        bottom.addView(done, Ui.lp(-1, c.dp(Ui.H_BTN)));
    }

    private void pickDir() {
        c.nav.push(new FolderPicker("Konum seç", "Bu klasörü seç", src, dir, (s, d) -> {
            src = s; dir = d;
            android.content.SharedPreferences.Editor e = Files.prefs(c.a).edit();
            if (s instanceof SmbSource) e.putString(PREF_SRV, ((SmbSource) s).server.id).putString(PREF_SRV_DIR, d);
            else e.putString(PREF_DIR, d).remove(PREF_SRV).remove(PREF_SRV_DIR);
            e.apply();
            build();
        }));
    }

    private void save() {
        long total = totalSize();
        if (total > 0 && !net()) {   // free space is known for the phone only
            try { if (new StatFs(dir).getAvailableBytes() < total) { Ui.toast(c, "Telefonda yeterli boş yer yok"); return; } }
            catch (IllegalArgumentException ignored) { }   // unreadable target: the copy reports it
        }
        String target = dir;
        FileSource dst = src;
        long t0 = System.currentTimeMillis();   // job start: files written from now on are the new ones
        List<Ops.Incoming> list = new ArrayList<>(items);
        Ops.Result[] out = {null};
        Jobs.run(c, "Kaydediliyor", "kaydedildi", (p, r) -> out[0] = Ops.importStreams(list, dst, target, r, p), () -> {
            if (out[0] == null || out[0].done == 0) return;
            if (dst == LocalSource.INSTANCE) {
                Files.prefs(c.a).edit().putString(PREF_DIR, target).apply();
                Files.rescan(c.a, listPaths(target, t0 - 2000));   // media index: the phone only
            }
            finished(out[0].done);
        }, true);
    }

    private void saveDownloads() {
        List<Ops.Incoming> list = new ArrayList<>(items);
        Ops.Result[] out = {null};
        Jobs.run(c, "Kaydediliyor", "kaydedildi", (p, r) -> out[0] = ShareIn.saveToDownloads(c.a, list, p), () -> {
            if (out[0] != null && out[0].done > 0) finished(out[0].done);
        }, true);
    }

    private void finished(int n) {
        saved = true; savedCount = n;
        build();
    }

    /** The files of the saved folder changed since {@code since}, so the media index picks up the new files. */
    private static List<String> listPaths(String d, long since) {
        List<String> out = new ArrayList<>();
        try { for (FileItem it : LocalSource.INSTANCE.list(d)) if (!it.dir && it.modified >= since) out.add(it.path); } catch (java.io.IOException ignored) { }
        return out;
    }
}
