# Arayüz sistemi

Hedef: "yerleşik, cilalı" his — az ama anlamlı hareket, tutarlı boşluk, açık/koyu tema. Her şey `app/src/main/java/app/kolay/app/ui/` altında; ekranlar yalnızca bu parçaları birleştirir.

## Parçalar
| Sınıf | Görev |
|---|---|
| `Palette` | Renk belirteçleri (açık/koyu). Android 12+'da vurgu ve nötr tonlar sistemin duvar kâğıdı paletinden (`system_accent1/2/3`, `system_neutral1/2`; uygulama ikonuyla aynı kaynak) gelir, öncesinde sabit turkuaz takım. Ayrıca sabit **kategori tonları** (`Tone`: mavi, mor, yeşil, kehribar, gül, gök, turkuaz, kırmızı). Tema veya duvar kâğıdı değişince etkinlik yeniden kurulur. |
| `Ctx` | `host`, `a` (Activity), `p` (Palette), `nav`, `ui` (Handler), `io` (havuz), `dp()` |
| `Ui` | Metin/buton/yüzey fabrikaları, dokunma geri bildirimi, ortak hareket eğrileri ve yardımcıları |
| `Icon` | 24 birimlik ızgarada çizgi ikonlar (path); renk serbest, her boyutta keskin |
| `Nav` / `Screen` | Yığın + yönlü geçişler; kenardan kenara çizim: her sayfa çerçevesi sistem çubukları ve klavye kadar içeri itilir (`c.insets`). `edgeToEdge()` true dönen sayfa (ana sayfa) çubukların altına çizer ve boşluğu `onInsets()`'te kendisi verir; `darkStatusIcons()` durum çubuğu ikon rengini seçer. |
| `Sheet` | Alt sayfa (menü, onay, metin alanı, bilgi): tutamaçlı kart, aşağıdan yükselir. Uygulamadaki her diyalog budur; `AlertDialog` kullanma. |
| `Snack` (`Ui.toast` / `Ui.toastLong`) | Sayfanın altında ters renkli kısa mesaj; sistem `Toast`'u yerine bu kullanılır. |
| `Header` | Geri düğmesi + başlık + değişen alt başlık |
| `Arc`, `Bar`, `Toggle` | Dönen yay yükleyici, kayan ilerleme çubuğu, yaylı anahtar |

Hazır kalıplar (`Ui`): `label` bölüm etiketi · `card` kart · `input` metin alanı · `pair` iki eşit düğme · `bottomBar` alt eylem alanı ·
`state` boş/izin/hata ekranı (ikon rozeti + başlık + açıklama + isteğe bağlı düğme) · `busy` yükleniyor ekranı · `segmented` segmentli seçici ·
`flashCopied` düğme üstünde “Kopyalandı” onayı · `toneTile` degrade ikon kutusu (beyaz ikon, tonun renginde gölge) · `softTile` açık zeminli ikon kutusu ·
`ripple` dolgusuz dokunma dalgası (liste satırı) · `gradient` · `divider` · `stagger` kademeli giriş · `toast`. Yeni ekran bunları kullanır; aynı şeyi elle yeniden kurma.

