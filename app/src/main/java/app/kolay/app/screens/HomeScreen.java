package app.kolay.app.screens;

import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
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
 * Landing page. A gradient hero under the status bar (name, settings, greeting, date, weather now + 24 h strip, update notice), then four
 * group cards (Belgeler, Dini, Araçlar, Dosyalar) overlapping its lower edge.
 * A group card opens {@link GroupScreen}.
 */
public final class HomeScreen extends Screen {
    private static final Locale TR = new Locale("tr", "TR");
    /** Tone index (Palette.tone) of each home group; a group's tool rows continue from there. */
    private static final int T_DOCS = 0, T_FAITH = 2, T_TOOLS = 3, T_FILES = 5;

    private final List<View> entrance = new ArrayList<>();
    private FrameLayout hero;
    private LinearLayout heroCol, banner;
    private TextView greet, date, wxNow, wxCond, bannerText;
    private LinearLayout wxSide, wxHours;
    private View wxStrip;
    private boolean wxLoading;
    private View scrim;
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
            card("Belgeler", "Kamera · galeri · Wi-Fi · PDF düzenle", Icon.Kind.DOCSCAN, T_DOCS, () -> c.nav.push(new DocsScreen())),
            card("Dini", "Vakitler · kıble · cuma · helal", Icon.Kind.CRESCENT, T_FAITH, () -> group("Dini", "Vakitler, kıble, cuma, helal", Tools.FAITH, T_FAITH)),
            card("Araçlar", "QR · sıkıştır · YouTube · uygulamalar", Icon.Kind.APPS, T_TOOLS, () -> group("Araçlar", "Küçük yardımcılar", Tools.UTILITIES, T_TOOLS)),
            card(Tools.FILES.title(), Tools.FILES.subtitle(), Tools.FILES.icon(), T_FILES, () -> c.nav.push(Tools.FILES.open(c))),
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

