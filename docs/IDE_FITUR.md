# Ide fitur PackPeek

Daftar ide pengembangan PackPeek setelah 2.0.0-alpha.3. Arah produk tetap sama: alat backup dan pemeriksa APK yang berjalan di perangkat, tanpa akun, iklan, atau analitik. (Catatan alpha.10: izin Internet kini ada, hanya untuk cek hash VirusTotal opsional.)

**Arah utama sejak alpha.4:** analisis risiko berbasis bukti. Rencana lengkapnya ada di `docs/ANALISIS_RISIKO.md`; ide 11 (tracker), 12 (riwayat izin), 13 (manifest), dan 26 (pemeriksaan sebelum pasang) menjadi bagian dari rencana itu.

Dokumen ini untuk direview pemilik. Belum ada fitur di sini yang dijanjikan; urutan dan cakupan diputuskan setelah review.

**Ukuran usaha:** K = kecil (beberapa jam) · S = sedang (1–2 hari) · B = besar (beberapa hari atau lebih).
**Status:** ✅ selesai · 🟡 sebagian · ⚪ belum.

## Ringkasan status

| # | Ide | Tema | Usaha | Status |
|---|---|---|---|---|
| 1 | Backup otomatis sebelum update | Backup | S | ⚪ |
| 2 | Backup terjadwal | Backup | S | ⚪ |
| 3 | Simpan N versi terakhir | Backup | K | ✅ alpha.3 |
| 4 | Favorit dan grup | Backup | K | 🟡 favorit selesai, grup belum |
| 5 | Ekspor/impor daftar aplikasi | Backup | K | ⚪ |
| 6 | Pasang ulang dari Riwayat | Pulihkan | K | ✅ alpha.3 |
| 7 | Downgrade terbantu | Pulihkan | K | 🟡 pesan jelas selesai, panduan belum |
| 8 | Pasang banyak sekaligus | Pulihkan | S | ⚪ |
| 9 | Peringatan tanda tangan berubah | Keamanan | K | ✅ alpha.3 |
| 10 | Bandingkan dua versi | Keamanan | S | ⚪ |
| 11 | Deteksi tracker dan SDK iklan | Keamanan | S | ⚪ |
| 12 | Riwayat izin | Keamanan | S | ⚪ |
| 13 | Penampil manifest lengkap | Keamanan | S | ⚪ |
| 14 | Laporan audit (PDF/teks) | Keamanan | S | ⚪ |
| 15 | Tile Quick Settings dan widget | Kenyamanan | K | ⚪ |
| 16 | Bagikan ke PackPeek | Kenyamanan | K | 🟡 "Buka dengan" sudah ada |
| 17 | Pencarian berdasarkan izin | Kenyamanan | S | ⚪ |
| 18 | Satuan ukuran dan label folder | Kenyamanan | K | ✅ alpha.3 |
| 19 | Backup ke kartu SD / USB OTG | Penyimpanan | K | 🟡 bisa lewat pemilih folder, belum diuji |
| 20 | Backup ke cloud | Penyimpanan | B | ⚪ perlu keputusan |
| 21 | Format output split (.apks/.xapk/.zip/.apk gabungan) | Ekspor | S | ✅ alpha.2 |
| 22 | Persiapan rilis publik | Rilis | S | ⚪ |
| 23 | Tes otomatis UI dan alur backup | Kualitas | S | ⚪ |
| 24 | Koleksi backup per versi | Pulihkan | S | ⚪ |
| 25 | Pembersih backup duplikat | Penyimpanan | K | ⚪ |
| 26 | Pemeriksaan kompatibilitas sebelum pasang | Pulihkan | K | ⚪ |
| 27 | Ekspor CSV/JSON | Ekspor | K | ⚪ |
| 28 | Kirim APK lewat Wi-Fi dan QR | Kenyamanan | B | ⚪ perlu keputusan |
| 29 | Build APK otomatis di GitHub (CI) | Kualitas | K | ⚪ |

Ide 24–29 berasal dari masukan tambahan (ChatGPT) dan sudah dinilai di bawah.

