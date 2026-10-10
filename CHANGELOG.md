# Changelog

## Belum dirilis

- Dialog hasil pemasangan dengan alasan spesifik (tanda tangan, downgrade, ABI, SDK, penyimpanan, split hilang, fitur perangkat), pesan sistem, tombol Buka aplikasi atau Copot aplikasi saat ini.
- Kartu "Siap dipasang?" untuk berkas lokal: tanda tangan, Android minimum, target SDK yang ditolak Android 14+, ABI, fitur perangkat wajib, signer dan downgrade terhadap aplikasi terpasang.
- Arsip XAPK/APKM dikenali dan bisa dibuka dari file manager; jumlah OBB yang tidak ikut terpasang ditampilkan.
- Tema hitam AMOLED dan ekspor daftar aplikasi (hasil filter) sebagai CSV.
- Pilih bagian APK sebelum memasang: CPU, kepadatan layar, bahasa dan modul fitur, dengan pilihan terbaik untuk perangkat dicentang otomatis; pemasangan dihentikan bila modul kehilangan bagian CPU.
- Arsip `.apks` bundletool diterima: `standalones/` diabaikan bila `splits/` ada.
- Pasang beberapa berkas sekaligus melalui antrean dengan progres, batal dan ringkasan hasil.
- Enam bahasa baru: Spanyol, Portugis (Brasil), Rusia, Hindi, Vietnam dan Turki; nama bahasa selalu ditampilkan dalam bahasanya sendiri. Perlu ditinjau penutur asli sebelum rilis.
- Tab Folder backup di Riwayat: isi folder dibaca langsung dari manifest tiap berkas dan dibandingkan dengan aplikasi terpasang (lebih baru, sama, lebih lama, tidak terpasang), plus daftar aplikasi tanpa backup; aksi Pulihkan/Perbarui, Periksa, Bagikan dan Hapus.
- Perbaikan: kunci backup yang macet, indikator progres yang hilang terlalu cepat, pembaruan notifikasi backup, ekspor ikon adaptif.

## 2.0.0-alpha.10 — 2026-10-10

**Analisis mendalam**: tombol baru di detail aplikasi, muncul bila ada temuan kode, kode yang tidak terbaca, atau indikator kuat. Semua berjalan di perangkat dan aplikasinya tidak dijalankan.

- **Jejak data** hingga tiga tingkat pemanggilan, termasuk perpindahan antarkomponen lewat Intent (Receiver → Service), objek (thread/callback), dan work request. Hasilnya: langkah demi langkah dari pembacaan data sampai pengirimannya. Jejak ini juga menjadi bukti T3 pada temuan.
- **Teks yang disamarkan dibuka**: Base64, teks dibalik, potongan yang digabung, array byte/karakter (termasuk yang disusun elemen demi elemen oleh d8), dan XOR satu byte. Alamat bot hasil pembukaan ini dihitung sebagai tujuan tersembunyi.
- **Kode tersembunyi dibongkar**: DEX/APK/JAR di `assets/` dipindai dengan aturan yang sama. Berkas aset yang tampak terenkripsi (entropi tinggi) dilaporkan.
- **Kode mencurigakan**: cuplikan singkat metode di balik bukti, dengan pemanggilan sensitif ditandai ⚠.
- **Yang bisa kamu lakukan**: uninstall, info aplikasi (cabut izin), setelan aksesibilitas, akses notifikasi, admin perangkat, cek pengalihan panggilan (`*#21#`), dan langkah mengamankan akun.
- **Laporan bukti**: bagikan sebagai PDF atau teks, lengkap dengan SHA-256, semua bukti T1–T4, dan hasil analisis mendalam.
- **Cek online (opsional)**: SHA-256 bisa dicocokkan dengan VirusTotal memakai kunci API milik pengguna (Pengaturan), atau dibuka di browser. Hanya sidik jari yang dikirim, tidak pernah berkasnya. Karena itu PackPeek kini meminta izin `INTERNET`, yang hanya dipakai untuk fitur ini dan hanya saat tombolnya diketuk.
- Sampel uji v2: SMS diteruskan ke Service lewat Intent, alamat bot dalam Base64, webhook di-XOR, payload DEX kedua di aset, dan blob acak. Sampel tetap tidak pernah berjalan.

- **Set sampel uji modus penipuan** (`tools/scam-samples/`): 8 APK aman, satu per modus (pencurian OTP Telegram, OTP lewat pembaca notifikasi, overlay bank + aksesibilitas, penukar clipboard, spyware ke server IP, pengalihan panggilan, dropper DEX tersembunyi, admin perangkat), plus 2 pembanding "aman". Semua `hasCode="false"` dan tidak pernah berjalan. Setiap deteksi dikunci oleh `ScamSamplesTest`.
- **Pengiriman data ke alamat IP mentah kini indikator kuat**: mengirim data pribadi ke IP tanpa nama domain adalah ciri server C2. Aplikasi sah memakai nama server, bukan IP mentah.

## 2.0.0-alpha.9 — 2026-10-10

