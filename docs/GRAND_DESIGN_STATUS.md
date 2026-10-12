# Status grand design

Implementasi source dan penerimaan perangkat dibedakan. Workbook asli tidak diubah. Status ditinjau terhadap alpha.20. Tidak ada persentase keseluruhan karena bukti implementasi, pengujian otomatis, dan penerimaan perangkat berbeda.

| ID | Tugas | Status | Bukti / batas |
|---|---|---|---|
| T001 | Tetapkan nama brand & package ID baru | Sebagian / menunggu QA | Identitas PackPeek / com.packpeek.app mengikuti keputusan di workbook; ketersediaan merek tidak diverifikasi ulang. |
| T003 | Inisialisasi Git & backup proyek | Implementasi alpha | Git lokal, source ZIP dan .gitignore |
| T101 | Ganti applicationId, namespace & package Kotlin | Implementasi alpha | Namespace/package, authority per applicationId |
| T102 | Ganti label & ikon launcher (adaptive + monochrome) | Implementasi alpha | Ikon adaptive dan monochrome |
| T103 | Naikkan compileSdk/targetSdk ke 36 & upgrade dependensi | Implementasi alpha | API 36; dependensi versi tetap, bukan klaim selalu terbaru |
| T104 | Bersihkan izin manifest yang tidak dipakai | Implementasi alpha | Izin sesuai inventaris, installer dan foreground; `INTERNET` sejak alpha.10 hanya untuk cek hash VirusTotal opsional |
| T106 | Pindahkan semua teks hardcoded ke strings.xml | Implementasi alpha | 219 string EN dan ID; label teknis tetap literal |
| T707 | Splash Screen API & tema brand | Implementasi alpha | Splash API dan brand theme |
| T201 | Perbaiki bug tombol ekstraksi tunggal | Implementasi alpha | Tombol tunggal menggunakan listOf(app), tidak mengubah seleksi |
| T211 | Format batch tepat: .apk untuk app biasa, .apks hanya untuk split | Implementasi alpha | Format per AppRecord .apk/.apks; uji JVM |
| T202 | Ringkasan hasil batch (sukses/gagal) & lanjut jika error | Implementasi alpha | Sukses/gagal/batal per item dan lanjut saat satu gagal |
| T203 | Throttle callback progres ekstraksi | Implementasi alpha | Progres sekitar 100 ms, notifikasi sekitar 1 detik; belum benchmark 1 GB |
| T204 | Lazy-load & cache ikon aplikasi | Implementasi alpha | Ikon dimuat async saat terlihat, LruCache 8 MiB |
| T207 | Navigasi Compose & tombol Back sistem | Implementasi alpha | NavHost enam layar; back perlu uji perangkat |
| T205 | Pindahkan preferensi ke DataStore + SettingsRepository | Sebagian / menunggu QA | DataStore ada. Migrasi preferensi hanya dalam package yang sama; data privat package OmniExtract lama tidak dapat dibaca otomatis. |
| T209 | Ekstraksi tetap jalan di background (WorkManager/Foreground Service) | Implementasi alpha | Worker foreground dan batal; quota/force stop/background perlu uji |
| T210 | Tangani izin SAF dicabut & APK tidak terbaca | Implementasi alpha | SAF dicabut, storage penuh, source tidak terbaca |
| T301 | Bagikan (share) hasil ekstraksi | Implementasi alpha | Share SAF URI + ClipData/grant baca |
| T312 | Aksi cepat per aplikasi (buka, info, Play Store, uninstall) | Implementasi alpha | Buka/info/store/uninstall/copy package |
| T315 | Simpan ikon aplikasi (PNG) | Implementasi alpha | PNG 512 px termasuk adaptive layer |
| T316 | Konfirmasi sebelum ekstrak + lokasi tersimpan + penghitung | Implementasi alpha | Konfirmasi opsional, preview nama/ukuran, folder ramah, statistik |
| T302 | Riwayat ekstraksi (Room) | Implementasi alpha | Room riwayat, cari/share/sembunyikan; berkas tidak dihapus |
| T303 | Pengurutan daftar aplikasi (nama, ukuran, tanggal update) | Implementasi alpha | Sort nama/ukuran/update/SDK dan simpan preferensi |
| T311 | Filter lanjutan: sumber instalasi, framework, target SDK | Implementasi alpha | Filter jenis/sumber/SDK/ukuran/framework berbasis marker |
| T305 | Install .apk/.apks dari file (aktifkan SplitPackageInstaller) | Implementasi alpha | Installer session, verifikasi semua APK/signer/ABI dan konfirmasi Android |
| T320 | Layar detail aplikasi penuh dengan tab | Implementasi alpha | Tab ringkasan/izin/komponen/sertifikat/teknologi |
| T321 | Temuan keamanan tambahan & skor risiko | Sebagian / menunggu QA | Skor berbobot ada; bukan detektor malware. Cleartext hanya flag, tidak membaca Network Security Configuration. |
| T322 | Daftar komponen & izin yang detail | Implementasi alpha | Status izin, nama komponen, exported dan required permission |
| T323 | Info skema tanda tangan (v1/v2/v3/v4) & detail sertifikat | Sebagian / menunggu QA | Verifikasi base v1/v2/v3/v3.1 dan riwayat sertifikat Android. v4 .idsig belum didukung. |
| T314 | Analisis file APK/APKS dari penyimpanan (tanpa instal) | Sebagian / menunggu QA | Picker APK/APKS ada; Open with baru untuk MIME APK. Arbitrary XAPK/APKM/Bundletool belum dijamin. |
| T325 | Dasbor statistik perangkat | Implementasi alpha | Count/size/top10/SDK/sumber/framework/skor atas permintaan |
| T703 | Lokalisasi Bahasa Inggris + Indonesia | Implementasi alpha | English fallback, values-in dan app language picker |
| T701 | Onboarding 3 layar | Implementasi alpha | Tiga layar, skip dan pilih folder; tanpa janji auto-backup |
| T702 | Empty, loading & error state yang rapi | Sebagian / menunggu QA | Loading, empty, error dan pull-to-refresh ada; skeleton/shimmer khusus belum dibuat. |
| T318 | Pilihan warna tema (palet aksen) | Implementasi alpha | Enam aksen, dynamic Android 12+, terang/gelap |
| T803 | Aksesibilitas & layar besar (tablet/foldable) | Sebagian / menunggu QA | Grid adaptive dan kontrol Material; TalkBack, font 200%, kontras dan foldable menunggu QA perangkat. |
| T706 | Halaman Tentang & Lisensi Open Source | Sebagian / menunggu QA | About/versi/notice OSS/privasi lokal ada; kontak dan URL kebijakan privasi publik belum diisi. |
| T317 | Menu Masukan/Dukungan, Bagikan Aplikasi & Beri Rating | Sebagian / menunggu QA | Share info/diagnostik ada; email belum diberikan, rating Play disembunyikan. |
| T208 | Unit test untuk logika inti | Implementasi alpha | BUILD_REPORT alpha.20 mencatat 151 tes; jumlah dan hasil perubahan PR berikutnya mengikuti laporan CI pada commit terkait |
| T341 | Optimasi performa: Baseline Profile & startup | Belum selesai | Baseline Profile khusus aplikasi dan benchmark startup/scroll belum dibuat/dijalankan. |
| T802 | Uji build release dengan R8 & aturan ProGuard | Sebagian / menunggu QA | Build release R8 diperiksa dalam BUILD_REPORT; pengujian runtime release belum dilakukan. |
| T801 | Uji di berbagai versi Android & perangkat | Belum selesai | BUILD_REPORT mencatat emulator API 36 alpha.13 dan uji HP pemilik alpha.17/18. Matriks API 24/29/33/34/36 untuk commit rilis belum lengkap. |
| T901 | Buat upload keystore & signingConfig aman | Sebagian / menunggu QA | SigningConfig membaca keystore.properties; kunci produksi dan backup milik pemilik belum dibuat. |
| T350 | Build APK rilis v2.0 + changelog | Belum selesai | Alpha, belum rilis stabil v2.0. Menunggu pengujian perangkat dan signing produksi. |

Deteksi SDK iklan/analitik dan laporan PDF sudah tersedia; deteksi SDK bukan bukti pelacakan berjalan. Auto-backup terjadwal, Hilt, manifest viewer penuh, monetisasi ads/billing, dan analitik penggunaan PackPeek belum ditambahkan.