## 1. Backup lebih pintar

### 1. Backup otomatis sebelum update ⚪ · S
**Apa:** saat aplikasi favorit di-update, salinan versi lamanya sudah tersimpan, sehingga pengguna bisa kembali bila versi baru bermasalah.

**Catatan teknis:** Android tidak memberi tahu aplikasi lain *sebelum* update terjadi. Ada dua pendekatan:
- **Backup lebih dulu:** backup favorit secara berkala atau saat versinya berubah, sehingga versi terakhir selalu tersedia sebelum update berikutnya.
- **Tangkap sesudahnya:** broadcast `PACKAGE_REPLACED` datang setelah APK lama terhapus, jadi pendekatan ini **tidak** bisa menyelamatkan versi lama.

Pendekatan yang realistis adalah yang pertama, digabung dengan ide 2 dan 3.

**Risiko:** memakan ruang penyimpanan; perlu dibatasi dengan ide 3 (simpan N versi).

### 2. Backup terjadwal ⚪ · S
**Apa:** backup otomatis aplikasi favorit pada jadwal tertentu (harian/mingguan), hanya untuk aplikasi yang versinya berubah sejak backup terakhir.

**Catatan teknis:** `PeriodicWorkRequest` di WorkManager yang sudah dipakai, dengan syarat opsional "sedang mengisi daya" dan "baterai tidak lemah". Folder SAF yang sudah dipilih tetap berlaku.

**Risiko:** Android membatasi kerja latar belakang. Jadwal tidak tepat waktu, dan pada Android 15+ ada batas waktu untuk layanan `dataSync`.

### 3. Simpan N versi terakhir ✅ alpha.3
Pengaturan → Backup per aplikasi (semua / 1 / 3 / 5). Berkas lama di luar jumlah itu dihapus setelah backup berhasil.

### 4. Favorit dan grup 🟡
**Selesai:** bintang di kartu aplikasi dan filter Jenis → Favorit.

**Lanjutan:** grup bernama (misalnya "Kerja", "Game"), backup per grup dengan satu ketukan, dan urutan favorit di atas daftar.

### 5. Ekspor/impor daftar aplikasi ⚪ · K
**Apa:** simpan daftar aplikasi terpasang (nama, paket, versi, sumber) sebagai berkas JSON atau CSV. Di HP baru, daftar itu bisa dibuka untuk melihat aplikasi mana yang belum terpasang, dengan tautan Play Store untuk tiap aplikasi.

**Catatan:** tidak butuh izin baru; berkas ditulis lewat SAF.

## 2. Pasang dan pulihkan

### 6. Pasang ulang dari Riwayat ✅ alpha.3
Satu ketukan di Riwayat. Bila izin pemasangan sudah diberikan, dialog pemasangan Android langsung muncul.

### 7. Downgrade terbantu 🟡
**Selesai:** pesan yang jelas saat Android menolak versi lama, tanda tangan berbeda, perangkat tidak kompatibel, ruang penuh, atau pemasangan diblokir.

**Lanjutan:**
- Sebelum memasang, bandingkan `versionCode` backup dengan versi terpasang, lalu beri peringatan bila backup lebih lama.
- Panduan langkah demi langkah: backup versi sekarang dulu, uninstall, lalu pasang backup.
- Jelaskan bahwa data aplikasi hilang saat uninstall.

### 24. Koleksi backup per versi ⚪ · S
**Apa:** Riwayat dikelompokkan per aplikasi. Setiap aplikasi menampilkan daftar versinya: nomor versi, tanggal, ukuran, format, tanda tangan, dan tombol Pasang/Bagikan/Bandingkan per versi.

**Catatan teknis:** data sudah tersedia di tabel `backups` (versi, tanggal, ukuran, tanda tangan); ini terutama pekerjaan tampilan. Cocok dengan ide 3 (simpan N versi), 6 (pasang ulang), 9 (tanda tangan), dan 10 (bandingkan dua versi). Tombol "Bandingkan" di sini menjadi pintu masuk ide 10.