- **Bukti lebih bersih:** teks `content://sms` (dan `content://` lain) hanya menjadi bukti bila kelas yang sama juga menjalankan query, sama seperti alamat bot Telegram/Discord yang hanya dihitung bila kelasnya bisa mengirim data. Ini mencegah daftar tanda pengenal milik aplikasi keamanan muncul sebagai bukti.
- **Pengujian tanpa emulator:** render layar di JVM (Robolectric) untuk Ringkasan dan kartu temuan, dalam mode terang, gelap, bahasa Indonesia, dan huruf besar. Enam layar butuh sekitar 12 detik.
- **Skrip emulator** `tools/emulator/emu.sh`: boot dari snapshot (146 → 31 detik di mesin tanpa KVM), menunggu dan mengetuk berdasarkan teks, serta menutup dialog ANR otomatis.
- **Emulator di GitHub:** workflow `Emulator test` menjalankan skenario uji pada emulator Android 16 yang berakselerasi (runner GitHub memiliki KVM). Screenshot dan tabel langkahnya diterbitkan di branch `emulator-results`, sehingga bisa dibuka dari HP. Karena repo privat, workflow hanya berjalan untuk commit berisi `[emulator]` atau lewat Run workflow.
- **Build lebih stabil:** dependensi diambil dari mirror `repo1.maven.org` sebelum `repo.maven.apache.org`, yang sering membalas 429 (rate limit). Framework Robolectric diunduh lewat Gradle, sehingga render layar juga bisa berjalan offline.

## 2.0.0-alpha.8 — 2026-10-10

Analisis risiko tahap 2 dan 3: PackPeek kini membaca **kode** aplikasi (DEX) untuk mencari bukti, tanpa menjalankannya. Ringkasan dirombak.

- **Bukti T2 (di kode):** pemanggilan fungsi sensitif beserta lokasinya, misalnya `SmsMessage.createFromPdu` di `com.x.SmsReceiver.onReceive`. Setiap bukti diberi asal: kode aplikasi sendiri atau library yang dikenal (AndroidX, Firebase, AdMob, …). Hanya kode aplikasi sendiri yang dihitung untuk pola.
- **Bukti T3 (jalur data):** metode yang membaca data pribadi (SMS, kontak, notifikasi, teks layar, clipboard, cookie, ID telepon, lokasi) lalu mengirimnya (internet, SMS, bot Telegram, webhook Discord), langsung atau lewat metode yang dipanggilnya.
- **Temuan baru:**
  - **Data pribadi dikirim ke tujuan tersembunyi** (indikator kuat);
  - Tujuan tersembunyi di kode (bot Telegram, webhook Discord, alamat IP);
  - Kode pengalihan panggilan (`**21*`, USSD);
  - Memuat kode tambahan (DexClassLoader, DEX/APK tersembunyi di aset, packer);
  - Membaca clipboard;
  - Membaca cookie WebView;
  - SDK iklan/analitik;
  - Alamat di dalam kode.
- **"Belum terbukti" kini mengikuti hasil kode:** jalur ditemukan (T3), hanya pemanggilan (T2), atau kode sudah diperiksa dan tidak memanggil fungsinya. Pola kuat menampilkan "Bukti terkuat".
- **Cakupan kode:** jumlah DEX, kelas, dan kelas kode aplikasi, serta bagian yang tidak bisa dianalisis (kode tersembunyi, packer, Flutter/React Native/Unity, batas waktu 30 detik).
- **Lebih sedikit salah tuduh:** bila kode terbaca penuh, "dipasang dari luar toko", overlay, admin, atau akses notifikasi saja tidak lagi membuat pola kuat. Pola kuat membutuhkan bukti di kode atau tanda penyamaran (ikon tersembunyi, nama memancing, sertifikat debug/baru, berkas yang belum dipasang). Teks seperti `api.telegram.org` hanya dihitung bila kodenya juga mengirim data.
- **Ringkasan baru:**
  - kartu **Periksa aplikasimu** (memeriksa semua aplikasi pengguna dengan manifest, kode, dan kondisi perangkat);
  - daftar **Perlu ditinjau**;
  - "Aktif di perangkat ini" yang ringkas;
  - baris statistik;
  - kartu **Analisis aplikasi** dengan pilihan Sumber, Android target/minimum, Sertifikat, Arsitektur, dan Teknologi, berupa satu batang bertumpuk dan legenda yang bisa diketuk.
- Sampel uji `tools/undangan-sim/` kini berisi kode tiruan (tetap `hasCode="false"`, jadi tidak pernah dijalankan; token bot palsu), termasuk kelas "detektor" untuk memastikan teks saja tidak dianggap pengiriman data.

## 2.0.0-alpha.7 — 2026-10-10

Fitur dari analisis pesaing (`docs/ANALISIS_PESAING.md`).

- **Bagikan setelah ekstrak:** ekstrak satu aplikasi yang berhasil kini menampilkan bilah "Tersimpan: nama berkas · **Bagikan**" di bawah layar, bukan dialog. Ekstrak beberapa aplikasi atau yang gagal tetap memakai dialog hasil.
- **Nama versi Android:** "Android 16 (Baklava, API 36)" untuk Android minimum dan target, plus lencana API di header detail.
- **Detail:** tanggal pertama dipasang, nama installer ("Google Play Store (com.android.vending)"), dan jumlah di tab (Izin (15), Komponen (32)).
- **Ringkasan yang bisa diketuk:** kelompok Sumber, Android target, Android minimum, **Sertifikat** (dengan sidik jari agar kunci bernama sama bisa dibedakan), **Arsitektur CPU**, dan teknologi, dengan persentase. Ketuk kelompok untuk membuka daftar aplikasinya; chip filter di atas daftar menghapusnya.
- **Menu ⋮ per baris:** Detail cepat, Detail lengkap, Buka aplikasi, Info aplikasi sistem, Lihat di Google Play, Simpan ikon, Salin nama paket, dan Favorit. Favorit ditandai bintang kecil di samping nama.
- **Detail cepat:** lembar setengah layar berisi versi, Android minimum/target, tanggal, installer, UID, ukuran, jumlah izin/activity/service/receiver/provider, dan tombol Ekstrak, Buka, Google Play, Detail lengkap. Di Pengaturan → Tampilan, "Ketuk aplikasi membuka" bisa diubah ke Detail cepat (bawaan: Detail lengkap).

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
