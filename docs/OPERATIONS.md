# İşletme

## Bağımsız çalışma (bilgisayara bağlı olmadan)
Yayın hattı tamamen GitHub'dadır; hiçbir makine gerekmez:
- **Düzenle:** github.com'da dosyayı aç → kalem, ya da `.` tuşu (web editör) / Codespaces. Yerel kurulum gerekmez.
- **Yayınla:** `main`'e commit. `app/**`, `gradle/**` veya `tools/release.sh` değiştiyse `release.yml` derler, imzalar, `vX` Release'ini (`Kolay.apk` + `manifest.json`) yayınlar. Sürüm numarası son manifestten türetilir, depoya geri yazılmaz. Elle tetik: Actions → *release* → *Run workflow*.
- **Doğrula:** Actions'ta `build` ve `release` yeşil olmalı; telefonda Ayarlar → *Şimdi denetle*.
- **Eski Release temizliği:** Actions → *purge-releases* (elle).
- **Gereken Secret'lar:** `UPDATE_KEY_PEM`, `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (Settings → Secrets and variables → Actions). Keystore Secret'ları eksikse `release` işi durur (imzasız/farklı imzalı APK telefondaki kurulumun üzerine yüklenemez).
- **Yerelde ne kaldı:** yalnızca anahtar yedeği (`~/.kolay`, parola yöneticisi/şifreli disk) ve APK'yı telefona ilk kez kurmak. Yerel `tools/release.sh` acil durum yedeğidir.
- **Dikkat:** Release/anahtar iş akışlarını (`.github/workflows/`) ve `tools/release.sh`'ı değiştiren her commit'i dikkatle incele; Secret'ları bunlar kullanır. `main` dalını korumalı tut (2FA).

## Anahtarlar
Asıl kaynak **depo dışında**, `~/.kolay/` (izin 700); CI için kopyaları GitHub Secrets'ta durur. **GitHub Secrets yalnızca yazılır, geri okunamaz**: yerel dosyalar yedeksiz kaybolursa tek kopya CI'da kalır ve elinize geri gelmez. Aşağıdakileri **yedekle** (şifreli taşınabilir disk / parola yöneticisi).

| Dosya | Ne işe yarar | Kaybolursa |
|---|---|---|
| `release.keystore` + `keystore.properties` | APK imzası | Telefondaki uygulamanın üzerine yüklenemez; kaldırıp yeniden kurmak gerekir (veri/ayarlar gider). |
| `update_key.pem` | Güncellemelerin ECDSA imzası | Mevcut kurulumlar güncelleme alamaz; yeni anahtarı içeren APK elle yüklenmeli. |

Yeni makinede: dosyaları aynı konuma kopyala, `chmod 700 ~/.kolay && chmod 600 ~/.kolay/*`, `keystore.properties` içindeki `storeFile` yolunu düzelt.

İlk üretim (sıfırdan):
```bash
mkdir -p ~/.kolay && chmod 700 ~/.kolay && cd ~/.kolay
openssl ecparam -name prime256v1 -genkey -noout -out update_key.pem
PW=$(openssl rand -hex 16)
keytool -genkeypair -keystore release.keystore -alias kolay -keyalg RSA -keysize 3072 -validity 36500 \
        -storepass "$PW" -keypass "$PW" -dname "CN=Kolay"
printf 'storeFile=%s/release.keystore\nstorePassword=%s\nkeyAlias=kolay\nkeyPassword=%s\n' "$HOME/.kolay" "$PW" "$PW" > keystore.properties
openssl ec -in update_key.pem -pubout -outform DER | base64 | tr -d '\n'   # → Config.UPDATE_PUBLIC_KEYS
```

## Sürüm yayınlama
**Otomatik (varsayılan):** `main`'e `app/**` (ya da Gradle dosyaları, `tools/release.sh`) değişikliği push et → `.github/workflows/release.yml`
sırayla: gizli anahtar taraması → keystore'u geçici dosyaya açar → APK'yı derler ve imzalar → `UPDATE_KEY_PEM` ile ECDSA imzası atar →
imzayı yayından önce doğrular → `v1.NNN` Release'i (`Kolay.apk` + `manifest.json`) açar.
Sürüm **durumsuzdur**: bir sonraki `versionCode`/`versionName`, son yayınlanan manifest'ten türetilir (`1.014 → 1.015`), depoya commit geri yazılmaz.
Yalnızca doküman değişiklikleri sürüm üretmez. Zorla: GitHub → Actions → *release* → *Run workflow* (veya `gh workflow run release`).

**Elle (yedek):** `main` push edilmiş ve ağaç temizken
```bash
tools/release.sh      # veya: tools/release.sh 1.020  (sürüm adı; verilmezse son sürüm +1)
```
Aynı betik yerelde `~/.kolay` anahtarlarını kullanır. Aynı anda ikisi çalışırsa ikinci "release vX already exists" der; sorun değil.

