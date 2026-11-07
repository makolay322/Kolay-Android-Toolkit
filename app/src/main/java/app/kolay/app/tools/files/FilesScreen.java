package app.kolay.app.tools.files;

import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.kolay.app.ui.Header;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Sheet;
import app.kolay.app.ui.Ui;
import java.io.File;
import java.util.List;

/** "Dosyalar" start page: the all-files permission state, storage volumes, network (Samba) servers, categories, bookmarks and recent files. */
final class FilesScreen extends Screen {
    private static final long DAY = 24L * 3600 * 1000;
    private LinearLayout col;
    private LinearLayout recent;
    private int gen;

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Dosyalar", "Telefondaki dosya ve klasörler", () -> c.nav.pop()).view);
        col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(24));
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        build();
        return page;
    }

    @Override public void onResume() { build(); }      // back from the system permission page
    @Override public void onReturn() { build(); }      // bookmarks may have changed

    @Override public void onPermission(int req, String[] perms, int[] results) { if (req == Files.REQ_STORAGE) build(); }

    private void build() {
        col.removeAllViews();
        gen++;
        if (!Files.hasAccess(c.a)) {
            boolean modern = Build.VERSION.SDK_INT >= 30;
            col.addView(Ui.state(c, Icon.Kind.FOLDER, "Tüm dosyalara erişim gerekli",
                "Klasörleri gezmek, kopyalamak, taşımak ve silmek için Android'in “Tüm dosyalara erişim” iznine ihtiyaç var. "
                    + (modern ? "Açılan sayfada Kolay için izni aç, sonra geri dön. " : "")
                    + "İzin yalnızca bu cihazdaki dosya işlemleri için kullanılır; dosya içerikleri hiçbir sunucuya gönderilmez.",
                modern ? "Ayarları aç" : "İzin ver", () -> Files.requestAccess(c)), Ui.lp(-1, -2, 0, c.dp(32), 0, 0));
            return;
        }

        col.addView(Ui.label(c, "DEPOLAMA"), Ui.lp(-1, -2, c.dp(4), c.dp(4), 0, c.dp(8)));
        for (Files.Volume v : Files.volumes(c.a)) col.addView(volumeCard(v), Ui.lp(-1, -2, 0, 0, 0, c.dp(Ui.GAP)));

        SmbUi.section(c, col, this::build);

        col.addView(Ui.label(c, "KATEGORİLER"), Ui.lp(-1, -2, c.dp(4), c.dp(14), 0, c.dp(8)));
        String img = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
        String vid = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
        String aud = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
        StringBuilder docs = new StringBuilder(MediaStore.MediaColumns.MIME_TYPE + " IN (");
        for (int i = 0; i < Files.DOC_MIMES.length; i++) docs.append(i == 0 ? "?" : ",?");
        docs.append(")");
        View[] tiles = {
            tile("Görseller", Icon.Kind.IMAGE, c.p.rose, () -> category("Görseller", img, null)),
            tile("Videolar", Icon.Kind.PLAY, c.p.red, () -> category("Videolar", vid, null)),
            tile("Sesler", Icon.Kind.MUSIC, c.p.amber, () -> category("Sesler", aud, null)),
            tile("Belgeler", Icon.Kind.DOC, c.p.sky, () -> category("Belgeler", docs.toString(), Files.DOC_MIMES)),
            tile("İndirilenler", Icon.Kind.DOWNLOAD, c.p.green, () -> c.nav.push(new BrowserScreen(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath()))),
            tile("APK'lar", Icon.Kind.APPS, c.p.teal, () -> c.nav.push(new ApksScreen())),
        };
        for (int i = 0; i < tiles.length; i += 3) {
            LinearLayout row = new LinearLayout(c.a);
            for (int j = i; j < i + 3; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (j > i) lp.setMarginStart(c.dp(Ui.GAP));
                row.addView(tiles[j], lp);
            }
            col.addView(row, Ui.lp(-1, -2, 0, 0, 0, c.dp(Ui.GAP)));
        }

        col.addView(Ui.navRow(c, Icon.Kind.LAYERS, "Büyük dosyalar", "Yer kaplayan en büyük dosyalar (10 MB üstü)", 4, () -> {
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            String root = Environment.getExternalStorageDirectory().getPath();
            c.nav.push(new BrowserScreen("Büyük dosyalar", ctx -> Files.largest(root, 10L << 20, 100, stop), stop));
        }), Ui.lp(-1, c.dp(68), 0, 0, 0, c.dp(Ui.GAP)));

        List<String> marks = Files.bookmarks(c.a);
        if (!marks.isEmpty()) {
            col.addView(Ui.label(c, "YER İMLERİ"), Ui.lp(-1, -2, c.dp(4), c.dp(14), 0, c.dp(8)));
            for (String m : marks) {
                LinearLayout r = Ui.navRow(c, Icon.Kind.STAR, FileSource.name(m), m, 1, () -> {
                    if (new File(m).isDirectory()) c.nav.push(new BrowserScreen(m)); else Files.toast(c, "Klasör artık yok");
                });
                r.setOnLongClickListener(v -> {
                    Sheet.confirm(c, "Yer imi kaldırılsın mı?", m, "Kaldır", false, () -> { Files.setBookmark(c.a, m, false); build(); });
                    return true;
                });
                col.addView(r, Ui.lp(-1, c.dp(68), 0, 0, 0, c.dp(Ui.GAP)));
            }
        }

        col.addView(Ui.label(c, "SON DOSYALAR"), Ui.lp(-1, -2, c.dp(4), c.dp(14), 0, c.dp(8)));
        recent = Ui.card(c, c.p.surface, 6);
        col.addView(recent, Ui.lp(-1, -2));
        loadRecent();
    }

    private View volumeCard(Files.Volume v) {
        LinearLayout card = Ui.card(c, c.p.surface, 16);
        card.setClickable(true);
        LinearLayout top = new LinearLayout(c.a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.toneTile(c, Icon.Kind.FOLDER, c.p.sky, 46, 22, Ui.R_TILE), new LinearLayout.LayoutParams(c.dp(46), c.dp(46)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(Ui.text(c, v.label, 16, c.p.ink, 700));
        long used = v.total - v.free;
        txt.addView(Ui.text(c, Files.size(used) + " / " + Files.size(v.total) + " kullanılıyor · " + Files.size(v.free) + " boş", 13, c.p.muted, 400), Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(14));
        top.addView(txt, tl);
        top.addView(Ui.icon(c, Icon.Kind.CHEVRON, c.p.muted, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        card.addView(top);
        FrameLayout track = new FrameLayout(c.a);
        track.setBackground(Ui.rounded(c, c.p.surface2, 4));
        View fill = new View(c.a);
        float frac = v.total > 0 ? used / (float) v.total : 0;
        fill.setBackground(frac > 0.9f ? Ui.rounded(c, c.p.danger, 4) : Ui.gradient(c, c.p.sky.a, c.p.sky.b, 4, android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT));
        fill.setPivotX(0); fill.setScaleX(frac);
        track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        card.addView(track, Ui.lp(-1, c.dp(8), 0, c.dp(14), 0, 0));
        card.setContentDescription(v.label + ", " + Files.size(v.free) + " boş");
        card.setOnClickListener(x -> { Ui.haptic(x, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(new BrowserScreen(v.path)); });
        Ui.press(card);
        return card;
    }

    private View tile(String title, Icon.Kind icon, app.kolay.app.ui.Palette.Tone tone, Runnable open) {
        LinearLayout t = new LinearLayout(c.a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(c.dp(8), c.dp(14), c.dp(8), c.dp(12));
        Ui.surface(c, t, c.p.surface, 20, true);
        t.setClickable(true);
        t.addView(Ui.toneTile(c, icon, tone, 44, 22, Ui.R_TILE), new LinearLayout.LayoutParams(c.dp(44), c.dp(44)));
        TextView name = Ui.text(c, title, 13, c.p.ink, 600);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name, Ui.lp(-2, -2, 0, c.dp(8), 0, 0));
        t.setContentDescription(title);
        t.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); open.run(); });
        Ui.press(t);
        return t;
    }

    private void category(String title, String sel, String[] args) {
        c.nav.push(new BrowserScreen(title, ctx -> Files.query(ctx, sel, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC", 20000)));
    }

    /** The newest files of the last 30 days from the media index (not opened-history: nothing about use is stored). */
    private void loadRecent() {
        int g = gen;
        LinearLayout box = recent;
        String sel = MediaStore.MediaColumns.DATE_ADDED + ">? AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + "!=" + MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
        String[] args = {String.valueOf((System.currentTimeMillis() - 30 * DAY) / 1000)};
        c.io.execute(() -> {
            List<FileItem> got = Files.query(c.a, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC", 12);
            c.ui.post(() -> {
                if (g != gen) return;
                if (got.isEmpty()) {
                    TextView t = Ui.text(c, "Son 30 günde eklenen dosya yok", 14, c.p.muted, 400);
                    t.setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12));
                    box.addView(t);
                    return;
                }
                for (FileItem it : got) box.addView(recentRow(it), Ui.lp(-1, c.dp(56)));
            });
        });
    }

    private View recentRow(FileItem it) {
        LinearLayout r = new LinearLayout(c.a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(c.dp(10), 0, c.dp(10), 0);
        r.setBackground(Ui.ripple(c, 16));
        FrameLayout badge = new FrameLayout(c.a);
        app.kolay.app.ui.Palette.Tone tn = Files.tone(c.p, it);
        badge.setBackground(Ui.rounded(c, tn != null ? tn.soft : c.p.surface2, 12));
        badge.setClipToOutline(true);
        android.widget.ImageView ic = Ui.icon(c, Files.icon(it), tn != null ? tn.onSoft : c.p.muted, 20);
        badge.addView(ic, new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
        android.widget.ImageView th = new android.widget.ImageView(c.a);
        th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        badge.addView(th, new FrameLayout.LayoutParams(-1, -1));
        if (Files.thumbable(it)) Thumbs.load(th, it, c.dp(40), () -> ic.setVisibility(View.INVISIBLE));
        r.addView(badge, new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = Ui.text(c, it.name, 14, c.p.ink, 500);
        n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        txt.addView(n);
        txt.addView(Ui.text(c, Files.size(it.size) + " · " + Files.date(it.modified), 12, c.p.muted, 400), Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(12));
        r.addView(txt, tl);
        r.setClickable(true);
        r.setOnClickListener(v -> Files.open(c, LocalSource.INSTANCE, it));
        r.setOnLongClickListener(v -> {   // long-press: the folder it is in
            Ui.haptic(v, HapticFeedbackConstants.LONG_PRESS);
            c.nav.push(new BrowserScreen(FileSource.parent(it.path)));
            return true;
        });
        return r;
    }
}
