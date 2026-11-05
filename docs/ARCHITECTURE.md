# Mimari

## Tek APK
Kolay tek bir APK'dır (`app.kolay`, Gradle modülü `app/`). 1.014'e kadar küçük bir "kabuk" APK'sı arayüzü ayrı bir modül APK'sından
`DexClassLoader` ile yüklüyordu; ancak ikon, izin, widget gibi değişiklikler zaten kabuk güncellemesi gerektirdiği için bu düzen kaldırıldı.
Uygulama artık kendini günceller: Android 12+ bir uygulamanın kendi güncellemesini genellikle onaysız kurmasına izin verir
([güncelleme protokolü](UPDATE_PROTOCOL.md)).

## Paketler

| Paket | İçerik |
|---|---|
| `app.kolay.shell` | Platform: `MainActivity`, `Host` (iç arayüz), `Updater` + `UpdateJob` + `InstallResultReceiver` (kendi kendini güncelleme), `SecretStore`, `Verify`, `Config`; namaz motoru (`PrayerCalc`, `Prayer`, `PrayerWidget`, `PrayerReceiver`), `QrTileService`, `TessData` (OCR dil verisi indirme/doğrulama). Tesseract `.so`, ZXing; dil verisi APK'da yok (1.029'dan beri indirilir). |
| `app.kolay.app` | Arayüz ve araçlar: `Entry`, `ui/*` (tasarım sistemi), `screens/*`, `tools/*`. |

`app.kolay.shell` adı tarihseldir ve **değiştirilmez**: manifestteki bileşen adları (başlatıcı etkinliği, widget, hızlı ayar kutucuğu,
paylaşım hedefi) bu pakettedir; ad değişirse güncellemede ana ekran kısayolu, yerleştirilmiş widget ve kutucuk kaybolur.

## Açılış akışı (`MainActivity.onCreate`)
```
onCreate
 ├─ Updater.cleanupLegacy()   eski düzenin artıkları (files/payloads, "registry" tercihleri) bir kez silinir
 ├─ ensureTessdata()          files/tessdata/ hazırlanır; yalnızca yarım kalmış eski indirmeler silinir (yüklü veriye dokunulmaz)
 ├─ new Entry().onCreate(host) arayüz: tema, gezinme, ana sayfa; QR kutucuğu / paylaşım hedefi eylemleri
 ├─ UpdateJob.schedule()      12 saatte bir arka plan denetimi (+ ekran kapalıyken kurulum)
 └─ Updater.checkAsync()      en çok 6 saatte bir; yeni sürüm indirilirse ana sayfada "Yeni sürüm hazır" bandı
```
Etkinlik ömür döngüsünü (`onResume/onPause/izin sonucu/geri tuşu/onDestroy`) `Entry`'ye iletir; `Entry` en üstteki ekrana dağıtır.
Otomatik geri dönüş (kötü sürümü kara listeye alma) eski düzenle birlikte kalktı: düzeltme her zaman daha yüksek sürümle ileriye doğrudur.