### 26. Pemeriksaan kompatibilitas sebelum pasang ⚪ · K
**Apa:** sebelum tombol Pasang ditekan, tampilkan daftar periksa yang mudah dipahami:
- versi Android cukup atau tidak (`minSdk`),
- arsitektur CPU cocok,
- split lengkap,
- lebih lama dari versi terpasang (downgrade),
- tanda tangan sama dengan aplikasi terpasang.

**Catatan teknis:** sebagian besar pemeriksaan sudah ada di `ArchiveImporter.validateForInstall` dan `AuditEngine` (`signerMatch`), tetapi saat ini hasilnya baru terlihat ketika pemasangan gagal. Melengkapi ide 7.

### 8. Pasang banyak sekaligus ⚪ · S
**Apa:** pilih beberapa backup di Riwayat, lalu pasang berurutan. Berguna saat pindah ke HP baru.

**Catatan teknis:** setiap pemasangan tetap butuh persetujuan pengguna (dialog sistem). Antrean dijalankan satu per satu, dan antrean berikutnya mulai setelah status pemasangan sebelumnya diterima.

## 3. Keamanan dan pemeriksaan

Bagian ini yang paling membedakan PackPeek dari aplikasi backup APK lain.

### 9. Peringatan tanda tangan berubah ✅ alpha.3
Tanda tangan dicatat saat backup. Layar detail memperingatkan bila aplikasi terpasang ditandatangani sertifikat yang berbeda dari backup terakhir; Riwayat menandai backup yang berbeda dari sebelumnya.

### 10. Bandingkan dua versi ⚪ · S
**Apa:** pilih dua backup aplikasi yang sama (atau backup dengan versi terpasang), lalu tampilkan perbedaannya:
- izin yang ditambah atau dihapus,
- komponen exported baru,
- perubahan SDK target dan minimum,
- perubahan tanda tangan, ukuran, dan framework.

**Catatan teknis:** memakai `AuditEngine` yang sudah ada untuk kedua sisi, lalu membandingkan hasilnya.

### 11. Deteksi tracker dan SDK iklan ⚪ · S
**Apa:** daftar SDK pelacak dan iklan di dalam aplikasi (misalnya Firebase Analytics, Facebook SDK, AppsFlyer, AdMob), dideteksi dari nama kelas di berkas `classes*.dex` tanpa internet.

**Catatan teknis:** butuh daftar tanda tangan nama paket per SDK. Daftar dari proyek Exodus Privacy memakai lisensi terbuka, tetapi lisensinya perlu dicek sebelum disertakan. Pembacaan dex bisa memakai ARSCLib (sudah ada di proyek) atau parser string-pool dex yang ringan.

**Risiko:** deteksi berbasis nama bisa salah (aplikasi yang di-obfuscate atau SDK yang hanya disertakan tanpa dipakai). Tampilkan sebagai "terdeteksi", bukan vonis.

### 12. Riwayat izin ⚪ · S
**Apa:** catat izin setiap aplikasi dari waktu ke waktu, lalu beri tahu bila sebuah update menambah izin sensitif (lokasi, kamera, mikrofon, kontak).

**Catatan teknis:** simpan snapshot izin di Room saat pemindaian; bandingkan setelah broadcast `PACKAGE_REPLACED`.

### 13. Penampil manifest lengkap ⚪ · S
**Apa:** tampilkan `AndroidManifest.xml` sebagai teks XML yang bisa dibaca, dicari, dan disalin.

**Catatan teknis:** ARSCLib bisa mendekode XML biner ke teks. Perlu dibatasi ukurannya dan ditampilkan secara lazy untuk manifest besar.

### 27. Ekspor CSV/JSON ⚪ · K
**Apa:** ekspor daftar backup dan ringkasan pemeriksaan sebagai CSV (untuk spreadsheet) atau JSON (untuk alat lain). Bisa dibuat sekaligus dengan ide 5 (ekspor daftar aplikasi) dan ide 14 (laporan PDF).

### 14. Laporan audit (PDF/teks) ⚪ · S
**Apa:** ekspor hasil pemeriksaan satu aplikasi (ringkasan, izin, komponen, sertifikat, teknologi, temuan) sebagai PDF atau teks untuk dibagikan.

