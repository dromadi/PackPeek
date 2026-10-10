# Analisis Pesaing

Kumpulan keunggulan aplikasi pesaing, diambil dari screenshot Google Play yang dikirim pemilik proyek (10 Oktober 2026). Screenshot promosi belum tentu sama persis dengan isi aplikasi; poin bertanda *(cek)* perlu dipastikan dengan memasang aplikasinya.

Pesaing yang dibahas:

- **A — Apk Extractor** (`com.toralabs.apkextractor`): gaya lama, berwarna biru, dengan iklan.
- **B — XAPK Extractor** (`com.ytheekshana.apkextractor`): Material 3, ada App Analyzer.

Status di PackPeek 2.0.0-alpha.6:

- ✅ sudah ada
- 🟡 sebagian
- ❌ belum ada

## 1. Daftar aplikasi

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 1 | Ekstrak satu ketuk langsung dari daftar | A | ✅ | Tombol unduh di tiap baris |
| 2 | Menu ⋮ per baris: Ekstrak, Info aplikasi, Simpan ikon, Lihat di Play Store, Bagikan | A | 🟡 | Di PackPeek tindakan ini ada di halaman detail, belum di baris |
| 3 | Tekan lama untuk memilih banyak, dengan penghitung "5/342" | A | ✅ | Ditambah "Pilih semua hasil" |
| 4 | Chip User / System / All, dengan jumlah aplikasi | B | ✅ | Chip Tipe, Sumber, Urutkan |
| 5 | Ukuran sebagai "pil" bergaris di kanan, mudah dipindai | B | 🟡 | Ukuran ada di baris teks, belum sebagai pil |
| 6 | Versi tampil di daftar | A, B | ✅ | |
| 7 | Favorit (bintang) di toolbar | A | ✅ | |
| 8 | Pencarian berdasarkan nama dan nama paket | A, B | ✅ | |

## 2. Ekstrak dan berbagi

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 9 | Snackbar "Extracted Apk Saved ✓ **SHARE**" langsung setelah ekstrak | A | ❌ | PackPeek berbagi lewat Riwayat |
| 10 | Dialog ringkasan sebelum ekstrak yang **bisa dimatikan** ("Show App Details") | A | 🟡 | PackPeek selalu menampilkan konfirmasi |
| 11 | Format nama berkas bisa diatur | A | ✅ | |
| 12 | Split APK diekstrak ke `.xapk` | B | ✅ | PackPeek: `.apks`, `.xapk`, `.zip`, `.apk` gabungan |
| 13 | Proses ekstrak terasa cepat, dengan dialog progres sederhana | A | 🟡 *(cek)* | Perlu dibandingkan langsung di HP yang sama |

## 3. Detail aplikasi

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 14 | **Lembar detail cepat (bottom sheet)**: info utama dan tombol tanpa pindah halaman | B | ❌ | PackPeek membuka halaman penuh |
| 15 | Nama versi Android yang manusiawi: "Android 16.0 (Baklava, API 36)" | B | ❌ | PackPeek hanya menampilkan angka API |
| 16 | Lencana "API 36" di header | B | ❌ | |
| 17 | Tanggal pasang **pertama** dan pembaruan terakhir | B | 🟡 | PackPeek baru menampilkan pembaruan terakhir; data pasang pertama sudah dibaca tetapi belum ditampilkan |
| 18 | Installer ditampilkan dengan nama ("Debug", "Galaxy Store") | B | ✅ | |
| 19 | Tombol jumlah: Permissions (15), Activities (11), Services (10), Receivers (6), Providers (3) | B | 🟡 | PackPeek punya tab Izin dan Komponen; jumlahnya belum tampil di tombol |
| 20 | Tombol Google Play / Extract App / Launch dalam satu baris | B | ✅ | |
| 21 | UID ditampilkan | A | ❌ | Info teknis kecil |
| 22 | Simpan ikon aplikasi | A | ✅ | PNG 512×512 |

## 4. Analisis dan ringkasan (App Analyzer)

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 23 | Kelompok per **Installer** dengan grafik donat dan persentase | B | 🟡 | PackPeek memakai batang, tanpa persentase |
| 24 | Kelompok per **Target SDK** | B | ✅ | |
| 25 | Kelompok per **Minimum SDK** | B | ❌ | |
| 26 | Kelompok per **Signature** (sertifikat) | B | ❌ | Sangat berguna untuk keamanan, misalnya aplikasi yang memakai sertifikat debug atau berbagi kunci |
| 27 | Kelompok per **Platform** (ABI / arsitektur) | B | ❌ | |
| 28 | Baris kelompok bisa diketuk untuk membuka daftar aplikasinya *(cek)* | B | ❌ | Baris di Ringkasan PackPeek belum bisa diketuk |
| 29 | Ikon toko pada tiap kelompok installer | B | ❌ | |

## 5. Riwayat dan pemasangan

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 30 | Riwayat dengan versi, tanggal, format, dan ukuran | B | ✅ | Dikelompokkan per aplikasi |
| 31 | Pasang XAPK hasil ekstrak dari Riwayat | B | ✅ | Ditambah cek tanda tangan dan alasan gagal pasang |

## 6. Tampilan dan pengaturan

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 32 | Tema gelap | A, B | ✅ | |
| 33 | Pilihan warna tema | A | ✅ | |
| 34 | Halaman pengaturan berkelompok (General, Support, Other) dengan Feedback dan Rate Us | A | ✅ | |

## 7. Pemasaran (listing Play Store)

| # | Keunggulan | Dari | PackPeek | Catatan |
|---|---|---|---|---|
| 35 | Screenshot promosi seragam: latar biru, judul besar, subjudul, mockup HP | A, B | ❌ | Perlu dibuat sebelum rilis ke Play |
| 36 | Teks promosi diterjemahkan (bahasa Indonesia) | A | ❌ | |
| 37 | Satu pesan per screenshot ("Ekstrak satu klik", "Tema gelap", "App Analyzer") | A, B | ❌ | Usulan pesan PackPeek: "Cek risiko sebelum pasang", "Simpan aplikasi split", "Riwayat versi", "Aktif di perangkat ini" |

## Yang tidak perlu ditiru

- **Iklan dan "Remove Ads"** (A): PackPeek bebas iklan, dan ini nilai jual.
- **Hanya satu format split** (B): pilihan format adalah keunggulan PackPeek.
- **Pasang tanpa pemeriksaan** (B): PackPeek memeriksa berkas dulu.
- **Daftar izin mentah dan terpotong** (A): PackPeek memberi label dan penjelasan.

## Prioritas yang diusulkan

| Prioritas | Item | Usaha |
|---|---|---|
| 1 | #9 Snackbar "Bagikan" setelah ekstrak | Kecil |
| 2 | #15, #16 Nama versi Android dan lencana API | Kecil |
| 3 | #17, #19, #21 Tanggal pasang pertama, jumlah komponen di tombol, UID | Kecil |
| 4 | #25–#28 Ringkasan: Minimum SDK, Sertifikat, ABI, baris bisa diketuk | Sedang |
| 5 | #2, #14 Menu ⋮ per baris dan lembar detail cepat | Sedang |
| 6 | #10 Opsi melewati konfirmasi ekstrak | Kecil |
| 7 | #5, #23 Pil ukuran dan persentase/donat | Kecil |
| 8 | #35–#37 Screenshot Play Store | Sedang, setelah UI stabil |
