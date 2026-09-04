# TikTok Video Downloader — Android Starter

Kotlin, Jetpack Compose, Retrofit ve Android Download Manager kullanan başlangıç projesi.

## API sözleşmesi

Uygulama `POST /resolve` isteği gönderir:

```json
{ "url": "https://www.tiktok.com/@hesap/video/123" }
```

API şu yanıtı vermelidir:

```json
{
  "downloadUrl": "https://cdn.example.com/video.mp4",
  "fileName": "video.mp4"
}
```

`app/build.gradle.kts` içindeki `API_BASE_URL` değerini kendi HTTPS API adresinizle değiştirin. Adres `/` ile bitmelidir.

## APK oluşturma

### GitHub üzerinden

1. **Actions** sekmesine girin.
2. **Build Android APK** iş akışını açın.
3. **Run workflow** düğmesine basın.
4. İşlem tamamlandığında **Artifacts** bölümündeki `TikTokVideoDownloader-debug` dosyasını indirin.

Her `main` güncellemesinde APK ayrıca otomatik oluşturulur.

### Android Studio üzerinden

1. Depoyu Android Studio ile açın.
2. Gradle eşitlemesinin tamamlanmasını bekleyin.
3. **Build > Build APK(s)** menüsünü seçin.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

## Sonraki adımlar

- Size ait veya kullanım izni bulunan bir TikTok çözümleme API’si hazırlayın.
- `API_BASE_URL` değerini güncelleyin.
- Gerçek cihazda farklı TikTok bağlantı biçimlerini test edin.
- Yayın için uygulama simgesi, gizlilik politikası ve imzalı release APK/AAB ekleyin.

> Bu proje yalnızca indirme hakkına sahip olduğunuz içerikler için kullanılmalıdır. TikTok koşullarına ve telif haklarına uyun.