**Catatan teknis:** `PdfDocument` bawaan Android; tidak butuh pustaka tambahan.

## 4. Kenyamanan

### 15. Tile Quick Settings dan widget ⚪ · K
**Apa:** tombol "Backup favorit" di panel Quick Settings, dan widget layar utama yang menampilkan backup terakhir.

**Catatan teknis:** `TileService` (Android 7+) memicu worker yang sama dengan tombol di aplikasi.

### 16. Bagikan ke PackPeek 🟡
**Selesai:** APK bisa dibuka dari aplikasi lain lewat "Buka dengan".

**Lanjutan:** menerima `ACTION_SEND` untuk berkas APK/APKS/XAPK dari menu Bagikan (misalnya dari Telegram atau browser), lalu langsung membuka pemeriksaan.

### 17. Pencarian berdasarkan izin ⚪ · S
**Apa:** filter "aplikasi yang punya izin kamera/lokasi/mikrofon", dan pencarian berdasarkan nama izin.

**Catatan teknis:** butuh indeks izin semua aplikasi; dibuat di latar belakang seperti pemindaian ringkasan.

### 18. Satuan ukuran dan label folder ✅ alpha.3
Ukuran memakai satuan desimal seperti file manager; folder akar menampilkan nama yang benar.

### 28. Kirim APK lewat Wi-Fi dan QR ⚪ · B · perlu keputusan
**Apa:** memindahkan hasil ekstraksi ke HP lain lewat jaringan lokal, dengan kode QR untuk menghubungkan.

**Keputusan yang dibutuhkan:** di Android, koneksi jaringan, termasuk Wi-Fi lokal, membutuhkan izin Internet. Fitur ini mengubah janji "tanpa izin Internet".

**Alternatif tanpa izin baru:** tombol Bagikan yang sudah ada bisa mengirim lewat Quick Share / Nearby Share, Bluetooth, atau aplikasi lain. Mungkin cukup diberi petunjuk di layar hasil backup.

## 5. Penyimpanan

### 25. Pembersih backup duplikat ⚪ · K
**Apa:** mengenali berkas backup yang isinya identik (aplikasi dan versi sama, dibackup berkali-kali), menghitung ruang yang bisa dibebaskan, lalu menghapus duplikat setelah dikonfirmasi pengguna.

**Catatan teknis:** bandingkan paket, `versionCode`, dan hash SHA-256 berkas. Penghapusan selalu lewat konfirmasi, karena berkas yang dihapus dari folder tidak bisa dikembalikan.

### 19. Backup ke kartu SD / USB OTG 🟡 · K
**Sudah bisa:** pemilih folder SAF bisa memilih kartu SD atau flashdisk OTG.

**Lanjutan:**
- Uji di perangkat nyata, termasuk saat media dicabut di tengah backup.
- Beri panduan singkat di Pengaturan.
- Tampilkan pesan yang jelas bila folder tidak tersedia.

### 20. Backup ke cloud ⚪ · B · perlu keputusan
**Apa:** unggah backup ke Google Drive atau penyimpanan cloud lain.

**Keputusan yang dibutuhkan:** fitur ini butuh izin Internet dan login akun. Itu mengubah janji PackPeek "tanpa internet, tanpa akun".

**Alternatif tanpa melanggar janji:** pilih folder milik aplikasi cloud lewat pemilih folder SAF (Google Drive, Dropbox, dan lain-lain menyediakan folder di sana bila aplikasinya terpasang). Dengan cara ini PackPeek tetap tanpa izin Internet.

## 6. Ekspor

### 21. Format output aplikasi split ✅ alpha.2
`.apks`, `.xapk`, `.zip`, dan `.apk` gabungan eksperimental (ditandatangani ulang dengan kunci PackPeek, disertai peringatan).

**Lanjutan yang mungkin:**
- Format **folder** (berkas APK terpisah) untuk `adb install-multiple`.
- Perkiraan ruang yang dibutuhkan sebelum penggabungan `.apk` dimulai.

