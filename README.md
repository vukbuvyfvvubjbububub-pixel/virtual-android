# Virtual Android

Android sanal makine uygulaması (arayüz + Android dosyası indirme/kurma). Telefonda çalışan bir Android uygulamasıdır (APK).

## Şu an ne yapıyor

- Arayüz: `+` ile Android sürümü ve makine adı seçilir, makine ana ekrana kart olarak eklenir.
- **Tamam**'a basınca o sürümün zip dosyası indirilir (MediaFire bağlantısından), doğrulanır ve açılır. Kartta ilerleme görünür, bittiğinde **İndirildi** yazar.
- İndirme ön plan servisiyle çalışır: ekran kapansa bile devam eder. Yarıda kesilirse kaldığı yerden devam eder.
- **Başlat düğmesi henüz Android'i çalıştırmaz.** Çalıştıracak motor bağlı değil (`engine/VmEngine.kt`, şimdilik `NoEngine`).

## APK'yı nasıl alırım

1. Bu klasörün içindekileri GitHub reposuna yükle (`.github` klasörü dahil).
2. Repoda **Actions → Build APK → Run workflow** (ya da `main` dalına her yüklemede otomatik çalışır).
3. İş bitince çalıştırmanın sayfasında **Artifacts → virtual-android-apk** indir, zip'in içindeki `app-debug.apk`'yı telefona kur.

## Yeni Android sürümü eklemek

`app/src/main/assets/versions.json` dosyasında ilgili sürümün `"url"` alanına MediaFire linkini yapıştır:

```json
{ "id": "android8", "label": "Android 8", "source": "mediafire", "url": "https://www.mediafire.com/file/....", "sha256": "" }
```

- `sha256` doluysa indirilen dosya doğrulanır (önerilir). Boşsa doğrulama atlanır.
- `source` değeri `"mediafire"` (sayfa linki) veya `"direct"` (doğrudan dosya linki) olabilir.
- `url` boş olan sürümler listede "(yakında)" olarak görünür ve seçilemez.

## Bilinen sınırlar

- **MediaFire:** İndirme bağlantısı MediaFire sayfasından çıkarılır (`core/Mediafire.kt`). MediaFire sayfa yapısını değiştirir veya otomatik indirmeyi engellerse çalışmaz. Daha sağlam yol: zip'i bu reponun **Releases** bölümüne yükleyip `source: "direct"` kullanmak (dosya başına 2 GB sınırı var).
- **Depolama:** Zip önce iner (≈320 MB), sonra açılır. Açma sırasında zip + açılmış dosyalar birlikte yer kaplar. Açıldıktan sonra zip silinir.
- **Motor:** Android'i gerçekten çalıştıran kısım henüz yok. Hangi motorun kullanılacağı zip'in içindeki dosya türüne bağlı.

## Klasörler

| Yol | Ne işe yarar |
| --- | --- |
| `app/src/main/assets/index.html` | Arayüz |
| `app/src/main/assets/versions.json` | Sürüm listesi ve indirme linkleri |
| `app/src/main/java/.../core/` | İndirme, MediaFire çözümleme, güvenli zip açma (Android'den bağımsız, test edilmiş) |
| `app/src/main/java/.../DownloadService.kt` | Arka planda indirme servisi |
| `app/src/main/java/.../Bridge.kt` | Arayüz ile Android kodu arasındaki köprü |
| `app/src/main/java/.../engine/VmEngine.kt` | Motorun bağlanacağı yer |
| `app/src/test/` | Birim testleri |