## Arayüz katmanı (`app.kolay.app`)
```
Entry                    Uygulama girişi: tema, Nav kökü, ana sayfa, geri tuşu, ömür döngüsü
ui/                      Palette, Ctx, Ui, Icon, Nav, Screen, Header, Arc, Bar, Toggle  → docs/UI.md
screens/                 HomeScreen, SettingsScreen
tools/Tool, Tools        araç arayüzü + kayıt listesi                                   → docs/ADDING_A_TOOL.md
doc/                     Belge/OCR ortak altyapısı: Langs + LangBar (dil kombinasyonu, hatırlanan seçim), TessScreen (dil verisi yöneticisi), OcrEngine (Tesseract, kelime kutuları), QuadDetector (sayfa kenarı algılama), Imaging (çözme, perspektif düzeltme, filtreler), PdfWriter (JPEG sayfalı, isteğe bağlı görünmez metin katmanlı PDF), MediaOut (MediaStore'a kayıt)
tools/scan/              Belge Tarayıcı: ScanTool, ScanSession (sayfalar + önbellek dosyaları), DocCamera (camera2: önizleme + analiz akışı + JPEG), CameraScreen, PagesScreen, CropScreen, FilterScreen, ExportScreen
tools/netscan/           Ağ Tarayıcı (eSCL): NetScanTool, DevicesScreen (bulunanlar + elle adres), ScannerScreen (bağlantı, ayarlar, tarama işi; akışın kökü), Discovery (NsdManager), EsclClient + Escl (XML) + Http (ham soket HTTP/1.1) + Tofu (sertifika sabitleme) + Lan (yerel ağ kuralı), Device, Store (tercihler)
tools/ocr/               OCR (tıbbi/genel mod): OcrTool, Picker/Progress/ResultScreen, Ocr, Mask, Session
tools/qr/                QR Kod: QrTool, QrScreen (okuyucu arayüzü), QrMakeScreen + QrPayload (QR oluşturma: metin, Wi-Fi, vCard), QrCamera (camera2; kipler QR / PRODUCT barkod / TEXT fotoğraf), QrDecoder (ZXing)
tools/halal/             Helal Sorgu: HelalTool, ScanScreen/LabelScreen (CamScreen tabanı), Off (Open Food Facts), LabelOcr, Halal (istem + karar), WorkScreen, VerdictScreen
tools/friday/            Cuma Mesajı: FridayTool, FridayScreen (önizleme/kaydet/paylaş), FridayArt (Canvas ile arka plan + yazı), Hadiths (`assets/hadiths.tsv` okuyucu)
tools/chat/              Sohbet: ChatTool, ChatScreen (balonlar + alt yazı çubuğu; geçmiş yalnızca bellekte, Llm.chat ile çok turlu istek)
tools/kible/             Kıble: Qibla (büyük daire açısı/uzaklık, saf Java), KibleScreen (dönme vektörü sensörü + manyetik sapma, kadran çizimi)
tools/namaz/             Namaz Vakitleri: NamazTool, NamazScreen (saatler, geri sayım, hatırlatıcı anahtarı, widget düğmesi, konum alma)
tools/apps/              Uygulama Güncelle: AppsTool, AppsScreen (liste + sıra), Sources (APKPure/APKMirror/LiteAPKs), Browser (WebView), Installer
```
UI tamamen programatiktir (XML düzen yok; tutarlılık için böyle kalır). XML kaynakları yalnızca ikon, widget ve açılış teması içindir.

## OCR akışı
1. `PickerScreen`: MediaStore'dan son 90 fotoğraf; seçim `Session.selected` içinde.
2. `ProgressScreen` → `Ocr.run(pages)`: her sayfa gri tonlama + hafif kontrast, ≤2400 px, Tesseract (`deu`, PSM single column). Genel modda dil seçilebilir (`LangBar`; ör. `deu+tur`, son seçim hatırlanır, otomatik sayfa düzeni). Gerekli dil yüklü değilse önce `TessScreen` açılır, “Devam” okumayı başlatır.
3. `Mask.collect` tüm sayfalardan isim adaylarını toplar; `Mask.apply` her sayfada maskeler (`[NAME]`, `[GEBURTSDATUM]`, `[ADRESSE]`, `[ORT]`, `[IBAN]`).
4. Maskeli metin **tek istekte** buluta gider: sayfaları doğru sıraya dizer + OCR hatalarını düzeltir + çöpü temizler, içerik eklemez/çıkarmaz.
5. `ResultScreen`: kopyala / paylaş. Hiçbir şey diske yazılmaz.

Bulut isteği başarısız olursa maskeli ham OCR gösterilir (uyarı notuyla).

## QR Okuyucu ve hızlı ayar kutucuğu
- `QrCamera`: arka kamera, tek arka plan iş parçacığı; önizleme (`TextureView`) + YUV akışı. Yalnızca en yeni kare çözülür (eskiler atılır) → gecikme birikmez. Uygulama arka plana geçince (`Module.onPause`) kamera bırakılır.
- `QrDecoder`: ZXing `QRCodeReader`, yalnızca QR; ters renkli kodlar her 3. boş karede denenir. Saf Java.
- Sonuç diske/loga yazılmaz; bağlantı kendiliğinden açılmaz, kullanıcı "Bağlantıyı aç"a dokunur (yalnızca http/https).
- Kutucuk: `QrTileService` `app.kolay.action.QR_SCAN` eylemiyle `MainActivity`'yi açar; `Entry` ilk açılışta bu eylemi görürse ana sayfanın üstüne QR ekranını iter ve eylemi tüketir (yeniden oluşturmada tekrar açılmaz).

