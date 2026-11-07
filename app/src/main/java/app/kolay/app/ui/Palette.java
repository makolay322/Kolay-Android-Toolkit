package app.kolay.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.R;
import android.os.Build;

/**
 * Color tokens. Every screen reads colors from here; nothing hard-codes a hex value.
 * On Android 12+ the accent and neutral tones come from the system's wallpaper palette (the same source as the
 * launcher icon's colors), so the app follows the icon; older versions use the fixed teal set below.
 * {@link #tone}/{@link #softBg}/{@link #softFg} give a small fixed set of category hues (blue, violet, green, amber…) so
 * rows of icon tiles are lively but stable; the accent ({@link #primary}) stays the wallpaper color.
 */
public final class Palette {
    public final boolean dark;
    public final int bg, surface, surface2, ink, muted, line, primary, onPrimary, primarySoft, onPrimarySoft, danger,
        ok, okSoft, warn, warnSoft, dangerSoft, track, thumb, scrim, ripple;
    /** Home hero gradient (accent → its tertiary partner) and the text on it. */
    public final int heroA, heroMid, heroB, onHero;
    /** Inverse surface (snackbars) and its text. */
    public final int inverse, onInverse;
    /** Tint of the soft shadows under raised surfaces (light theme). */
    public final int shadow;
    /** Named category tones: the home groups and the file types use these so the same thing always has the same color. */
    public final Tone blue, violet, green, amber, rose, sky, teal, red;
    private final Tone[] cycle;
    private final int[] softBg, softFg;

    private Palette(Context c, boolean dark) {
        this.dark = dark;
        // Semantic colors stay fixed: red/green/amber must keep their meaning whatever the wallpaper is.
        if (dark) {
            danger = 0xFFFF8A80; ok = 0xFF6EE79A; okSoft = 0xFF173B25; warn = 0xFFFFC857; warnSoft = 0xFF3F3012; dangerSoft = 0xFF4A1F1C;
        } else {
            danger = 0xFFB3261E; ok = 0xFF1B7F3B; okSoft = 0xFFDDF3E4; warn = 0xFF9A5B00; warnSoft = 0xFFFFEBC8; dangerSoft = 0xFFFADAD7;
        }
        scrim = 0x99000000;
        if (Build.VERSION.SDK_INT >= 31) {
            Sys s = new Sys(c);
            if (dark) {
                bg = s.n1(900); surface = mix(s.n1(900), s.n1(800), 0.45f); surface2 = s.n1(800); ink = s.n1(100); muted = s.n2(300);
                line = mix(s.n1(800), s.n2(700), 0.5f); primary = s.a1(200); onPrimary = s.a1(800);
                primarySoft = s.a1(800); onPrimarySoft = s.a1(100); track = s.n2(700); thumb = s.n1(100);
            } else {
                bg = s.n2(50); surface = s.n1(10); surface2 = s.n2(100); ink = s.n1(900); muted = s.n2(700);
                line = mix(s.n2(100), s.n2(200), 0.3f); primary = s.a1(600); onPrimary = s.a1(0);
                primarySoft = s.a1(100); onPrimarySoft = s.a1(800); track = s.n2(200); thumb = s.n1(10);
            }
        } else if (dark) {
            bg = 0xFF0E1417; surface = 0xFF162025; surface2 = 0xFF1E2A30; ink = 0xFFE8EFF1; muted = 0xFF8FA1A9; line = 0xFF2A383F;
            primary = 0xFF2DD4BF; onPrimary = 0xFF042925; primarySoft = 0xFF133A37; onPrimarySoft = 0xFF7FF0E1;
            track = 0xFF34444C; thumb = 0xFFE8EFF1;
        } else {
            bg = 0xFFF4F6F7; surface = 0xFFFFFFFF; surface2 = 0xFFEAEFF1; ink = 0xFF14201F; muted = 0xFF64727C; line = 0xFFE0E6E9;
            primary = 0xFF0F766E; onPrimary = 0xFFFFFFFF; primarySoft = 0xFFD9F0EC; onPrimarySoft = 0xFF0B5A54;
            track = 0xFFC9D2D6; thumb = 0xFFFFFFFF;
        }
        ripple = dark ? 0x22FFFFFF : (primary & 0x00FFFFFF) | 0x1E000000;
        inverse = dark ? ink : mix(ink, 0xFF000000, 0.1f);
        onInverse = dark ? bg : surface;
        shadow = dark ? 0xFF000000 : mix(primary, 0xFF0B1220, 0.7f);
        onHero = 0xFFFFFFFF;
        if (Build.VERSION.SDK_INT >= 31) {
            Sys s = new Sys(c);
            heroA = dark ? s.a1(800) : s.a1(600);
            heroMid = dark ? s.a1(600) : s.a1(500);
            heroB = dark ? s.a3(600) : s.a3(400);
        } else {
            heroA = dark ? 0xFF064E48 : 0xFF0F766E;
            heroMid = dark ? 0xFF0D7A70 : 0xFF14A394;
            heroB = dark ? 0xFF3A4A9C : 0xFF7C93F2;
        }
        // Gradient ends are the same in both themes (white icons sit on them); only the soft tile tones change.
        blue   = new Tone(dark, 0xFF5B7CFA, 0xFF7C5CF6, 0xFFE4E9FF, 0xFF3446C2, 0xFF242C52, 0xFFBAC6FF);
        violet = new Tone(dark, 0xFFA855F7, 0xFFE0569E, 0xFFF3E5FF, 0xFF8028BF, 0xFF35214D, 0xFFE3BCFF);
        green  = new Tone(dark, 0xFF12B886, 0xFF0D9AA6, 0xFFDBF4EA, 0xFF0A7350, 0xFF12362C, 0xFF8EE2C3);
        amber  = new Tone(dark, 0xFFF7A21B, 0xFFF46B55, 0xFFFFEFD6, 0xFF9A5200, 0xFF3E2D14, 0xFFFFD08C);
        rose   = new Tone(dark, 0xFFF4517A, 0xFFFF8A65, 0xFFFFE3E9, 0xFFBE1E48, 0xFF46202B, 0xFFFFB5C3);
        sky    = new Tone(dark, 0xFF22A7F0, 0xFF4F7DF7, 0xFFDCF0FC, 0xFF0A6A9E, 0xFF13324A, 0xFF9FD7F8);
        teal   = new Tone(dark, 0xFF14B8A6, 0xFF22A2E0, 0xFFD7F5F1, 0xFF0B6E63, 0xFF0F3735, 0xFF8BE6DA);
        red    = new Tone(dark, 0xFFEF4B4B, 0xFFF6803A, 0xFFFFE2DE, 0xFFB42318, 0xFF48201D, 0xFFFFB4AB);
        cycle = new Tone[]{blue, violet, green, amber, rose, sky, teal};
        softBg = new int[cycle.length]; softFg = new int[cycle.length];
        for (int i = 0; i < cycle.length; i++) { softBg[i] = cycle[i].soft; softFg[i] = cycle[i].onSoft; }
    }

