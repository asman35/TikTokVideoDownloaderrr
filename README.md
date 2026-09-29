# MediaSave Android

Tek bağlantı kutusundan sosyal medya içeriklerini algılamaya yönelik Android uygulaması.

## Mevcut durum

- TikTok video indirme
- TikTok fotoğraf/carousel algılama ve toplu indirme
- Instagram ve YouTube bağlantı algılama altyapısı
- Android Download Manager ile İndirilenler klasörüne kayıt
- GitHub Actions ile otomatik APK derleme

Instagram ve YouTube için sunucu çözümleyici kaynak kodu `server/` altında hazırdır. Bu iki özelliğin APK içinde çalışması için backend HTTPS üzerinde yayınlanmalı ve `API_BASE_URL` değeri yayınlanan adresle değiştirilmelidir.

## APK

Her main güncellemesinde GitHub Actions `MediaSave-debug` adlı APK artifact'i üretir.

> Yalnızca indirme hakkına sahip olduğunuz içerikleri indirin ve ilgili platformların koşullarına uyun.
