# Changelog

## Unreleased — review alpha.19–20

- Bound backup indexing by shared inflated bytes and entry count, including nested APKs and skipped entries; preserve cancellation and release ZIP inflaters.
- Require MAIN and a launcher category in the same intent filter in both manifest readers.
- Validate string-pool boundaries/terminators and bound decoded allocations.
- Correlate install results with a session ID retained in the PendingIntent, reject unidentified/stale results, and measure waiting with a monotonic clock.
- Label static URLs, unrecognized code origins and untraced flows conservatively in all eight supported languages.
- Make the ZIP-bomb regression deterministic with an explicit inflated-byte cap and a valid-manifest control; test expired deadlines without timing a busy loop.
- Read DEX files and hidden payloads with a limit on the bytes actually inflated, since ZIP sizes can be understated by a crafted APK; reading now sits inside the error handling, so one oversized or out-of-memory file becomes a coverage gap instead of ending the APK's code analysis, and an out-of-memory payload no longer stops the main DEX scan.
- Name `versionCodeMajor` by its framework ID, so split checks still compare it when attribute names are stripped.
- Document why the installer's background-start opt-in differs between Android 14–15 and 16.
- Recognize Alipay SDK, ZEGO SDK and Drake libraries, so findings from them (cookie reads, screen capture for live streaming, clipboard and shell helpers) are no longer reported as the app's own code. Seen in a report on HOT51, a sideloaded live-streaming app.
- Add regression coverage and CI for unit tests, lint, debug and R8 release builds. CI results are recorded on the PR; no new release or production-signing claim.

## 2.0.0-alpha.20 — 2026-10-11

**Kalibrasi lanjutan "Pola pencurian OTP" dari laporan Getcontact.** Di alpha.19, Getcontact (Play Store) masih ditandai indikator kuat karena `TelephonyManager.sendUssdRequest` dianggap tanda pengalihan panggilan. Getcontact memakainya di `TelcoActivationRepositoryImpl.callUssd` untuk aktivasi layanan operator. USSD juga dipakai untuk cek pulsa dan paket data.

- Untuk aplikasi dari toko resmi, hanya **kode pengalihan yang tertulis di kode** (`**21*`, `*61*`, `*62*`, `*67*`, `**002*`, `**004*`) yang dihitung sebagai tanda pencurian OTP. Panggilan USSD tanpa kode itu tidak lagi dihitung.
- Untuk aplikasi dari luar toko dan berkas APK, aturannya tidak berubah: panggilan USSD apa pun tetap dihitung.
- Temuan "Kode pengalihan panggilan" (perlu perhatian) tetap muncul seperti sebelumnya.
- Tes: 151.

## 2.0.0-alpha.19 — 2026-10-11

**Pemeriksaan semua aplikasi bisa dilanjutkan, hasil tersimpan, progres per aplikasi, diagram hasil, dan salah deteksi OTP diperbaiki.**

- **Hasil pemeriksaan tersimpan di HP.** Hasil setiap aplikasi disimpan begitu selesai diperiksa, ke berkas kecil di penyimpanan internal PackPeek; tidak ada yang keluar dari HP. Bila PackPeek ditutup atau dimatikan sistem, pemeriksaan **dijeda**, bukan diulang dari awal. Kartu Ringkasan menulis "Dijeda: 120 dari 258 aplikasi sudah diperiksa…" dengan tombol Lanjutkan. Aplikasi yang diperbarui sejak diperiksa akan diperiksa lagi. Setelah PackPeek diperbarui, semua hasil diperiksa ulang karena aturannya bisa berubah.
- **Progres saat memeriksa satu aplikasi:** persentase dan langkahnya, yaitu membaca manifest dan sertifikat, membaca kode (berkas 3 dari 8), lalu menelusuri aliran data. Analisis mendalam juga menampilkannya.
- **Diagram donat "Hasil pemeriksaan"** di Ringkasan: indikator kuat, perlu perhatian, tanpa temuan penting, dan belum diperiksa, masing-masing dengan jumlah di legenda. Pembaca layar membacakan keempat angkanya.
- **Salah deteksi "Pola pencurian OTP" diperbaiki.** Getcontact, Meta AI, dan Perplexity dari Play Store ditandai indikator kuat hanya karena ada jalur kode dari baca-SMS ke internet. Banyak aplikasi resmi membaca SMS untuk mengisi otomatis kode login mereka sendiri dan mengirimnya ke server mereka untuk verifikasi. Kini jalur seperti itu hanya dihitung sebagai tanda pencurian OTP untuk aplikasi dari luar toko resmi atau berkas APK. Tanda yang lebih tegas tetap berlaku untuk semua sumber: kiriman ke Telegram atau webhook, ikon tersembunyi, dan penerusan panggilan. Akses SMS-nya tetap dilaporkan sebagai "perlu perhatian", lengkap dengan jalur kodenya.
- Tes: 150. Tes baru mencakup penyimpanan hasil (versi aplikasi, versi PackPeek, baris rusak), progres yang hanya bergerak maju sampai 100%, kalibrasi OTP, dan render diagram.

