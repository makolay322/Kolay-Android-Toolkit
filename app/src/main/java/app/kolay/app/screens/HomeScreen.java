package app.kolay.app.screens;

import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.StatFs;
import android.view.*;
import android.widget.*;
import app.kolay.app.tools.Tool;
import app.kolay.app.tools.Tools;
import app.kolay.app.tools.scan.DocsScreen;
import app.kolay.app.ui.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Landing page. A gradient hero under the status bar (name, settings, greeting, date, next prayer, update notice), then four
 * group cards (Belgeler, Dini, Araçlar, Sohbet) overlapping its lower edge and a full-width Dosyalar card with the free space.
 * A group card opens {@link GroupScreen}.
 */
public final class HomeScreen extends Screen {
    private static final String[] PRAYERS = {"Sabah", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};   // as on the prayer screen
    private static final Locale TR = new Locale("tr", "TR");
    /** Tone index (Palette.tone) of each home group; a group's tool rows continue from there. */
    private static final int T_DOCS = 0, T_FAITH = 2, T_TOOLS = 3, T_CHAT = 1, T_FILES = 5;

    private final List<View> entrance = new ArrayList<>();
    private FrameLayout hero;
    private LinearLayout heroCol, banner;
    private TextView greet, date, prayerText, bannerText, filesSub;
    private View scrim, filesFill;
    private ScrollView sv;
    private LinearLayout col;
    private boolean pastHero;

    @Override public boolean edgeToEdge() { return true; }

    @Override public boolean darkStatusIcons() { return pastHero && !c.p.dark; }

    @Override public View create() {
        Palette p = c.p;
        col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildHero(), Ui.lp(-1, -2));

        // Group cards: a 2 × 2 grid pulled up over the hero's lower edge.
        LinearLayout body = new LinearLayout(c.a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        final int gap = c.dp(12);
        View[] cards = {
            card("Belgeler", "Kamera · galeri · Wi-Fi tarayıcı", Icon.Kind.DOCSCAN, T_DOCS, () -> c.nav.push(new DocsScreen())),
            card("Dini", "Vakitler · kıble · cuma · helal", Icon.Kind.CRESCENT, T_FAITH, () -> group("Dini", "Vakitler, kıble, cuma, helal", Tools.FAITH, T_FAITH)),
            card("Araçlar", "QR · sıkıştır · YouTube · uygulamalar", Icon.Kind.APPS, T_TOOLS, () -> group("Araçlar", "Küçük yardımcılar", Tools.UTILITIES, T_TOOLS)),
            card(Tools.CHAT.title(), Tools.CHAT.subtitle(), Tools.CHAT.icon(), T_CHAT, () -> c.nav.push(Tools.CHAT.open(c))),
        };
        for (int i = 0; i < cards.length; i += 2) {
            LinearLayout row = new LinearLayout(c.a);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                if (j == i) lp.setMarginEnd(gap / 2); else lp.setMarginStart(gap / 2);
                row.addView(cards[j], lp);
                entrance.add(cards[j]);
            }
            body.addView(row, Ui.lp(-1, -2, 0, 0, 0, gap));
        }
        View files = filesCard();
        body.addView(files, Ui.lp(-1, -2));
        entrance.add(files);

        TextView foot = Ui.text(c, "Kolay-Android-Toolkit · Sürüm " + c.host.versionName(), Ui.T_CAP, p.muted, 500);
        foot.setGravity(Gravity.CENTER);
        body.addView(foot, Ui.lp(-1, -2, 0, c.dp(20), 0, 0));
        entrance.add(foot);
        LinearLayout.LayoutParams bl = Ui.lp(-1, -2);
        bl.topMargin = -c.dp(40);
        col.addView(body, bl);

