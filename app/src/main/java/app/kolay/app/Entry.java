package app.kolay.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import app.kolay.app.doc.PhotoPick;
import app.kolay.app.screens.HomeScreen;
import app.kolay.app.tools.qr.QrTool;
import app.kolay.app.tools.scan.DocsScreen;
import app.kolay.app.tools.youtube.YoutubeTool;
import app.kolay.app.ui.*;
import app.kolay.app.viewer.Viewer;
import app.kolay.shell.Host;

/** UI entry point: sets up theme, navigation and the home screen inside {@code MainActivity}, which forwards its lifecycle here. */
public final class Entry {
    /** Must match PrayerWidget.ACTION_PRAYER in the shell (widget tap). */
    static final String ACTION_PRAYER = "app.kolay.action.PRAYER";
    private Ctx c;

    public void onCreate(Host host, Bundle saved) {
        Palette p = Palette.of(host.activity());
        c = new Ctx(host, p);
        android.app.Activity a = c.a;

        // Edge-to-edge: transparent bars, the pages are padded by the insets in Nav (the home hero draws under the status bar).
        android.view.Window w = a.getWindow();
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        w.setStatusBarContrastEnforced(false);
        w.setNavigationBarContrastEnforced(false);
        if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!p.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(p.bg);
        a.setContentView(root);

        new Nav(c, root).push(new HomeScreen());
        // Opened from the quick-settings tile: show the QR reader on top of home (so Back lands on home).
        // Only on a fresh start, and consume the action so a later recreate() does not reopen it.
        Intent in = a.getIntent();
        if (saved == null && in != null && QrTool.ACTION_QR.equals(in.getAction())) {
            in.setAction(Intent.ACTION_MAIN);
            c.nav.push(new QrTool().open(c));
        }
        // Tapped prayer-time widget: the prayer times on top of home.
        if (saved == null && in != null && ACTION_PRAYER.equals(in.getAction())) {
            in.setAction(Intent.ACTION_MAIN);
            c.nav.push(new app.kolay.app.tools.namaz.NamazTool().open(c));
        }
        // Pictures shared via "Metne çevir": straight to text recognition in the documents flow. Only content:// pictures
        // (the share grant), consumed like the others. Checked before text: a picture share may carry a caption too.
        String type = in == null ? null : in.getType();
        if (saved == null && type != null && type.startsWith("image/")
                && (Intent.ACTION_SEND.equals(in.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(in.getAction()))) {
            java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
            for (android.net.Uri u : PhotoPick.uris(in)) if ("content".equals(u.getScheme())) uris.add(u);
            in.setAction(Intent.ACTION_MAIN);
            in.removeExtra(Intent.EXTRA_STREAM);
            in.removeExtra(Intent.EXTRA_TEXT);
            in.setClipData(null);
            if (!uris.isEmpty()) c.nav.push(new DocsScreen(uris));
        }
        // "Birlikte aç" (ACTION_VIEW) for documents: straight to the viewer; Back returns to the opening app. Consumed like the others.
        if (saved == null && in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            android.net.Uri u = in.getData();
            String t = in.getType();
            in.setAction(Intent.ACTION_MAIN);
            c.nav.push(Viewer.external(u, t));
        }
        // Shared from another app via "YouTube Özet": open the summary screen with the link. Consumed so recreate() does not repeat it.
        if (saved == null && in != null && Intent.ACTION_SEND.equals(in.getAction()) && in.getStringExtra(Intent.EXTRA_TEXT) != null) {
            String text = in.getStringExtra(Intent.EXTRA_TEXT);
            in.setAction(Intent.ACTION_MAIN);
            in.removeExtra(Intent.EXTRA_TEXT);
            c.nav.push(new YoutubeTool().openShared(c, text));
        }
    }

    public void onResume() {
        Screen s = c == null ? null : c.nav.top();
        if (s != null) s.onResume();
    }

    public void onPause() {
        Screen s = c == null ? null : c.nav.top();
        if (s != null) s.onPause();
    }

    public void onPermissionResult(int req, String[] perms, int[] results) {
        Screen s = c.nav.top();
        if (s != null) s.onPermission(req, perms, results);
    }

    public void onActivityResult(int req, int res, Intent data) {
        Screen s = c.nav.top();
        if (s != null) s.onActivityResult(req, res, data);
    }

    public boolean onBackPressed() {
        Screen s = c.nav.top();
        if (s != null && s.onBack()) return true;
        return c.nav.pop();
    }

    public void onDestroy() {
        c.host.onUpdateReady(null);
        c.nav.destroyAll();
        c.io.shutdownNow();
    }
}
