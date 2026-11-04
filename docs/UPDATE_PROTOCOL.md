# Güncelleme protokolü

Kolay tek bir APK'dır ve kendini GitHub Releases'ten günceller (1.014'ten beri; önceki kabuk + modül düzeni kaldırıldı).

## Yayın içeriği
Her GitHub Release (etiket `v<versionName>`, örn. `v1.014`) iki dosya taşır:

| Dosya | Amaç |
|---|---|
| `Kolay.apk` | Uygulamanın kendisi (`app.kolay`), release keystore ile imzalı |
| `manifest.json` | Cihazın okuduğu açıklama + ECDSA imzası |

Cihaz yalnızca şu sabit adresi sorgular (GitHub "latest" yönlendirmesi, API anahtarı/kota gerekmez):
`https://github.com/makolay322/Kolay-Android-Toolkit/releases/latest/download/manifest.json`

## `manifest.json`
```json
{
  "versionCode": 24, "versionName": "1.014",
  "size": 10765450,
  "sha256": "<Kolay.apk SHA-256, küçük harf hex>",
  "signature": "<base64(DER ECDSA-SHA256 imza), Kolay.apk baytları üzerinde>",
  "url": "https://github.com/makolay322/kolay/releases/download/v1.014/Kolay.apk",
  "api": 5,
  "shellVersionCode": 24, "shellVersionName": "1.014", "shellSize": 10765450,
  "shellSha256": "<aynı>", "shellSignature": "<aynı>", "shellUrl": "<aynı>"
}
```
- Kolay ≥ 1.014 yalnızca ilk altı alanı okur.
- `api` ve `shell*` alanları **eski kurulumlar** içindir (kabuk ≤ 1.013, iki APK düzeni). Onların güncelleyicisi `api` 5 > 4 görünce modül
  indirmez, "daha yeni bir kabuk gerektiriyor" der ve `shell*` alanlarıyla Ayarlar'da “Kabuğu indir ve yükle” düğmesini açar; bu düğme
  aynı `Kolay.apk`'yı kurar. Böylece eski kurulumlar tek dokunuşla yeni düzene geçer. Kaldırmak için o sürümde kalan cihaz olmamalı.
- **Depo adı değişikliği (`kolay` → `Kolay-Android-Toolkit`):** manifest bağlantıları bilerek eski adla (`makolay322/kolay`) yazılır; adı değişmeden önceki sürümler yalnızca o öneki kabul eder.
  GitHub eski adresi yeni depoya yönlendirir (hem manifest hem indirme). Yeni sürümler iki öneki de kabul eder. Bu yönlendirme, eski adla yeni bir depo
  açılmadığı sürece çalışır: **`makolay322/kolay` adıyla yeni depo açma.** Eski kurulum kalmayınca `tools/release.sh` yeni adı yazacak şekilde güncellenebilir.
- `signature` **dosya baytlarını** imzalar (manifest alanlarını değil). `versionCode` APK'nın içinde olduğundan imzalı veriye dahildir.

## Cihaz tarafı adımlar (`Updater.run`)
1. Manifest'i indir (≤64 KB).
2. `url`, `Config.REPO_BASE` (veya eski ad `Config.LEGACY_REPO_BASE`) + `"releases/download/"` ile başlamalı; yönlendirmeden sonra son sunucu `github.com` veya `*.githubusercontent.com` ve HTTPS olmalı. `size` ≤ 120 MB.
3. `versionCode` ≤ yüklü sürüm ise "Güncel"; indirilmiş eski dosya silinir.
4. Geçici dosyaya indir (beklenen boyutu aşarsa kes). Sırayla: SHA-256 → ECDSA imzası (`Config.UPDATE_PUBLIC_KEYS` içindeki **herhangi** bir anahtarla) →
   `getPackageArchiveInfo`: paket adı `app.kolay`, `versionCode` manifestle aynı, imza sertifikası yüklü uygulamayla aynı.
5. `files/update/Kolay.apk` olarak sakla, "Yeni sürüm hazır" dinleyicisine haber ver (ana sayfa bandı, Ayarlar'da “Şimdi kur”).

Herhangi bir adım başarısızsa dosya atılır, 30 dk sonra tekrar denenir (başarıda 6 saat).

## Kurulum (`Updater.install`)
- Tek `PackageInstaller` oturumu; Android 12+'da `USER_ACTION_NOT_REQUIRED` istenir. Android bir uygulamanın **kendini** güncellemesine
  genellikle onaysız izin verir; izin vermezse kendi onay ekranını gösterir (`InstallResultReceiver`).
- Ön planda: kullanıcı banda / “Şimdi kur”a dokununca. Kurulum uygulamayı kapatır; yeniden açınca yeni sürüm çalışır.
- Arka planda (`UpdateJob`, 12 saatte bir): uygulama ekranda değilken indirilmiş sürüm kendiliğinden kurulur. Android onay isterse
  ekran açılamadığı için "Kolay güncellemesi hazır" bildirimi çıkar; dokununca uygulama açılır ve bant kurulumu yapar.
- "Bilinmeyen kaynaklardan yükleme" izni yoksa ön planda ayar sayfası açılır; arka planda kurulum denenmez.

## Anahtarlar
- **Güncelleme imzası:** ECDSA P-256. Özel anahtar `~/.kolay/update_key.pem` (depo dışı) + Secret `UPDATE_KEY_PEM`. Açık anahtarlar `Config.UPDATE_PUBLIC_KEYS`.
- **APK imzası (Android):** `~/.kolay/release.keystore` + Secret'lar. Her sürüm aynı anahtarla imzalanmalı; yoksa Android güncellemeyi reddeder.
- Rotasyon ve kaza durumları: [OPERATIONS.md](OPERATIONS.md).

## İmza komutu (referans)
```bash
openssl dgst -sha256 -sign ~/.kolay/update_key.pem Kolay.apk | base64 | tr -d '\n'
```
Açık anahtar üretimi: `openssl ec -in ~/.kolay/update_key.pem -pubout -outform DER | base64 | tr -d '\n'`.
