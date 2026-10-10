# Arah pengembangan: analisis risiko berbasis bukti

**Tujuan:** membantu pengguna memahami risiko aplikasi, baik sebelum dipasang (berkas `.apk`/`.apks`/`.xapk`) maupun saat sudah terpasang, dengan bukti yang mudah dipahami.

**Bukan tujuan:** memberi vonis "aman" atau "malware". PackPeek menunjukkan apa yang ditemukan, seberapa kuat buktinya, dan apa yang belum terbukti.

Dokumen ini adalah rencana yang sudah disetujui pemilik: model T1–T4, skor diganti daftar kekhawatiran, pilihan B (Akses penggunaan), dan sampel `undangan-sim`.

**Status:**
- Tahap 1 selesai di 2.0.0-alpha.5.
- Tahap 2 (DEX, bukti T2) dan tahap 3 (indikasi jalur data, T3) selesai di 2.0.0-alpha.8.
- Tahap 4 (perilaku saat berjalan) tetap terbatas pada bukti kondisi perangkat (T4).

**Catatan uji salah tuduh (alpha.8, emulator):**
- **API Demos** (sampel Google berisi demo SMS, aksesibilitas, overlay, dan admin) sempat diberi indikator kuat karena dipasang lewat adb. Kini cukup "perlu perhatian", karena kodenya terbaca penuh dan tidak ada jalur kirim.
- **PackPeek sendiri** sempat dianggap mengirim ke bot Telegram, karena daftar tanda pengenalnya berisi teks `api.telegram.org`. Kini teks hanya dihitung bila ada pemanggilan jaringan atau query.

## 1. Kondisi saat ini (2.0.0-alpha.4)

| Aspek | Yang sudah ada | Kekurangan |
|---|---|---|
| Manifest | Izin, komponen, dan flag dibaca lewat PackageManager | Hanya APK dasar; manifest split tidak diperiksa. Konfigurasi aksesibilitas, prioritas penerima SMS, dan ikon tersembunyi belum dikenali |
| Kode (DEX) | Belum ada | Fungsi yang dipanggil aplikasi belum diketahui |
| SDK | Hanya framework (Flutter, React Native, Unity, …) dari nama berkas | SDK iklan/analitik belum dikenali |
| Tanda tangan | Diverifikasi untuk APK dasar; perubahan dibanding backup | Split belum diverifikasi di layar detail |
| Hasil | Skor angka 0–100 dari 8 temuan konfigurasi | Angka tunggal mudah dibaca sebagai vonis; tidak ada tingkat bukti |

## 2. Model bukti

Setiap temuan diberi **tingkat bukti**. Tingkatnya tidak dijumlahkan menjadi satu angka.

| Tingkat | Nama | Arti | Contoh | Kekuatan |
|---|---|---|---|---|
| 1 | **Dideklarasikan** | Tertulis di manifest | `READ_SMS` diminta | Lemah: belum tentu dipakai |
| 2 | **Ditemukan dalam kode** | Pemanggilan fungsi ada di DEX | `SmsManager.sendTextMessage` dipanggil di `com.x.Sender` | Sedang: kode ada, belum tentu dijalankan |
| 3 | **Aliran data terindikasi** | Sumber data dan pengiriman berada dalam jalur kode yang sama | Metode yang membaca `content://sms` juga membuka koneksi ke `api.telegram.org` | Kuat: masih indikasi, bukan bukti pengiriman |
| 4 | **Teramati** | Kondisi atau perilaku nyata di perangkat | Layanan aksesibilitas aplikasi **sedang aktif**; aplikasi memakai 40 MB data di latar belakang | Paling kuat untuk perangkat ini |

Selain tingkat bukti, setiap temuan juga mencatat:
- **Asal:** kode aplikasi, library/SDK yang dikenal, atau tidak dapat ditentukan.
- **Cakupan:** bagian yang tidak bisa dianalisis, misalnya kode terenkripsi, DEX di dalam aset, library native, atau kode yang dimuat dari internet.

## 3. Format setiap temuan

Setiap temuan menjawab lima pertanyaan:

| Bagian | Contoh (clipboard pada InSaver) |
|---|---|
| **Apa yang ditemukan + lokasi** | `ClipboardManager.getPrimaryClip()` dipanggil di `com.insaver.ui.MainActivity` (kode aplikasi) |
| **Dampak bagi pengguna** | Aplikasi bisa membaca teks yang kamu salin, termasuk kata sandi atau OTP |
| **Konteks penggunaan** | Pengunduh video biasanya membaca clipboard untuk mengambil tautan yang baru disalin |
| **Yang belum terbukti** | Tidak ditemukan jalur kode yang mengirim isi clipboard ke jaringan. Sejak Android 10, aplikasi hanya bisa membaca clipboard saat tampil di layar |
| **Tindakan yang disarankan** | Hindari menyalin kata sandi atau OTP saat aplikasi ini terbuka |

## 4. Katalog kemampuan

Kekhawatiran pemilik dipetakan ke sinyal yang diperiksa.

