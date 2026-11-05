package app.kolay.app.tools.ocr;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.LangBar;
import app.kolay.app.doc.Langs;
import app.kolay.app.doc.TessScreen;
import app.kolay.app.ui.*;
import java.util.ArrayList;

/** Recent photos as a grid; tap to select, then read all selected pages in one go. */
final class PickerScreen extends Screen {
    private static final int MAX_PHOTOS = 90;

    private final Session s;
    private Header header;
    private BaseAdapter adapter;
    private TextView read;
    private FrameLayout content;
    private GridView grid;
    private FrameLayout modeBar;
    private LangBar langBar;
    private boolean askedOnce;

    PickerScreen(Session s) { this.s = s; }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "OCR", subtitle(), () -> c.nav.pop());
        page.addView(header.view);
        modeBar = new FrameLayout(c.a);
        page.addView(modeBar, Ui.lp(-1, c.dp(44), c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(10)));
        // general mode only: which languages to read (medical documents are always German)
        langBar = new LangBar(c, s.langs, v -> { s.langs = v; Langs.remember(c.a, "ocr", v); });
        page.addView(langBar.view, Ui.lp(-1, -2, c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(10)));
        paintModeBar();

        content = new FrameLayout(c.a);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        if (granted()) showGrid();
        else c.a.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1);
        return page;
    }

    private boolean granted() { return c.a.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED; }

    /** Back from the app settings with the permission granted: show the photos. */
    @Override public void onResume() { if (askedOnce && grid == null && granted()) showGrid(); }

    /** Back from the language download screen. */
    @Override public void onReturn() { langBar.refresh(); }

    @Override public void onPermission(int req, String[] perms, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) showGrid();
        else {
            askedOnce = true;
            boolean again = c.a.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES);
            content.removeAllViews();
            LinearLayout st = Ui.state(c, Icon.Kind.IMAGE, "Fotoğraflara erişim gerekli", "Okunacak sayfaları seçebilmek için fotoğraf izni ver.",
                again ? "İzin ver" : "Ayarları aç", () -> {
                    if (c.a.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES))
                        c.a.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1);
                    else c.a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", c.a.getPackageName(), null)));
                });
            content.addView(st, new FrameLayout.LayoutParams(-1, -1));
            Ui.appear(st, 0);
        }
    }

    /** Tıbbi (default) | Genel. Switching only changes how the selected pages are processed afterwards. */
    private void paintModeBar() {
        langBar.view.setVisibility(s.general ? View.VISIBLE : View.GONE);
        modeBar.removeAllViews();
        modeBar.addView(Ui.segmented(c, new String[]{"Tıbbi belge", "Genel metin"}, null, s.general ? 1 : 0, i -> {
            s.general = i == 1;
            paintModeBar();
            refresh(false);
        }), new FrameLayout.LayoutParams(-1, -1));
    }

    private String subtitle() {
        int n = s.selected.size();
        String mode = s.general ? "cihazda okunur" : "okunur, maskelenir, cilalanır";
        return n == 0 ? "Sayfaları seç · " + mode : n + " sayfa seçili · " + mode;
    }

    private void showGrid() {
        loadPhotos();
        if (s.photos.isEmpty()) {
            content.removeAllViews();
            LinearLayout st = Ui.state(c, Icon.Kind.IMAGE, "Fotoğraf bulunamadı", "Kamerayla çektiğin belgeler burada görünür.", null, null);
            content.addView(st, new FrameLayout.LayoutParams(-1, -1));
            Ui.appear(st, 0);
            return;
        }

        grid = new GridView(c.a);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(c.dp(6)); grid.setVerticalSpacing(c.dp(6));
        grid.setPadding(c.dp(Ui.GUTTER), c.dp(2), c.dp(Ui.GUTTER), c.dp(Ui.H_BAR + 2 * Ui.GUTTER + 8));
        grid.setClipToPadding(false); grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return s.photos.size(); }
            @Override public Object getItem(int i) { return s.photos.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                Cell cell = v instanceof Cell ? (Cell) v : new Cell();
                cell.bind(s.photos.get(i));
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) -> {
            Uri u = s.photos.get(i);
            boolean on = !s.selected.remove(u);
            if (on) s.selected.add(u);
            ((Cell) v).animateTo(on);
            Ui.haptic(v, on ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.KEYBOARD_TAP);
            refresh(true);
        });
        content.removeAllViews();
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        read = Ui.button(c, "", Ui.Style.PRIMARY, Icon.Kind.SCAN);
        read.setElevation(c.dp(6));
        read.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            String need = s.general ? s.langs : "deu";
            Runnable go = () -> c.nav.replace(new ProgressScreen(s, new ArrayList<>(s.selected)));
            if (Langs.ready(c.host, need)) go.run();
            else c.nav.push(new TessScreen(need, go));   // language data first; "Devam" then starts reading
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, c.dp(Ui.H_BAR), Gravity.BOTTOM);
        lp.setMargins(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        content.addView(read, lp);
        read.setVisibility(View.INVISIBLE);
        refresh(false);
    }

    private void refresh(boolean animate) {
        int n = s.selected.size();
        if (animate) Ui.swapText(header.subtitle, subtitle()); else header.subtitle.setText(subtitle());
        if (read == null) return;
        read.setText(n + " sayfayı oku");
        if (animate) Ui.slideVisible(read, n > 0, c.dp(24));
        else read.setVisibility(n > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void loadPhotos() {
        s.photos.clear();
        try (Cursor cur = c.a.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (cur != null && cur.moveToNext() && s.photos.size() < MAX_PHOTOS)
                s.photos.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)));
        } catch (Exception ignored) { }
    }

    /** Square thumbnail: the image shrinks inside a teal frame with a pop-in check when selected. */
    private final class Cell extends FrameLayout {
        final ImageView img; final View frame; final FrameLayout badge; Uri uri;

        Cell() {
            super(c.a);
            final float r = c.dp(12);
            img = new ImageView(c.a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(c.p.surface2);
            img.setClipToOutline(true);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            addView(img, new LayoutParams(-1, -1));
            frame = new View(c.a);
            frame.setBackground(Ui.outlined(c, 0, c.p.primary, 14));
            ((android.graphics.drawable.GradientDrawable) frame.getBackground()).setStroke(c.dp(3), c.p.primary);
            frame.setAlpha(0f);
            addView(frame, new LayoutParams(-1, -1));
            badge = new FrameLayout(c.a);
            badge.setBackground(Ui.rounded(c, c.p.primary, 12));
            badge.addView(Ui.icon(c, Icon.Kind.CHECK, c.p.onPrimary, 16), new LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER));
            badge.setScaleX(0f); badge.setScaleY(0f);
            LayoutParams bp = new LayoutParams(c.dp(24), c.dp(24), Gravity.TOP | Gravity.END);
            bp.setMargins(0, c.dp(8), c.dp(8), 0);
            addView(badge, bp);
        }

        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth()); }

        void bind(Uri u) {
            uri = u;
            applyState(s.selected.contains(u), false);
            Bitmap bm = s.thumbs.get(u);
            if (bm != null) { img.setImageBitmap(bm); img.setAlpha(1f); return; }
            img.setImageDrawable(null);
            c.io.execute(() -> {
                try {
                    Bitmap t = c.a.getContentResolver().loadThumbnail(u, new Size(320, 320), null);
                    c.ui.post(() -> {
                        s.thumbs.put(u, t);
                        if (u.equals(uri)) { img.setImageBitmap(t); img.setAlpha(0f); img.animate().alpha(1f).setDuration(220).start(); }
                    });
                } catch (Exception ignored) { }
            });
        }

        void animateTo(boolean on) { applyState(on, true); }

        private void applyState(boolean on, boolean animate) {
            float imgScale = on ? 0.86f : 1f;
            if (!animate) {
                img.setScaleX(imgScale); img.setScaleY(imgScale); frame.setAlpha(on ? 1f : 0f);
                badge.setScaleX(on ? 1f : 0f); badge.setScaleY(on ? 1f : 0f);
                return;
            }
            img.animate().scaleX(imgScale).scaleY(imgScale).setDuration(260).setInterpolator(on ? Ui.POP : Ui.STD).start();
            frame.animate().alpha(on ? 1f : 0f).setDuration(160).start();
            badge.animate().scaleX(on ? 1f : 0f).scaleY(on ? 1f : 0f).setDuration(on ? 300 : 140).setInterpolator(on ? Ui.POP : Ui.EXIT).start();
        }
    }
}