        foot = Ui.text(c, "Kolay-Android-Toolkit · Sürüm " + c.host.versionName(), Ui.T_CAP, p.muted, 500);
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
        showWeather();
        // Tall screens: let the cards grow into the empty space below the grid instead of leaving it blank.
        View.OnLayoutChangeListener fit = (v, l, t, r, b, ol, ot, or, ob) -> fitCards();
        sv.addOnLayoutChangeListener(fit);
        hero.addOnLayoutChangeListener(fit);
        return page;
    }

    private TextView foot;
    private final java.util.ArrayList<View> cardBodies = new java.util.ArrayList<>();

    private void fitCards() {
        if (sv == null || sv.getHeight() == 0 || hero.getHeight() == 0 || foot == null) return;
        int free = sv.getHeight() - hero.getHeight() + c.dp(40) - c.dp(12) * 2 - c.dp(20) - foot.getHeight()
                - c.dp(20) - c.insets.bottom;
        int h = Math.max(c.dp(164), Math.min(c.dp(200), free / 2));
        final int fh = h;   // applied after this layout pass: a request raised inside a layout listener is dropped
        sv.post(() -> { for (View v : cardBodies) if (v.getMinimumHeight() != fh) v.setMinimumHeight(fh); });
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

        // Greeting + date on the left, current weather on the right.
        LinearLayout head = new LinearLayout(c.a);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(c.a);
        left.setOrientation(LinearLayout.VERTICAL);
        greet = Ui.text(c, "", Ui.T_DISPLAY, p.onHero, 800);
        greet.setLetterSpacing(-0.02f);
        left.addView(greet);
        date = Ui.text(c, "", 15, withAlpha(p.onHero, 0xD9), 500);
        left.addView(date, Ui.lp(-1, -2, 0, c.dp(6), 0, 0));
        head.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        wxSide = new LinearLayout(c.a);
        wxSide.setOrientation(LinearLayout.VERTICAL);
        wxSide.setGravity(Gravity.END);
        wxNow = Ui.text(c, "", 26, p.onHero, 800);
        wxNow.setMaxLines(1);
        wxSide.addView(wxNow);
        wxCond = Ui.text(c, "", Ui.T_SUB, withAlpha(p.onHero, 0xD9), 500);
        wxCond.setSingleLine(true);
        wxSide.addView(wxCond, Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
        wxSide.setVisibility(View.GONE);
        head.addView(wxSide, Ui.lp(-2, -2, c.dp(12), 0, 0, 0));
        heroCol.addView(head, Ui.lp(-1, -2, 0, c.dp(28), 0, 0));

        // 24 h strip: hour, symbol, temperature, rain chance (and amount) per hour; hidden without data.
        HorizontalScrollView hs = new HorizontalScrollView(c.a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setBackground(Ui.rounded(c, 0x2EFFFFFF, 20));
        wxHours = new LinearLayout(c.a);
        wxHours.setPadding(c.dp(6), c.dp(8), c.dp(6), c.dp(8));
        hs.addView(wxHours);
        hs.setVisibility(View.GONE);
        wxStrip = hs;
        heroCol.addView(hs, Ui.lp(-1, -2, 0, c.dp(16), 0, 0));

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
        cardBodies.add(in);
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

    private void group(String title, String subtitle, Tool[] tools, int tone) { c.nav.push(new GroupScreen(title, subtitle, tools, tone)); }

    // ---- weather -------------------------------------------------------------------------------

    /** Shows the cached forecast for the saved prayer location, refreshing it when older than 45 min; hidden without data. */
    private void showWeather() {
        if (wxSide == null) return;
        double[] loc = null;
        try { loc = c.host.prayerLocation(); } catch (RuntimeException ignored) { }
        if (loc == null) { renderWeather(null); return; }
        final String key = Weather.key(loc);
        renderWeather(Weather.cached(c.a, key));
        long age = Weather.age(c.a, key);
        if (wxLoading || age >= 0 && age < Weather.FRESH_MS) return;
        wxLoading = true;
        final android.content.Context app = c.a.getApplicationContext();
        c.io.execute(() -> {
            Weather w = null;
            try { Weather.fetch(app, key); w = Weather.cached(app, key); } catch (Exception ignored) { }   // offline: keep what is shown
            final Weather got = w;
            c.ui.post(() -> { wxLoading = false; if (got != null && wxSide != null) renderWeather(got); });
        });
    }

    private void renderWeather(Weather w) {
        int vis = w == null ? View.GONE : View.VISIBLE;
        wxSide.setVisibility(vis); wxStrip.setVisibility(vis);
        if (w == null) return;
        wxNow.setText(Weather.symbol(w.code, w.day) + " " + Math.round(w.temp) + "°");
        wxCond.setText(Weather.label(w.code));
        wxHours.removeAllViews();
        int soft = withAlpha(c.p.onHero, 0xCC);
        for (int i = 0; i < w.hour.length; i++) {
            LinearLayout col = new LinearLayout(c.a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(Ui.text(c, i == 0 ? "Şimdi" : w.hour[i], Ui.T_CAP, soft, 500));
            col.addView(Ui.text(c, Weather.symbol(w.codes[i], w.days[i]), 16, c.p.onHero, 400), Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
            col.addView(Ui.text(c, Math.round(w.t[i]) + "°", 14, c.p.onHero, 700), Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
            String rain = "%" + w.prob[i] + (w.mm[i] >= 0.1 ? "\n" + String.format(TR, "%.1f", w.mm[i]) + " mm" : "");
            TextView r = Ui.text(c, rain, 11, w.prob[i] >= 30 || w.mm[i] >= 0.1 ? c.p.onHero : soft, w.prob[i] >= 30 ? 700 : 500);
            r.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(r, Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
            col.setContentDescription(w.hour[i] + ":00, " + Math.round(w.t[i]) + " derece, yağış olasılığı yüzde " + w.prob[i]);
            wxHours.addView(col, Ui.lp(c.dp(46), -2));
        }
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

    @Override public void onResume() { if (c.host.updateReady()) showBanner(); refreshGreeting(); showWeather(); }

    @Override public void onReturn() { refreshGreeting(); showWeather(); }

    private void showBanner() {
        if (banner == null) return;
        String n = c.host.updateName();
        bannerText.setText("Yeni sürüm hazır" + (n.isEmpty() ? "" : " (" + n + ")") + " — dokun, kurulsun");
        if (banner.getVisibility() == View.VISIBLE) return;
        banner.setVisibility(View.VISIBLE);
        Ui.appear(banner, 0);
    }
}
