# Kolay

Telefonda çalışan, **kendini GitHub'dan güncelleyen kişisel araç kutusu**. Tek bir Android uygulaması (Android 10+), tek APK.

## Araçlar

| Araç | Ne yapar |
|---|---|
| **OCR** | Belge fotoğraflarını cihazda okur (Tesseract). *Tıbbi* modda kişisel verileri cihazda maskeler, sayfaları sıraya dizip temizlenmiş metin verir (internet yoksa yerel metni olduğu gibi verir, “Cilala” ile sonradan gönderilebilir); *Genel* modda ham metin ve isteğe bağlı cilalama. |
| **Helal Sorgu** | Barkoddan (Open Food Facts) ya da etiket fotoğrafından içindekileri alır, Hanefi ölçütleriyle *helal / haram / şüpheli* değerlendirmesi yapar. |
| **Cuma Mesajı** | Diyanet'in *Hadislerle İslâm* eserinden seçilmiş, öğüt içeren 274 hadisten biriyle rastgele, yatay 3:2 Cuma görseli çizer; kaydet / paylaş. |
| **Sohbet** | Ufak tefek işler için sohbet kutusu (cilalama servisiyle): soru sor, metin düzelttir, çevirt, özetlet. Konuşma kaydedilmez; ekrandan çıkınca silinir. |
| **Namaz Vakitleri** | Konuma göre vakitler (Diyanet açıları), geri sayım, vakit başına hatırlatıcı ve ana ekran widget'ı. |
| **Kıble** | Kayıtlı namaz konumu ve telefonun pusulasıyla kıble yönü; manyetik sapma düzeltilir, kıbleye dönünce titreşir. |
| **Uygulama Güncelle** | Google Play dışından yüklenen uygulamalar için yeni sürüm arar: APKMirror + APKPure (hangisi daha yeniyse) ya da LiteAPKs, uygulama başına seçilir. İndirir, kurar, dosyayı siler. |
| **YouTube Özeti** | YouTube bağlantısından Türkçe özet; paylaşım menüsünde “YouTube Özet” olarak da çıkar. |
| **Fotoğraf Sıkıştır** | Seçilen fotoğrafın %90 kaliteli JPEG kopyasını `Pictures/Kolay`'a kaydeder. |
| **QR Kod** | Hızlı QR okuma (bildirim panelindeki hızlı ayar kutucuğundan da açılır) ve QR oluşturma: metin/bağlantı, Wi-Fi (şifreyle bağlanma) veya kişi kartı; kaydet / paylaş. |

Ana sayfadaki “Ko” logosu günün saatine göre renk değiştirir; O harfi birkaç saniyede bir madeni para gibi kendi ekseninde döner.
Yeni araç eklemek: [docs/ADDING_A_TOOL.md](docs/ADDING_A_TOOL.md).

## Kurulum

1. [Releases](https://github.com/makolay322/kolay/releases/latest) sayfasından **`Kolay.apk`** dosyasını indirip yükle.
2. Uygulamayı aç → sağ üstte **Ayarlar** → *Gemini API anahtarı* alanına anahtarı yapıştır → **Kaydet**. Anahtar yalnızca
   cilalama, özet ve helal değerlendirmesi için gerekir; diğer araçlar anahtarsız çalışır.
3. Sonraki sürümler kendiliğinden iner ve doğrulanır; ana sayfada “Yeni sürüm hazır” bandına dokununca kurulur (Android 12+'da
   genellikle onaysız; uygulama kapanır, yeniden açınca yeni sürüm çalışır). Uygulama kapalıyken arka planda kendiliğinden de kurulur.

1.013 veya daha eski bir kurulumdan geçiş: Ayarlar → **“Kabuğu indir ve yükle”** bir kez yeni tek APK'yı kurar; ayarlar ve anahtar korunur.

API anahtarı yalnızca telefonda, Android Keystore ile şifreli tutulur. Bu depoda **hiçbir sır yoktur**.

## Nasıl çalışır (kısaca)

```
 main'e push ──► GitHub Actions (release.yml) ──► Release vX: Kolay.apk + manifest.json
                 derle · keystore ile imzala ·            │  (SHA-256 + ECDSA imzası)
                 ECDSA güncelleme imzası                  ▼
 Telefon: Kolay ── 6–12 saatte bir manifest'i okur ── indirir · doğrular ── kendini günceller
```

- **`app.kolay.app`**: arayüz ve tüm araçlar.
- **`app.kolay.shell`**: platform katmanı — açılış, güncelleyici, şifreli anahtar deposu, namaz motoru/widget/alarmlar, QR kutucuğu.
- Güncelleme yalnızca imza (uygulamaya gömülü açık anahtar) ve APK sertifikası tutarsa kurulur.

`main`'e yapılan her push GitHub Actions'ta derlenir, imzalanır ve yeni sürüm olarak yayınlanır (`1.014`, `1.015`, …).
Ayrıntı: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Güvenlik özeti

- Güncelleme dosyaları GitHub'da durur ama **yalnızca sahibinin anahtarıyla imzalıdır**; uygulama imzayı ve APK sertifikasını doğrulamadan güncellemeyi kurmaz.
- OCR'da kişisel veriler buluta gitmeden önce cihazda maskelenir; OCR metni diske yazılmaz.
- “Uygulama Güncelle” bir dosyayı yalnızca paket adı, sürümü ve imzası yüklü uygulamayla uyuşuyorsa kurar.
- Yedekleme kapalı, yalnızca HTTPS.

Tam model: [SECURITY.md](SECURITY.md).

## Geliştirme ve işletme

| Konu | Doküman |
|---|---|
| Mimari, açılış akışı, araçların iç işleyişi | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Güncelleme protokolü ve manifest biçimi | [docs/UPDATE_PROTOCOL.md](docs/UPDATE_PROTOCOL.md) |
| Derleme, kurulum, test | [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) |
| Sürüm çıkarma, anahtarlar, geri alma, olay müdahalesi | [docs/OPERATIONS.md](docs/OPERATIONS.md) |
| Yeni araç ekleme | [docs/ADDING_A_TOOL.md](docs/ADDING_A_TOOL.md) |
| Arayüz sistemi (renkler, ölçüler, hareket) | [docs/UI.md](docs/UI.md) |
| Başka oturumlar / yapay zekâ için çalışma kuralları | [CLAUDE.md](CLAUDE.md) |
| Sürüm notları | [CHANGELOG.md](CHANGELOG.md) |

## Hızlı komutlar

```bash
./gradlew :app:assembleRelease     # APK; JDK 17 + Android SDK gerekir
tools/release.sh [sürümAdı]        # elle sürüm (normalde main'e push yeterli)
```

Üçüncü taraf bileşenler: [NOTICE.md](NOTICE.md).
