# Uji penerimaan perangkat

## Tiga lapis pengujian

Emulator tanpa akselerasi hardware (mesin build tanpa `/dev/kvm`) berjalan 10–20× lebih lambat dari HP. Karena itu pengujian dibagi menjadi tiga lapis, dari yang tercepat.

| Lapis | Perintah | Waktu | Untuk |
|---|---|---|---|
| **1. Unit test (JVM)** | `./gradlew :app:testDebugUnitTest` | ±1 menit | Parser, aturan risiko, pemindai DEX, prune |
| **2. Render layar (JVM, Robolectric)** | `./gradlew :app:testDebugUnitTest --tests '*ScreenRenderTest*'` → `app/build/screenshots/*.png` | ±12 detik untuk 6 layar | Tata letak terang/gelap, bahasa Indonesia, huruf besar, tanpa emulator |
| **3. Emulator atau HP** | `tools/emulator/emu.sh` | menit | Hal yang butuh Android sungguhan: PackageManager, SAF, pemasangan, WorkManager |

`tools/emulator/emu.sh` mempercepat lapis 3:
- **boot dari snapshot**: di mesin tanpa KVM, 146 detik turun menjadi 31 detik;
- animasi dimatikan;
- dialog "isn't responding" ditutup otomatis;
- **menunggu teks** (`emu.sh wait "Overview"`) dan **mengetuk berdasarkan teks** (`emu.sh tap "Check 10 apps"`), bukan `sleep` dan koordinat.

```bash
export ANDROID_HOME=/path/to/sdk
tools/emulator/emu.sh start t26      # boot pertama: cold boot
tools/emulator/emu.sh save           # simpan snapshot "ready" sekali
tools/emulator/emu.sh install app/build/outputs/apk/debug/app-debug.apk
tools/emulator/emu.sh launch com.packpeek.app.debug
tools/emulator/emu.sh wait "Overview" && tools/emulator/emu.sh tap "Overview"
tools/emulator/emu.sh shot overview.png
```

## Skenario

Jalankan pada API 24, 29, 33, 34, 36 dan minimal satu HP fisik. Belum dinyatakan lulus hanya karena build berhasil. Catat perangkat, API, ABI, bahasa, hasil dan logcat.

| Skenario | Hasil yang diharapkan |
|---|---|
| Pasang, onboarding, buka ulang | Tidak crash; onboarding sekali; folder tersimpan |
| Scan/refresh/install/uninstall aplikasi lain | Daftar berubah sesuai keadaan; seleksi aplikasi hilang dibuang |
| Centang A+B, ekstrak A dari kartu | Hanya A dibuat; seleksi batch tetap A+B |
| Batch APK biasa + split | Masing-masing APK/APKS; APK hasil identik byte dengan sumber |
| Satu sumber tidak terbaca | Gagal beralasan; item lain tetap berjalan |
| Nama sama, Unicode, simbol, panjang | Tidak menimpa; suffix nomor; nama sah |
| Folder dicabut/dihapus, storage penuh | Pesan ramah, pilih ulang dan partial dibersihkan |
| Batal lewat UI/notifikasi | Pekerjaan berhenti; hasil sukses tetap; partial dihapus |
| Layar mati/background/process death | Uji batas WorkManager; item sukses tidak duplikat |
| Force stop | Android dapat menahan work; cek pemulihan setelah buka ulang |
| Notifikasi ditolak | UI progres tetap berjalan; tidak crash |
| Share satu/banyak/ikon | Penerima dapat membaca URI |
| Riwayat cari/sembunyikan | File tetap ada; total kumulatif tetap |
| Impor APK/APKS | Tidak memasang otomatis; package/versi benar |
| ZIP traversal, base ganda, split beda versi | Ditolak; cache dibersihkan; tidak hang |
| Signer berbeda, APK rusak, ABI salah | Validasi menolak sebelum install |
| Installer/izin sumber tidak dikenal/batal | Konfirmasi Android benar; setelah izin ketuk Pasang kembali |
| Izin, komponen, sertifikat | Cocokkan dengan dumpsys/apksigner |
| Tema, EN/ID, font 200%, TalkBack | Terbaca, terjangkau, label dan terjemahan tepat |
| Rotasi/tablet/foldable/split-screen/back | State dan layout wajar; dialog tidak terpotong |
| Release R8 | Room/DataStore/Worker/audit/installer tetap berfungsi |

T341: tambahkan dan rekam Baseline Profile/Macrobenchmark pada perangkat; bandingkan startup/scroll sebelum dan sesudah. Jangan menyimpulkan performa dari waktu build.

T350: menunggu uji wajib selesai, kontak dukungan diisi, kunci produksi dibackup dan uji instal/update fisik selesai.
