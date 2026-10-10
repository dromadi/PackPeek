# Changelog

## Belum dirilis

- Dialog hasil pemasangan dengan alasan spesifik (tanda tangan, downgrade, ABI, SDK, penyimpanan, split hilang, fitur perangkat), pesan sistem, tombol Buka aplikasi atau Copot aplikasi saat ini.
- Kartu "Siap dipasang?" untuk berkas lokal: tanda tangan, Android minimum, target SDK yang ditolak Android 14+, ABI, fitur perangkat wajib, signer dan downgrade terhadap aplikasi terpasang.
- Arsip XAPK/APKM dikenali dan bisa dibuka dari file manager; jumlah OBB yang tidak ikut terpasang ditampilkan.
- Tema hitam AMOLED dan ekspor daftar aplikasi (hasil filter) sebagai CSV.
- Pilih bagian APK sebelum memasang: CPU, kepadatan layar, bahasa dan modul fitur, dengan pilihan terbaik untuk perangkat dicentang otomatis; pemasangan dihentikan bila modul kehilangan bagian CPU.
- Arsip `.apks` bundletool diterima: `standalones/` diabaikan bila `splits/` ada.
- Pasang beberapa berkas sekaligus melalui antrean dengan progres, batal dan ringkasan hasil.
- Tab Folder backup di Riwayat: isi folder dibaca langsung dari manifest tiap berkas dan dibandingkan dengan aplikasi terpasang (lebih baru, sama, lebih lama, tidak terpasang), plus daftar aplikasi tanpa backup; aksi Pulihkan/Perbarui, Periksa, Bagikan dan Hapus.
- Perbaikan: kunci backup yang macet, indikator progres yang hilang terlalu cepat, pembaruan notifikasi backup, ekspor ikon adaptif.

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
