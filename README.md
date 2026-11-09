# CouchSubs Android Phone — ilk prototip

Bu proje, CouchSubs'un Android TV uygulamasından ayrı olarak telefon üzerinde ekran yakalama + OCR + İngilizce→Türkçe çeviri + kayan altyazı katmanı için hazırlanmış bir Android prototipidir.

## Durum
- MediaProjection ile ekran yakalama iskeleti
- ML Kit Latin OCR
- ML Kit İngilizce→Türkçe çeviri modeli (cihazda indirilir)
- Diğer uygulamaların üzerinde altyazı penceresi
- OCR ilk aşamada ekranın alt %38'ini tarar

## Önemli sınır
PS Remote Play'in görüntüsünün MediaProjection tarafından yakalanıp yakalanamayacağı henüz doğrulanmadı. Uygulama korumalı/siyah görüntü döndürürse Android uygulamasından bunu aşmak mümkün olmayabilir. İlk cihaz testi bu yüzden zorunludur.

## Derleme
Android Studio'da bu klasörü aç, Gradle senkronizasyonunu tamamla ve `app` yapılandırmasını çalıştır. JDK 17 önerilir. `Build > Build Bundle(s) / APK(s) > Build APK(s)` ile APK üret.

İlk çalıştırmada:
1. Diğer uygulamaların üzerinde gösterme iznini ver.
2. Ekran yakalamayı başlat ve Android'in onayını ver.
3. PS Remote Play'e geç, oyunda İngilizce altyazıları aç.
4. Ekranın üst kısmında OCR/çeviri metni belirirse yakalama yolu çalışıyor demektir.

## Lisans
Bu prototip CouchSubs'un AGPL-3.0 lisanslı kaynak kodundan esinlenen ayrı bir Android uyarlama çalışmasıdır. Orijinal proje: https://github.com/yorulmazsinan/couchsubs
