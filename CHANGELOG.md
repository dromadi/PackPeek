# Changelog

## 2.0.0-alpha.6 — 2026-10-10

Perbaikan keamanan data dan ketelitian parser, sebelum digabung ke `main`.

- **Hapus backup lama lebih aman:** hanya berkas di folder backup yang sedang dipakai yang boleh dihapus; berkas yang masih dipakai backup terbaru (URI yang sama) tidak pernah dihapus; catatan riwayat tetap disimpan bila berkas gagal dihapus; berkas yang ukurannya berubah (sudah ditimpa) dibiarkan.
- **Nama atribut lewat resource ID:** atribut Android dibaca berdasarkan resource ID-nya (seperti yang dilakukan sistem), sehingga nama yang diobfuskasi atau atribut "tiruan" tanpa ID tidak bisa menyembunyikan izin, komponen, atau konfigurasi aksesibilitas.
- **String panjang:** batas 32.768 karakter dihapus; batas tetap dijaga terhadap ukuran chunk.
- **`maxSdkVersion`:** izin yang hanya diminta pada versi Android lebih lama tidak lagi dihitung di perangkat yang lebih baru.
- **Ikon tersembunyi teramati (T4):** untuk aplikasi terpasang, PackPeek memeriksa apakah launcher benar-benar menampilkannya, sehingga aplikasi yang menyembunyikan ikonnya setelah dibuka pertama kali tetap terdeteksi.
- Tes baru: nama atribut terobfuskasi, atribut tiruan, string > 32K, `maxSdkVersion`, launcher teramati, dan fuzz 3.000 manifest rusak (harus gagal dengan rapi, tidak crash).

## 2.0.0-alpha.5 — 2026-10-10

Analisis risiko berbasis bukti, tahap 1 (lihat `docs/ANALISIS_RISIKO.md`).

- **Kekhawatiran** menggantikan skor angka di bagian atas layar detail. Setiap temuan menampilkan apa yang ditemukan beserta lokasinya, dampak, konteks, yang belum terbukti, dan tindakan yang disarankan, dengan tingkat bukti T1 (dideklarasikan) dan T4 (teramati). Skor lama masih ditampilkan di bawah sebagai pembanding.
- **Manifest semua APK** (base dan split) dibaca lewat parser XML biner baru, termasuk konfigurasi `res/xml` layanan aksesibilitas dan admin perangkat yang di-resolve lewat `resources.arsc`.
- **Katalog kemampuan:** aksesibilitas (membaca layar, gestur), pembaca notifikasi, admin perangkat, SMS (penerima beserta prioritas), kirim SMS, kontak dan panggilan, identitas telepon, kamera/mikrofon, lokasi, perekaman layar, overlay, berjalan di latar belakang, ikon tersembunyi, memasang aplikasi lain, akses semua berkas, cleartext, dan sertifikat tidak biasa.
- **Pola gabungan:** pencurian OTP / pengambilalihan akun (modus `undangan.apk`) dan pengintaian layar menjadi "indikator kuat" hanya bila beberapa bukti muncul bersama. Aplikasi SMS bawaan dan aplikasi sistem diberi konteks, bukan dituduh.
- **Bukti teramati (T4):** layanan aksesibilitas, pembaca notifikasi, dan admin perangkat yang aktif, aplikasi SMS bawaan, izin yang diberikan, izin overlay, pengecualian baterai, serta pemakaian data 30 hari (latar depan/latar belakang) lewat izin opsional **Akses penggunaan**.
- **Sebelum pasang:** berkas dengan indikator kuat meminta konfirmasi tambahan; tombol Batal menjadi pilihan utama.
- **Ringkasan:** bagian "Aktif di perangkat ini" berisi aplikasi dengan aksesibilitas, pembaca notifikasi, atau admin perangkat yang aktif, serta aplikasi SMS bawaan.
- **Pengaturan:** baris Akses penggunaan (opsional).
- Header layar detail melipat saat digulir, agar isi mendapat lebih banyak ruang pada ukuran huruf besar.
- Sampel uji `tools/undangan-sim/` tanpa kode sama sekali, dibangun dengan `aapt2`, dipakai di unit test.

## 2.0.0-alpha.4 — 2026-10-10

Perombakan UI/UX. Perbandingan sebelum/sesudah: `docs/ui-redesign-before-after.png`.

- **Tema:** semua peran warna Material 3 diturunkan dari aksen yang dipilih, sehingga tidak ada lagi tombol dan latar ungu bawaan yang bertabrakan dengan aksen. Bentuk sudut seragam; mode gelap ikut disesuaikan.
- **Ikon:** ikon yang sesuai untuk navigasi dan tindakan (Aplikasi, Riwayat, Ringkasan, unduh, folder, pasang), disalin dari Material Icons agar ukuran aplikasi tetap kecil.
- **Daftar aplikasi:** kolom cari berbentuk pil di atas, filter sebagai chip yang bisa digeser, dan baris aplikasi ringkas, sehingga sekitar 4 aplikasi terlihat sekaligus (sebelumnya sekitar 1,5 pada ukuran huruf besar). Mode pilih dengan ketuk ikon atau tekan lama, beserta bilah tindakan.
- **Detail:** header dengan skor berwarna, tombol utama yang jelas, info yang dikelompokkan, izin dan komponen dengan label, serta temuan dengan ikon.
- **Riwayat:** dikelompokkan per aplikasi dengan daftar versinya; tombol Pasang ulang di setiap versi, tindakan lain di menu.
- **Ringkasan:** kotak statistik, grafik batang rapi, dan aplikasi terbesar dengan ikonnya.
- **Pengaturan dan Tentang:** daftar bergaya Android dengan ikon, pilihan lewat dialog radio, dan sakelar.
- **Dialog:** konfirmasi backup menampilkan ikon aplikasi, total ukuran, dan folder tujuan; hasil backup menampilkan status per aplikasi.
- **Onboarding:** ilustrasi ikon, indikator halaman, dan tombol di bawah layar.
- Splash tetap tampil sampai aplikasi siap; tidak ada lagi pesan "tidak ada aplikasi" yang berkedip saat memuat.

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
