package app.kolay.app.tools.files;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.kolay.app.ui.Header;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Toggle;
import app.kolay.app.ui.Ui;
import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "APK'lar": two tabs. "APK dosyaları" (default) lists the APK files lying in storage (media index). "Yüklü uygulamalar"
 * lists the installed apps (user apps; system apps behind a switch) with icon, version and size; a tap shares the app's
 * APK (base plus split APKs, if any).
 */
final class ApksScreen extends Screen {
    private static final class App { String label, pkg, version; long size; Drawable icon; List<String> apks = new ArrayList<>(); }

    private FrameLayout content;
    private LinearLayout sw;
    private boolean system, apps;   // apps: the "Yüklü uygulamalar" tab (default: the APK files in storage)
    private int gen;

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "APK'lar", "APK dosyaları ve yüklü uygulamalar", () -> c.nav.pop()).view);
        LinearLayout top = new LinearLayout(c.a);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        top.addView(Ui.segmented(c, new String[]{"APK dosyaları", "Yüklü uygulamalar"}, null, 0, k -> { apps = k == 1; load(); }), Ui.lp(-1, c.dp(44), 0, 0, 0, c.dp(8)));
        sw = new LinearLayout(c.a);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(c.dp(4), 0, 0, 0);
        sw.addView(Ui.text(c, "Sistem uygulamalarını da göster", 15, c.p.ink, 500), new LinearLayout.LayoutParams(0, -2, 1));
        Toggle tg = new Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, false);
        tg.setContentDescription("Sistem uygulamalarını da göster");
        tg.onChange(v -> { system = v; load(); });
        sw.addView(tg, new LinearLayout.LayoutParams(c.dp(52), c.dp(32)));
        sw.setVisibility(View.GONE);
        top.addView(sw, Ui.lp(-1, c.dp(48)));
        page.addView(top);
        content = new FrameLayout(c.a);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return page;
    }

    @Override public void onDestroy() { gen++; }

    private void show(View v) { content.removeAllViews(); content.addView(v, new FrameLayout.LayoutParams(-1, -1)); }

    private void load() {
        int g = ++gen;
        boolean sys = system;
        sw.setVisibility(apps ? View.VISIBLE : View.GONE);
        if (!apps) {
            show(Ui.busy(c, "APK dosyaları aranıyor…", null));
            String sel = MediaStore.MediaColumns.MIME_TYPE + "='application/vnd.android.package-archive' OR " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE '%.apk'";
            c.io.execute(() -> {
                List<FileItem> got = Files.query(c.a, sel, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC", 2000);
                c.ui.post(() -> { if (g == gen) renderFiles(got); });
            });
            return;
        }
        show(Ui.busy(c, "Uygulamalar okunuyor…", null));
        c.io.execute(() -> {
            List<App> list = read(sys);
            c.ui.post(() -> { if (g == gen) render(list); });
        });
    }

    /** The APK files lying in storage (media index); a tap hands the file to the system installer, the folder icon shows where it lies. */
    private void renderFiles(List<FileItem> files) {
        if (files.isEmpty()) { show(Ui.state(c, Icon.Kind.APPS, "APK dosyası yok", "Telefonda indirilen ya da kaydedilen .apk dosyası bulunamadı.", null, null)); return; }
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(24));
        col.addView(Ui.label(c, files.size() + " APK DOSYASI · DOKUN: AÇ"), Ui.lp(-1, -2, c.dp(4), c.dp(8), 0, c.dp(8)));
        LinearLayout card = Ui.card(c, c.p.surface, 6);
        for (FileItem it : files) card.addView(fileRow(it), Ui.lp(-1, -2));
        col.addView(card, Ui.lp(-1, -2));
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        show(sv);
    }

    private View fileRow(FileItem it) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(c.dp(58));
        r.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6));
        r.setBackground(Ui.ripple(c, 16));
        r.addView(Ui.icon(c, Icon.Kind.APPS, c.p.primary, 24), new LinearLayout.LayoutParams(c.dp(36), c.dp(24)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = Ui.text(c, it.name, 15, c.p.ink, 600);
        n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        txt.addView(n);
        TextView s = Ui.text(c, Files.size(it.size) + " · " + Files.date(it.modified), 12, c.p.muted, 400);
        s.setSingleLine(true);
        txt.addView(s, Ui.lp(-1, -2, 0, c.dp(2), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(12)); tl.setMarginEnd(c.dp(8));
        r.addView(txt, tl);
        FrameLayout where = new FrameLayout(c.a);
        where.setBackground(Ui.ripple(c, 20));
        where.setClickable(true);
        where.setContentDescription("Konumu aç");
        where.addView(Ui.icon(c, Icon.Kind.FOLDER, c.p.muted, 20), new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
        where.setOnClickListener(v -> c.nav.push(new BrowserScreen(FileSource.parent(it.path))));
        r.addView(where, new LinearLayout.LayoutParams(c.dp(44), c.dp(44)));
        r.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); Files.open(c, LocalSource.INSTANCE, it); });
        return r;
    }

    private List<App> read(boolean sys) {
        PackageManager pm = c.a.getPackageManager();
        List<App> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!sys && (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            if (ai.sourceDir == null || !new File(ai.sourceDir).isFile()) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ai.loadLabel(pm));
            try { PackageInfo pi = pm.getPackageInfo(ai.packageName, 0); a.version = pi.versionName == null ? "" : pi.versionName; }
            catch (PackageManager.NameNotFoundException e) { continue; }
            a.apks.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) if (s != null) a.apks.add(s);
            for (String p : a.apks) a.size += new File(p).length();
            a.icon = ai.loadIcon(pm);
            out.add(a);
        }
        Collator col = Collator.getInstance(new Locale("tr", "TR"));
        out.sort((x, y) -> col.compare(x.label, y.label));
        return out;
    }

    private void render(List<App> apps) {
        if (apps.isEmpty()) { show(Ui.state(c, Icon.Kind.APPS, "Uygulama bulunamadı", "Yüklü uygulama listesi okunamadı.", null, null)); return; }
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(24));
        col.addView(Ui.label(c, apps.size() + " UYGULAMA · DOKUN: PAYLAŞ"), Ui.lp(-1, -2, c.dp(4), c.dp(8), 0, c.dp(8)));
        LinearLayout card = Ui.card(c, c.p.surface, 6);
        for (App a : apps) card.addView(row(a), Ui.lp(-1, -2));
        col.addView(card, Ui.lp(-1, -2));
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        show(sv);
    }

    private View row(App a) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(c.dp(58));
        r.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6));
        r.setBackground(Ui.ripple(c, 16));
        ImageView ic = new ImageView(c.a);
        ic.setImageDrawable(a.icon);
        r.addView(ic, new LinearLayout.LayoutParams(c.dp(36), c.dp(36)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = Ui.text(c, a.label, 15, c.p.ink, 600);
        n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(n);
        String sub = (a.version.isEmpty() ? "" : "v" + a.version + " · ") + Files.size(a.size) + (a.apks.size() > 1 ? " · " + a.apks.size() + " parça" : "");
        TextView s = Ui.text(c, sub, 12, c.p.muted, 400);
        s.setSingleLine(true); s.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(s, Ui.lp(-1, -2, 0, c.dp(2), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(12)); tl.setMarginEnd(c.dp(8));
        r.addView(txt, tl);
        r.addView(Ui.icon(c, Icon.Kind.SHARE, c.p.muted, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        r.setContentDescription(a.label + ", " + sub + ". Paylaşmak için dokun.");
        r.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); share(a); });
        return r;
    }

    /** Base APK named "<label>_<version>.apk"; split APKs (if any) go along, named after their split. */
    private void share(App a) {
        String base = (a.label + (a.version.isEmpty() ? "" : "_" + a.version)).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        ArrayList<Uri> uris = new ArrayList<>();
        for (String p : a.apks) uris.add(ShareProvider.uri(p, p.equals(a.apks.get(0)) ? base + ".apk" : base + "_" + new File(p).getName()));
        String type = "application/vnd.android.package-archive";
        Intent i = uris.size() == 1
            ? new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.get(0))
            : new Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        android.content.ClipData clip = android.content.ClipData.newRawUri(null, uris.get(0));   // lets the share sheet read the names too
        for (int k = 1; k < uris.size(); k++) clip.addItem(new android.content.ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (uris.size() > 1) Files.toast(c, "Bu uygulama " + uris.size() + " parçalı (split APK): hepsi birlikte paylaşılır");
        try { c.a.startActivity(Intent.createChooser(i, "APK paylaş")); }
        catch (ActivityNotFoundException e) { Files.toast(c, "Paylaşacak uygulama yok"); }
    }
}
