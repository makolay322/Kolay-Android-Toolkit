package app.kolay.app.tools.ocr;

import app.kolay.app.tools.Llm;
import app.kolay.shell.Host;

import android.graphics.Bitmap;
import app.kolay.app.doc.OcrEngine;
import app.kolay.app.tools.scan.ScanSession;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;
import java.util.regex.*;

/** Whole original pictures of a document flow (not the cropped pages) -> Tesseract (medical: deu; general: the chosen languages) -> local PII masking -> local pre-clean -> one LLM request (cleanup, pages kept in order) -> plain text. Nothing is written to disk. */
final class Ocr {
    /**
     * Long side of the whole picture handed to Tesseract: the page is only part of the photo, so a little more than the
     * 2400 px of a cropped page keeps the text at a similar resolution.
     */
    private static final int WHOLE_SIDE = 3000;
    private static final String PROMPT =
        "Aşağıda, Almanca bir tıbbi belgenin (doktor mektubu, epikriz, bulgu, laboratuvar, reçete, faks vb.) sayfalarının Tesseract OCR çıktıları var. "
        + "Kişisel veriler [NAME], [GEBURTSDATUM], [ADRESSE], [ORT], [IBAN] ile sansürlendi; telefon, sayfa numarası, selamlama gibi bazı satırlar "
        + "önceden silindi. Her sayfa '=== SAYFA n ===' satırıyla başlar ve sayfalar DOĞRU SIRADA gelir. "
        + "Görevin belgeyi, tıbbi içeriği eksiksiz ve aynen koruyarak temiz bir metne çevirmek: "
        + "(1) SIRA: Sayfaları verilen sırayla işle; sayfaların veya bölümlerin sırasını DEĞİŞTİRME. Sayfa sınırında bölünmüş cümleyi birleştir. "
        + "(2) OCR HATALARI: Bozuk kelimeleri, yanlış harf/rakamları, tıbbi terimleri, ilaç adlarını ve birimleri yalnızca bağlamdan kesin "
        + "anlaşılıyorsa düzelt. Tıbbi içerik taşıdığı belli olan ama okunamayacak kadar bozuk yere tahmin yazma, '[unleserlich]' yaz; "
        + "eksik kalan sayı, tarih, doz veya değeri ASLA tamamlama ya da uydurma. Tıbbi anlam taşımayan bozuk parçaları (rastgele harf ve "
        + "sembol dizileri, kenar boşluğu artıkları) tamamen sil. İki sütunlu düzenden karışmış cümle parçalarını yalnızca sıraları açıkça "
        + "belliyse yeniden birleştir. "
        + "(3) ÇIKAR: antet ve logo metni, kurum/bölüm adres blokları, telefon/faks/e-posta/web satırları, bölüm iletişim kutuları "
        + "(çalışma saatleri, randevu hatları), banka bilgileri, Geschäftsführung/Vorstand, USt-IdNr., Amtsgericht/HRB, her sayfada yinelenen "
        + "hasta başlık/alt bilgi satırları ('Pat.: [NAME], Geb.Dat. …', '[NAME], [GEBURTSDATUM]'), sayfa numaraları ('4/6', 'Seite x von y'), "
        + "Fallnummer ve benzeri dosya/belge numaraları, dikte kısaltmaları, faks başlık/altlıkları, gizlilik/veri koruma ibareleri, alıcı adres "
        + "bloğu, 'Sehr geehrte…' selamlaması, teşekkür ve nezaket cümleleri ('Vielen Dank für …'), 'Mit freundlichen Grüßen' kapanışı ve "
        + "imza bloğu (imzalayan isimler, unvanlar, görevler). Belgenin kaynağı olarak gönderen kurum ve bölüm adı (ör. 'Klinik für "
        + "Innere Medizin') en başta tek satır kalabilir, adres ve iletişim olmadan; belgenin tarihi de kalsın. Aynen tekrar eden satır/paragrafların "
        + "yalnızca ilkini bırak. "
        + "(4) AYNEN KORU: tanılar ve ICD kodları, anamnez, muayene ve bulgular, görüntüleme/EKG/ekokardiyografi sonuçları, laboratuvar değerleri "
        + "(referans aralığı, birim ve yüksek/düşük işaretleriyle), girişimler ve operasyonlar, epikriz, öneriler, kontrol randevuları, ilaçlar "
        + "(etken madde, ticari ad, doz, form, kullanım şeması ve notlarla). Bunları özetleme, yeniden yazma veya kısaltma; cümleler aynen kalsın. "
        + "Aynı görünen ama farklı tarih veya değer taşıyan satırları ASLA birleştirme. Hiçbir bilgi ekleme. Emin değilsen metni bırak. "
        + "(5) LABORATUVAR TABLOLARI: her parametre tek satırda, şu biçimde: 'Parameter | TT.MM.JJ: Wert; TT.MM.JJ: Wert | Einheit | Referenzbereich'. "
        + "Tarih (ve varsa saat) sütun başlığından okunur; bir değerin hangi tarihe ait olduğu belli değilse tarihi yazma, değerleri okunduğu "
        + "sırayla yaz. Okunamayan değer yerine '[unleserlich]' yaz. Markdown tablosu kullanma. "
        + "(6) İLAÇ LİSTESİ: her ilaç tek satırda (etken madde, ticari ad, doz, form, şema); OCR'ın ayrı satıra böldüğü aynı ilacı birleştir. "
        + "Başlıklar (Diagnosen, Anamnese, Befunde, Labor, Epikrise, Empfehlung, Medikation vb.) kendi satırında kalsın. "
        + "Dili Almanca bırak, çevirme. [NAME] gibi sansür işaretlerini aynen koru. "
        + "Çıktı yalnızca düz metin olsun: '=== SAYFA n ===' satırlarını yazma; açıklama, markdown veya kod bloğu yazma; bölümleri bir boş "
        + "satırla ayır.\n\nSAYFALAR:\n";

