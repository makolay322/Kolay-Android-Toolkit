# Yeni araç ekleme

Bir araç = `Tool` uygulaması + en az bir `Screen`. Ana sayfa 2 × 2 kart (Belgeler, Dini, Araçlar, Sohbet) ve altında tam genişlikte Dosyalar kartı gösterir; grup kartı `GroupScreen` ile o gruptaki araçları satır satır listeler (Belgeler kendi akışını açar: `tools/scan/DocsScreen`, araç listesi yok); kart animasyonu,
ikon kutusu, geçiş ve geri tuşu hazır gelir.

## 1. Araç sınıfı
`app/src/main/java/app/kolay/app/tools/<ad>/<Ad>Tool.java`:
```java
public final class NotTool implements Tool {
    @Override public String id() { return "not"; }                 // sabit, ekranda görünmez
    @Override public String title() { return "Hızlı Not"; }
    @Override public String subtitle() { return "Panoya hızlı metin"; }
    @Override public Icon.Kind icon() { return Icon.Kind.COPY; }   // ui/Icon.java'daki hazır ikonlar
    @Override public Screen open(Ctx c) { return new NotScreen(); }
}
```
## 2. Ekran
```java
final class NotScreen extends Screen {
    @Override public View create() {                 // c: Ctx — tema, host, nav, dp(), arka plan havuzu
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Hızlı Not", "…", () -> c.nav.pop()).view);
        // … içerik: Ui.text / Ui.button / Ui.surface …
        return page;
    }
    @Override public void onShown() { /* giriş animasyonları: Ui.appear(view, gecikmeMs) */ }
}
```
## 3. Kaydet
`Tools.java` içinde uygun grup dizisine (`FAITH`, `UTILITIES`) `new NotTool()` ekle. Bitti. Yeni bir ana sayfa kartı gerekiyorsa `HomeScreen`'e bir `card(...)` ekle (ızgara 2 sütun).

## Ekranlar arası geçiş
| Amaç | Çağrı | Hareket |
|---|---|---|
| Yeni sayfa aç | `c.nav.push(screen)` | sağdan kayarak girer |
| Geri dön | `c.nav.pop()` | sağa kayarak çıkar |
| Akış içinde değiştir | `c.nav.replace(screen)` | çapraz solma + hafif ölçek |
| Akışın köküne dön | `c.nav.popTo(screen)` | aradaki ekranlar animasyonsuz kalkar, en üstteki `pop` gibi çıkar |
Geçiş sürerken gelen istek kuyruğa alınır (son istek kazanır). Geri tuşu: ekranda `onBack()` `true` dönerse tüketilir, yoksa `pop()`.

## Kurallar
- Renk/ölçü sabit yazma: `c.p` (Palette) ve `c.dp()`; açık/koyu tema otomatik uyar. ([UI.md](UI.md))
- Uzun işleri `c.io` havuzunda yap, arayüze `c.ui.post(...)` ile dön. Ekran yok edilince (`onDestroy`) işi iptal et / sonucu yok say.
- Yığında alttaki bir ekranın hâlâ gösterebileceği bir `Bitmap`'i `recycle()` etme: `pop` sırasında o ekran yeniden çizilir ve geri dönüştürülmüş bitmap uygulamayı çökertir (bırak, GC toplasın).
- Belge akışında (`ScanSession`) oturumun sahibi kök ekrandır (`DocsScreen`): kaynaklar ve çıktılar (kamera, tarayıcı, kayıt, OCR) oturumu bitirmez/silmez; sayfaları arka planda okumak için `s.jobs()` anlık kopyası alınır.
- Sır gerekiyorsa `c.host.secret("ad")` / Ayarlar'a bir giriş alanı ekle. **Kodda veya depoda sır tutma.**
- Çıktıyı/kullanıcı verisini diske veya loga yazma (gerekçeli ve belgelenmiş istisna yoksa).
- Yeni izin gerekiyorsa `app/src/main/AndroidManifest.xml`'e ekle; sonraki sürümle birlikte gelir (ayrı kurulum gerekmez). Gereksiz izin isteme.
- Platform yeteneği (alarm, widget, arka plan işi) gerekiyorsa `app.kolay.shell` içine koyup `Host`'a metot ekle; `Host` iç arayüzdür, serbestçe değişir.

## Yayın
`main`'e push → `release.yml` yeni sürümü yayınlar. Telefonda ana sayfadaki banda (veya Ayarlar → *Şimdi denetle → Şimdi kur*) dokun.
