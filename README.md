# PackPeek 2.0.0-alpha.3

Penulisan ulang OmniExtract berdasarkan `Grand_Design_PackPeek.xlsx` milik pengguna. Source ini ditulis baru, bukan pemulihan source asli APK. Kode pesaing tidak disalin. Fokus pada tahap 1; tanpa iklan, billing, akun, atau analitik.

## Build

Gunakan JDK 17 lengkap, Android SDK Platform 36, Build Tools 36.0.0, dan Android Studio yang mendukung AGP 8.13.2.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
```

Windows: `gradlew.bat`. Tentukan SDK melalui `ANDROID_HOME` atau `local.properties` lokal. Build awal memerlukan internet untuk dependensi.

ZIP final juga menyertakan `PackPeek-history.bundle`. Untuk memulihkan riwayat Git ke folder baru, jalankan `git clone PackPeek-history.bundle PackPeek-with-history`. Folder `PackPeek` dalam ZIP dapat langsung dibuka sebagai proyek Android Studio.

Debug: `com.packpeek.app.debug`, dapat berdampingan dengan OmniExtract. Release: `com.packpeek.app`. APK debug memakai kunci pengembangan. Release tetap unsigned sampai kunci pemilik dikonfigurasi; tidak ada fallback debug signing.

Salin `keystore.properties.example` ke `keystore.properties`, isi secara lokal untuk signing produksi. Jangan commit kunci/password. Simpan backup kunci secara terpisah.

## Fitur yang ditulis

- Daftar aplikasi, pencarian, sort, filter jenis/sumber/ukuran/SDK/framework, ikon lazy/cache.
- Ekstrak tunggal independen dari seleksi; batch `.apk` atau `.apks` per aplikasi; progres, batal, ringkasan dan share.
- Folder SAF, DataStore, background WorkManager dan Room untuk riwayat serta statistik.
- Detail izin, komponen, sertifikat, tanda tangan dan teknologi; skor konfigurasi transparan.
- Pemeriksaan APK/APKS lokal dan pemasangan melalui persetujuan sistem Android.
- Dasbor, ekspor ikon PNG 512 px, quick actions, tema terang/gelap, enam aksen, bahasa EN/ID.

## Memakai alpha

Pilih folder melalui onboarding/pengaturan. Ketuk Ekstrak pada satu kartu atau centang beberapa lalu Ekstrak pilihan. Hasil dapat dibagikan melalui dialog hasil/Riwayat. Periksa berkas membuka APK/APKS tanpa memasang. Setelah mengizinkan sumber tidak dikenal untuk installer, kembali dan ketuk Pasang lagi.

Backup berisi paket instalasi, bukan data aplikasi, akun, progres permainan, atau OBB. Aplikasi split dapat disimpan sebagai `.apks`, `.xapk`, `.zip`, atau `.apk` gabungan eksperimental. `.apk` gabungan ditandatangani ulang dengan kunci PackPeek, sehingga bukan lagi build asli pengembang: tidak dapat meng-update aplikasi asli dan sebagian aplikasi menolak berjalan. APKS di sini berupa ZIP base + split, bukan jaminan kompatibilitas semua arsip Bundletool/XAPK/APKM.

## Batas dan keputusan

- Skor heuristik bukan hasil antivirus atau vonis malware. Flag cleartext bukan pemeriksaan Network Security Configuration. Teknologi dideteksi dari penanda arsip/pustaka.
- Detail memverifikasi APK dasar; pemasangan memverifikasi semua split dan kecocokan signer. Riwayat sertifikat mengikuti Android. v4 `.idsig` belum didukung.
- Impor dibatasi 4 GiB, 10.000 entri ZIP, 512 APK, manifest 2 MiB. Traversal, base ganda, split duplikat dan package/versi berbeda ditolak.
- Package ID baru tidak dapat membaca preferensi privat OmniExtract lama. Adaptor SharedPreferences hanya bekerja dalam sandbox package yang sama; folder dipilih ulang.
- Email dukungan belum diberikan: tombol menyalin diagnostik; isi `SUPPORT_EMAIL` nanti. `SHOW_RATE=false` menyembunyikan rating Play.
- QUERY_ALL_PACKAGES untuk inventaris; REQUEST_INSTALL_PACKAGES untuk instalasi; izin foreground/notifikasi untuk backup. Tidak ada izin Internet atau akses semua file.
- Baseline Profile, pengujian perangkat API 24/29/33/34/36, uji TalkBack dan signing produksi belum dinyatakan selesai. Alpha bukan rilis stabil final grand design.

Lihat `docs/BUILD_REPORT.md` dan `docs/GRAND_DESIGN_STATUS.md` untuk hasil aktual serta pekerjaan lanjutan, dan `docs/IDE_FITUR.md` untuk daftar ide pengembangan.

Referensi resmi: https://developer.android.com/build/releases/agp-8-13-0-release-notes ; https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running ; https://developer.android.com/reference/android/content/pm/PackageInstaller
