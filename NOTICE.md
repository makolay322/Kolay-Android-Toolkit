# Üçüncü taraf bileşenler

| Bileşen | Lisans | Kullanım |
|---|---|---|
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) | Apache-2.0 | OCR motoru |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Apache-2.0 | Android bağlayıcısı |
| [tessdata_fast `deu`, `tur`, `eng`](https://github.com/tesseract-ocr/tessdata_fast) | Apache-2.0 | Almanca, Türkçe, İngilizce dil verisi. APK'da yok (1.029'dan beri); uygulama ilk kullanımda sabitlenmiş commit `87416418657359cb625c412a48b6e1d6d41c29bd`'den indirir, boyut + SHA-256 denetler (`app.kolay.shell.TessData`). |
| Leptonica | BSD-2-Clause | Tesseract4Android ile gelir |
| [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0 | PDF düzenle (sayfa işlemleri). Kütüphanenin yazı tipi, CMap ve metin tabloları (LiberationSans, AFM, Adobe CMap'leri) APK'ya alınmaz: sayfa işlemleri bunları kullanmaz. |
| [Apache POI 4.1.2](https://poi.apache.org/) (poi, poi-scratchpad) + Commons Codec, Commons Collections4, Commons Math3, SparseBitSet | Apache-2.0 | Eski .doc/.xls/.ppt okuyucusu. APK'da yok; R8 ile küçültülmüş tek dex olarak `office-plugin-1` ön-sürümünden istek üzerine indirilir, boyut + SHA-256 denetlenir (`app.kolay.shell.OfficePlugin`, üretim: `tools/office-plugin/build.sh`). |

Bu deponun kendi kodu için henüz bir lisans seçilmedi; seçilene kadar tüm hakları saklıdır.
