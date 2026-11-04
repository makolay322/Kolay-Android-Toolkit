# Değişiklik günlüğü

Biçim: [Keep a Changelog](https://keepachangelog.com/tr/). Sürümler `main`'e push ile otomatik yayınlanır
(görünen sürüm `1.NNN`; dahili `versionCode` her yayında +1 olur ve kullanıcıya gösterilmez).

## Depo adı
- Depo `kolay` adından **`Kolay-Android-Toolkit`** olarak değişti. Uygulama yeni adresten güncellenir; eski adla yayınlanmış sürümler için manifest bağlantıları geçiş boyunca eski adla yazılır (bkz. [docs/UPDATE_PROTOCOL.md](docs/UPDATE_PROTOCOL.md)). Uygulama adı, paket adı (`app.kolay`) ve `Kolay.apk` dosya adı aynı kaldı.
- Eski yayın notları sadeleştirildi.

## 1.014 ve sonrası
### Değişti (1.025)
- **OCR (Tıbbi mod):** internet yoksa, anahtar girilmemişse veya cilalama başarısız olursa sonuç artık hiçbir not eklenmeden doğrudan yerel (maskelenmiş) OCR metni olarak gelir; sonuç ekranındaki **Cilala** düğmesiyle metin sonradan gönderilip sıralanıp düzeltilebilir (“Yerel metni göster” ile geri dönülür). Başarılı durumda davranış aynı.
- OCR koduna ve ekranlarına hizmet adı girmez; ortak bulut istemcisi `Llm` olarak yeniden adlandırıldı.
### Değişti (1.024)
- Namaz Vakitleri: sabah namazından sonra güneş doğana kadar “Sıradaki vakit” artık **Güneş** saatini gösterir (geri sayım ve listedeki vurgu dahil); widget da bu arada güneşin doğuş saatini köşede **g** harfiyle gösterir.
### Değişti (1.023)
- Uygulamanın renkleri (düğmeler, vurgular, ikon kutuları, arka plan ve kartlar) artık uygulama ikonuyla aynı kaynaktan, Android'in duvar kâğıdına göre ürettiği sistem renklerinden geliyor (Android 12+); duvar kâğıdı değişince uygulama da değişir. Ana sayfa ve Ayarlar'daki ikon kutuları vurgu renginin birbirine yakın üç tonu arasında sırayla değişir. Kırmızı/yeşil/sarı uyarı renkleri sabit kaldı; Android 10–11'de eski sabit renkler sürer.
### Değişti (1.022)
- Namaz vakti widget'ında sağ üst köşede, gösterilen saatin hangi vakte ait olduğunu belirten küçük harf: s (sabah), ö (öğle), i (ikindi), a (akşam), y (yatsı). Saatle aynı renk geçişinde.
### Değişti (1.021)
- **Cuma Mesajı** hadisleri daha temiz bir kaynaktan yenilendi: Diyanet'in *Hadislerle İslâm* eserinden, bugünkü Türkçeyle, yalnızca Peygamber'in sözü (ravi zinciri, olay anlatımı ve köşeli açıklama kalabalığı yok), öğüt içeren **274** hadis.
### Değişti (1.020)
- **Cuma Mesajı** hadisleri ayıklandı: 1000 hadisten yalnızca Müslümana öğüt içeren **327** hadis kaldı. Kehanetler ve kıyamet alametleri, cennet-cehennem tasvirleri, sahabe menkıbeleri, siyer anlatıları, salt dua metinleri ve teknik fıkıh hükümleri (alım-satım, zekat oranları, namaz/oruç ayrıntıları) çıkarıldı; kalan birkaç tekrar da silindi.
### Değişti (1.019)
- **Sohbet** karosu (yeni): ufak tefek işler için sohbet kutusu — soru sor, metni düzelt, çevir, özetle, mesaj taslağı yaz. Çok turlu konuşur, yanıtlar seçilip kopyalanabilir, “Yeni sohbet” ile sıfırlanır. Ayarlar'daki anahtarı kullanır; konuşma yalnızca bellekte tutulur, diske/loga yazılmaz, ekrandan çıkınca silinir.
- **Cuma Mesajı** hadisleri yenilendi: 189 kendi meâlimiz yerine Prof. Dr. İbrahim Canan'ın Kütüb-i Sitte tercümesinden (muhaddis.org veritabanı) seçilmiş, tekrarsız **1000 hadis**; kaynak satırında kitap ve bab numarası (ör. “Buhârî, Edeb 27”). Hadisler APK içinde `assets/hadiths.tsv` dosyasında.
- Cilalama hata iletileri sadeleşti (kota doldu, anahtar kabul edilmedi, sunucu hatası ayrı ayrı).
### Kaldırıldı (1.019)
- **Tıbbi Hesap** aracı ve kodu tamamen kaldırıldı.
### Eklendi (1.018)
- **Kıble** aracı: Namaz Vakitleri'nde kaydedilen konumdan kıble açısı ve Kâbe'ye uzaklık; telefonun pusulası (dönme vektörü sensörü, yoksa ivmeölçer + manyetometre) ile dönen kadran, Kâbe işareti, “Sağa/Sola X° dön” yönlendirmesi; kıbleye ±3° içinde dönünce yeşile döner ve titreşir. Manyetik sapma otomatik düzeltilir; telefon dik tutulunca veya pusula ayarsızken uyarı çıkar. Yeni izin yok.
- **Tıbbi Hesap** aracı (çevrimdışı): eGFR (CKD-EPI 2021), kreatinin klirensi (Cockcroft–Gault), VKİ ve vücut yüzey alanı, QTc (Bazett; Fridericia/Framingham da), CHA₂DS₂-VA (ESC 2024; VASc da gösterilir), HAS-BLED, Wells (PE), CURB-65, qSOFA, Glasgow Koma Skalası, düzeltilmiş kalsiyum, anyon açığı. Kreatinin ve kalsiyumda birim seçimi (mg/dL · µmol/L / mmol/L); sonuç kartı altta sabit, her değişiklikte güncellenir. Girilen değerler hiçbir yere yazılmaz.
- **QR oluşturma** (QR aracında “QR oluştur”): metin/bağlantı, Wi-Fi (ağ adı, şifre, güvenlik, gizli ağ — okutan telefon doğrudan bağlanır) ve kişi kartı (vCard). Yazarken önizleme; koda dokununca ekran parlaklığı artar; PNG olarak Pictures/Kolay'a kaydet veya paylaş. Araç adı “QR Kod” oldu.
### Değişti (1.017)
- Logo: açılış animasyonu kaldırıldı; yerine boşta animasyon: O harfi 6 sn'de bir madeni para gibi dikey ekseni etrafında bir tur döner (parıltı ve nokta nefesi sürer). Ana sayfa başlığı baştan ince.
- Helal Sorgu: “Barkod” ve “İçindekiler” iki eşit mod (önceden içindekiler barkodun alt sayfasıydı); İçindekiler ekranında geri tuşu artık ana sayfaya döner. Başlıklar sadeleşti (ikisi de “Helal Sorgu”, altında modun kısa açıklaması), mod etiketi “Etiket (OCR)” → “İçindekiler”, alt notlar kısaldı. Sonuç ekranında geri ana sayfaya, “Yeni sorgu” aynı moda gider.
- Segmentli seçici (OCR ve Helal modları): ikon yazının hemen yanında; dar ekranda/büyük yazı tipinde yazı üst üste binmek yerine “…” ile kısalır.
### Değişti (1.016)
- Ana sayfa başlığı logonun giriş animasyonu bitince daralır (logo ve “Kolay” yazısı küçülür, “Araç kutun” katlanır); araçlar yukarı çıkar. “Yeni sürüm hazır” bandı da inceldi.
- APK küçültüldü: R8 kod küçültme + kullanılmayan kaynakların atılması, Tesseract kütüphaneleri ve OCR verisi APK içinde sıkıştırılmış (her güncellemede inen dosya ~10,7 MB'den yaklaşık yarısına iner; kütüphaneler kurulumda bir kez açılır). Kullanılmayan kod (eski hatırlatıcı açma/kapama, ikon, yardımcılar, import'lar) silindi.
### Değişti (1.015)
- Namaz: ikindi vakti Diyanet takvimindeki gibi **asr-ı evvel** (gölge = boy) ile hesaplanır; önceden Hanefi asr-ı sani (gölge = 2 boy) idi ve kışın ~40 dk geç çıkıyordu (Mettmann, 1 Kasım: 15:20 → 14:42). Widget ve hatırlatıcılar da buna göre kayar.
### Değişti (tek APK — 1.014)
- **Kabuk + modül düzeni kaldırıldı; Kolay artık tek APK.** Uygulama kendini GitHub'dan indirip doğrular ve kurar: ana sayfadaki “Yeni sürüm hazır” bandı veya Ayarlar → “Şimdi kur”. Android 12+'da genellikle onaysız; uygulama ekranda değilken arka planda kendiliğinden kurulur (Android onay isterse bildirim çıkar). İkon/izin/widget değişiklikleri artık ayrı “kabuk güncellemesi” istemez.
- Eski kurulumlar (kabuk ≤ 1.013) yeni APK'yı Ayarlar → “Kabuğu indir ve yükle” ile bir kez alır; anahtar, ayarlar, widget ve kısayollar korunur. Eski modül dosyaları ilk açılışta silinir.
- Kaldırılan: otomatik kara liste/geri dönüş (çöken sürüm önceki modüle düşmez; düzeltme daha yüksek sürümle gelir), `payload.apk`, `sdk` modülü.
- Uygulama Güncelle: liste tek kartta ince satırlar; takibi kapatılanlar alttaki “KAPALI” grubuna iner. Kaynak iki seçenek: **M+P** (APKMirror + APKPure, hangisinde daha yeni sürüm varsa ondan iner) ve **Lite** (LiteAPKs); satırdaki rozete dokununca değişir.

## 1.014 öncesi (özet)
Kabuk + modül düzeniyle yayınlanan 1.0.x sürümleri; ayrıntılı notlar git geçmişindedir. Bugünkü araçların çoğu o dönemde eklendi:
OCR (Tesseract, tıbbi mod + yerel isim maskeleme), Helal Sorgu, Cuma Mesajı, YouTube Özeti, Fotoğraf Sıkıştır, QR okuyucu, Namaz Vakitleri + widget + hatırlatıcılar,
Uygulama Güncelle; ayrıca açık/koyu tema, ortak arayüz ölçüleri, canlı “Ko” logosu, ECDSA imzalı kendi kendini güncelleme ve Gradle bağımlılık doğrulaması.
Kaldırılanlar: Sesli Not, Tıbbi Hesap, Cuma görseli yön seçeneği, ekran görüntüsü engeli, otomatik geri dönüş (kara liste) ve `payload.apk`/`sdk` modülü.