    /** One hue in two forms: a vivid gradient ({@link #a} → {@link #b}, white content) and a soft tile ({@link #soft} + {@link #onSoft}). */
    public static final class Tone {
        public final int a, b, soft, onSoft;
        Tone(boolean dark, int a, int b, int softL, int onSoftL, int softD, int onSoftD) {
            // In the dark theme the gradient is a little deeper so it does not glare against the near-black page.
            this.a = dark ? mix(a, 0xFF000000, 0.12f) : a; this.b = dark ? mix(b, 0xFF000000, 0.12f) : b;
            soft = dark ? softD : softL; onSoft = dark ? onSoftD : onSoftL;
        }
    }

    /** The i-th tone of the cycle (neighbouring indexes differ in hue). */
    public Tone tone(int i) { return cycle[Math.floorMod(i, cycle.length)]; }

    /** Background of the i-th tonal tile (icon boxes in lists); cycles through close variants of the accent. */
    public int softBg(int i) { return softBg[Math.floorMod(i, softBg.length)]; }
    /** Icon/text color matching {@link #softBg(int)}. */
    public int softFg(int i) { return softFg[Math.floorMod(i, softFg.length)]; }

    public static Palette of(Context c) {
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Palette(c, night == Configuration.UI_MODE_NIGHT_YES);
    }

    /** Linear blend of two opaque colors, t = 0 → a, 1 → b. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** The system's wallpaper-derived tonal palettes (Android 12+). Tone keys follow the platform names: 0 = white … 1000 = black. */
    @android.annotation.TargetApi(31)
    private static final class Sys {
        private final Context c;
        Sys(Context c) { this.c = c; }
        int a1(int t) { return get(t, A1); }
        int a2(int t) { return get(t, A2); }
        int a3(int t) { return get(t, A3); }
        int n1(int t) { return get(t, N1); }
        int n2(int t) { return get(t, N2); }

        private static final int[] TONES = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        private static final int[] A1 = {R.color.system_accent1_0, R.color.system_accent1_10, R.color.system_accent1_50, R.color.system_accent1_100, R.color.system_accent1_200, R.color.system_accent1_300, R.color.system_accent1_400, R.color.system_accent1_500, R.color.system_accent1_600, R.color.system_accent1_700, R.color.system_accent1_800, R.color.system_accent1_900, R.color.system_accent1_1000};
        private static final int[] A2 = {R.color.system_accent2_0, R.color.system_accent2_10, R.color.system_accent2_50, R.color.system_accent2_100, R.color.system_accent2_200, R.color.system_accent2_300, R.color.system_accent2_400, R.color.system_accent2_500, R.color.system_accent2_600, R.color.system_accent2_700, R.color.system_accent2_800, R.color.system_accent2_900, R.color.system_accent2_1000};
        private static final int[] A3 = {R.color.system_accent3_0, R.color.system_accent3_10, R.color.system_accent3_50, R.color.system_accent3_100, R.color.system_accent3_200, R.color.system_accent3_300, R.color.system_accent3_400, R.color.system_accent3_500, R.color.system_accent3_600, R.color.system_accent3_700, R.color.system_accent3_800, R.color.system_accent3_900, R.color.system_accent3_1000};
        private static final int[] N1 = {R.color.system_neutral1_0, R.color.system_neutral1_10, R.color.system_neutral1_50, R.color.system_neutral1_100, R.color.system_neutral1_200, R.color.system_neutral1_300, R.color.system_neutral1_400, R.color.system_neutral1_500, R.color.system_neutral1_600, R.color.system_neutral1_700, R.color.system_neutral1_800, R.color.system_neutral1_900, R.color.system_neutral1_1000};
        private static final int[] N2 = {R.color.system_neutral2_0, R.color.system_neutral2_10, R.color.system_neutral2_50, R.color.system_neutral2_100, R.color.system_neutral2_200, R.color.system_neutral2_300, R.color.system_neutral2_400, R.color.system_neutral2_500, R.color.system_neutral2_600, R.color.system_neutral2_700, R.color.system_neutral2_800, R.color.system_neutral2_900, R.color.system_neutral2_1000};

        private int get(int tone, int[] ids) {
            for (int i = 0; i < TONES.length; i++) if (TONES[i] == tone) return c.getColor(ids[i]);
            throw new IllegalArgumentException("tone " + tone);
        }
    }
}
