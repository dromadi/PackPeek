# Changelog

## 2.0.0-alpha.3 — 2026-10-10

- **Pasang ulang dari Riwayat** dengan satu ketukan. Bila izin pemasangan sudah diberikan, dialog pemasangan Android langsung muncul.
- **Peringatan tanda tangan berubah:** tanda tangan aplikasi dicatat saat backup. Layar detail memperingatkan bila aplikasi terpasang kini ditandatangani sertifikat berbeda dari backup terakhir, dan Riwayat menandai backup yang tanda tangannya berbeda dari backup sebelumnya. Database naik ke versi 2 dengan migrasi; riwayat lama tetap ada.
- **Favorit:** tanda bintang pada kartu aplikasi dan filter Jenis → Favorit untuk backup sekelompok aplikasi.
- **Simpan N versi terakhir:** Pengaturan → Backup per aplikasi (semua / 1 / 3 / 5). Berkas backup lama di luar jumlah itu dihapus setelah backup berhasil.
- Pesan gagal pasang yang jelas: versi lebih lama (downgrade), tanda tangan berbeda, tidak kompatibel, ruang penuh, diblokir, atau dibatalkan.
- Ukuran memakai satuan desimal (1 MB = 1.000.000 byte), sama dengan file manager.
- Nama folder backup tampil benar untuk folder akar seperti Download dan penyimpanan internal.

## 2.0.0-alpha.2 — 2026-10-10

- Perbaikan: backup tidak lagi macet permanen setelah worker dihentikan sistem. Penghentian oleh sistem mengembalikan job ke antrean, bukan menandainya batal; baris job yang basi direkonsiliasi dengan status WorkManager; request baru tidak lagi dibuang diam-diam oleh `KEEP`.
- Skor: aktivitas launcher tidak lagi dihitung sebagai komponen exported tanpa izin. Target SDK lama memakai ambang tetap API 31, sehingga skor sama di semua perangkat.
- Daftar aplikasi diperbarui lewat broadcast perubahan package, bukan pemindaian penuh setiap `onResume`.
- Ekstraksi: cek nama bentrok memakai satu listing folder; entri `.apks` tidak dikompresi ulang.
- Parser manifest memeriksa batas indeks, membaca seluruh dokumen, dan selalu gagal sebagai `ARCHIVE`.
- Filter framework mencocokkan semua framework yang terdeteksi; `saveIcon` meneruskan pembatalan coroutine.
- Perbaikan crash: menekan Ekstrak di dialog konfirmasi membuat aplikasi berhenti (`NullPointerException` di `LazyColumn` dialog). Semua dialog kini memakai nilai lokal non-null, bukan `!!`. Pola yang sama di layar detail (tombol Coba lagi) dan riwayat juga diperbaiki.
- Pilihan format untuk aplikasi split di Pengaturan: `.apks` (bawaan), `.xapk` (dengan `manifest.json` untuk installer XAPK/APKPure) atau `.zip`. Aplikasi tunggal tetap `.apk`. Berkas `.xapk`/`.zip` juga bisa dibuka lewat Periksa berkas.
- Format eksperimental `.apk` gabungan untuk aplikasi split: base dan split digabung dengan ARSCLib lalu ditandatangani ulang dengan kunci PackPeek di Android Keystore. Peringatan ditampilkan di Pengaturan dan dialog konfirmasi: hasilnya tidak bisa meng-update aplikasi asli, tidak di-update Play Store, sebagian aplikasi menolak jalan, dan isinya hanya untuk perangkat ini. Kelas `android.*`/`org.xmlpull.*` bawaan ARSCLib dibuang dari build agar R8 tidak merusak AppCompat.
- Log crash lokal: bila aplikasi berhenti, detail error disimpan di perangkat dan ditampilkan saat dibuka berikutnya dengan tombol Salin. Tidak ada data yang dikirim ke internet.

## 2.0.0-alpha.1 — 2026-10-10

Penulisan ulang OmniExtract menjadi PackPeek dengan Kotlin/Compose dan target API 36.

- Ekstraksi tunggal terpisah dari seleksi batch; format APK/APKS per aplikasi.
- Backup SAF, Worker foreground, progres, batal, ringkasan dan share.
- DataStore, Room history, statistik, search/sort/filter.
- Detail izin, komponen, sertifikat, teknologi, ABI dan skor konfigurasi.
- Inspeksi arsip lokal serta installer dengan verifikasi semua APK.
- Dasbor, ekspor ikon, quick actions, onboarding, navigasi, tema dan EN/ID.
- Pengujian logika inti, konfigurasi R8 dan signing produksi terpisah.

Build alpha belum menjadi rilis stabil. Pengujian perangkat, Baseline Profile dan kunci produksi masih menunggu. Monetisasi tahap 2 belum ditambahkan.