## 2.0.0-alpha.18 — 2026-10-11

**Perbaikan dari uji di HP: Ringkasan lambat, tampilan statistik, dan layar pengenalan.**

- **Pemeriksaan semua aplikasi di Ringkasan jauh lebih cepat.** Sebelumnya setiap aplikasi menjalani analisis lengkap, termasuk pelacakan nilai yang bisa memakan sampai 30 detik per aplikasi, satu per satu. Kini Ringkasan memakai pemeriksaan cepat tanpa pelacakan nilai, dua aplikasi sekaligus. Temuan dan tingkatnya tetap sama; tes baru membandingkan keduanya pada 16 APK uji. Tanda "nilainya dilacak" dan alamat tujuan tetap muncul saat aplikasi dibuka.
- **Statistik perangkat** kini kotak 2×2 dengan ikon. Angka dan label tidak lagi berantakan dengan huruf besar.
- **Layar pengenalan dirombak:**
  - halaman bisa digeser;
  - setiap halaman punya ilustrasi dan tiga poin manfaat;
  - isinya kini menyebut cek penipuan APK (SMS/OTP, Telegram, layar palsu di atas aplikasi bank, laporan PDF);
  - paragraf privasi hanya sekali, di halaman terakhir;
  - pilihan folder cadangan berupa kartu dengan keterangan bahwa folder bisa diubah nanti.
- Teks baru dalam 8 bahasa. Tes: 142.

## 2.0.0-alpha.17 — 2026-10-11

**Perbaikan regresi: analisis terpotong pada aplikasi besar.** Laporan InSaver alpha.16 hanya berisi 1 temuan, 3 SDK, dan bagian analisis mendalam kosong. Penyebabnya, pelacakan nilai (alpha.14–16) menghabiskan waktu dan memori pada aplikasi besar yang kodenya disamarkan, sehingga pemindaian dasar ikut gagal. Laporan alpha.16 untuk aplikasi seperti itu tidak bisa dipakai. Kini:

- Pemindaian dasar (kemampuan, SDK, alamat, jalur kode) selalu selesai. Pelacakan nilai hanya mendapat sebagian waktu (60% untuk pelacakan per fungsi, sampai 90% untuk penyelesaian) dan berhenti sendiri bila memori menipis.
- Penyelesaian hanya mencakup fungsi yang bisa berpengaruh pada pengiriman: fungsi yang mengirim atau menyimpan nilai, beserta pemanggilnya.
- Data per fungsi dibuat hanya bila dipakai, dan nama metode disimpan sekali. Memori turun sekitar 40% pada uji 154 ribu metode.
- Bila pelacakan terpotong, laporan menyebutnya dengan jujur di kartu Cakupan: "Pelacakan nilai kehabisan waktu; kemampuan dan alamat lengkap, tetapi sebagian jalur data belum ditandai terlacak".
- Laporan teks/PDF kini punya bagian **Cakupan analisis** (jumlah kelas, batas waktu, kode tersembunyi, pelacakan terpotong).
- Uji beban: APK PackPeek dengan semua 22.587 kelas dianggap kode aplikasi. Sebelumnya kehabisan memori, kini selesai dalam 28 detik (batas 30 detik) dan 18 detik (mendalam, batas 90 detik). Tes: 135.

## 2.0.0-alpha.16 — 2026-10-11

**Field dilacak per objek.** Di InSaver, semua jalur cookie dan clipboard menyebut delapan alamat yang sama. Penyebabnya, satu kelas klien HTTP dipakai untuk Instagram, Facebook, TikTok, dan server sendiri, sementara PackPeek mencatat field per nama. Kini:

- Setiap `new` adalah satu objek (lokasi alokasi). `this.base` di dalam metode dibaca relatif terhadap objek tempat metode itu dipanggil. `Clients.instagram.send(cookie)` kini hanya menyebut alamat milik objek Instagram.
- Identitas objek ikut terbawa lewat field (singleton/DI), nilai kembali fungsi, dan pemanggilan pada `this`.
- Kalau objeknya tidak bisa ditentukan, PackPeek kembali ke cara lama (per nama field), jadi deteksi tidak pernah berkurang.
- Laporan yang menyebut beberapa alamat kini ditulis "salah satu dari", dan daftar yang terpotong diberi akhiran "dan lainnya".
- Fixture baru `two-clients` (Kotlin): satu kelas klien, dua instans di singleton, satu instans lokal. Masing-masing hanya menyebut alamatnya sendiri.
- Tes: 134. Waktu pindai sekitar 1,2× alpha.15.

