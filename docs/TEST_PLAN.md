# Uji penerimaan perangkat

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
