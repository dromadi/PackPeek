# Hasil verifikasi — 2026-10-10

| Pemeriksaan | Hasil |
|---|---|
| assembleDebug | Lulus; APK debug tersedia |
| testDebugUnitTest | 12 tests, 0 failures, 0 errors |
| lintDebug | 0 errors, 23 warnings |
| assembleRelease + R8 | Lulus; output unsigned, bukan APK produksi siap pasang |
| apksigner verify APK debug | Lulus |
| zipalign 16 KiB APK debug | Lulus |
| Identitas | com.packpeek.app.debug, 2.0.0-alpha.1-debug |
| SDK | minimum 24, target 36 |
| Izin Internet | Tidak ada |
| Uji emulator/HP fisik | Belum dilakukan |

Ukuran APK debug: 15,246,157 byte. SHA-256:
`02edb762b186d1329b39b765cc1bc34b9b7f728b819d5e5fb11ff5b11d11bc66`

Perintah yang dijalankan:
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --max-workers=1
apksigner verify --verbose --print-certs app-debug.apk
zipalign -c -P 16 4 app-debug.apk
```

Toolchain: JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36.0.0. Hash arsip toolchain diperiksa terhadap sumber distribusi resminya.

Warnings Lint yang tersisa tercatat di `verification/lint-results-debug.txt`; tidak dihapus lewat baseline. Sebagian berupa saran gaya KTX/KSP, parameter Modifier dan kompatibilitas deklarasi locale/icon API. Build tidak membuktikan semua perilaku runtime benar.

Alpha ini menggunakan signing debug. Kunci produksi pemilik belum disediakan. Release unsigned tidak disertakan sebagai unduhan siap instal. Baseline Profile khusus aplikasi, pengujian lintas API, TalkBack, ketahanan background/SAF dan installer tetap menjadi gerbang rilis stabil.

Unit test mencakup format batch campuran, nama berkas, collision suffix, ZIP traversal, filter/pencarian, sort ukuran, skor deduplikasi, ABI, manifest APK milik pemilik dan input biner rusak.