        for (View v : entrance) v.setAlpha(0f);
        sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);   // the hero is the top edge; no glow above it
        sv.addView(col);
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> onScroll(y));

        FrameLayout page = new FrameLayout(c.a);
        page.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        // Status bar backdrop: fades in once the hero has scrolled away, so the clock stays readable over the cards.
        scrim = new View(c.a);
        scrim.setBackgroundColor(p.bg);
        scrim.setAlpha(0f);
        page.addView(scrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        showNextPrayer();
        loadStorage();
        return page;
    }

    @Override public void onInsets() {
        heroCol.setPadding(c.dp(20), c.insets.top + c.dp(10), c.dp(20), c.dp(64));
        col.setPadding(c.insets.left, 0, c.insets.right, c.insets.bottom + c.dp(20));
        scrim.getLayoutParams().height = c.insets.top;
        scrim.requestLayout();
    }

    // ---- hero ------------------------------------------------------------------------------------

    private View buildHero() {
        Palette p = c.p;
        hero = new FrameLayout(c.a);
        final float r = c.dp(36);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{p.heroA, p.heroMid, p.heroB});
        g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        hero.setBackground(g);
        hero.setOutlineProvider(new ViewOutlineProvider() {   // round bottom only: the top corners sit above the screen
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, -Math.round(r), v.getWidth(), v.getHeight(), r); }
        });
        hero.setClipToOutline(true);
        // Two soft light discs for depth.
        hero.addView(disc(p.dark ? 0x0DFFFFFF : 0x17FFFFFF), discLp(260, Gravity.TOP | Gravity.END, -80, -90));
        hero.addView(disc(p.dark ? 0x09FFFFFF : 0x10FFFFFF), discLp(170, Gravity.BOTTOM | Gravity.START, -70, -40));

        heroCol = new LinearLayout(c.a);
        heroCol.setOrientation(LinearLayout.VERTICAL);
        hero.addView(heroCol, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(c.a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(c.a);
        mark.setBackground(Ui.rounded(c, p.surface, 14));
        Ui.roundOutline(c, mark, 14);
        Ui.softShadow(c, mark, 3, 0xFF000000);
        mark.addView(new Logo(c.a, p.dark), new FrameLayout.LayoutParams(c.dp(30), c.dp(22), Gravity.CENTER));
        top.addView(mark, new LinearLayout.LayoutParams(c.dp(42), c.dp(42)));
        TextView name = Ui.text(c, "Kolay", 20, p.onHero, 800);
        name.setLetterSpacing(-0.01f);
        top.addView(name, Ui.lp(0, -2, c.dp(12), 0, 0, 0));
        ((LinearLayout.LayoutParams) name.getLayoutParams()).weight = 1;
        top.addView(glassButton(Icon.Kind.SETTINGS, "Ayarlar", () -> c.nav.push(new SettingsScreen())));
        heroCol.addView(top);

        greet = Ui.text(c, "", Ui.T_DISPLAY, p.onHero, 800);
        greet.setLetterSpacing(-0.02f);
        heroCol.addView(greet, Ui.lp(-1, -2, 0, c.dp(28), 0, 0));
        date = Ui.text(c, "", 15, withAlpha(p.onHero, 0xD9), 500);
        heroCol.addView(date, Ui.lp(-1, -2, 0, c.dp(6), 0, 0));

        LinearLayout chip = glassChip(Icon.Kind.CLOCK);
        prayerText = (TextView) chip.getChildAt(1);
        chip.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(Tools.FAITH[0].open(c)); });
        heroCol.addView(chip, Ui.lp(-2, c.dp(40), 0, c.dp(18), 0, 0));

        // "New version" notice (only when a downloaded update waits)
        banner = glassChip(Icon.Kind.REFRESH);
        bannerText = (TextView) banner.getChildAt(1);
        bannerText.setText("Yeni sürüm hazır — dokun, kurulsun");
        banner.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            c.host.installUpdate(msg -> bannerText.setText(msg));
        });
        banner.setVisibility(View.GONE);
        heroCol.addView(banner, Ui.lp(-2, -2, 0, c.dp(10), 0, 0));
        banner.setMinimumHeight(c.dp(40));
        refreshGreeting();
        return hero;
    }

    private View disc(int color) { View v = new View(c.a); v.setBackground(Ui.oval(color)); return v; }

    private FrameLayout.LayoutParams discLp(int size, int gravity, int dx, int dy) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(c.dp(size), c.dp(size), gravity);
        if ((gravity & Gravity.END) == Gravity.END) lp.setMarginEnd(c.dp(dx)); else lp.setMarginStart(c.dp(dx));
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = c.dp(dy); else lp.bottomMargin = c.dp(dy);
        return lp;
    }

    /** Round translucent-white button on the hero. */
    private View glassButton(Icon.Kind k, String desc, Runnable r) {
        FrameLayout f = new FrameLayout(c.a);
        f.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            Ui.oval(0x2EFFFFFF), Ui.oval(0xFFFFFFFF)));
        f.addView(Ui.icon(c, k, c.p.onHero, 22), new FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER));
        f.setContentDescription(desc); f.setClickable(true);
        f.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        Ui.press(f);
        f.setLayoutParams(new LinearLayout.LayoutParams(c.dp(44), c.dp(44)));
        return f;
    }

    /** Pill on the hero: icon + one line of text, translucent white. Child 1 is the text. */
    private LinearLayout glassChip(Icon.Kind k) {
        LinearLayout chip = new LinearLayout(c.a);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(c.dp(14), c.dp(8), c.dp(16), c.dp(8));
        chip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            Ui.rounded(c, 0x2EFFFFFF, 20), Ui.rounded(c, 0xFFFFFFFF, 20)));
        chip.addView(Ui.icon(c, k, c.p.onHero, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        TextView t = Ui.text(c, "", 14, c.p.onHero, 600);
        t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.addView(t, Ui.lp(-2, -2, c.dp(10), 0, 0, 0));
        chip.setClickable(true);
        Ui.press(chip);
        return chip;
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    private void refreshGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greet.setText(h >= 5 && h < 11 ? "Günaydın" : h < 17 && h >= 11 ? "İyi günler" : h >= 17 && h < 22 ? "İyi akşamlar" : "İyi geceler");
        String d = new SimpleDateFormat("d MMMM, EEEE", TR).format(new Date());
        date.setText(d);
    }

    // ---- cards -----------------------------------------------------------------------------------

    /** Group card: tone glow in the corner, gradient icon tile, title, up to two lines of subtitle. */
    private View card(String title, String subtitle, Icon.Kind icon, int tone, Runnable open) {
        Palette p = c.p;
        Palette.Tone t = p.tone(tone);
        FrameLayout card = new FrameLayout(c.a);
        styleCard(card);
        View glow = new View(c.a);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(c.dp(70));
        gd.setColors(new int[]{withAlpha(t.a, p.dark ? 0x22 : 0x26), withAlpha(t.a, 0)});
        glow.setBackground(gd);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(c.dp(140), c.dp(140), Gravity.TOP | Gravity.END);
        gl.topMargin = -c.dp(50); gl.setMarginEnd(-c.dp(50));
        card.addView(glow, gl);

        LinearLayout in = new LinearLayout(c.a);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16));
        in.setMinimumHeight(c.dp(164));
        in.addView(Ui.toneTile(c, icon, t, 50, 25, 16));
        TextView name = Ui.text(c, title, Ui.T_HEAD, p.ink, 700);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        in.addView(name, Ui.lp(-1, -2, 0, c.dp(16), 0, 0));
        TextView sub = Ui.text(c, subtitle, Ui.T_SUB, p.muted, 400);
        sub.setMaxLines(2); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); sub.setLineSpacing(0, 1.15f);
        in.addView(sub, Ui.lp(-1, -2, 0, c.dp(4), 0, 0));
        card.addView(in, new FrameLayout.LayoutParams(-1, -1));
        card.setContentDescription(title + ". " + subtitle);
        card.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); open.run(); });
        Ui.press(card);
        return card;
    }

    /** Full-width Dosyalar card: icon tile, title, free space of the internal storage with a usage bar, chevron. */
    private View filesCard() {
        Palette p = c.p;
        Palette.Tone t = p.tone(T_FILES);
        FrameLayout card = new FrameLayout(c.a);
        styleCard(card);
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16));
        row.addView(Ui.toneTile(c, Tools.FILES.icon(), t, 50, 25, 16));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, Tools.FILES.title(), Ui.T_HEAD, p.ink, 700);
        txt.addView(name);
        filesSub = Ui.text(c, Tools.FILES.subtitle(), Ui.T_SUB, p.muted, 400);
        filesSub.setSingleLine(true); filesSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(filesSub, Ui.lp(-1, -2, 0, c.dp(4), 0, 0));
        FrameLayout track = new FrameLayout(c.a);
        track.setBackground(Ui.rounded(c, p.surface2, 3));
        filesFill = new View(c.a);
        filesFill.setBackground(Ui.gradient(c, t.a, t.b, 3, GradientDrawable.Orientation.LEFT_RIGHT));
        filesFill.setPivotX(0); filesFill.setScaleX(0f);
        track.addView(filesFill, new FrameLayout.LayoutParams(-1, -1));
        track.setVisibility(View.INVISIBLE);
        txt.addView(track, Ui.lp(-1, c.dp(6), 0, c.dp(10), c.dp(8), 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(16));
        row.addView(txt, tl);
        row.addView(Ui.icon(c, Icon.Kind.CHEVRON, p.muted, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        card.addView(row, new FrameLayout.LayoutParams(-1, -2));
        card.setContentDescription(Tools.FILES.title());
        card.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(Tools.FILES.open(c)); });
        Ui.press(card);
        return card;
    }

    /** Card surface: rounded fill, ripple on top of the content, tinted shadow (light) or hairline (dark), clipped. */
    private void styleCard(FrameLayout card) {
        Palette p = c.p;
        card.setBackground(p.dark ? Ui.outlined(c, p.surface, p.line, Ui.R_CARD) : Ui.rounded(c, p.surface, Ui.R_CARD));
        card.setForeground(Ui.ripple(c, Ui.R_CARD));
        Ui.roundOutline(c, card, Ui.R_CARD);
        card.setClipToOutline(true);
        if (!p.dark) Ui.softShadow(c, card, 3, p.shadow);
        card.setClickable(true);
    }

    /** Free space of the internal storage, read off the UI thread. */
    private void loadStorage() {
        c.io.execute(() -> {
            long total, free;
            try {
                StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
                total = st.getTotalBytes(); free = st.getAvailableBytes();
            } catch (RuntimeException e) { return; }
            if (total <= 0) return;
            String s = "Dahili depolama · " + app.kolay.app.tools.files.FilesTool.size(free) + " boş";
            float frac = (total - free) / (float) total;
            c.ui.post(() -> {
                if (filesSub == null) return;
                filesSub.setText(s);
                View track = (View) filesFill.getParent();
                track.setVisibility(View.VISIBLE);
                filesFill.animate().scaleX(frac).setDuration(Ui.D_SLOW).setInterpolator(Ui.EMPH).start();
            });
        });
    }

    private void group(String title, String subtitle, Tool[] tools, int tone) { c.nav.push(new GroupScreen(title, subtitle, tools, tone)); }

    // ---- next prayer ---------------------------------------------------------------------------

    /** Hero chip: the next prayer time when a location is saved, else an invitation to set one; never fails. */
    private void showNextPrayer() {
        if (prayerText == null) return;
        String next = null;
        try { if (c.host.prayerLocation() != null) next = nextPrayer(); } catch (RuntimeException ignored) { }
        prayerText.setText(next != null ? next : "Namaz vakitleri için konumu seç");
    }

    /** "Sonraki: İkindi 14:32 · 1 sa 12 dk" (today or tomorrow, sunrise included as on the prayer screen), or null. */
    private String nextPrayer() {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        for (int d = 0; d < 2; d++) {
            long[] t = c.host.prayerTimes(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            if (t == null) return null;
            for (int i = 0; i < t.length && i < PRAYERS.length; i++)
                if (t[i] > now) {
                    long min = (t[i] - now) / 60000;
                    String left = min >= 60 ? (min / 60) + " sa " + (min % 60) + " dk" : Math.max(1, min) + " dk";
                    return "Sonraki: " + PRAYERS[i] + " " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(t[i])) + " · " + left;
                }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return null;
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    private void onScroll(int y) {
        int limit = Math.max(1, hero.getHeight() - c.insets.top - c.dp(40));
        scrim.setAlpha(Math.min(1f, Math.max(0f, (y - limit * 0.6f) / (limit * 0.4f))));
        boolean past = y > limit;
        if (past != pastHero) { pastHero = past; c.lightBars(darkStatusIcons()); }
    }

    @Override public void onShown() {
        Ui.stagger(entrance, 0);
        c.host.onUpdateReady(this::showBanner);
        if (c.host.updateReady()) showBanner();
    }

    @Override public void onResume() { if (c.host.updateReady()) showBanner(); refreshGreeting(); showNextPrayer(); }

    @Override public void onReturn() { refreshGreeting(); showNextPrayer(); loadStorage(); }

    private void showBanner() {
        if (banner == null) return;
        String n = c.host.updateName();
        bannerText.setText("Yeni sürüm hazır" + (n.isEmpty() ? "" : " (" + n + ")") + " — dokun, kurulsun");
        if (banner.getVisibility() == View.VISIBLE) return;
        banner.setVisibility(View.VISIBLE);
        Ui.appear(banner, 0);
    }
}
