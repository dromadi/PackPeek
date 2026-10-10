# Hasil verifikasi — 2.0.0-alpha.12 (2026-10-11)

| Pemeriksaan | Hasil |
|---|---|
| assembleDebug | Lulus |
| testDebugUnitTest | 109 tes, 0 gagal, 0 error (JVM, termasuk render layar Robolectric dan 10 sampel uji modus penipuan) |
| lintDebug | 0 error, 70 peringatan |
| assembleRelease + R8 | Lulus; keluaran unsigned, bukan APK produksi siap pasang |
| apksigner verify APK debug | Lulus |
| zipalign 16 KiB APK debug | Lulus |
| Identitas | com.packpeek.app.debug, 2.0.0-alpha.12-debug (versionCode 20012) |
| SDK | minimum 24, target 36 |
| Izin Internet | **Ada** sejak alpha.10, hanya untuk cek hash VirusTotal opsional (kunci milik pengguna; hanya SHA-256 yang dikirim) |
| Uji emulator | Workflow GitHub (API 36): daftar aplikasi, ringkasan, buka berkas, analisis mendalam, laporan PDF, pemasangan. Hasil di branch `emulator-results` |
| HP fisik | Belum diuji oleh pengembang |

Ukuran APK debug: 17,504,368 byte. SHA-256:
`07eebe776042daf7828dae504b0646d9feef5f0a6edc868ca31eb88ed0e406d1`

Perintah yang dijalankan:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease --max-workers=2
apksigner verify app-debug.apk
zipalign -c -P 16 4 app-debug.apk
```

## Isi alpha.11

- Branch installer (`ccr-0cf010ff-77jy0f`) disatukan: pemilih bagian APK, kartu siap dipasang, antrean pasang banyak berkas, dialog hasil pemasangan, tab Folder backup, tema AMOLED, ekspor CSV, enam bahasa baru.
- Parser manifest: tag penutup wajib cocok dengan elemen yang terbuka; struktur yang tidak seimbang gagal dengan aman.
- Aturan IP mentah dikalibrasi: hanya IP publik di kelas kode pengirim; kuat hanya untuk sumber tidak tepercaya.
- 304 teks baru diterjemahkan ke es, pt-BR, ru, tr, hi, vi (terjemahan mesin, perlu ditinjau).

Toolchain: JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36.0.0. Hash arsip toolchain diperiksa terhadap sumber distribusi resminya.

Warnings Lint yang tersisa tercatat di `verification/lint-results-debug.txt`; tidak dihapus lewat baseline. Sebagian berupa saran gaya KTX/KSP, parameter Modifier dan kompatibilitas deklarasi locale/icon API. Build tidak membuktikan semua perilaku runtime benar.

Alpha ini memakai signing debug. Kunci produksi pemilik belum disediakan. Release unsigned tidak disertakan sebagai unduhan siap instal. Baseline Profile khusus aplikasi, pengujian lintas API, TalkBack, ketahanan background/SAF dan installer tetap menjadi gerbang rilis stabil.

Unit test mencakup format batch, nama berkas, ZIP traversal, filter/pencarian, parser manifest (termasuk manifest rusak dan bersarang tidak seimbang), aturan risiko T1–T4, pemindai DEX dan analisis mendalam, kalibrasi IP, aturan instalasi/split, indeks folder backup, dan render layar.
