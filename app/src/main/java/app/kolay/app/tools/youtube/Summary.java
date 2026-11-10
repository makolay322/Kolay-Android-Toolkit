package app.kolay.app.tools.youtube;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import app.kolay.app.tools.Llm;
import app.kolay.app.ui.Ctx;
import app.kolay.shell.Host;

/** The summary request and its on-screen look. The model writes a tiny fixed markup (## heading, - bullet, **bold**); {@link #render} turns it into styled text, {@link #plain} into copyable text. */
final class Summary {
    private static final int MAX_CHARS = 400_000;
    private static final String PROMPT =
        "Aşağıda bir YouTube videosunun transkripti var (dil: %s). Videoyu TÜRKÇE ve telefon ekranında hızlıca taranacak şekilde özetle.\n\n"
        + "Çıktı tam olarak şu yapıda olsun:\n"
        + "## Kısaca\n"
        + "Videonun ne anlattığı ve vardığı sonuç: en fazla 3 kısa cümle.\n"
        + "## Ana noktalar\n"
        + "- Her satır tek bir fikir, en fazla 2 kısa cümle. En önemli fikir en üstte. 5-8 madde (çok uzun videoda 10'a kadar).\n"
        + "- Her maddede en önemli ifadeyi **çift yıldızla** işaretle (en fazla bir yer).\n"
        + "## Akılda kalsın\n"
        + "Tek cümle: izleyen biri bir şey hatırlayacaksa o ne olmalı.\n\n"
        + "Kurallar:\n"
        + "- Yalnızca transkriptte olanı yaz; yorum ve bilgi ekleme.\n"
        + "- Önemli sayı, isim, tarih ve terimleri koru.\n"
        + "- Selamlaşma, reklam, abone ol çağrısı ve tekrarları atla.\n"
        + "- Transkript otomatik üretilmiş olabilir: bariz yazım ve çeviri hatalarını anlamı koruyarak düzelt.\n"
        + "- Sade, kısa cümleler kur; resmi ve süslü anlatımdan kaçın.\n"
        + "- Yukarıdaki üç başlık, '- ' ile madde ve ** dışında hiçbir biçim (başka başlık, tablo, kod bloğu, emoji, numaralı liste) kullanma.\n\n"
        + "TRANSKRİPT:\n";

    private Summary() {}

    static String generate(Host host, Transcript.Result t) throws Exception {
        String text = t.text.length() > MAX_CHARS ? t.text.substring(0, MAX_CHARS) : t.text;
        return Llm.generate(host, String.format(PROMPT, t.lang) + text);
    }

    /** Headings in the accent colour, bullets with a hanging indent, bold phrases; stray markup is dropped. */
    static CharSequence render(Ctx c, String raw) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            int start = b.length();
            if (line.startsWith("#")) {
                b.append(line.replaceFirst("^#+\\s*", "").replace("**", ""));
                if (start > 0) b.insert(start, "\n");   // blank line above a heading (not the first one)
                int s = start > 0 ? start + 1 : start;
                b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new ForegroundColorSpan(c.p.primary), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new AbsoluteSizeSpan(c.dp(14), false), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.matches("^[-*•]\\s+.*")) {
                int s = b.length();
                b.append("• ");
                bold(b, line.replaceFirst("^[-*•]\\s+", ""));
                b.setSpan(new LeadingMarginSpan.Standard(0, c.dp(18)), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                bold(b, line);
            }
        }
        return b;
    }

    /** Appends {@code s}, turning **x** into bold x. */
    private static void bold(SpannableStringBuilder b, String s) {
        String[] parts = s.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            int from = b.length();
            b.append(parts[i]);
            if (i % 2 == 1 && parts.length % 2 == 1) b.setSpan(new StyleSpan(Typeface.BOLD), from, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** The same text without markup, for copy and share. */
    static String plain(String raw) {
        StringBuilder o = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) { if (o.length() > 0) o.append('\n'); o.append(line.replaceFirst("^#+\\s*", "").toUpperCase(new java.util.Locale("tr"))); }
            else if (line.matches("^[-*•]\\s+.*")) o.append("• ").append(line.replaceFirst("^[-*•]\\s+", ""));
            else o.append(line);
            o.append('\n');
        }
        return o.toString().replace("**", "").trim();
    }
}