## 7. Rilis dan kualitas

### 22. Persiapan rilis publik ⚪ · S
Belum fitur, tetapi syarat sebelum PackPeek dibagikan luas:
- **Kunci tanda tangan rilis** milik pemilik, dengan cadangan aman. Kalau kunci hilang, aplikasi tidak bisa di-update selamanya.
- **Kebijakan privasi** di URL publik dan email dukungan.
- **Deklarasi izin Play Store** untuk `QUERY_ALL_PACKAGES` dan `REQUEST_INSTALL_PACKAGES`.
- **Uji perangkat** di Android 8, 10, 13, 14, dan 16.
- **Baseline Profile** untuk mempercepat startup.

### 23. Tes otomatis UI dan alur backup ⚪ · S
**Apa:** tes instrumentasi Compose untuk alur utama (ekstrak, konfirmasi, hasil, pasang ulang) dan tes worker.

**Alasan:** crash tombol Ekstrak di alpha.1 akan tertangkap oleh tes UI sederhana. Tes ini mencegah masalah serupa muncul lagi.

### 29. Build APK otomatis di GitHub (CI) ⚪ · K
**Apa:** GitHub Actions membangun APK dan menjalankan unit test serta lint setiap kali ada push atau PR. APK-nya bisa diunduh langsung dari halaman GitHub, tanpa menunggu dikirim.

**Catatan teknis:**
- Butuh kunci tanda tangan **tetap** yang disimpan sebagai *secret* di GitHub. Tanpa itu, APK dari CI memakai kunci acak dan tidak bisa dipasang menimpa versi di HP.
- Kunci ini sebaiknya bukan kunci rilis publik (ide 22), tetapi kunci khusus untuk build uji.

## Usulan urutan berikutnya

Usulan ini untuk direview; bisa diubah sesuai prioritas pemilik.

| Tahap | Isi | Alasan |
|---|---|---|
| **sebelum fitur baru** | Uji alpha.3 di HP: Bagikan, Pasang ulang, peringatan tanda tangan; 29 CI | Fitur yang ada harus terbukti di perangkat nyata dulu; CI mempercepat pengujian berikutnya |
| **alpha.4** | 24 Koleksi backup per versi, 10 Bandingkan dua versi, 26 Pemeriksaan kompatibilitas | Saling melengkapi dan memakai data yang sudah ada; memberi alasan membuka PackPeek lagi setelah ekstraksi |
| **alpha.5** | 11 Deteksi tracker, 16 Bagikan ke PackPeek, 25 Pembersih duplikat | Memperkuat sisi pemeriksaan dan merapikan penyimpanan |
| **alpha.6** | 2 Backup terjadwal (sekaligus menjawab ide 1), 4 Grup, 15 Tile Quick Settings | Membuat backup berjalan sendiri; jadwal mengikuti batasan kerja latar belakang Android |
| **beta** | 22 Persiapan rilis, 23 Tes otomatis, 7 Panduan downgrade | Syarat sebelum dibagikan luas |
| **nanti** | 12, 13, 14, 27, 17, 5, 8, 19 | Bermanfaat tetapi tidak mendesak |
| **perlu keputusan** | 20 Backup ke cloud, 28 Kirim lewat Wi-Fi/QR | Menyangkut janji "tanpa izin Internet" |

## Pertanyaan untuk pemilik

1. Apakah urutan di atas sesuai, atau ada ide yang ingin didahulukan?
2. Untuk ide 20 dan 28: tetap tanpa izin Internet (cloud lewat folder SAF, kirim lewat Quick Share), atau boleh menambah izin Internet?
3. Untuk ide 11: boleh memakai daftar tracker dari Exodus Privacy setelah lisensinya dicek?
4. Apakah PackPeek direncanakan masuk Play Store? Jawabannya menentukan seberapa cepat ide 22 perlu dikerjakan.
5. Untuk ide 29: boleh dibuatkan kunci tanda tangan khusus build uji yang disimpan sebagai *secret* di GitHub? Pemasangannya perlu dilakukan pemilik di Settings → Secrets repositori.
