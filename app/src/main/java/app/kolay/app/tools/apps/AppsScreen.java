package app.kolay.app.tools.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.*;
import app.kolay.app.ui.*;
import app.kolay.shell.Host;
import java.io.File;
import java.text.Collator;
import java.util.*;

/**
 * Apps installed from outside Google Play: checks APKPure / APKMirror / LiteAPKs (chosen per app) for a newer version,
 * downloads it, hands it to Android's installer and deletes the file. Updates run one after another so only one
 * confirmation is ever on screen.
 */
final class AppsScreen extends Screen {
    private static final String PLAY = "com.android.vending";

    private static final class Item {
        Sources.App app; String installer; Drawable icon;
        TextView status; String statusText; int statusColor;
        String found;   // newer version seen on the site (null = none known)
    }

    private static final class Job { final Item it; final boolean install; Job(Item it, boolean install) { this.it = it; this.install = install; } }

    private final List<Item> items = new ArrayList<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private SharedPreferences prefs;
    private FrameLayout root, content;
    private TextView runBtn;
    private Browser browser;
    private BroadcastReceiver receiver;
    private boolean running, destroyed;
    private Job current;
    private Thread worker;
    /** Waiting for the "install unknown apps" permission (user is in Settings) with this unpacked update. */
    private Installer.Pack pendingPack; private Item pendingItem;

