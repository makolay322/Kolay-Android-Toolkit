# Geliştirme

## Gereksinimler
- JDK 17 (`JAVA_HOME`), Android SDK (platform 34, build-tools 34+) ve kökte `local.properties` → `sdk.dir=/yol/android-sdk`
- `gh` (GitHub CLI, giriş yapılmış) — yalnızca sürüm yayınlamak için
- `~/.kolay/` altında imza anahtarları (aşağıda). **Depoda yoktur.**

## İlk kurulum (yeni makine)
```bash
git clone https://github.com/makolay322/Kolay-Android-Toolkit && cd Kolay-Android-Toolkit
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease          # ilk seferde Gradle ve bağımlılıkları indirir
```
Anahtar yoksa derleme **debug anahtarıyla** imzalar ve çalışır; ama bu APK'lar üretimdeki kurulumun üzerine yüklenemez ve
güncelleme imzası üretemez. Sürüm yayınlamak için anahtarlar gerekir ([OPERATIONS.md](OPERATIONS.md#anahtarlar)).

## Yapı
| Komut | Sonuç |
|---|---|
| `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` (yayında `Kolay.apk` adıyla) |

Tek Gradle modülü vardır (`app/`). Release derlemesi R8 ile küçültülür (`minifyEnabled`, `shrinkResources`; kurallar `app/proguard-rules.pro`: Tesseract/Leptonica sınıfları yerel koddan adla çağrıldığı için tutulur, isimler karıştırılmaz). Yeni bir kütüphane yansıma/JNI ile sınıf arıyorsa oraya `-keep` ekle. Yerel kütüphaneler ve `deu.traineddata` APK'da sıkıştırılmıştır (`useLegacyPackaging`); OCR verisi her yeni sürümün ilk açılışında dosyaya kopyalanır. Sürüm bilgisi `app/version.properties`'te yalnızca **taban** değerdir; gerçek sürümü yayın betiği
son manifest'ten türetip derlemeden önce yazar (sonra geri alır, depoya commit edilmez).

## Telefona yükleme
- İlk kurulum: Release'teki `Kolay.apk` telefona indirilip elle yüklenir.
  (HyperOS/MIUI'de `adb install` için "USB üzerinden yükle" geliştirici seçeneği açık olmalı.)
- Sonrası otomatik: uygulama yeni sürümü indirir, ana sayfadaki banda dokununca kurar (arka planda ekran kapalıyken kendiliğinden de kurar).
- Debug imzalı yerel derleme üretim kurulumunun üzerine yüklenemez (imza farklı).

## Test
Emülatör/otomatik arayüz testi yoktur; cihazda elle denenir. Masaüstü JVM'de denenebilen saf Java parçalar:
- `app/.../shell/Verify.java` (SHA-256, ECDSA) — `openssl dgst -sign` ile üretilmiş imzayla doğrulama
- `app/.../app/tools/ocr/Mask.java` (isim maskeleme) — **uydurma** örneklerle; gerçek belge/isim kullanma

Cihazda kontrol listesi (sürüm öncesi):
1. Temiz kurulum → ana sayfa animasyonla açılıyor, Ayarlar'da sürüm bilgisi doğru.
2. Anahtarı kaydet → OCR → 2–3 fotoğraf seç (karışık sıra) → sonuç, sıra doğru, `[NAME]` görünüyor.
3. Kopyala / Paylaş çalışıyor; uygulamayı kapat-aç → sonuç diske yazılmamış.
4. Ayarlar → *Şimdi denetle* → durum metni mantıklı; yeni sürüm varsa *Şimdi kur* kurar, uygulama kapanır, yeniden açınca sürüm numarası artmış.
5. Koyu/açık tema geçişi arayüzü bozmuyor; geri tuşu her ekranda beklenen yere dönüyor.

## Sorun giderme
| Belirti | Neden / çözüm |
|---|---|
| Güncelleme "imza doğrulanamadı" | Release'i farklı anahtarla imzaladın ya da `Config.UPDATE_PUBLIC_KEYS` eski. Anahtarları karşılaştır. |
| "APK imzası bu uygulamayla aynı değil" | Release farklı keystore ile imzalanmış (ör. Secret eksik → debug). CI keystore Secret'larını kontrol et. |
| Yeni sürüm gelmiyor | 6 saat bekleme: Ayarlar'dan zorla denetle. `latest` Release'in `manifest.json`'u var mı bak. |
| Kabuk ≤ 1.013 cihaz güncellenmiyor | Ayarlar → “Kabuğu indir ve yükle” (manifestte `api: 5` + `shell*` alanları olmalı). |
| `Gradle verification` hatası | Bağımlılık değişti: `./gradlew --write-verification-metadata sha256 :app:assembleRelease`, farkı incele. |
| Derleme `Unable to locate a Java Runtime` | `JAVA_HOME` JDK 17'ye işaret etmeli. |
