# Değişiklik günlüğü

Biçim: [Keep a Changelog](https://keepachangelog.com/tr/). Modül sürümleri `tools/release.sh` ile yayınlanır
(görünen sürüm `1.0.x` yama sayısıyla artar; dahili `versionCode` her yayında +1 olur ve kullanıcıya gösterilmez).

## 1.000
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
### Düzeltildi
- Logonun giriş animasyonunda O harfi yaylanırken sağ kenardan kesiliyordu.
### Eklendi
- **Uygulama Güncelle** karosu: Google Play dışından yüklenen uygulamalar için APKPure, APKMirror veya LiteAPKs'te (uygulama başına seçilir, takip anahtarı var) yeni sürüm arar; varsa indirir, Android yükleyicisine verir ve dosyayı siler. XAPK/APKM paketleri desteklenir; paket adı, sürüm ve imza kurulumdan önce denetlenir. Siteler bot doğrulaması isterse veya indirme kendiliğinden başlamazsa sayfa ekranda açılır, kullanıcı dokunur. **Kabuk 1.013 gerekir** (uygulama listesi için paket görünürlüğü).
### Değişti (açılış, Cuma 3:2)
- Sistem açılış animasyonu kaldırıldı, uygulama hemen açılır. ~2 sn'lik “Ko” giriş animasyonu ana sayfanın sol üstündeki logoda oynar: açılışta ve uygulama arka plandan her öne geldiğinde. **Kabuk 1.013.**
- Cuma Mesajı görseli yatay 3:2 (1500×1000): başlık üstte, hilal üst köşede başlığın yanında, hadis paneli genişledi, cami silueti alçaldı.
### Değişti
- Uygulama ikonunun arka planı açık/koyu temaya göre seçilir (açıkta yumuşak renkli zemin, koyuda koyu zemin + açık logo; Android 12+ duvar kağıdı renkleriyle). Açılış animasyonu ~2,2 sn'ye yavaşladı ve başlatıcıdan açılışta sonuna kadar ekranda kalır (QR kutucuğu ve paylaşım hedefi beklemeden açılır). **Kabuk 1.012 gerekir.**
- Cuma Mesajı: hilal küçültülüp başlığın üstüne alındı, başlığa gölge eklendi ve başlık bandına yıldız düşmez; hilal artık yazıyla çakışmaz.
### Değişti (logo)
- Yeni “Ko” logosu uygulama ikonu oldu. Android 12+ cihazlarda ikonun renkleri duvar kağıdından gelir (dinamik renk); Android 13+ temalı ikon desteği vardır. Açılış ekranında logo animasyonla belirir (K kayar, O yaylanarak açılır, nokta belirir). **Kabuk 1.011 gerekir** (Ayarlar → “Kabuğu indir ve yükle”).
- Ana sayfada canlı logo: saat gibi günün saatine göre renk değiştirir (sabah pembe-mavi, gündüz turkuaz, akşam mercan-mor, gece çivit), parıltısı yavaşça döner, nokta hafifçe nabız atar. Başlatıcıdaki ikon bu şekilde canlandırılamaz; Android bunu yalnızca Google'ın Saat/Takvim uygulamalarına tanır.
### Değişti (arayüz cilası)
- Tüm ekranlar ortak ölçülere bağlandı: 16 dp kenar boşluğu, düğme yükseklikleri (alt çubuk 56, içerik 52, ikincil 48), tek tip bölüm etiketi, kart ve giriş alanı.
- Boş, izin ve hata ekranları tek tasarımda (ikon rozeti, başlık, açıklama, eylem düğmesi). Fotoğraf izni reddedildiğinde “İzin ver / Ayarları aç”, ayarlardan dönünce liste kendiliğinden açılır.
- Yeni ikonlar: fotoğraf, konum, cilalama, yapıştır, indir, katmanlar, video, belge. Aynı ikonu paylaşan karolar ayrıştı (OCR belge, Sıkıştır fotoğraf, YouTube video).
- Segmentli seçiciler (OCR tıbbi/genel, Helal barkod/etiket) aynı bileşen. “Kopyala” her yerde düğme üstünde “Kopyalandı” onayı verir.
- Namaz: vakitler kartta; geçmiş vakitler soluk, sıradaki vurgulu; tarih başlıkta; konum aranırken yükleniyor göstergesi; izin/konum hatalarında eylem düğmesi; ayarlardan dönünce ekran tazelenir (tam zamanlı alarm düğmesi bayat kalmaz). Kayıtlı konum varken yenileme başarısızsa vakitler silinmez, kısa bilgi verilir.
- Cuma: önizleme yuvarlatılmış kare kartta, ilk çizimde yükleniyor göstergesi; “Paylaş” birincil düğme.
- YouTube: klavyeden “Git” ile başlar, klavye kapanır, durum satırında küçük yükleniyor göstergesi.
- Sıkıştır: işlem sırasında yükleniyor göstergesi; sonuç ve hata ekranları yeni tasarımda.
- Helal: hata kartı ve alt çubuk düzenlendi. OCR: hata sonucunda “Başka sayfa seç”.
### Kaldırıldı
- Sesli Not aracı (ve kabuktaki mikrofon izni), Cuma Mesajı'nda dikey/yatay seçeneği, Ayarlar'daki "Ekran görüntüsünü engelle" (kayıtlı "açık" değer kabuk açılışında temizlenir).
### Düzeltildi
- Genel OCR sonrası tıbbi modun Tesseract ayarı bozuluyordu; geri yüklenir. Namaz ekranında vakit geçişinde sayaç zinciri çoğalıyordu; güney yarım küre enlem etiketi; widget çift çiziliyordu. Genel moddaki “Cilala” artık metni önce cihazda maskeler.
### Değişti
- Namaz widget'ına dokununca renk paleti rastgele değişir (uygulama açılmaz). Kabuk 1.008.
- Cuma görselleri biraz aydınlatıldı; menüde "kare" yazar. Namaz ekranında satırlar/düğmeler kırpılmayacak boyuta döndü.
- Cuma Mesajı yalnızca kare (1:1) görsel üretir; başlık yalnızca "Cumamız Mübarek Olsun" (Arapça satır ve "Cumanız" kalktı).
- Namaz widget'ı: arka plan yok; pastel gradyanlı, ince yazılı saat; dar widget'ta saat/dakika alt alta büyük çizilir. **Kabuk 1.007 gerekir.**
- "Tıbbi OCR" karosu "OCR" oldu (tıbbi/genel mod seçimi içeride kalır).
- Namaz Vakitleri ekranı sıkıştırıldı: tüm vakitler kaydırmadan sığar.
### Eklendi
- YouTube Özet paylaşım menüsü hedefi: başka uygulamalardan paylaşılan YouTube bağlantısı doğrudan özetlenir.
- Tıbbi OCR'a Genel OCR modu (Gemini ile isteğe bağlı temizleme); mod anahtarı.
- Helal Sorgu: barkod/etiket mod anahtarı; açılış modu barkod, otomatik OCR geçişi kaldırıldı.
- Namaz: vakit başına ayrı hatırlatıcı anahtarı; boyutlandırılabilir modern widget (yalnızca sıradaki vaktin 24 sa saati). **Kabuk 1.006 + Host API 4 gerekir.**
- Cuma Mesajı: 189 doğrulanmış hadis (tek kaynak: Buhârî/Müslim/Tirmizî), "Cumamız/Cumanız Mübarek Olsun", yalnızca koyu/olgun tonlar, pencere ışıksız siluetler.
- QR, Fotoğraf Sıkıştır ve YouTube Özeti karoları ana sayfanın altında ince yatay şeritler.
- Namaz Vakitleri aracı + ana ekran widget'ı + hatırlatıcı: konumdan vakitler hesaplanır, widget sıradaki vakti ve geri sayımı gösterir, vakit çıkmadan 15 dk önce bildirim gelir. **Kabuk 1.005 + Host API 3 gerekir** (konum, bildirim, alarm izinleri ve widget kabukta).
- Cuma Mesajı aracı: rastgele İslami temalı (yarısı siyah) 3:2 görsel üzerine Buhârî, Müslim ve Tirmizî'den ünlü hadislerden biri yazılır; yeni görsel/başka hadis/dikey 2:3, kaydet ve paylaş. Görsel Canvas ile üretilir, ağ gerekmez.
- Uygulama içi kabuk güncellemesi: yeni sürüm daha yeni kabuk istediğinde Ayarlar'da “Kabuğu indir ve yükle” çıkar; kabuk APK'sı Release'ten indirilir, SHA-256 + güncelleme anahtarı imzası + paket adı/sürüm/imza sertifikası doğrulanır, sistem yükleyicisine verilir (onayı Android sorar, sessiz yükleme mümkün değil). İlk kez “bilinmeyen kaynaktan yükleme” izni istenir. Manifest'e `shell*` alanları eklendi; `tools/release-shell.sh` ve `tools/manifest_shell.py`.
- QR Okuyucu aracı: kamera açılır açılmaz tarar (arka kamera, fener, yalnızca en yeni kare çözülür); sonuç önce gösterilir, bağlantı ancak dokununca açılır. Bildirim panelinde (hızlı ayarlar) "QR Okuyucu" kutucuğu: tek dokunuşla uygulamayı QR ekranında açar; araç ekranındaki "Hızlı ayarlara ekle" düğmesi (Android 13+) kutucuğu tek onayla ekler.
- **Kabuk 1.0.5 (elle yüklenmeli) + sözleşme API 2:** kabuk CAMERA izni, ZXing çözücüsü ve hızlı ayar kutucuğu servisini taşır; `Module.onPause()` eklendi (kamera arka plana geçince bırakılır). QR'lı modül `api=2` ister; eski kabuklu cihazlar "kabuk güncellemesi gerekli" görür.
- Otomatik yayın: `main`'e modül değişikliği push edilince GitHub Actions derler, imzalar, Release açar (`release.yml`). Sürüm numarası artık durumsuz (son manifest'ten).
- YouTube Özeti aracı: yapıştırılan bağlantının transkripti özgün dilinde alınır, Gemini ile Türkçe özetlenir.
- Fotoğraf Sıkıştır aracı: seçilen fotoğrafın %90 kaliteli JPEG kopyası Pictures/Kolay'a kaydedilir (tarih, cihaz ve çekim EXIF bilgileri taşınır; konum, Android izin kısıtı nedeniyle taşınmayabilir).
### Düzeltildi
- Ayarlar: "Şimdi denetle"ye art arda basınca indirilen sürüm uygulanamıyor ve "güncel" deniyordu; düğme artık "Şimdi yenile"de kalıyor.
### Değişti
- Ana sayfa: araçlar dikdörtgen liste yerine 2 sütunlu karo düzeninde.
- Ayarlar: "Cilalama anahtarı" alanı "Gemini API anahtarı" olarak yeniden adlandırıldı.

## 1.0.1 — ilk yayın
### Eklendi
- Uygulama adı **Kolay**; çok araçlı kutu mimarisi (ana sayfa + araç kaydı).
- Yeni arayüz sistemi: açık/koyu tema, animasyonlu gezinme, dokunma geri bildirimi, yönlü geçişler, özel ikonlar.
- Ayarlar ekranı: API anahtarı, ekran görüntüsü engeli, güncelleme durumu.
- Sayfa sırasını düzelten çoklu fotoğraf OCR akışı; iptal edilebilir ilerleme ekranı.
- Güncelleme: çoklu imza anahtarı (rotasyon), indirme sunucusu doğrulaması, "Yeni sürüm hazır" bandı ve canlı yenileme.
- Güvenlik: yedekleme/aktarım kapalı, ağ güvenliği yapılandırması, Gradle bağımlılık doğrulaması, hassas pano işareti.
- Düz (flat) yeni simge: K harfi + devre izi; Android tema simgesi (monochrome) desteği.
- Tıbbi OCR aracı: Tesseract + yerel isim maskeleme + cilalama.
- Eski deneme sürümleri (v2, v3) ve etiketleri silindi; bu sürüm tek başlangıç noktasıdır.
