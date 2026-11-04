# Kolay — çalışma kılavuzu (insanlar ve yapay zekâ oturumları için)

Bu depo birden fazla oturumdan yönetilir. **Önce bunu oku**, sonra [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tek APK, tek modül
Kolay **tek bir APK**'dır (`app.kolay`, Gradle modülü `app/`); 1.014'ten önceki kabuk + ayrı modül (payload) düzeni kaldırıldı.

| Paket (`app/src/main/java/…`) | Rol |
|---|---|
| `app.kolay.shell` | Platform katmanı: `MainActivity`, `Updater` (kendi kendini güncelleme), `UpdateJob`, `SecretStore`, `Verify`, `Config`, namaz motoru/widget/alarmlar, QR kutucuğu. **Paket ve sınıf adları değişmez**: başlatıcı kısayolu, yerleştirilmiş widget ve hızlı ayar kutucuğu bileşen adına bağlıdır, adı değişirse güncellemede kaybolur. |
| `app.kolay.app` | Arayüz ve araçlar: `Entry`, `ui/`, `screens/`, `tools/`. Normal geliştirme burada. |

`Host` (`app.kolay.shell.Host`) iki katman arasındaki iç arayüzdür; aynı derlemede olduğu için serbestçe değişebilir.
Manifest/izin/ikon değişikliği artık ayrı "kabuk güncellemesi" istemez: her sürüm tek APK olarak gelir.

## Değişmez kurallar (ihlal = güvenlik/stabilite hatası)
1. **Depoya sır girmez**: API anahtarı, `*.keystore`, `*.pem`, `keystore.properties`, `local.properties`. Anahtar cihazda Ayarlar'dan girilir (`Host.secret`).
2. Hasta verisi/örnek belge/gerçek isim depoya girmez (test örneklerinde uydurma isim kullan).
3. Güncelleme yalnızca imzayla: `Updater` APK'yı SHA-256 + ECDSA (`Config.UPDATE_PUBLIC_KEYS`) + paket adı/sürüm/imza sertifikası denetimi olmadan kurmaz. Bu denetimleri gevşetme.
4. APK her sürümde aynı keystore ile imzalanır (CI Secret'ları); farklı imzalı APK telefondaki kurulumun üzerine yüklenemez.
5. `versionCode` yalnızca artar (`tools/release.sh` yönetir). Geri alma = eski kodla **daha yüksek** sürüm yayınlamak.
6. İmza anahtarları depo dışında: hepsi `~/.kolay/` altında; güncelleme anahtarı (`update_key.pem` → Secret `UPDATE_KEY_PEM`) ve APK keystore'u (`release.keystore` → Secret'lar `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) ayrıca GitHub Secrets'ta durur (cihaza bağlı kalmadan, CI'da imzalamak için; sahibi bilerek onayladı, 2026-10-31). Asla commit etme, asla çıktıya yazma; iş akışını (`release.yml`) anahtarı yazdıracak/yükletecek biçimde değiştirme.
7. `main`'e `app/**` (veya Gradle dosyaları, `tools/release.sh`) değişikliği push etmek **otomatik olarak yeni sürüm yayınlar** (`.github/workflows/release.yml`). Yarım iş main'e girmesin; sürüm elle de çıkarılabilir (`tools/release.sh`).
8. Ağ yalnızca HTTPS; güncelleme adresi `Config.REPO_BASE` ile başlamalı, indirme sunucusu `Config.trustedHost` ile sınırlı.
9. OCR metni diske/loga yazılmaz. Loglara kişisel veri koyma.
10. Gradle bağımlılık doğrulaması açık (`gradle/verification-metadata.xml`); bağımlılık eklersen `./gradlew --write-verification-metadata sha256 :app:assembleRelease` ile güncelle ve farkı gözle incele.

## Yayın politikası (sahibinin kesin talimatı)
- **Dal açma, PR açma, RC/ön sürüm çıkarma.** Değişiklik doğrudan `main`'e gider.
- **Hiçbir push `main` dışındaki bir dala yazılmaz** (sahibinin kesin talimatı, 2026-10-31). Oturum "geliştirme dalı" dese bile her push yeni sürümle doğrudan `main`'e gider; dal yeniden oluşturulmaz.
- Her değişiklik **sürüm ilerletilerek** yayına alınır (`main` push → `release.yml`; sürüm adı otomatik artar, gerekirse Actions → *release* → *Run workflow* → `version`).
- Sürüm adı biçimi `1.001`, `1.002`… (otomatik artar). Eski biçim `1.0.x` yalnızca geçmiş sürümlerde.
- Eski kurulumlar için uyumluluk: `manifest.json`'daki `"api":5` ve `shell*` alanları kabuk ≤ 1.013 cihazların yeni APK'yı “Kabuğu indir ve yükle” ile bulması içindir; kaldırma.
- Eski sürümleri temizle: yalnızca en son Release kalır (yeni sürüm yayınlandıktan **sonra** silinir).

## Sık işler
```bash
./gradlew :app:assembleRelease            # yerel derleme (gerekli: JDK 17, Android SDK, local.properties → sdk.dir)
tools/release.sh                          # elle sürüm yayınla (normalde main'e push yeterli) (çalışma ağacı temiz olmalı, gh girişi gerekir)
```
- Yeni araç: [docs/ADDING_A_TOOL.md](docs/ADDING_A_TOOL.md). Arayüz: [docs/UI.md](docs/UI.md) (renk/ölçü sabit kodlama; `Palette` + `Ui` kullan).
- Sürüm/anahtar/geri alma: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Test gerçekliği
Derleme yalnızca GitHub Actions'ta (`release.yml`) yapılır; başarısız derleme sürüm çıkarmaz ve telefon sessizce eski sürümde kalır. `main`'e push ettikten sonra çalışmanın **başarıyla bittiğini** ve Release'in açıldığını doğrula; bitmeden "yayınlandı" deme.
Cihaz testi elle yapılır (emülatör yok). Saf Java parçalar (`Verify`, `Mask`) masaüstünde JVM ile denenebilir.
Bir değişikliği "çalışıyor" diye raporlamadan önce neyin gerçekten çalıştırıldığını yaz; cihazda denenmediyse söyle.

## Dil ve stil
Arayüz metinleri ve dokümanlar Türkçe; kod, tanımlayıcılar ve kod yorumları İngilizce. Çevredeki koda uy (yorum yoğunluğu, adlandırma).
Arayüzde yalnızca Ayarlar'daki anahtar alanı "Gemini API anahtarı" diye adlandırılır; diğer yerlerde servis adı yerine "cilalama" denir.