## Renk belirteçleri
`bg` sayfa · `surface` kart · `surface2` girdi/ikincil düğme · `ink` ana metin · `muted` ikincil metin · `line` ayraç ·
`primary`/`onPrimary` vurgu · `primarySoft`/`onPrimarySoft` yumuşak vurgu (ikon kutuları, tonlu düğme) · `danger`/`dangerSoft`, `ok`/`okSoft`, `warn`/`warnSoft` ·
`track`/`thumb` anahtar (kapalı ray, düğme) · `scrim` kamera üstü koyu katman · `ripple` dokunma dalgası.
`heroA`/`heroMid`/`heroB` + `onHero`: ana sayfa başlık degradesi (vurgu → üçüncül ton; Namaz'ın "Sıradaki vakit" kartı ve sohbetteki kendi balonun da bunu kullanır) · `inverse`/`onInverse`: snackbar · `shadow`: açık temada gölge rengi.
`tone(i)` (ve onun açık hâli `softBg(i)`/`softFg(i)`): ikon kutuları için kategori tonları; sıra numarasıyla çağır ki komşu kutular farklı renkte olsun. Aynı şey hep aynı renk: ana sayfa grupları (Belgeler mavi, Sohbet mor, Dini yeşil, Araçlar kehribar, Dosyalar gök; bir grubun araç satırları grubun tonundan devam eder) ve dosya türleri (`Files.tone`: görsel gül, video/PDF kırmızı, ses kehribar, tablo yeşil, sunum kehribar, metin gök, arşiv mor, APK turkuaz).
Anlamsal renkler (`danger`, `ok`, `warn`) duvar kâğıdından etkilenmez.
Açık temada kartlar gölgeli, koyu temada ince çerçeveli (gölge koyuda okunmaz).

## Ölçü ve tipografi
- Köşe (`Ui.R_*`): kart 24 dp, düğme 16, girdi 14, ikon kutusu 14, çip 12. Sayfa kenar boşluğu `Ui.GUTTER` (16 dp); yan yana düğmeler arası `Ui.GAP` (10 dp); kartlar arası 12 dp.
- Düğme yüksekliği: alt eylem çubuğu `Ui.H_BAR` (56), içerik içi `Ui.H_BTN` (52), ikincil/hayalet ve kart içi `Ui.H_GHOST` (48). Dokunma hedefi ≥ 44 dp.
- Yazı ölçeği (`Ui.T_*`): `T_DISPLAY` 30 (ana sayfa selamı) · `T_TITLE` 22 (sayfa başlığı, 800) · `T_HEAD` 17 (kart başlığı, 700) · `T_BODY` 15 · `T_SUB` 13 (ikincil) · `T_CAP` 12 (etiket, 700 + harf aralığı). `Ui.text(c, metin, sp, renk, ağırlık)`.
- Derinlik: açık temada yüzeyler 3 dp yükseltili ve gölgeleri renkli (`Ui.softShadow`, gri değil); koyu temada gölge yerine ince çerçeve.

## Hareket ilkeleri
Süreler `Ui.D_FAST` 150 · `Ui.D_MED` 200 · `Ui.D_SLOW` 250 ms; hiçbir geçiş 250 ms'yi aşmaz (dönen yükleyici gibi sürekli göstergeler hariç).

| Durum | Hareket | Süre / eğri |
|---|---|---|
| Sayfa girişi (push) | sağdan 48 dp kay + belir; eski sayfa hafifçe geri çekilip solar | 250 / 200 ms, `EMPH` |
| Geri (pop) | tersi | 250 / 200 ms, `EMPH`/`EXIT` |
| Akış içi geçiş (replace) | çapraz solma + 0.97→1 ölçek | 250 ms |
| Liste girişi (`Ui.stagger`) | aşağıdan 14 dp yüksel + belir, öğeler arası 30 ms (ilk 8 öğe) | 250 ms, `EMPH` |
| Dokunma | 0.97'ye küçül, bırakınca hafif yayla geri | 100 / 200 ms `POP` |
| Alt sayfa (`Sheet`) | 48 dp aşağıdan yükselip belirir; kapanırken 150 ms'de iner | 250 ms `EMPH` |
| Snackbar | 12 dp yükselip belirir, 150 ms'de söner | 200 ms |
| Metin değişimi | sönüp 5 dp kayarak yeni metin belirir (`Ui.swapText`) | 90 + 150 ms |
| Geri bildirim | `performHapticFeedback` (seçim: CLOCK_TICK, onay: CONFIRM) | — |

Kurallar: animasyon bilgi taşır, süs değildir; sistem animasyon ölçeği otomatik uygulanır, animasyonlar kapalıysa (`Ui.motion()` false:
geliştirici seçeneği 0 ya da erişilebilirlikte "animasyonları kaldır") giriş/alt sayfa hareketleri atlanır, öğeler doğrudan son hâlinde görünür;
giriş animasyonları yalnızca `onShown()` içinde başlar (geri dönüşte tekrarlanmaz).

## Yeni görsel parça eklerken
1. Renk için `Palette`'e belirteç ekle (açık + koyu), sabit hex kullanma.
2. Ölçüleri `c.dp()` ile ver.
3. Etkileşimli ise `Ui.surface` (ripple + gölge) ya da liste satırında `Ui.ripple`, ve `Ui.press` kullan. Mesaj için `Ui.toast`, diyalog için `Sheet`.
4. İkon gerekiyorsa `Icon.Kind`'a path ekle (24'lük ızgara, 2 birim çizgi).
