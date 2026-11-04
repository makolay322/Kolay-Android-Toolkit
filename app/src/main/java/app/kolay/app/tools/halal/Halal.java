package app.kolay.app.tools.halal;

import app.kolay.app.tools.Llm;
import app.kolay.shell.Host;
import java.util.Locale;

/** Ingredient list -> Hanafi assessment through the LLM. The ingredient text is not stored anywhere. */
final class Halal {
    private Halal() {}

    enum Level { HELAL, HARAM, SUPHELI, UNKNOWN }

    static final class Verdict {
        final Level level; final String detail;
        Verdict(Level level, String detail) { this.level = level; this.detail = detail; }
    }

    private static final int MAX_CHARS = 6000;

    private static final String PROMPT =
        "Sen bir gıda içeriği denetçisisin. Aşağıdaki ürün içindekiler listesini HANEFİ MEZHEBİNE göre helal / haram / şüpheli açısından değerlendir. "
        + "Liste bir barkod veritabanından ya da OCR çıktısından gelmiş olabilir: dili Türkçe, Almanca, İngilizce veya karışık olabilir; "
        + "OCR hataları (ş/ğ/ı yerine başka harfler, bölünmüş veya birleşmiş kelimeler, yanlış rakamlar) içerebilir. Bağlama göre düzelt, "
        + "ama listede olmayan hiçbir madde uydurma ve emin olmadığın bir kelimeyi sessizce 'helal' sayma. "
        + "KARAR KURALLARI: "
        + "HARAM: domuz ve türevleri (domuz yağı, domuz jelatini, Schweine-/porcine kaynaklı her şey), kan ve kan ürünleri, leş, "
        + "içki (şarap, bira, rom, viski, likör vb.) veya içki eklenmiş ürün, üzüm/hurma kaynaklı alkol (hamr). "
        + "ŞÜPHELİ: kaynağı belirtilmemiş hayvansal katkılar (jelatin/E441, E470-E472 mono-digliseritler ve yağ asidi türevleri, E542, E631/E627/E635, "
        + "L-sistein/E920, hayvansal gliserin/E422, hayvansal peynir mayası/rennet/enzim, hayvansal yağ ve et suyu aromaları); "
        + "kesim şekli ve helal sertifikası belirtilmemiş et, tavuk ve et ürünleri; böcek kaynaklı katkılar (E120 karmin/koşenil, E904 şellak) "
        + "— Hanefi'de çekirge dışındaki böceklerin yenmesi caiz görülmediği için; balık dışı deniz hayvanları (Hanefi'de karides vb. konusunda görüş ayrılığı vardır); "
        + "kaynağı belirsiz etanol/alkol veya aroma çözücüsü (Hanefi'de üzüm ve hurma dışı kaynaklı etanolün aroma çözücüsü olarak eser miktarı içki sayılmaz, "
        + "ama kaynak belirsiz olduğu için not et); 'doğal aroma' gibi içeriği belirsiz ifadeler yalnızca tek başına şüphe sebebi sayılmaz, ancak diğer riskle birlikte not et. "
        + "HELAL: tüm maddeler bitkisel, mineral, mikrobiyal, sentetik veya kaynağı belli helal ise. "
        + "Hanefi içinde görüş ayrılığı olan bir madde varsa (istihale görüşü, peynir mayası, eser alkol gibi) bunu kısaca belirt ama kararda ihtiyatlı ol. "
        + "GENEL KARAR: herhangi bir madde HARAM ise HARAM; değilse herhangi biri ŞÜPHELİ ise ŞÜPHELİ; hepsi açıkça helal ise HELAL. "
        + "Liste okunamayacak kadar bozuk, çok kısa veya ürünü anlamaya yetmiyorsa ŞÜPHELİ de ve nedenini yaz. "
        + "ÇIKTI BİÇİMİ (düz metin; markdown, yıldız veya kod bloğu kullanma): "
        + "1. satır yalnızca şunlardan biri: HELAL, HARAM veya ŞÜPHELİ. "
        + "2. satırdan itibaren Türkçe: önce 'Özet:' ile 1-2 cümle; sonra 'Dikkat çeken maddeler:' başlığı altında her madde için "
        + "'- madde adı: HARAM veya ŞÜPHELİ — kısa gerekçe' satırları (hiç yoksa 'Dikkat çeken madde yok.'). Başka açıklama ekleme.\n\n"
        + "ÜRÜN: %s\n\nİÇİNDEKİLER:\n";

    static Verdict judge(Host host, String productName, String ingredients) throws Exception {
        String ing = ingredients.length() > MAX_CHARS ? ingredients.substring(0, MAX_CHARS) : ingredients;
        String out = Llm.generate(host, String.format(PROMPT, productName.isEmpty() ? "(adı bilinmiyor)" : productName) + ing, 0.0);
        if (out.isEmpty()) throw new java.io.IOException("Değerlendirme üretilemedi");
        int nl = out.indexOf('\n');
        String first = (nl < 0 ? out : out.substring(0, nl)).replaceAll("[^\\p{L}]", "").toUpperCase(new Locale("tr"));
        String rest = nl < 0 ? "" : out.substring(nl + 1).trim();
        Level lv = first.startsWith("HARAM") ? Level.HARAM
            : first.startsWith("ŞÜPHE") || first.startsWith("SUPHE") ? Level.SUPHELI
            : first.startsWith("HELAL") ? Level.HELAL : Level.UNKNOWN;
        return new Verdict(lv, lv == Level.UNKNOWN ? out : rest);
    }
}