    private final Host host;
    private final String lang;
    private TessBaseAPI tess;

    /** @param lang Tesseract language combo, e.g. "deu" or "deu+tur" (must be installed) */
    Ocr(Host h, String lang) { host = h; this.lang = lang; }

    private synchronized TessBaseAPI tess() throws IOException {
        if (tess != null) return tess;
        TessBaseAPI a = new TessBaseAPI();
        if (!a.init(host.tessDataParent().getAbsolutePath(), lang)) { a.recycle(); throw new IOException("Tesseract başlatılamadı"); }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN);
        a.setVariable("user_defined_dpi", "300");
        return tess = a;
    }

    /**
     * The whole original picture (uncropped, unfiltered, turned like the page): the outline only matters for the saved file,
     * and a wrong outline must never cut text off. Grayscale + mild contrast, long side ≤ {@link #WHOLE_SIDE}.
     */
    private static Bitmap load(ScanSession.Job j) throws IOException {
        Bitmap b = j.whole(WHOLE_SIDE);
        try { return OcrEngine.prepare(b, WHOLE_SIDE); } finally { b.recycle(); }
    }

    /** Tesseract text of one page ("" when none); the page bitmap is recycled even when reading fails. */
    private static String read(TessBaseAPI a, ScanSession.Job j) throws IOException {
        Bitmap bmp = load(j);
        try { a.setImage(bmp); String raw = a.getUTF8Text(); return raw == null ? "" : raw; }
        finally { bmp.recycle(); }
    }

    interface Progress { void on(String status, float fraction); }

    private volatile boolean cancelled;
    void cancel() { cancelled = true; }

    synchronized void close() { if (tess != null) { tess.recycle(); tess = null; } }

    /** Result of the medical mode: the text to show, and (only when it is the unpolished local text) the masked, page-marked text for a later polish. */
    static final class Out {
        final String text, pending;
        Out(String text, String pending) { this.text = text; this.pending = pending; }
    }

    /**
     * All pages: Tesseract + masking + pre-clean for each, then one cleanup request (fixes; the page order is kept). Offline, without a key or on any
     * error the local text is returned as it is, without a note; {@link Out#pending} then lets the user polish it later.
     */
    synchronized Out run(java.util.List<ScanSession.Job> pages, Progress pr) throws Exception {
        StringBuilder all = new StringBuilder(), plain = new StringBuilder();
        TessBaseAPI a = tess();
        java.util.List<String> raws = new java.util.ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            if (cancelled) throw new InterruptedIOException("iptal");
            pr.on("Sayfa " + (i + 1) + "/" + pages.size() + " okunuyor…", 0.08f + 0.62f * i / pages.size());
            raws.add(read(a, pages.get(i)));
        }
        java.util.Set<String> known = Mask.collect(raws);  // bir sayfada bulunan isim tüm sayfalarda sansürlenir
        java.util.List<String> masked = new java.util.ArrayList<>();
        for (String r : raws) masked.add(Mask.apply(r, known).trim());
        java.util.List<String> clean = PreClean.pages(masked);   // drops contact/legal/page-number/header lines before the request
        int n = 0;
        for (int i : PreClean.order(masked)) {   // printed page numbers when every page has one, else the order as scanned
            String page = clean.get(i);
            if (page.isEmpty()) continue;
            all.append("=== SAYFA ").append(++n).append(" ===\n").append(page).append("\n\n");
            plain.append(page).append("\n\n");
        }
        if (all.length() == 0) return new Out("", null);
        if (cancelled) throw new InterruptedIOException("iptal");
        String local = plain.toString().trim();
        if (!Llm.hasKey(host)) return new Out(local, all.toString());
        pr.on("Metin cilalanıyor…", 0.78f);
        try {
            String polished = polish(host, all.toString());
            if (!polished.isEmpty()) return new Out(polished, null);
        } catch (Exception ignored) { }   // offline or failed: fall back to the local text
        return new Out(local, all.toString());
    }

    /** Medical mode cleanup of already masked, page-marked text. Throws on a missing key or network error. */
    static String polish(Host host, String marked) throws Exception {
        String out = Llm.generate(host, PROMPT + marked, 0.0, 32768);
        return PAGE_MARK.matcher(out).replaceAll("").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Page markers of the request, should the model copy them into its answer. */
    private static final Pattern PAGE_MARK = Pattern.compile("(?m)^[ \\t]*=== SAYFA \\d+ ===[ \\t]*\\n?");

    /** General mode: Tesseract only, automatic page layout, no masking and no network. Pages are joined with a blank line. */
    synchronized String runPlain(java.util.List<ScanSession.Job> pages, Progress pr) throws Exception {
        TessBaseAPI a = tess();
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
        StringBuilder out = new StringBuilder();
        try {
            for (int i = 0; i < pages.size(); i++) {
                if (cancelled) throw new InterruptedIOException("iptal");
                pr.on("Sayfa " + (i + 1) + "/" + pages.size() + " okunuyor…", 0.08f + 0.9f * i / pages.size());
                String raw = read(a, pages.get(i)).trim();
                if (!raw.isEmpty()) out.append(raw).append("\n\n");
            }
        } finally {
            a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN);   // the medical mode relies on the default set in tess()
        }
        return out.toString().trim();
    }

    private static final String GENERAL_PROMPT =
        "Aşağıdaki metin bir fotoğraftan Tesseract OCR ile okundu (Almanca, Türkçe veya İngilizce olabilir). "
        + "OCR hatalarını düzelt (bozuk harf ve kelimeler, yanlış birleşmiş veya bölünmüş kelimeler, satır sonu kırıkları), "
        + "paragraf ve satır düzenini okunur hale getir, çöp karakterleri ve anlamsız tekrarları at. "
        + "Anlamı değiştirme; bilgi ekleme, özetleme veya çevirme. Dil ve içerik aynı kalsın. "
        + "Çıktı yalnızca düzeltilmiş düz metin olsun; açıklama, markdown veya kod bloğu yazma.\n\nMETİN:\n";

    /** General mode, on request only: one cleanup request with a loose prompt. Throws on a missing key or network error. */
    static String cleanGeneral(Host host, String text) throws Exception {
        // same on-device masking as the medical mode: names, birth dates, addresses and IBANs never leave the phone
        String masked = Mask.apply(text, Mask.collect(java.util.Collections.singletonList(text))).trim();
        return Llm.generate(host, GENERAL_PROMPT + masked, 0.2).trim();
    }
}
