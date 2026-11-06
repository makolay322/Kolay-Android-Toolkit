package app.kolay.app.screens;

import android.view.*;
import android.widget.*;
import app.kolay.app.tools.Tool;
import app.kolay.app.tools.Tools;
import app.kolay.app.ui.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Landing page: four cards (Belgeler, Dini, Araçlar, Sohbet); a group card opens {@link GroupScreen}. */
public final class HomeScreen extends Screen {
    private static final String FAITH_SUB = "Vakitler · kıble · cuma · helal";
    private static final String[] PRAYERS = {"Sabah", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};   // as on the prayer screen
    private final List<View> entrance = new ArrayList<>();
    private TextView faithSub;
    private LinearLayout banner;
    private TextView bannerText;
    private Logo logo;

    @Override public View create() {
        Palette p = c.p;
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), c.dp(8), c.dp(Ui.GUTTER), c.dp(24));

        // Header: a slim bar with the live logo, the wordmark and settings.
        LinearLayout head = new LinearLayout(c.a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        logo = new Logo(c.a, p.dark);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(c.dp(43), c.dp(32));
        ll.setMarginEnd(c.dp(12));
        head.addView(logo, ll);
        LinearLayout titles = new LinearLayout(c.a);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, "Kolay-Android-Toolkit", 20, p.ink, 700);
        name.setLetterSpacing(-0.02f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(name);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.iconButton(c, Icon.Kind.SETTINGS, "Ayarlar", () -> c.nav.push(new SettingsScreen())));
        col.addView(head);
        entrance.add(head);

        // "New version" banner (only when a downloaded update waits)
        banner = new LinearLayout(c.a);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(8));
        Ui.surface(c, banner, p.primarySoft, 16, false);
        banner.setClickable(true);
        banner.addView(Ui.icon(c, Icon.Kind.REFRESH, p.onPrimarySoft, 18));
        bannerText = Ui.text(c, "Yeni sürüm hazır — dokun, kurulsun", 13, p.onPrimarySoft, 600);
        TextView bt = bannerText;
        banner.addView(bt, Ui.lp(0, -2, c.dp(12), 0, 0, 0));
        ((LinearLayout.LayoutParams) bt.getLayoutParams()).weight = 1;
        banner.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            c.host.installUpdate(msg -> bannerText.setText(msg));
        });
        Ui.press(banner);
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        // 2 × 2 grid: three groups and the chat.
        final int gap = c.dp(12);
        View[] cards = {
            card("Belgeler", "Tarayıcı · ağ tarayıcı · OCR", Icon.Kind.DOCSCAN, 0, () -> group("Belgeler", "Tara, kaydet, metne çevir", Tools.DOCUMENTS)),
            card("Dini", FAITH_SUB, Icon.Kind.CRESCENT, 1, () -> group("Dini", "Vakitler, kıble, cuma, helal", Tools.FAITH)),
            card("Araçlar", "QR · sıkıştır · YouTube · uygulamalar", Icon.Kind.APPS, 2, () -> group("Araçlar", "Küçük yardımcılar", Tools.UTILITIES)),
            card(Tools.CHAT.title(), Tools.CHAT.subtitle(), Tools.CHAT.icon(), 3, () -> c.nav.push(Tools.CHAT.open(c))),
        };
        faithSub = (TextView) cards[1].getTag();
        for (int i = 0; i < cards.length; i += 2) {
            LinearLayout row = new LinearLayout(c.a);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                if (j == i) lp.setMarginEnd(gap / 2); else lp.setMarginStart(gap / 2);
                row.addView(cards[j], lp);
                entrance.add(cards[j]);
            }
            col.addView(row, Ui.lp(-1, -2, 0, i == 0 ? c.dp(16) : 0, 0, gap));
        }
        showNextPrayer();

        TextView foot = Ui.text(c, "Sürüm " + c.host.versionName(), 12, p.muted, 400);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, Ui.lp(-1, -2, 0, c.dp(16), 0, 0));
        entrance.add(foot);

        for (View v : entrance) v.setAlpha(0f);
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        return sv;
    }

    /** A square card: icon tile, title, up to two lines of subtitle (the subtitle view is the card's tag). */
    private View card(String title, String subtitle, Icon.Kind icon, int idx, Runnable open) {
        Palette p = c.p;
        LinearLayout card = new LinearLayout(c.a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16));
        card.setMinimumHeight(c.dp(148));
        Ui.surface(c, card, p.surface, 22, true);
        card.setClickable(true);

        FrameLayout tile = new FrameLayout(c.a);
        tile.setBackground(Ui.rounded(c, p.softBg(idx), 16));
        tile.addView(Ui.icon(c, icon, p.softFg(idx), 26), new FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER));
        card.addView(tile, new LinearLayout.LayoutParams(c.dp(52), c.dp(52)));

        TextView name = Ui.text(c, title, 16, p.ink, 600);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, Ui.lp(-1, -2, 0, c.dp(14), 0, 0));
        TextView sub = Ui.text(c, subtitle, 13, p.muted, 400);
        sub.setMaxLines(2); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); sub.setLineSpacing(0, 1.1f);
        card.addView(sub, Ui.lp(-1, -2, 0, c.dp(4), 0, 0));
        card.setContentDescription(title);
        card.setTag(sub);

        card.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); open.run(); });
        Ui.press(card);
        return card;
    }

    private void group(String title, String subtitle, Tool[] tools) { c.nav.push(new GroupScreen(title, subtitle, tools)); }

    /** "Dini" card: the next prayer time when a location is saved, else a fixed line; never fails. */
    private void showNextPrayer() {
        if (faithSub == null) return;
        String next = null;
        try { if (c.host.prayerLocation() != null) next = nextPrayer(); } catch (RuntimeException ignored) { }
        faithSub.setText(next != null ? next : FAITH_SUB);
    }

    /** "Sonraki: İkindi 14:32" (today or tomorrow, sunrise included as on the prayer screen), or null. */
    private String nextPrayer() {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        for (int d = 0; d < 2; d++) {
            long[] t = c.host.prayerTimes(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            if (t == null) return null;
            for (int i = 0; i < t.length && i < PRAYERS.length; i++)
                if (t[i] > now) return "Sonraki: " + PRAYERS[i] + " " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(t[i]));
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return null;
    }

    @Override public void onShown() {
        long d = 40;
        for (View v : entrance) { Ui.appear(v, d); d += 55; }
        c.host.onUpdateReady(this::showBanner);
        if (c.host.updateReady()) showBanner();
    }

    @Override public void onResume() { if (c.host.updateReady()) showBanner(); showNextPrayer(); }

    @Override public void onReturn() { showNextPrayer(); }

    private void showBanner() {
        if (banner == null) return;
        String n = c.host.updateName();
        bannerText.setText("Yeni sürüm hazır" + (n.isEmpty() ? "" : " (" + n + ")") + " — dokun, kurulsun");
        if (banner.getVisibility() == View.VISIBLE) return;
        banner.setVisibility(View.VISIBLE);
        Ui.appear(banner, 0);
    }
}