## 2.0.0-alpha.15 — 2026-10-11

**Alamat tujuan untuk nilai yang terlacak.** Bila nilai data pribadi terlacak sampai ke panggilan pengiriman, PackPeek kini juga menyebut **ke alamat mana** pengiriman itu, misalnya "Nilai cookie login dilacak sampai ke panggilan yang mengirim ke internet, alamat: i.instagram.com".

- Alamat diambil dari URL yang tertulis di kode dan diikuti seperti nilai data:
  - lewat `Request.Builder().url(...)`, penggabungan teks, field (alamat dasar klien API), dan nilai kembali fungsi;
  - lewat parameter fungsi pembantu seperti `get(url, cookie)`;
  - lewat coroutine.
- Setiap pengiriman dirangkum per fungsi (parameter yang ikut, sumber data, alamat), lalu dirangkum naik ke pemanggilnya. Dengan begitu nilai dan alamat yang datang dari jalur berbeda tetap bertemu di pengiriman yang sama.
- Alamat tempat data dibaca tidak dianggap tujuan. Contohnya `getCookie("https://www.instagram.com")` membaca cookie, bukan mengirim ke Instagram.
- Panggilan ke kelas Android/Java tidak lagi dianggap pemanggilan metode aplikasi.
- `HttpURLConnection.getResponseCode`/`getInputStream`/`connect` dikenali sebagai pengiriman, karena request baru terkirim setelah header seperti Cookie dipasang.
- Alamat tampil di bukti temuan, di jejak data analisis mendalam ("Dikirim ke: …"), dan di laporan.
- Fixture baru `destination-cookie` (Kotlin). Tes: 131. Waktu pindai sekitar 1,3× alpha.14.

## 2.0.0-alpha.14 — 2026-10-11

**Pelacakan nilai menembus coroutine Kotlin.** Aplikasi modern, seperti InSaver, mengoper data lewat coroutine. Sebelumnya nilai hilang di setiap perpindahan, sehingga hasilnya "nilainya belum dilacak".

- Nilai diikuti lewat **lambda** (`launch { kirim(cookie) }`: variabel tertangkap di field `$c`), **continuation** (variabel `suspend` disimpan di `L$0` saat menunggu), dan **hasil fungsi `suspend`** (diterima `invokeSuspend` atau field `result`).
- Label per parameter: lambda yang menangkap objek luar (`this`) dan cookie sekaligus tidak lagi membuat `this` dianggap cookie.
- Pelacakan kini **mengikuti percabangan** (dataflow per blok dasar, digabung di titik temu). Sebelumnya satu cabang bisa menimpa nilai cabang lain, seperti cabang "resume" pada state machine coroutine.
- Jejak data di analisis mendalam ditandai terlacak bila nilai terlacak di salah satu langkahnya, dan jejak yang terlacak didahulukan.
- Fixture baru `coroutine-cookie` dikompilasi oleh compiler Kotlin sungguhan: tiga jalur coroutine terlacak, pembanding (cookie hanya untuk keputusan) tidak.
- `yads` (bagian dalam Yandex Ads) dikenali sebagai library. "Bukti terkuat" menyebut bila nilainya terlacak.
- Waktu pindai sekitar 1,5× dibanding sebelumnya. Tes: 126.

## 2.0.0-alpha.13 — 2026-10-11

Perbaikan dari hasil review kode, diurutkan menurut dampaknya pada kepercayaan laporan.

- **Identitas metode lengkap**: metode di DEX kini dikenali dari kelas, nama, *dan* signature parameternya. Sebelumnya overload seperti `a(String)` yang membaca clipboard dan `a(int)` yang mengirim statistik dianggap satu metode, sehingga muncul jalur clipboard→internet palsu.
- **T3 dikalibrasi, nilai data dilacak**:
  - Nilai yang dibaca dari sumber data diikuti lewat register, field (termasuk field objek), builder/koleksi, dan pemanggilan metode aplikasi (ringkasan parameter→pengiriman dan hasil→sumber).
  - Alur ditandai **"nilai dilacak"** hanya bila nilai itu benar-benar sampai ke argumen panggilan yang mengirim.
  - Selain itu, bukti ditulis jujur sebagai "membaca dan mengirim di jalur kode yang sama (nilainya belum dilacak)". Contohnya membaca cookie lalu membuat request yang tidak membawa cookie itu.
