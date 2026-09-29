# MediaSave Android

Tek bağlantı kutusundan sosyal medya içeriklerini algılamaya yönelik Android uygulaması.

## Mevcut durum

- TikTok video indirme
- TikTok fotoğraf/carousel algılama ve toplu indirme
- Instagram ve YouTube bağlantı algılama altyapısı
- Android Download Manager ile İndirilenler klasörüne kayıt
- GitHub Actions ile otomatik APK derleme

Instagram ve YouTube çözümleme katmanı sonraki aşamada, yalnızca herkese açık veya indirme hakkınız bulunan içerikler için bağlanacaktır. WhatsApp durumları link ile değil, cihazdaki görüntülenmiş durum dosyalarına kullanıcı izniyle erişecek ayrı bir modül olarak ele alınacaktır.

## APK

Her main güncellemesinde GitHub Actions `MediaSave-debug` adlı APK artifact'i üretir.

> Yalnızca indirme hakkına sahip olduğunuz içerikleri indirin ve ilgili platformların koşullarına uyun.