Sürüm adı `1.NNN` biçimindedir, etiket `v1.NNN`. Cihazlar en geç 6–12 saatte indirir (ya da Ayarlar → *Şimdi denetle*).
Yayından sonra: `curl -sL .../releases/latest/download/manifest.json` ile kontrol et ve CHANGELOG'u güncelle.

**Eski düzenden geçiş:** kabuk ≤ 1.013 cihazlar manifestteki `api: 5` ve `shell*` alanları sayesinde Ayarlar'da “Kabuğu indir ve yükle”
düğmesini görür; bu, yeni tek APK'yı kurar. Bu alanları üreten satır `tools/release.sh`'tadır; eski kurulum kalmayana dek kaldırma.

**Secret kurulumu / yenileme** (bir kez; değer ekrana yazılmaz):
```bash
gh secret set UPDATE_KEY_PEM < ~/.kolay/update_key.pem
# APK imzası (keystore) için:
base64 -i ~/.kolay/release.keystore | gh secret set KEYSTORE_B64
# storePassword, keyAlias, keyPassword değerleri ~/.kolay/keystore.properties içindedir:
gh secret set KEYSTORE_PASSWORD   # KEY_ALIAS ve KEY_PASSWORD için de aynısı
```
Keystore Secret'ları 2026-10-31'de yüklendi; `release.yml` bunları kullanıp APK'yı CI'da imzalar (anahtar geçici dosyaya açılır, iş sonunda silinir).
Anahtar rotasyonunda yeni anahtarı aynı komutla yükle (bkz. aşağıdaki rotasyon adımları).

## Geri alma (kötü sürüm yayınlandı)
Cihazlar `versionCode` düşürmez; bu yüzden **düzeltme ileriye doğrudur**:
1. `git revert <kötü commit>` (veya eski kodu geri getir) → `tools/release.sh` → versionCode daha yüksek, kod eski/sağlam.
2. Kötü sürümü almış cihazlar düzeltme sürümünü normal yoldan alır. **Otomatik geri dönüş yoktur** (tek APK düzeninde kaldırıldı): sürüm açılışta çöküyorsa ana sayfa bandına ulaşılamaz; arka plan işi (`UpdateJob`) düzeltmeyi yine indirip ekran kapalıyken kurar, olmazsa Release'teki `Kolay.apk` elle yüklenir (veriler kalır). Bu yüzden `build` yeşil olmadan push etme.
3. İstersen kötü GitHub Release'ini sil (`gh release delete vN --yes`); `latest` bir öncekine döner. Ama cihaza inmiş kopyayı etkilemez, bu yüzden 1. adım şart.

## Anahtar rotasyonu (planlı)
1. Yeni ECDSA anahtarı üret; açık anahtarı `Config.UPDATE_PUBLIC_KEYS` dizisine **ekle** (eskisini silme).
2. Yeni sürüm yayınla (eski anahtarla imzalı); cihazlar onu kurunca yeni açık anahtarı da tanır.
3. Cihazlar o sürümdeyken `tools/release.sh`'ın anahtarını yenisine çevir (`update_key.pem`) ve GitHub Secret'ı da güncelle.
4. Bir sonraki sürümde eski açık anahtarı diziden çıkar.

## Anahtar ele geçirilirse / kaybolursa (olay müdahalesi)
**Şüphe varsa güncellemeleri durdur:** GitHub hesabında 2FA'yı doğrula, yetkisiz Release var mı bak (`gh release list`), şüpheli olanları sil.
- `update_key.pem` ele geçirildi: saldırgan geçerli imzalı sahte güncelleme üretebilir (APK yine keystore ile imzalı olmak zorundadır). Yeni anahtar üret, yalnızca yeni anahtarı içeren sürüm yayınla (eskisini dizide **bırakma**). Eski anahtara güvenen sürüm kalmayana dek risk sürer.
- `release.keystore` ele geçirildi: sahte "güncelleme" APK'sı aynı imzayla yüklenebilir. Yeni keystore + yeni `applicationId` ile yeni kurulum gerekir; eskiyi kaldır.
- Kayıp (çalınma yok): yukarıdaki "Kaybolursa" tablosu.
- API anahtarı sızdıysa: sağlayıcı konsolundan iptal et/yenile, telefonda Ayarlar'dan yenisini gir (depoyu etkilemez).

## Depo ayarları (GitHub)
- Hesapta **2FA zorunlu** olmalı; depo erişimi yalnızca sahibinde.
- `main` dalı için "force push yasak / silme yasak" koruması önerilir (Settings → Branches).
- Secret scanning ve push protection (public depoda varsayılan) açık kalsın.
- Release'leri yalnızca `release.yml` veya `tools/release.sh` üretmeli; elle yüklenen dosya imzasız olacağı için cihazlarca reddedilir.

## Eski sürümleri temizleme
Yeni sürüm yayında olduğu doğrulandıktan sonra: GitHub → Actions → *purge-releases* → *Run workflow* (veya `gh workflow run purge-releases`). Son Release dışındakilerin hepsini ve etiketlerini siler; geri alınamaz. Önce silme: cihazlar `latest` adresini kaybeder.