- **Siklus sesi pemasangan**:
  - Hasil dicocokkan per `sessionId`.
  - Pemasangan tunggal tetap terkunci sampai Android memberi hasil akhir.
  - Antrean tidak bisa macet lagi: sesi diperiksa tiap detik. Bila hilang tanpa laporan, versi yang terpasang dibandingkan. Setelah 10 menit sesi dibatalkan dengan hasil "Android tidak melaporkan hasil".
- **Kartu "Siap dipasang?"**: batas target SDK mengikuti versi Android (API 23 di Android 14, **API 24 di Android 15+**).
- **Teks gabungan hanya dari kode yang benar-benar menggabungkan** (StringBuilder/`concat`), bukan dari semua konstanta dalam satu metode.
- **Parser manifest**: ukuran header dokumen harus cocok dengan berkas, dan chunk harus habis tepat di akhir. Sisa 1–7 byte kini ditolak.
- **Perintah shell dipisah** dari "Memuat kode tambahan" menjadi temuan sendiri, "Menjalankan perintah shell".
- **Pemindaian folder backup dibatasi**: maks 1 GiB dibaca dan 20 detik per berkas, berhenti segera saat dibatalkan. Zip bomb atau arsip raksasa tidak lagi membuat pemindaian macet.
- Tes: 121 (sebelumnya 109), termasuk 3 APK uji baru (`control-overload`, `control-unlinked`, `joined-pieces`).

## 2.0.0-alpha.12 — 2026-10-11

- **Perbaikan pemasangan di Android 15/16**: tombol Pasang tidak memunculkan prompt sistem karena Android memblokir layar status pemasangan (pembatasan "background activity launch" untuk target SDK 35+). PendingIntent pemasangan kini memberi izin yang diperlukan. Ditemukan lewat uji emulator Android 16.
- **Laporan bukti lebih mudah dijangkau**: tombol "Bagikan laporan bukti (PDF)" langsung di bawah ringkasan analisis mendalam.
- **Analisis mendalam lebih bersih** (dari laporan aplikasi Play sungguhan):
  - "Digabung dari potongan" hanya muncul bila gabungannya membentuk tujuan yang mencurigakan (bot, webhook, alamat data, kode pengalihan). Sebelumnya semua teks satu metode digabung sehingga muncul ratusan baris tak bermakna.
  - Alamat di kode harus berupa nama domain atau IP yang valid.
  - SDK ByteDance APM, Pangle (pgl), TradPlus, TaurusX, TopOn, myTarget, OM SDK, AppMetrica, Yandex, JJWT, dan AIHelp dikenali sebagai library, bukan kode aplikasi sendiri.
  - Laporan menulis langkah jejak data sebagai `Kelas.metode` dan menandai setiap bukti kode sebagai kode aplikasi sendiri atau library.

## 2.0.0-alpha.11 — 2026-10-10

Branch installer disatukan dengan alpha.10, parser manifest diperketat, dan aturan IP dikalibrasi.

- **Parser manifest**: setiap tag penutup harus menutup elemen yang sedang terbuka (nama dan namespace sama). Tag penutup liar, root kedua, dan elemen yang tidak ditutup kini gagal dengan aman sebagai "arsip rusak", bukan menghasilkan pohon yang bergeser atau terpotong.
- **Kalibrasi alamat IP**: hanya IP publik yang dihitung. Alamat lokal (10.x, 192.168.x, 172.16–31.x, 127.x, 169.254.x, CGNAT, multicast, IPv6 lokal) dan DNS publik terkenal (8.8.8.8, 1.1.1.1, …) diabaikan. IP harus berada di kelas kode yang benar-benar mengirim, dan alur data pribadinya harus sampai ke kode itu. IP di bagian lain aplikasi bukan bukti pencurian.
- Pencurian data ke IP publik menjadi **indikator kuat hanya untuk sumber tidak tepercaya** (berkas, sideload, ikon tersembunyi, sertifikat lemah). Dari toko aplikasi, hasilnya "perlu perhatian".
- Teks privasi kini menyebut izin Internet secara jujur: hanya untuk cek VirusTotal opsional, dan yang dikirim hanya SHA-256.
- Teks baru alpha.7–alpha.10 diterjemahkan ke enam bahasa baru. Terjemahan mesin, perlu ditinjau penutur asli.

Dari branch installer:

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
- **Pengiriman data ke alamat IP mentah kini indikator kuat**: mengirim data pribadi ke IP tanpa nama domain adalah ciri server C2. (Dikalibrasi di alpha.11: hanya IP publik di kode pengirim, dan kuat hanya untuk sumber tidak tepercaya.)

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