| Kekhawatiran | Tingkat 1 (manifest) | Tingkat 2 (kode) | Tingkat 4 (perangkat) |
|---|---|---|---|
| **Memata-matai layar/aktivitas** | Service `BIND_ACCESSIBILITY_SERVICE` + konfigurasinya (`canRetrieveWindowContent`, tipe event); `FOREGROUND_SERVICE_MEDIA_PROJECTION` | `AccessibilityNodeInfo.getText`, `dispatchGesture`, `performGlobalAction`, `MediaProjectionManager` | Aksesibilitas **aktif** (`Settings.Secure`) |
| **Membaca notifikasi** | Service `BIND_NOTIFICATION_LISTENER_SERVICE` | `getActiveNotifications`, `Notification.extras` | Pembaca notifikasi **aktif** |
| **Kamera/mikrofon** | `CAMERA`, `RECORD_AUDIO`, tipe foreground service | `Camera2`, `AudioRecord`, `MediaRecorder` | Status izin diberikan |
| **SMS/OTP** | `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`; receiver `SMS_RECEIVED` beserta prioritasnya | `SmsManager.send*`, `content://sms`, `SmsMessage.createFromPdu` | Status izin; aplikasi SMS bawaan |
| **Kontak, telepon, identitas** | `READ_CONTACTS`, `READ_CALL_LOG`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` | `getLine1Number`, `getDeviceId`, `ContactsContract` | Status izin |
| **Clipboard** | Tidak memerlukan izin | `getPrimaryClip`, `addPrimaryClipChangedListener` | Pengguna diberi tahu Android 12+ saat clipboard dibaca |
| **Cookie dan sesi login** | Activity dengan WebView | `CookieManager.getCookie`, `WebView.addJavascriptInterface`, `evaluateJavascript` | – |
| **Pengiriman data** | `INTERNET`, cleartext, Network Security Config | Alamat URL di kode, `HttpURLConnection`/OkHttp, `api.telegram.org`, socket | Pemakaian data latar belakang (perlu izin Akses Penggunaan) |
| **SDK iklan/analitik** | Komponen dan meta-data SDK | Paket SDK dikenal di DEX | – |
| **Overlay, latar belakang, aktif kembali** | `SYSTEM_ALERT_WINDOW`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, service exported, admin perangkat | `WindowManager.addView` dengan `TYPE_APPLICATION_OVERLAY`, `AlarmManager`, `JobScheduler` | Admin perangkat **aktif**; pengecualian baterai |
| **Menyembunyikan diri** | Tidak ada activity launcher; `activity-alias` yang dinonaktifkan | `setComponentEnabledSetting` (menyembunyikan ikon) | Ikon tidak tampil di launcher |
| **Kode tersembunyi/dimuat kemudian** | – | `DexClassLoader`, `InMemoryDexClassLoader`, `Runtime.exec`, refleksi; rasio nama kelas pendek (obfuscation) | – |
| **Bagian yang tidak bisa dianalisis** | – | DEX/JAR di `assets/`, berkas terenkripsi, library native, packer dikenal | Dicatat sebagai **batas cakupan** |
| **Pemasangan aplikasi lain** | `REQUEST_INSTALL_PACKAGES` | `PackageInstaller`, `ACTION_INSTALL_PACKAGE` | – |

## 5. Pola gabungan

Satu kemampuan sensitif bukan malware. Pola dari beberapa bukti yang muncul bersamaan diberi label, dengan alasan yang terlihat.

| Pola | Bukti yang dibutuhkan | Label |
|---|---|---|
| **Pencurian OTP / pengambilalihan akun** (modus `undangan.apk`) | Penerima SMS dengan prioritas tinggi atau pembacaan SMS (T1–T2) **dan** pengiriman jaringan ke tujuan tak dikenal atau bot Telegram (T2–T3). Diperkuat bila tidak ada ikon launcher, nama menyamar (misalnya "Undangan", "Surat Tilang"), sertifikat baru/debug, dan pemasangan dari luar toko | **Indikator kuat**: sebutkan setiap bukti dan yang belum terbukti |
| **Pengintaian layar** | Aksesibilitas yang membaca isi jendela **dan** pengiriman jaringan | Indikator kuat bila aksesibilitas aktif (T4) |
| **Risiko privasi aplikasi biasa** (InSaver) | Clipboard, WebView/cookie login pihak ketiga, SDK iklan, pengiriman ke server sendiri | **Perlu perhatian**: jelaskan konteks; bukan indikator pencurian |
| **Kemampuan wajar** | Misalnya kamera pada aplikasi kamera, atau SMS pada aplikasi SMS bawaan | **Wajar untuk fungsinya**, tetap ditampilkan |

Ringkasan per aplikasi tidak lagi berupa satu angka. Bentuknya:
- **Daftar kekhawatiran** (Indikator kuat / Perlu perhatian / Informasi), masing-masing dengan tingkat bukti tertinggi.
- **Cakupan analisis:** misalnya "3 dari 3 APK diperiksa, 2 DEX di aset tidak dapat dianalisis".
- Kalimat tetap di bagian bawah: "Hasil ini tidak menyatakan aplikasi aman atau berbahaya."

## 6. Tahapan dan kelayakan

| Tahap | Isi | Berjalan di | Kelayakan | Usaha |
|---|---|---|---|---|
| **1** | Manifest lengkap semua split: aksesibilitas + XML konfigurasinya, pembaca notifikasi, penerima SMS + prioritas, admin perangkat, overlay, boot, ikon tersembunyi, Network Security Config, tanda tangan semua split. Status aktif di perangkat (aksesibilitas, pembaca notifikasi, admin, izin diberikan). Model temuan baru + UI "Kekhawatiran". | HP, tanpa izin baru | Tinggi | B |
| **2** | Pemindaian DEX: pemanggilan API sensitif beserta kelas lokasinya, string URL, deteksi SDK, pemisahan kode aplikasi vs library, pemuatan kode dinamis, obfuscation, batas cakupan | HP (ARSCLib sudah ada) | Tinggi; perlu dibatasi waktu dan memori untuk aplikasi besar | B |
| **3** | Indikasi aliran data: sumber (SMS, kontak, clipboard, notifikasi, cookie) dan pengiriman (jaringan) dalam metode atau kelas yang sama, ditambah pemanggilan satu tingkat | HP | Sedang. Analisis aliran data lengkap (seperti FlowDroid) terlalu berat untuk HP, jadi hasilnya berupa **indikasi** | B |
| **4** | Perilaku saat berjalan | Lihat bawah | Terbatas | – |

**Batasan tahap 4 yang perlu diputuskan:**
- **Sandbox sungguhan tidak mungkin di dalam PackPeek.** Menjalankan aplikasi mencurigakan secara terisolasi butuh root atau emulator. Itu terlalu berisiko di HP pengguna dan tidak bisa dilakukan aplikasi biasa.
- **Pilihan realistis A (tanpa izin baru):** bukti kondisi perangkat yang dapat dibaca aplikasi biasa: aksesibilitas, pembaca notifikasi, admin perangkat, status izin, dan pengecualian baterai. Ini sudah masuk tahap 1.
- **Pilihan B (izin khusus "Akses penggunaan", opsional):** pemakaian data jaringan per aplikasi di latar depan dan latar belakang, serta waktu terakhir aktif. Ini bukti teramati, tetapi tidak menunjukkan *isi* atau *tujuan* data.
- **Pilihan C (di luar aplikasi):** sandbox emulator di komputer, atau layanan analisis daring. Butuh internet dan mengirim APK ke pihak lain, sehingga bertentangan dengan janji PackPeek.

## 7. Sampel uji

| Sampel | Tujuan | Cara |
|---|---|---|
| **InSaver** (asli, dari HP pemilik) | Risiko privasi tanpa label "malware": clipboard, WebView/cookie Instagram, SDK iklan, server sendiri | Diuji pemilik di HP |
| **`undangan-sim.apk`** (buatan sendiri, tidak berbahaya) | Memicu semua indikator modus undangan: penerima SMS prioritas tinggi, pembacaan SMS, URL bot Telegram, ikon tersembunyi, label "Undangan Pernikahan", sertifikat debug | Dibangun dari kode uji. **Kodenya tidak pernah mengirim data:** pemanggilan berbahaya berada di metode yang tidak pernah dijalankan, dan URL-nya palsu |
| **Sampel nyata `undangan.apk`** | Validasi akhir | Hanya diperiksa (tidak dipasang) oleh pemilik di HP. Sampel malware nyata tidak diunduh ke lingkungan pengembangan |
| **Aplikasi wajar sebagai pembanding** | Mencegah salah tuduh: aplikasi SMS, kamera, password manager (aksesibilitas) | Aplikasi sistem emulator dan aplikasi umum |

Setiap tahap dianggap selesai bila hasil InSaver dan `undangan-sim` sesuai harapan, dan aplikasi pembanding tidak diberi label indikator kuat.

## 8. Perubahan pada UI

- **Detail aplikasi:** tab "Ringkasan" dibuka dengan kartu **Kekhawatiran**, diurutkan dari indikator kuat. Setiap kartu menampilkan lima bagian (bagian 3) dan lencana tingkat bukti T1–T4.
- **Periksa berkas (sebelum pasang):** kekhawatiran tampil **sebelum** tombol Pasang. Untuk indikator kuat, tombol Pasang meminta konfirmasi tambahan.
- **Skor angka** diganti ringkasan kekhawatiran. Angka lama disimpan sementara di tab terpisah sampai model baru teruji.
- **Ringkasan perangkat:** daftar aplikasi dengan aksesibilitas, pembaca notifikasi, atau admin perangkat yang **sedang aktif**. Ini sinyal paling kuat yang tersedia tanpa izin tambahan.

## 9. Keputusan yang dibutuhkan

1. Setuju model bukti T1–T4 dan penggantian skor angka dengan daftar kekhawatiran?
2. Tahap 4: cukup pilihan A, atau tambahkan pilihan B (izin "Akses penggunaan", opsional dan bisa dimatikan)? Pilihan C tidak disarankan.
3. Setuju memakai `undangan-sim.apk` buatan sendiri sebagai sampel uji, dan sampel nyata hanya diperiksa di HP pemilik?
4. Mulai dari tahap 1 sekarang?
