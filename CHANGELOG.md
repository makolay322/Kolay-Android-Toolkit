# Değişiklik günlüğü

Biçim: [Keep a Changelog](https://keepachangelog.com/tr/). Sürümler `main`'e push ile otomatik yayınlanır
(görünen sürüm `1.NNN`; dahili `versionCode` her yayında +1 olur ve kullanıcıya gösterilmez).

## Depo adı
- Depo `kolay` adından **`Kolay-Android-Toolkit`** olarak değişti; güncelleme adresleri yönlendirmesiz yeni adı kullanır. Uygulama başlığı (başlatıcı etiketi ve ana sayfa) da bu ad oldu. **1.026 ve öncesi kurulumlar kendiliğinden güncellenemez; son sürüm APK'sı bir kez elle yüklenmeli** (ayarlar ve anahtar korunur). Paket adı (`app.kolay`) ve `Kolay.apk` dosya adı aynı kaldı.
- Eski yayın notları sadeleştirildi.

## 1.001 ve sonrası (numaralandırma yeniden başladı)
Görünen sürüm adı 1.033'ten sonra **1.001**'den yeniden başladı. Dahili `versionCode` artmaya devam eder; 1.033 kurulu telefonlar 1.001'i normal güncelleme olarak alır. Eski Release'ler silindi.
### Değişti — Sade logo
- Ana sayfadaki animasyonlu logo ve arkasındaki koyu kutu kaldırıldı; yerine başlık renginde sabit, tek renkli “Ko” işareti.

### Değişti — PDF düzenleyicide görüntü sıkıştırma
- Eklenen görüntüler 300 dpi'ye küçültülüp %90 kaliteli JPEG olarak gömülür (PNG kayıpsız kalır); PDF'ler küçülür. Tarama sonrası PDF kaydı zaten A4 300 dpi / %90'dı.

### Değişti — Cuma görseli kare
- Cuma görseli yatay 2:1 yerine kare (1080×1080); hilaller başlığın hemen dışına yerleşir.

### Değişti — Cuma hadis yazısına üst sınır
- **Cuma Mesajı:** hadis yazısı en fazla 64 px (başlığın biraz üstü); kısa hadisler artık aşırı büyümez, uzun olanlar yine sığana kadar küçülür.

### Değişti — Daha küçük APK, Cuma görselinde daha küçük hadis paneli
- **APK ≈ 6,8 MB → ≈ 4,2 MB** (her güncelleme daha az indirir). Özellik kaldırılmadı:
  - PDF motorunun kullanılmayan yazı tipleri ve tabloları çıkarıldı (Adobe CJK CMap'leri, 14 standart yazı tipinin AFM ölçüleri, LiberationSans, glif listeleri, Unicode/Bidi tabloları; ≈ 1,6 MB). PDF düzenle yalnızca sayfa işlemleri yaptığı için bunları hiç okumaz.
  - Uygulama kodu (`classes.dex`) sıkıştırılmış olarak paketlenir (≈ 0,9 MB daha az indirme; kurulumda bir kez açılır).
  - Samba (smbj) ve PdfBox için geniş “hepsini tut” küçültme kuralları daraltıldı (≈ 0,1 MB).
- **Cuma Mesajı:** hadis paneli önceki alanının %75'ine indi (her kenar × 0,866: görselin ≈ %82'si genişlik, ≈ %75'i yükseklik), aynı merkezde; çevresinde desen görünür. Yazı yine panele sığan en büyük boyutta.

### Eklendi — PDF düzenle (sayfa işlemleri)
- **Belgeler → PDF düzenle**, Dosyalar'da PDF menüsü → **PDF düzenle** ve PDF görüntüleyicide kalem düğmesi: sayfalar ızgarada; basılı tutup sürükleyerek sırala, 90° döndür, sil, başka PDF ekle, galeriden görüntü sayfası ekle.
- Metin ve çizimler korunur (sayfalar resme çevrilmez); döndürme kayıpsız (`/Rotate`).
- Eklenen görüntü (ör. 300 dpi tarama) artık piksel boyutundan dev bir sayfa olmaz: komşu sayfanın kâğıt boyutunu (yoksa A4) alır, yatay görüntüde yatay; tam çözünürlükte, JPEG ise yeniden sıkıştırılmadan.
- Kayıt yeni dosya olarak (`ad_duzenlenmis.pdf`, aynı klasöre ya da `Documents/Kolay`) ya da onayla asıl dosyanın üzerine; parolalı PDF parola sorularak açılır (kayıt parolasız). Büyük dosyalar için geçici dosya tamponu, küçük resimler yalnızca ekrandaki sayfalar için.
- Yeni bağımlılık: PdfBox-Android 2.0.27.0 (Apache-2.0; APK ≈ 1,9 MB büyüdü).
- Düzeltme: PDF görüntüleyici PDF'leri açamıyordu (“Belge açılamadı”): yakınlaştırma katmanı arka plan iş parçacığında kurulurken hareket algılayıcıları çöküyordu.

### Değişti (1.009) — Arayüz yenilendi
- **Ana sayfa:** durum çubuğunun altına uzanan degrade başlık (selam, tarih, sıradaki namaz vakti ve kalan süre, dokununca Namaz Vakitleri; konum yoksa konum seçme kısayolu; yeni sürüm bildirimi de burada). Grup kartları başlığın alt kenarına biner; her grubun kendi rengi, degrade ikon kutusu ve köşede hafif ışıması var. Dosyalar kartı dahili depolamadaki boş yeri ve doluluk çubuğunu gösterir.
- **Ortak tasarım sistemi:** sabit kategori renkleri (gruplar ve dosya türleri hep aynı renkte), yazı ölçeği, köşe/süre belirteçleri, açık temada renkli yumuşak gölgeler, odaklanınca vurgu çerçeveli metin alanları. Tüm ekranlar aynı parçaları kullanır.
- **Kenardan kenara:** şeffaf durum ve gezinme çubukları; sayfalar çubuklar ve klavye kadar içeri itilir.
- **Dosyalar:** dosya türüne göre renkli ikonlar (görsel, video/PDF, ses, tablo, sunum, metin, arşiv, APK), satırlarda dokunma dalgası, seçimde yüzen işlem çubuğu. Boş sonuçlu bir listenin “Yükleniyor…”da takılı kalması düzeltildi.
- **Diyaloglar ve mesajlar:** tüm diyaloglar tutamaçlı, aşağıdan yükselen alt sayfa (sayfaya git dahil); sistem “toast”ları yerine sayfanın altında uygulamanın renginde kısa mesaj.
- **Sohbet:** boş ekran, degrade kendi balonların, yuvarlak giriş alanı. **Namaz Vakitleri:** sıradaki vakit kartı ana sayfayla aynı degradede.
- **Hareket:** geçişler 150–250 ms'ye kısaltıldı; sistemde animasyonlar kapalıysa hareketler atlanır.
- Düzeltme: Ayarlar'daki anahtar alanı yazılan anahtarı açık gösteriyordu; artık maskelenir.

### Eklendi (1.007) — Eski Office dosyaları (.doc / .xls / .ppt)
- Belge Görüntüleyici artık eski **Word (.doc)**, **Excel (.xls)** ve **PowerPoint (.ppt)** dosyalarını da açar. Okuyucu APK'yı büyütmemek için içinde değil: böyle bir dosya ilk açıldığında boyutu yazan bir ekran çıkar, **İndir**'e basınca bir kerelik eklenti (yaklaşık 1,5 MB) ilerleme çubuğuyla iner (iptal edilebilir) ve dosya hemen açılır.
- **.doc:** paragraflar, kalın/italik/altı çizili, başlıklar, madde işaretli ve numaralı listeler, tablolar (metin olarak). **.xls:** tüm görünür sayfalar, kayıtlı değerler (formül hesaplanmaz), tarih/sayı/yüzde Türkçe gösterimle. **.ppt:** her slayt için “Slayt N” başlığı altında metin kutuları ve madde işaretleri. Resim ve sayfa düzeni gösterilmez; .ppt içindeki tabloların metni henüz çıkmaz.
- Güvenlik: eklenti yalnızca GitHub'dan HTTPS ile iner; boyutu ve SHA-256 özeti uygulamaya gömülüdür, her yüklemeden önce yeniden denetlenir, tutmazsa silinir ve çalıştırılmaz. Belge cihazdan çıkmaz. **Ayarlar → Eklentiler → Eski Office eklentisini sil** ile kaldırılabilir.

### Eklendi (1.006) — Dosyalar: Samba (ağ paylaşımları)
- Dosyalar giriş sayfasında yeni **Ağ** bölümü: kayıtlı Samba sunucuları (ad, adres, isteğe bağlı port, kullanıcı/parola ya da misafir, isteğe bağlı etki alanı). Ekle, düzenle (basılı tut), sil. Kaydederken bağlantı denenir; olmazsa yine de kaydedilebilir.
- **Ağda ara:** mDNS (`_smb._tcp`) ve bu ağdaki 445 numaralı portun hızlı taranmasıyla sunucular bulunur; dokununca form adresle dolar.
- Sunucu açılınca paylaşımları listelenir (gizli `$` ve yönetim paylaşımları hariç; listeyi vermeyen sunucuda paylaşım adı elle eklenir). Paylaşımlar yerel klasörlerle aynı tarayıcıda gezilir: sıralama, arama, yeniden adlandır, sil, yeni klasör, ZIP.
- **Telefon ↔ sunucu kopyala/taşı** (pano artık kaynağını hatırlar): geçici ad, boyut doğrulaması, sonra yerine taşıma; taşımada kaynak ancak doğrulanmış kopyadan sonra silinir. Aynı paylaşımda taşıma tek adımda.
- Ağdaki dosyaya dokununca önbelleğe indirilir ve açılır (100 MB üstünde önce sorulur; kopyalar bir saat sonra silinir).
- **Uzun işlemler ekran kapalıyken de sürer** (yerel işlemler dahil): bildirimde ilerleme ve **İptal**; iş sürerken telefon ve Wi-Fi uyumaz.
- Protokol: SMB 2.0.2 – 3.1.1; sunucu isterse imzalı, SMB 3'te şifreli. **SMB1 desteklenmez** (açık mesaj). Bağlantı sunucu başına tek, ekranlar arasında paylaşılır; kopan ya da uzun süre boşta kalan bağlantı bir kez kendiliğinden yeniden kurulur.
- Güvenlik: yalnızca **yerel ağ adreslerine** bağlanılır (genel adres açık bir Türkçe mesajla reddedilir); parola Android Keystore ile şifreli saklanır; kimlik bilgisi ve dosya içeriği loga yazılmaz.
- Dosyaya dokununca (yerel ya da ağdaki) PDF, Word, Excel, PowerPoint, metin ve CSV artık uygulamanın kendi **Belge Görüntüleyici**'sinde açılır; diğer türler ve “Birlikte aç” yine sistem seçicisiyle.
- Yeni bağımlılık: `smbj` (BouncyCastle, mbassador, asn-one, slf4j-api ile).

### Eklendi (1.005) — Belge Görüntüleyici
- Yeni, salt okunur **Belge Görüntüleyici**: başka uygulamalarda “Birlikte aç” listesinde **Belge Görüntüleyici** olarak çıkar (PDF, Word, Excel, PowerPoint, metin, CSV). Ana sayfada kartı yok; geri tuşu dosyayı açan uygulamaya döner.
- Hız önce: dosya arka planda okunur, ilk sayfa/ilk paragraflar/ilk satırlar hazır olur olmaz gösterilir, kalanı akarak gelir; yalnızca ekrandaki kısım çizilir.
- **PDF:** dikey kaydırma, sayfalar yalnızca görünürken çizilir (bellek sınırlı önbellek), iki parmakla / çift dokunuşla yakınlaştırma (yakınlaşınca daha keskin yeniden çizim), “3 / 12” göstergesi; dokununca sayfaya git. Parolalı PDF için açık bir uyarı.
- **Word (.docx):** paragraflar, kalın/italik/altı çizili/üstü çizili, başlık boyutları, madde işaretli ve numaralı listeler, basit tablolar, belge içindeki görseller.
- **Excel (.xlsx):** sayfa sekmeleri, hızlı çizilen hücre ızgarası (sabit sütun harfleri ve satır numaraları, iki yönlü kaydırma, yakınlaştırma), dokunulan hücrenin tam değeri altta; yalnızca kayıtlı değerler (formül hesaplanmaz), tarih/sayı/yüzde gösterimi.
- **PowerPoint (.pptx):** her slayt kendi en-boy oranında; metin kutuları ve resimler yaklaşık yerlerinde, kaydırarak slayttan slayta, “Slayt 3 / 12” göstergesi.
- **Metin / CSV:** düz metin (UTF-8, UTF-16 ya da Türkçe Windows kodlaması kendiliğinden), CSV basit tablo olarak (virgül, noktalı virgül ya da sekme).
- Eski **.doc / .xls / .ppt** dosyaları için şimdilik “Eski Office biçimi” bilgisi çıkar (ileride eklenecek destek için tek bir giriş noktası hazır).
- Güvenlik: ağ kullanılmaz, belge içeriği log'a yazılmaz; sıkıştırma bombasına karşı girdi boyutu ve görsel sayısı sınırlı; bozuk dosyada çökme yerine anlaşılır bir hata ekranı.

### Eklendi (1.004) — Dosyalar
- Ana sayfada yeni, tam genişlikte **Dosyalar** kartı: yerel dosya yöneticisi.
- Giriş sayfası: depolama alanları (doluluk çubuğuyla), kategoriler (**Görseller, Videolar, Sesler, Belgeler, İndirilenler, APK'lar**), son 30 günde eklenen dosyalar, **yer imleri** (basılı tut: kaldır).
- Klasör gezme: üstte tıklanabilir yol çubuğu, **liste / ızgara** görünümü, sıralama (ad, tarih, boyut, tür; artan/azalan), gizli dosyaları göster/gizle, geri tuşu üst klasöre çıkar, görsel ve videolarda küçük resim. Binlerce dosyalı klasörler arka planda listelenir.
- Arama: yazdıkça bu klasörde süzer; “Alt klasörlerde de ara” açıkken alt klasörleri de tarar (durdurulabilir).
- İşlemler: basılı tutunca çoklu seçim, tümünü seç, **kopyala / taşı → Buraya yapıştır**, yeniden adlandır, sil (onaylı, kalıcı), yeni klasör, paylaş, birlikte aç, özellikler (klasör boyutu arka planda hesaplanır), **ZIP sıkıştır / çıkart**.
- Uzun işlemler ilerleme çubuğuyla, iptal edilebilir. Aynı adlı öğede sorulur: **Değiştir / Atla / İkisini de tut** (klasörde *Birleştir*), istenirse hepsine uygulanır.
- Veri kaybı yok: dosya önce gizli geçici adla yazılır, boyutu doğrulanır, sonra yerine konur; birimler arası taşımada kaynak ancak doğrulanmış kopyadan sonra silinir; ZIP içinden klasör dışına yazmaya çalışan girdiler reddedilir.
- İzin: Android 11+'da “Tüm dosyalara erişim” (açıklama + ayarlar düğmesi), Android 10'da depolama izni. Dosya içerikleri hiçbir sunucuya gönderilmez.

### Değişti (1.003) — Cuma görseli 2:1, sade sohbet
- **Cuma Mesajı:** görsel yatay **2:1** (2000×1000) oldu. Yerleşim dikey orta eksene göre simetrik: iki üst köşede birbirinin aynası iki hilal, aynalanmış yıldızlar, ortalı desen ve cami silueti, eşit kenar boşluklu ortalı hadis paneli. Hadis satırları tüm genişliğe yayılmaz; yazı boyutu metnin uzunluğuna göre sığacak en büyük boyuta ayarlanır.
- **Sohbet:** “Hızlı başla” düğmeleri kaldırıldı; boş ekranda yalnızca kısa bir ipucu kalır. İsteğe artık sabit bir yönerge eklenmez (önceki mesajlar yine gönderilir) ve yanıt olduğu gibi gösterilir (biçim düzeltmesi yok).

### Değişti (1.002) — Sayfa kenarı algılama
- Sayfa çerçevesi daha doğru yere oturur: açık renkli masada beyaz sayfa ve içinde yoğun tablo/metin olan sayfalar artık bulunuyor (yoğun metin kenar eşiğini sayfa kenarının üstüne çıkarmıyor; sayfadaki tablo çizgileri çerçeveyi içeri çekmiyor).
- Kenar algılama rengi de kullanır (renkli masa / kâğıt farkı); kameradaki canlı algılama dahil.
- Kenar çizgilerine ek olarak “parlak, renksiz bölge” adayı: iki aday da denenir, kenarlarca daha iyi desteklenen seçilir. Galeriden gelen zaten kırpılmış görüntüler eskisi gibi tümüyle alınır.
- Çekilen ve içe aktarılan sayfalarda köşeler daha yüksek çözünürlükte inceltilir (canlı önizlemede yapılmaz).
- Canlı çerçeve titremez: son karelerin ortancası gösterilir, birkaç kare sabit kalınca çıkar, tek tük kaçan karede kaybolmaz, sayfa değişince sıfırlanır.

### Değişti (1.001)
- **Cuma Mesajı:** görselin en altındaki ince siyah şerit (zemin) kaldırıldı; görsel altta doğrudan arka plan rengiyle biter.
- **Belgeler → Sayfalar:** liste 3 sütunlu, daha sık bir ızgara oldu (küçük sayfa önizlemesi, altında sayfa numarası ve filtre).
- Sıralama yukarı/aşağı düğmeleri yerine **basılı tutup sürükle-bırak** ile yapılır; liste kenarına yaklaşınca kendiliğinden kayar. Her sayfada küçük **Döndür**, **Kırp** ve **Sil** (iki dokunuş) düğmeleri kaldı.
- Üstte tek bir **“Filtre (tümü)”** düğmesi: dokununca filtreler açılır, seçilen filtre tüm sayfalara uygulanır (sonraki sayfalar için de varsayılan olur); önizlemeler arka planda tek tek yenilenir. Tek sayfanın filtresi için sayfaya dokun (filtre ekranı açılır).
- Alttaki dört düğme (**Sayfa ekle**, **Metne çevir**, **Tıbbi rapor**, **Kaydet**) daha küçük, simge + kısa etiketle tek satırda.

## 1.014 – 1.033
### Değişti (1.033) — Kararlılık ve temizlik
- Galeriden ya da paylaşımla tek seferde en çok **50 görüntü** alınır; fazlası eklenmez ve bunu bildiren bir not çıkar (çok sayıda görüntüde bellek ve bekleme sorununu önler).
- Metne çevir / Tıbbi rapor: bir sayfa okunurken hata olursa o sayfanın görüntüsü bellekte kalmıyor.
- Önceki akışlardan kalan önbellek dosyaları artık arka planda silinir (Belgeler açılırken takılma yok).
- Kullanılmayan kod kaldırıldı, yinelenen dosya silme yardımcıları teke indirildi. Görünür davranış değişmedi.
### Değişti (1.032)
- **Belgeler** artık tek bir belge akışı: kart doğrudan kaynak seçimini açar — **Kamera**, **Galeri** (sistem Fotoğraf Seçicisi, kamera açılmaz) ve **Wi-Fi tarayıcı**. Hepsi aynı sayfa listesine gider; kaynaklar karıştırılabilir (“Sayfa ekle” ya da geri ile Belgeler ekranına dönüp başka kaynaktan ekle; sayfalar kaybolmaz, altta “Sayfalar (n)”). Ayrı Belge Tarayıcı, Ağ Tarayıcı ve OCR girişleri kalktı.
- Sayfa listesinin altında üç çıktı: **Kaydet** (PDF/JPEG, isteğe bağlı aranabilir PDF; eskisi gibi), **Metne çevir** (cihazda OCR, seçili dillerle; sonuç ekranında dil değiştirip yeniden okuma, isteğe bağlı cilalama) ve **Tıbbi rapor** (önceki tıbbi OCR: Almanca, cihazda maskeleme, sayfa sıralama ve temizleme). OCR artık sayfaların kırpılmış, döndürülmüş ve filtrelenmiş hâlini okur; sonuçtan geri sayfalara dönülür, düzeltip yeniden okunabilir. Akış Belgeler ekranından çıkınca (ya da Kaydet → “Bitti”) biter, sayfalar silinir.
- Paylaşım menüsünde yeni **“Metne çevir”**: başka bir uygulamadan paylaşılan bir ya da birden çok fotoğraf hemen kopyalanıp kırpılır ve doğrudan metne çevrilir (gerekirse önce dil verisi). PDF paylaşımı alınmaz. “YouTube Özet” metin paylaşımı eskisi gibi.
- OCR artık galeri izni istemez (eski OCR'ın fotoğraf ızgarası kaldırıldı); `READ_MEDIA_IMAGES` yalnızca Fotoğraf Sıkıştır için kalır. Çok büyük (64 MB üstü) içe aktarılan dosyalar okunamaz olarak işaretlenir.
### Değişti (1.031)
- **Ana sayfa** dört karta toplandı (2 × 2): **Belgeler** (Belge Tarayıcı, Ağ Tarayıcı, OCR), **Dini** (Namaz Vakitleri, Kıble, Cuma, Helal Sorgu), **Araçlar** (QR, Fotoğraf Sıkıştır, YouTube Özeti, Uygulamalar) ve **Sohbet**. Grup kartı o gruptaki araçların listesini açar; araçtan geri grup listesine, oradan ana sayfaya dönülür. Sohbet doğrudan açılır.
- Dini kartında konum kayıtlıysa sonraki vakit görünür (ör. “Sonraki: İkindi 14:32”). Ayarlar düğmesi, güncelleme bandı, QR hızlı ayar kutucuğu ve YouTube'a metin paylaşımı eskisi gibi.
### Değişti (1.030)
- **Belge Tarayıcı:** deklanşör sesi kaldırıldı; çekimdeki beyaz parlama yerine kısa ve hafif bir karartma + kartın bir an küçülmesi, titreşim ve “Bitti (n)” düğmesinde küçük bir sıçrama.
- Galeriden ekleme artık sistem **Fotoğraf Seçicisi**ni kullanır (Android 13+ ya da güncellemesi gelmiş Android 11/12; yoksa önceki dosya seçici). Seçici açıkken kamera kapalıdır, vazgeçince yeniden açılır.
- Galeriden gelen, **zaten kırpılmış** görüntüler artık içteki bir tablo, kutu, paragraf ya da fotoğraf yüzünden yanlış kırpılmıyor: kenarları kâğıt gibi görünen görüntü olduğu gibi alınır; masa üstünde çekilmiş sayfalarda kenar yalnızca güvenilir ve makul büyüklükteyse kullanılır, değilse tüm görüntü alınır (Kırp → “Algıla” / “Tümü” ile her zaman değiştirilebilir). Açık renkli masadaki sayfalar da artık bulunuyor. Yapay test sayfalarında: kırpılmış görüntülerin 450/450'si doğru (önce 154/450); koyu/gri/ahşap zeminde sayfa bulma kamera algılamasıyla aynı (68/90), açık zeminde 40/60 (önce 0/60). Kameranın canlı algılaması değişmedi.
- **PDF** sayfaları A4 300 dpi'ye göre boyutlanır (uzun kenar en çok 3508 px, büyütme yok), JPEG kalitesi 85 → 90: dosyalar daha küçük ve baskıda yeterince keskin. JPEG kaydı değişmedi.
- Sayfa listesinde her sayfaya **Döndür** (90°) düğmesi; düğmeler kartın altında tam genişlikte.
- **Ağ Tarayıcı:** kayıtlı ayarı olmayan tarayıcıda varsayılan çözünürlük 300 dpi (destekleniyorsa).
### Eklendi (1.029)
- **Belge Tarayıcı** aracı: kamerayla çok sayfalı tarama; sayfa kenarı canlı algılanır ve önizlemede çizilir, isteğe bağlı **otomatik çekim** (çerçeve ~1 sn sabit kalınca), fener, sayfa şeridi. Galeriden çoklu seçimle içe aktarma (görüntüler de otomatik kırpılır). Kırpma ekranında 4 sürüklenebilir köşe ve büyüteç, “Tümü”, “Algıla”, “Döndür”. Sayfa listesinde sıralama, silme, yeniden kırpma ve filtre: Orijinal, Otomatik, Gri, Siyah-Beyaz, Gölge giderme (bir sayfaya ya da tümüne). Kayıt: **PDF** (`Documents/Kolay`) ya da sayfa başına **JPEG** (`Pictures/Kolay`); isteğe bağlı OCR ile **aranabilir PDF** (görünmez metin katmanı) ve metni kopyala / paylaş. Varsayılan dosya adı `Tarama_yyyyMMdd_HHmm`. Her şey cihazda; sayfa görüntüleri yalnızca tarama sürerken uygulamanın önbelleğinde durur.
- **Ağ Tarayıcı** aracı: Wi-Fi'daki eSCL / AirScan (Mopria) tarayıcılar ağda kendiliğinden bulunur (mDNS) ya da IP/adres elle girilir (son adresler hatırlanır). Camdan ya da besleyiciden (çift taraflı dahil, tarayıcı sunuyorsa) tarama; çözünürlük (150/200/300 dpi, tarayıcının desteklediklerinden), renk (Renkli / Gri / Siyah-beyaz), kâğıt (A4 / Letter); ayarlar tarayıcı başına hatırlanır. Camda “Bir sayfa daha tara” ile çok sayfa; tarama sırasında “Vazgeç” işi tarayıcıda da iptal eder. Sayfalar Belge Tarayıcı'nın sayfa listesine gelir: sıralama, kırpma, filtre, PDF/JPEG ve isteğe bağlı OCR aynı. Yalnızca PDF veren tarayıcıların sayfaları cihazda görüntüye çevrilir. Yalnızca WSD destekleyen tarayıcılar desteklenmez.
- Ağ Tarayıcı güvenliği: yalnızca yerel ağ adreslerine bağlanır; önce HTTPS, tarayıcı sertifikası ilk bağlantıda sabitlenir (parmak izi gösterilir), sonradan değişirse bağlanmaz ve uyarır (“Güveni sıfırla” ile kaldırılabilir). HTTPS sunmayan tarayıcıya yalnızca yerel ağda şifresiz bağlanılır; sertifikası sabitlenmiş tarayıcı şifresize düşürülmez.
- **OCR dilleri** ekranı: Almanca, Türkçe ve İngilizce dil verisi (tessdata_fast, sabit sürüm) ilk kullanımda indirilir; boyut ve SHA-256 doğrulanır, dil başına boyut görünür, silinebilir.
- OCR aracının *Genel* modunda dil seçimi (birden çok dil birlikte, ör. Almanca + Türkçe; son seçim hatırlanır). Helal Sorgu etiket okuması Türkçe veri yüklüyse Almanca + Türkçe okur.
- Yeni izin yok (galeri içe aktarma sistem seçicisiyle; ağda tarayıcı bulma ek izin istemez).
### Değişti (1.029)
- Almanca OCR verisi artık APK'da değil, gerektiğinde indiriliyor: APK yaklaşık **0,77 MB küçüldü** (yerel derleme: 4,82 → 4,05 MB; yeni araçlarla birlikte 4,09 MB). Önceki sürümlerin telefona kopyaladığı Almanca veri korunur ve kullanılmaya devam eder; yeni kurulumda OCR ilk kullanımda indirme önerir.
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