## Helal Sorgu akışı
1. `ScanScreen`: kamera `PRODUCT` kipinde; kare 90° döndürülüp EAN-13/8, UPC-A/E, Code 128 okunur (1-B okuyucular yalnızca satır tarar).
2. `WorkScreen`: `Off.lookup` Open Food Facts'e **yalnızca barkod rakamlarını** gönderir; içindekiler (tr → genel → de → en) alınır.
3. Barkod yoksa/içindekiler boşsa/ağ yoksa `LabelScreen` (kamera `TEXT` kipi, ≤3,5 MP gri kare) → `CropScreen` (kullanıcı yalnızca içindekiler kısmını çerçeveler; çevre yazı OCR girdisini gürültülü yapar) → `LabelOcr` (Tesseract `deu`) açılır. Fotoğraf OCR biter bitmez geri dönüştürülür; metin diske yazılmaz.
4. `Halal.judge`: içindekiler tek istekte Hanefi ölçütleriyle değerlendirilir (sıcaklık 0). Çıktının 1. satırı `HELAL | HARAM | ŞÜPHELİ`, gerisi gerekçe. Belirsiz/bozuk liste → ŞÜPHELİ.
5. `VerdictScreen`: renkli karar + gerekçe + kullanılan içindekiler. "Fetva değildir" notu sabittir.
Not: `LabelOcr` `deu` ister (yoksa çekimden önce `TessScreen` açılır), `tur` yüklüyse `deu+tur` okur; yalnızca `deu` ile Türkçe özel harfler (ş ğ ı) yanlış okunabilir, istem bunu bağlamdan düzeltmesini ister.

## OCR dil verisi (`TessData`, `doc/TessScreen`)
- APK yalnızca Tesseract motorunu taşır; `deu`, `tur`, `eng` (`tessdata_fast`) ilk kullanımda `files/tessdata/` altına indirilir. Kaynak `raw.githubusercontent.com/tesseract-ocr/tessdata_fast/<commit>/` — commit SHA'sı, her dosyanın boyutu ve SHA-256'sı `TessData`'da sabittir. İndirme `Updater.download` ile (yalnızca HTTPS, yönlendirme sonrası `Config.trustedHost`, boyut sınırı, iptal) geçici adla yapılır; özet tutarsa atomik olarak yerine taşınır.
- 1.028'e kadar APK'dan kopyalanan `deu.traineddata` aynı dosyadır, olduğu yerde kalır ve "yüklü" sayılır. Açılışta yalnızca bir saatten eski `*.download` artıkları silinir.
- `TessScreen`: dil başına durum (yüklü/boyut), ilerlemeli indirme, iki dokunuşla silme. `required` verilirse eksikler tamamlanınca “Devam” çıkar ve çağıranın işi sürer. Ekrandan çıkmak indirmeyi iptal eder.
- `Host.tessInstalled / tessDownload / tessDelete …` arayüzden platforma tek yoldur.