    @Override public View create() {
        prefs = c.a.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        Installer.cleanup(c.a);
        root = new FrameLayout(c.a);
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Uygulama Güncelle", "Play dışından yüklenenler", () -> c.nav.pop()).view);
        content = new FrameLayout(c.a);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = Ui.bottomBar(c);
        runBtn = Ui.button(c, "Kontrol et ve güncelle", Ui.Style.PRIMARY, Icon.Kind.REFRESH);
        runBtn.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CONFIRM); if (running) stop("Durduruldu"); else runAll(); });
        bar.addView(runBtn, new LinearLayout.LayoutParams(-1, c.dp(Ui.H_BAR)));
        page.addView(bar);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        browser = new Browser(c, root);
        receiver = Installer.receiver(c.a, go -> { if (!destroyed) c.a.startActivity(go); }, (pkg, err) -> c.ui.post(() -> installed(pkg, err)));

        show(Ui.busy(c, "Uygulamalar okunuyor…", null));
        runBtn.setEnabled(false); runBtn.setAlpha(0.5f);
        c.io.execute(() -> {
            List<Item> list = load();
            c.ui.post(() -> { if (!destroyed) { items.clear(); items.addAll(list); render(); } });
        });
        return root;
    }

    // ---- installed apps -------------------------------------------------------------------------

    private List<Item> load() {
        PackageManager pm = c.a.getPackageManager();
        Map<String, Item> out = new HashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg) || pkg.equals(c.a.getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                String inst = installerOf(pm, pkg);
                if (PLAY.equals(inst)) continue;
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                Item it = new Item();
                it.app = new Sources.App(pkg, String.valueOf(ai.loadLabel(pm)), pi.versionName == null ? "" : pi.versionName, pi.getLongVersionCode());
                it.installer = installerLabel(pm, inst);
                it.icon = ai.loadIcon(pm);
                String f = prefs.getString("new:" + pkg, null);
                if (f != null && Sources.Ver.compare(f, it.app.version) > 0) it.found = f;
                out.put(pkg, it);
            } catch (Exception ignored) { }
        }
        List<Item> list = new ArrayList<>(out.values());
        Collator col = Collator.getInstance(new Locale("tr", "TR"));
        list.sort((a, b) -> col.compare(a.app.label, b.app.label));
        return list;
    }

    private static String installerOf(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            return pm.getInstallerPackageName(pkg);
        } catch (Exception e) { return null; }
    }

    private String installerLabel(PackageManager pm, String inst) {
        if (inst == null || inst.endsWith("packageinstaller") || inst.equals("com.android.shell")) return "elle yüklendi";
        if (inst.equals(c.a.getPackageName())) return "Kolay ile güncellendi";
        try { return String.valueOf(pm.getApplicationInfo(inst, 0).loadLabel(pm)); } catch (Exception e) { return inst; }
    }

    // ---- list -----------------------------------------------------------------------------------

    private void show(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        Ui.appear(v, 0);
    }

    private void render() {
        if (items.isEmpty()) {
            show(Ui.state(c, Icon.Kind.APPS, "Güncellenecek uygulama yok", "Google Play dışından (APK ile) yüklediğin uygulamalar burada listelenir.", null, null));
            return;
        }
        runBtn.setEnabled(true); runBtn.setAlpha(1f);
        // Tracked apps first, switched-off ones pushed to the bottom; alphabetical within each group.
        List<Item> on = new ArrayList<>(), off = new ArrayList<>();
        for (Item it : items) (tracked(it.app.pkg) ? on : off).add(it);
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(c.a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(c.dp(Ui.GUTTER), c.dp(2), c.dp(Ui.GUTTER), c.dp(16));
        if (!on.isEmpty()) group(list, "TAKİPTE · " + on.size(), on, true);
        if (!off.isEmpty()) group(list, "KAPALI · " + off.size(), off, false);
        TextView hint = Ui.text(c, "Satıra dokun: kontrol et / güncelle. Rozete dokun: kaynağı değiştir (M+P = APKMirror + APKPure, hangisinde daha yeni sürüm varsa; Lite = LiteAPKs).", 12, c.p.muted, 400);
        hint.setLineSpacing(0, 1.15f);
        list.addView(hint, Ui.lp(-1, -2, c.dp(4), c.dp(12), c.dp(4), 0));
        sv.addView(list);
        int y = content.getChildCount() > 0 && content.getChildAt(0) instanceof ScrollView ? content.getChildAt(0).getScrollY() : 0;
        content.removeAllViews();
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        sv.post(() -> sv.scrollTo(0, y));
    }

    private void group(LinearLayout list, String title, List<Item> group, boolean tracked) {
        TextView l = Ui.label(c, title);
        list.addView(l, Ui.lp(-2, -2, c.dp(4), c.dp(list.getChildCount() == 0 ? 6 : 18), 0, c.dp(8)));
        LinearLayout card = new LinearLayout(c.a);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.surface(c, card, c.p.surface, 18, true);
        card.setClipToOutline(true);
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) {
                View d = new View(c.a);
                d.setBackgroundColor(c.p.line);
                card.addView(d, Ui.lp(-1, 1, c.dp(56), 0, 0, 0));
            }
            card.addView(row(group.get(i), tracked));
        }
        list.addView(card, Ui.lp(-1, -2));
    }

    /** One slim row: icon · name + status line · source chip · follow switch. Tapping the row checks / updates it. */
    private View row(Item it, boolean tracked) {
        String pkg = it.app.pkg;
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(c.dp(56));
        row.setPadding(c.dp(12), c.dp(8), c.dp(10), c.dp(8));
        ImageView icon = new ImageView(c.a);
        icon.setImageDrawable(it.icon);
        row.addView(icon, new LinearLayout.LayoutParams(c.dp(32), c.dp(32)));

        LinearLayout names = new LinearLayout(c.a);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, it.app.label, 15, c.p.ink, 600);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        it.status = Ui.text(c, "", 12, c.p.muted, 500);
        it.status.setMaxLines(2); it.status.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(it.status, Ui.lp(-1, -2, 0, c.dp(1), 0, 0));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        nl.setMarginStart(c.dp(12)); nl.setMarginEnd(c.dp(8));
        row.addView(names, nl);

        TextView chip = Ui.text(c, chipText(choice(pkg)), 12, c.p.onPrimarySoft, 700);
        chip.setBackground(Ui.rounded(c, c.p.primarySoft, 10));
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setContentDescription("Kaynak: " + Sources.CHOICES[choice(pkg)] + ". Değiştirmek için dokun.");
        chip.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            if (current != null && current.it == it) return;
            int next = choice(pkg) == Sources.LITE ? Sources.MIRRORS : Sources.LITE;
            prefs.edit().putInt("choice:" + pkg, next).remove("new:" + pkg).apply();
            it.found = null;
            chip.setText(chipText(next));
            chip.setContentDescription("Kaynak: " + Sources.CHOICES[next] + ". Değiştirmek için dokun.");
            idle(it);
        });
        Ui.press(chip);
        row.addView(chip, new LinearLayout.LayoutParams(c.dp(48), c.dp(28)));

        Toggle sw = new Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, tracked);
        sw.setContentDescription(it.app.label + " güncellemelerini takip et");
        sw.onChange(v -> {
            prefs.edit().putBoolean("off:" + pkg, !v).apply();
            c.ui.postDelayed(() -> { if (!destroyed) render(); }, 300);   // let the switch finish, then re-sort
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMarginStart(c.dp(10));
        row.addView(sw, sl);

        row.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            if (current != null && current.it == it) return;
            enqueue(new Job(it, it.found != null));
        });
        Ui.press(row);
        if (!tracked) { icon.setAlpha(0.5f); names.setAlpha(0.55f); chip.setAlpha(0.55f); }
        if (it.statusText != null) paint(it); else idle(it);
        return row;
    }

    private static String chipText(int choice) { return choice == Sources.LITE ? "Lite" : "M+P"; }

    private boolean tracked(String pkg) { return !prefs.getBoolean("off:" + pkg, false); }

    /** Source choice per app; values saved by the earlier three-site version are mapped (LiteAPKs stays, the rest → mirrors). */
    private int choice(String pkg) {
        if (prefs.contains("choice:" + pkg)) return prefs.getInt("choice:" + pkg, Sources.MIRRORS);
        return prefs.getInt("src:" + pkg, 0) == 2 ? Sources.LITE : Sources.MIRRORS;
    }

    /** Resting state of a row: the known newer version, or the installed version. */
    private void idle(Item it) {
        String v = it.app.version.isEmpty() ? "sürüm ?" : it.app.version;
        if (it.found != null) status(it, v + " → " + it.found, c.p.primary, null, null);
        else status(it, v + " · " + it.installer, c.p.muted, null, null);
    }

    private void status(Item it, String text, int color, String action, Icon.Kind k) {
        it.statusText = text; it.statusColor = color;
        paint(it);
    }

    private void paint(Item it) {
        if (it.status == null) return;
        it.status.setText(it.statusText);
        it.status.setTextColor(it.statusColor);
    }

    // ---- queue ----------------------------------------------------------------------------------

    private void runAll() {
        boolean any = false;
        for (Item it : items) if (tracked(it.app.pkg)) { queue.add(new Job(it, true)); any = true; }
        if (!any) { Toast.makeText(c.a, "Takip edilen uygulama yok", Toast.LENGTH_SHORT).show(); return; }
        for (Job j : queue) status(j.it, "Sırada", c.p.muted, null, null);
        setRunning(true);
        next();
    }

    private void enqueue(Job j) {
        for (Job q : queue) if (q.it == j.it) return;
        queue.add(j);
        status(j.it, "Sırada", c.p.muted, null, null);
        if (!running) { setRunning(true); next(); }
    }

    private void setRunning(boolean on) {
        running = on;
        runBtn.setText(on ? "Durdur" : "Kontrol et ve güncelle");
        Ui.style(c, runBtn, on ? Ui.Style.TONAL : Ui.Style.PRIMARY, on ? null : Icon.Kind.REFRESH);
    }

    private void stop(String why) {
        if (worker != null) worker.interrupt();
        worker = null;
        Job cur = current;
        current = null;
        browser.cancel(why);
        for (Job j : queue) idle(j.it);
        queue.clear();
        if (cur != null) idle(cur.it);
        if (pendingPack != null) { Host.deleteTree(pendingPack.dir); pendingPack = null; pendingItem = null; }
        setRunning(false);
    }

    private void next() {
        if (destroyed) return;
        current = queue.poll();
        if (current == null) { setRunning(false); return; }
        Job j = current;
        Item it = j.it;
        int src = choice(it.app.pkg);
        status(it, (src == Sources.LITE ? "LiteAPKs" : "APKMirror ve APKPure") + " kontrol ediliyor…", c.p.muted, null, null);
        Sources.find(src, browser, it.app, new Sources.Result() {
            @Override public void found(Sources.Found f) {
                if (j != current) return;
                if (Sources.Ver.compare(f.version, it.app.version) <= 0) {
                    it.found = null;
                    prefs.edit().remove("new:" + it.app.pkg).apply();
                    status(it, "Güncel · " + it.app.version + " (" + f.site + ": " + f.version + ")", c.p.ok, null, null);
                    next();
                    return;
                }
                it.found = f.version;
                prefs.edit().putString("new:" + it.app.pkg, f.version).apply();
                if (!j.install) { idle(it); next(); return; }
                fetch(j, f);
            }
            @Override public void fail(String why) {
                if (j != current) return;
                status(it, why, c.p.warn, "Tekrar dene", Icon.Kind.REFRESH);
                next();
            }
        });
    }

    private void fetch(Job j, Sources.Found f) {
        Item it = j.it;
        status(it, f.version + " indiriliyor (" + f.site + ")…", c.p.primary, null, null);
        browser.download(f.start, f.hosts, f.hop, f.hops, (url, ua, referer) -> {
            if (j != current) return;
            worker = new Thread(() -> {
                try {
                    File file = Installer.download(c.a, url, ua, referer, it.app.pkg,
                        p -> c.ui.post(() -> { if (j == current) status(it, f.version + " indiriliyor" + (p >= 0 ? " · %" + p : "…"), c.p.primary, null, null); }));
                    c.ui.post(() -> { if (j == current) status(it, "Dosya doğrulanıyor…", c.p.primary, null, null); });
                    Installer.Pack pack = Installer.prepare(c.a, file, it.app.pkg);
                    c.ui.post(() -> { if (j == current) install(it, pack); else Host.deleteTree(pack.dir); });
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Host.deleteTree(new File(Installer.workDir(c.a), it.app.pkg + ".parts"));
                    new File(Installer.workDir(c.a), it.app.pkg + ".download").delete();
                    c.ui.post(() -> { if (j == current) { status(it, "İndirilemedi: " + m, c.p.danger, "Tekrar dene", Icon.Kind.REFRESH); next(); } });
                }
            }, "app-update");
            worker.start();
        }, new Browser.Done() {
            @Override public void ok(String v) { }
            @Override public void fail(String why) {
                if (j != current) return;
                status(it, "İndirilemedi: " + why, c.p.danger, "Tekrar dene", Icon.Kind.REFRESH);
                next();
            }
        });
    }

    private void install(Item it, Installer.Pack pack) {
        if (!c.a.getPackageManager().canRequestPackageInstalls()) {
            pendingPack = pack; pendingItem = it;
            status(it, "Kurulum izni gerekli: Kolay için “Bu kaynaktan izin ver”i aç ve geri dön", c.p.warn, null, null);
            c.a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.a.getPackageName())));
            return;
        }
        status(it, pack.version + " kuruluyor · onay bekleniyor…", c.p.primary, null, null);
        try { Installer.install(c.a, it.app.pkg, pack); }
        catch (Exception e) {
            status(it, "Kurulamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), c.p.danger, "Tekrar dene", Icon.Kind.REFRESH);
            next();
        }
    }

    /** Result broadcast from Android's installer. */
    private void installed(String pkg, String err) {
        if (destroyed || current == null || !current.it.app.pkg.equals(pkg)) return;
        Item it = current.it;
        if (err == null) {
            try {
                PackageInfo pi = c.a.getPackageManager().getPackageInfo(pkg, 0);
                it.app = new Sources.App(pkg, it.app.label, pi.versionName == null ? "" : pi.versionName, pi.getLongVersionCode());
            } catch (Exception ignored) { }
            it.found = null;
            prefs.edit().remove("new:" + pkg).apply();
            status(it, "Güncellendi · " + it.app.version + " (dosya silindi)", c.p.ok, "Kontrol et", null);
        } else status(it, "Kurulmadı: " + err, c.p.danger, "Tekrar dene", Icon.Kind.REFRESH);
        next();
    }

    @Override public void onResume() {
        if (pendingPack == null) return;
        Installer.Pack p = pendingPack; Item it = pendingItem;
        pendingPack = null; pendingItem = null;
        if (c.a.getPackageManager().canRequestPackageInstalls()) install(it, p);
        else {
            Host.deleteTree(p.dir);
            status(it, "Kurulum izni verilmedi", c.p.warn, "Tekrar dene", Icon.Kind.REFRESH);
            next();
        }
    }

    @Override public boolean onBack() {
        if (browser != null && browser.showing()) { browser.cancel("İptal edildi"); return true; }
        return false;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (worker != null) worker.interrupt();
        queue.clear(); current = null;
        if (browser != null) browser.destroy();
        try { c.a.unregisterReceiver(receiver); } catch (Exception ignored) { }
        Installer.cleanup(c.a);
    }
}