## Belge Tarayıcı akışı (`tools/scan`)
1. `CameraScreen` (akışın kökü): `DocCamera` arka kamerayı üç akışla açar — önizleme (`TextureView`, karenin tamamı görünür), ~640 px YUV analiz akışı, ≤ ~13 MP JPEG. Cihaz üç akışı reddederse analiz akışı düşer (elle çekim sürer). Analiz kendi iş parçacığında ~10 kare/sn `QuadDetector`'a gider: ≤360 px'e küçültme → 5×5 Gauss → Sobel → Canny (NMS + histerezis) → Hough doğruları → iki yaklaşık paralel doğru çiftinden, çevresinin kenar piksellerince en çok desteklendiği dörtgen (dışbükey, açılar ~44–136°, alan ≥ %10). Sonuç yumuşatılıp önizleme üstünde çizilir. “Otomatik çekim” açıksa çerçeve ~1 sn sabit kalınca çeker; aynı sayfayı yeniden çekmemek için çerçeve kaybolana ya da belirgin değişene kadar bekler. Çekim yönü `OrientationEventListener` ile `JPEG_ORIENTATION`'a yansır. Fener, deklanşör sesi, sayfa şeridi, “Bitti (n)”. Kamera ekran üstteyken ve uygulama öndeyken çalışır.
2. Galeri: sistem seçicisi (`ACTION_GET_CONTENT`, çoklu seçim; ek izin yok). Sonuç `MainActivity.onActivityResult → Entry → Screen.onActivityResult` ile gelir. Seçilen görüntüler önbelleğe kopyalanır (okuma izni geçicidir).
3. Her sayfa `ScanSession`'da: özgün dosya `cache/scan/<oturum>/pN.jpg`, normalleştirilmiş 4 köşe, döndürme, filtre. Arka planda ~1,2 MP kopyada kenar algılanır (bulunamazsa canlı çerçeve, o da yoksa tüm görüntü), sonra önizleme (~0,36 MP) çizilir.
4. `PagesScreen`: sırala (yukarı/aşağı), kırp, filtre, sil (iki dokunuş). `CropScreen`: 4 sürüklenebilir köşe + büyüteç, “Tümü”, “Algıla”, “Döndür”. `FilterScreen`: Orijinal / Otomatik (kanal başına seviye: beyaz dengesi + kontrast) / Gri / Siyah-Beyaz (küçük kopyada integral görüntüyle yerel ortalama, Bradley eşiği) / Gölge giderme (küçük kopyada genişletme + bulanıklaştırma ile arka plan tahmini, kanal başına bölme); bu sayfaya ya da tüm sayfalara.
5. `ExportScreen`: PDF / JPEG, dosya adı (`Tarama_yyyyMMdd_HHmm`), OCR aç/kapa + dil seçimi (eksikse `TessScreen`). Her sayfa tam çözünürlükte (≤ 12 MP; bellek yetmezse ¼'ü) çözülür → `Matrix.setPolyToPoly` ile düzeltilir → döndürülür → filtrelenir; filtreler satır bantlarıyla çalışır (Java yığınında yalnızca küçük diziler). PDF: `PdfWriter` sayfa JPEG'ini olduğu gibi gömer (DCTDecode; `PdfDocument` görüntüyü kayıpsız yeniden kodladığı ve tam saydam metni attığı için kullanılmaz), OCR açıksa kelime kutularına görünmez metin (render modu 3, ToUnicode eşlemli Helvetica) koyar → `Documents/Kolay` (MediaStore; olmazsa `Download/Kolay`). JPEG: sayfa başına `Pictures/Kolay`. Kayıtlar `IS_PENDING` ile yazılır, hata/iptalde silinir. Sonra Paylaş, “Metni kopyala / paylaş” (yalnızca bellekten), “Yeni tarama”, “Bitti”.
6. Akıştan çıkınca (kök ekran yok edilince) önbellek klasörü (kendi iş parçacığında; etkinlik kapanırken de) ve bitmap'ler silinir; uygulama öldürülmüşse bir sonraki taramada temizlenir. Akış sürerken değişen/silinen önizlemeler geri dönüştürülmez (alttaki ekranlar, ör. kameranın sayfa şeridi, onları hâlâ gösteriyor olabilir), GC'ye bırakılır.

## Ağ Tarayıcı akışı (`tools/netscan`)
1. `DevicesScreen`: `Discovery` `_uscan._tcp` ve `_uscans._tcp` hizmetlerini `NsdManager` ile arar (izin ve multicast kilidi gerekmez; hizmetler tek tek çözülür, Android 14 öncesinde eşzamanlı çözme başarısız olur). TXT: `rs` (kaynak yolu, varsayılan `eSCL`), `ty` (model), `UUID` (aynı cihazın iki hizmeti birleşir; sabitleme ve ayarlar bu kimlikle). Yalnızca yerel ağ adresleri listelenir. Arama yalnızca ekran üstteyken sürer. Elle girilen adresler (`192.168.1.20`, `ad:port/yol`) `Store`'da (tercih `netscan`) son 8 tane.
2. `ScannerScreen` → `EsclClient.connect`: ad bir kez çözülür, `Lan.isLan` denetlenir, o adrese bağlanılır. Önce HTTPS (duyurulan `_uscans` portu ya da 443), `Tofu` ile ilk kullanımda sabitleme; olmazsa ve sabitleme yoksa HTTP (duyurulan port ya da 80). `GET {rs}/ScannerCapabilities` → `Escl.caps` (yerel adla eşleşen ad alanı duyarlı XmlPullParser): cam / besleyici / çift taraflı, çözünürlükler (liste ya da aralık), renk kipleri, en büyük alan, biçimler (JPEG tercih, yoksa PDF).
3. Ayarlar: kaynak, dpi (150/200/300 desteklenenler; yoksa 200'e en yakın üçü), renk (Gri/Siyah-beyaz tarayıcıdan `Grayscale8` — yoksa `RGB24` — alınıp sayfa filtresiyle tamamlanır; 1 bit JPEG olmadığından `BlackAndWhite1` istenmez), kâğıt (A4 2480×3508, Letter 2550×3300; 1/300 inç, tarayıcının üst sınırına kırpılır). Tarayıcı başına hatırlanır.
4. İş: besleyicide önce `ScannerStatus` (besleyici boşsa uyarı), `POST {rs}/ScanJobs` (`scan:ScanSettings`, sürüm yeteneklerden) → 201 + `Location` (yalnızca yolu kullanılır), sonra `GET {iş}/NextDocument` döngüsü: 200 sayfa (dosyaya akıtılır), 404 bitti, 503 1 sn sonra yeniden (sınırlı), 409 meşgul. Camda iş başına tek sayfa; sayfadan sonra kısa zaman aşımıyla (5 sn) bir kez daha `NextDocument` istenir, çünkü eSCL işi 404 ile kapatır ve bazı tarayıcılar ancak o zaman serbest kalır (yanıt ne olursa olsun iş biter; fazladan gelen sayfa atılır, dosyaya yazılmaz). İş sonunda — bitince, hatada ya da iptalde — `DELETE` edilir (en iyi çaba); iptal önce soketi kapatır. PDF gelirse `PdfRenderer` ile tarama çözünürlüğünde (≤ 12 MP) JPEG sayfalara çevrilir.
5. Sayfalar `ScanSession.addScanned` ile Belge Tarayıcı akışına girer (kenar algılama yok, sayfa tümüyle alınır); `PagesScreen` → `ExportScreen` aynıdır. “Sayfa ekle” tarayıcı ekranına döner; ekrandan çıkınca önbellek silinir.

## Cuma Mesajı akışı
- `FridayArt.render(seed, hadis, kaynak)`: görsel tamamen Canvas ile çizilir (görsel dosyası yok): gradyan, geometrik desen (yıldız/daire), hilâl, yıldızlar, cami silueti, yarı saydam panelde hadis. Aynı seed aynı arka planı verir; seed'in yarısı siyah (koyu) temadır. Boyut yalnızca yatay 3:2, 1500×1000 (`FridayArt.W/H`): başlık üstte ortada, hilal üst köşede (başlığın yanında, üstüne binmez), hadis paneli ortada, cami silueti altta.
- `Hadiths.all(ctx)`: `assets/hadiths.tsv`, satır başına `metin<TAB>kaynak`, 274 hadis. Kaynak: Diyanet İşleri Başkanlığı, *Hadislerle İslâm* (7 cilt), `https://hadislerleislam.diyanet.gov.tr`; her bölümün başındaki hadis sayfasından alındı. Yalnızca Peygamber'in sözü tutuldu (ravi zinciri ve olay anlatımı yok), metin aynen (yalnızca tırnaklar tipografik); kaynak satırı eser Buhârî/Müslim gösteriyorsa o, yoksa ilk künye + kitap/bab. Yalnızca okuyana öğüt içerenler var; kehanet, ahiret tasvirleri, menkıbeler, uygulama aktarımları, salt dua metinleri, teknik fıkıh hükümleri ve tekrarlar alınmadı. Dosyayı elle düzenlerken sekme/satır biçimini koru, kaynak satırı ~36 karakteri geçmesin (görselde tek satır).
- Yalnızca koyu ve olgun tonlar (pencere ışıksız cami siluetleri); başlık yalnızca "Cumamız Mübarek Olsun".
- Görsel yalnızca Kaydet/Paylaş'a basılınca `Pictures/Kolay`'a yazılır (aynı görsel iki kez yazılmaz). Ağ ve Gemini kullanılmaz.

## Namaz Vakitleri akışı
- Hesap, widget ve alarmlar `app.kolay.shell`'de çalışır (arayüz kapalıyken de gerekir): `PrayerCalc` (saf Java, JVM'de denenir), `Prayer` (ayarlar + alarm + bildirim), `PrayerWidget`, `PrayerReceiver`. Arayüz yalnızca ön yüzdür; `Host.prayer*` metotlarıyla konuşur.
- Konum: arayüz `ACCESS_COARSE_LOCATION` ister, son bilinen/anlık konumu alır, yalnızca koordinat `Host.prayerSetLocation` ile saklanır (~5 km'den az oynama yok sayılır). İnternete gönderilmez.
- Yöntem: Diyanet açıları (imsak 18°, yatsı 17°), ikindi Diyanet gibi asr-ı evvel (gölge 1; 1.015'e kadar Hanefi asr-ı sani, gölge 2 idi), ihtiyat payları `PrayerCalc.TUNE_MIN` (güneş −7, öğle +5, ikindi +4, akşam +7 dk). Hafızaya dayalı; Diyanet takvimiyle karşılaştırılıp ayarlanmalı. Yüksek enlemde yatsı/imsak "gecenin açıya oranlı payı" ile hesaplanır.
- Widget: arka plansız, dokununca rastgele pastel palet; sıradaki vaktin 24 saatlik saati pastel gradyan, ince (light) yazı tipiyle `Canvas` bitmap'i olarak çizilir ve widget boyutunu doldurur (dar/dikeyde saat ve dakika alt alta, geniş olunca tek satır; `onAppWidgetOptionsChanged` ile yeniden çizilir). Güncelleme: vakit değişince alarm + 30 dk'da bir sistem güncellemesi. Sıradaki vakit "Güneş"i de kapsar (sabahtan sonra güneşin doğuş saati); sağ üst köşedeki küçük harf vakti gösterir (s, g, ö, i, a, y).
- Hatırlatıcı her vakit için ayrı ayrı açılıp kapatılır (`Host.prayerReminderMask/prayerSetReminderMask`, bit maskesi); kapalı vakitlerin alarmı kurulmaz. Her vaktin **bitmesine 15 dk kala** (Sabah→güneş, Öğle→ikindi, İkindi→akşam, Akşam→yatsı, Yatsı→ertesi imsak) bildirim. Tek seferde yalnızca sıradaki alarm kurulur; tetiklenince bir sonraki kurulur. Önyükleme, saat/saat dilimi değişimi ve kabuk güncellemesinde yeniden kurulur. Android 12+ tam zamanlı alarm izni yoksa `setAndAllowWhileIdle` (birkaç dakika kayabilir); ekrandaki düğme izin sayfasını açar.

## Uygulama Güncelle akışı
- Liste: başlatıcı simgesi olan, sistem uygulaması olmayan ve yükleyicisi Google Play (`com.android.vending`) olmayan uygulamalar. Görünürlük için manifestte `<queries>` (MAIN/LAUNCHER) vardır; `QUERY_ALL_PACKAGES` kullanılmaz.
- Kaynak uygulama başına iki seçenekten biridir (satırdaki rozet, dokununca değişir): **M+P** (varsayılan) APKMirror ve APKPure'a sırayla bakar, hangisinde daha yeni sürüm varsa ondan indirir (eşitse APKMirror: yalnızca Play imzalı dosya barındırır; biri başarısızsa diğeri kullanılır). **Lite** = LiteAPKs. Anahtarla takipten çıkarılan uygulamalar listenin altındaki "KAPALI" grubuna iner. Liste tek kartta ince satırlar (ikon, ad, durum satırı, rozet, anahtar); satıra dokunmak kontrol eder / güncelle. Ayarlar `app_updates` tercih dosyasında: `choice:<paket>` (0 = M+P, 1 = Lite; eski üç seçenekli `src:` değeri okunurken eşlenir), `off:<paket>`, `new:<paket>` (sitede görülen yeni sürüm).
- Üç site de tarayıcı olmayan isteklere Cloudflare doğrulaması döndürür (masaüstü/sunucudan `curl` 403 alır). Bu yüzden `Browser` gerçek bir `WebView` kullanır: sayfayı telefondaki tarayıcı gibi açar, küçük DOM betikleriyle okur, indirmeyi `DownloadListener` ile yakalar. Doğrulama 8 sn'de geçmezse ya da indirme kendiliğinden başlamazsa sayfa ekranda gösterilir, kullanıcı dokunur; sonrası otomatik sürer. Ana çerçeve gezintisi kaynağın kendi alan adları ve `.apk/.xapk/.apkm/.apks` bağlantılarıyla sınırlıdır (reklam yönlendirmeleri düşer).
- Bulma: APKPure arama → uygulama sayfası `/<slug>/<paket>/download` (sürüm: `data-dt-version` / JSON-LD / başlık; dosya: `d.apkpure.com/b/...`). APKMirror paket adıyla arama → uygulama başına en yeni kararlı sürüm → sürüm sayfasının varyant tablosu (APK > paket, telefonun mimarisi/universal, nodpi) → varyant sayfasında paket adı doğrulaması ve `downloadButton` → `download.php`. LiteAPKs uygulama adıyla arama → sayfada paket adı (Google Play bağlantısı) doğrulaması, "Version" satırı, `/download/…` zinciri. Paket adı "tam ad" olarak eşlenir (`org.app` ≠ `org.app.beta`).
- Kurulum: dosya Kolay'ın tarayıcı çerezleri/kullanıcı aracısıyla `cache/app-updates/` altına iner (yalnızca HTTPS, ≤ 2 GB). Düz APK ya da XAPK/APKM/APKS paketi açılır (mimari bölümleri yalnızca telefonun işlemcileri için alınır). Kontroller: paket adı aynı, `versionCode` yüklüden büyük, imza yüklü uygulamayla aynı (farklıysa Android zaten reddeder; mod/yeniden imzalı dosyalar bu yüzden kurulamaz). Tek `PackageInstaller` oturumu; dosyalar oturuma yazılır yazılmaz silinir, ekran açılış/kapanışında klasör temizlenir.
- Onay: Android, başka bir yükleyiciyle kurulmuş uygulamanın güncellemesi için onay ister. Android 12+'da `USER_ACTION_NOT_REQUIRED` istenir: Kolay bir kez güncelledikten sonra (kayıtlı yükleyici Kolay olur) sonraki güncellemeler onaysız geçebilir. Güncellemeler sırayla yapılır; ekranda tek onay olur.
- Arka planda otomatik kontrol yok (WebView ve doğrulama ön planda çalışır); "Kontrol et ve güncelle" takip edilen tüm uygulamaları sırayla kontrol edip yeni sürümü olanları kurar.

## Araç düzeni ve genel OCR
- `Tool.compact()` true olan araçlar (QR, Fotoğraf Sıkıştır, YouTube Özeti) ana sayfada karo ızgarasının altında ince yatay şeritler olarak gösterilir.
- Tıbbi OCR: iki mod, anahtarla seçilir. Varsayılan "Tıbbi"; "Genel" mod yerel OCR sonucunu tıbbi bağlam varsaymadan gösterir, Kopyala yanında isteğe bağlı "Cilala" (gevşek istem) sunar. "Tıbbi" modda internet yoksa (veya anahtar girilmemişse ya da istek başarısızsa) sonuç, hiçbir not eklenmeden yerel OCR (maskelenmiş) metni olarak verilir ve aynı "Cilala" düğmesi sonradan gönderme imkânı verir. Ortak bulut istemcisi `tools/Llm`. OCR metni diske yazılmaz.
- Helal Sorgu: iki eşit mod, **Barkod** (açılış modu) ve **İçindekiler** (etiket fotoğrafı + OCR); üstteki seçiciyle değişir (ekran yer değiştirir, yığına eklenmez). İki moddan da geri tuşu ana sayfaya döner; sonuç ekranında geri ana sayfaya, “Yeni sorgu” aynı moda götürür. Barkod bulunamazsa otomatik OCR'a geçilmez.
- YouTube Özeti paylaşım hedefi: manifestte `activity-alias .YoutubeShare` ("YouTube Özet", `ACTION_SEND text/plain`); `Entry` paylaşılan bağlantıyı alıp özeti doğrudan başlatır.
