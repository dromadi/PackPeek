# Sampel uji modus penipuan

Satu APK uji per modus penipuan, untuk membuktikan PackPeek (terutama **Analisis mendalam**) mendeteksi setiap pola. Ditambah dua sampel "aman" sebagai pembanding agar tidak salah tuduh.

## ⚠️ Semua sampel ini TIDAK berbahaya

- Manifest setiap sampel bertanda **`android:hasCode="false"`**. Artinya Android **tidak pernah memuat atau menjalankan** kodenya, bahkan jika APK dipasang.
- Semua alamat dan token **palsu**, ditandai `SIMULATION`. Bot Telegram, webhook Discord, dan server IP tidak nyata.
- Kode ada hanya supaya analisis statis PackPeek punya sesuatu untuk ditemukan. Sampel ini tidak mengirim apa pun dan tidak dapat berjalan.

Jangan pakai sampel ini untuk hal lain selain menguji PackPeek.

## Membangun

```bash
export ANDROID_HOME=/path/to/sdk
./build-all.sh          # menjalankan generate.py, lalu membangun dist/<nama>.apk
```

`generate.py` menulis sumbernya ke `samples/`. Tambah modus baru dengan menambah satu entri `sample(...)` di `generate.py`.

## Daftar modus dan yang seharusnya dideteksi

| APK | Meniru | Deteksi yang diharapkan |
|---|---|---|
| `otp-telegram` | Pencurian OTP modus "undangan": SMS diteruskan ke Service lewat Intent, dikirim ke bot Telegram (Base64) | **Indikator kuat** Pencurian OTP (T3), tujuan tersembunyi, ikon tersembunyi |
| `notif-webhook` | Pencuri OTP lewat pembaca notifikasi → webhook Discord (XOR) | Pembaca notifikasi, tujuan tersembunyi |
| `bank-overlay` | Overlay palsu di atas aplikasi bank + aksesibilitas membaca layar | **Indikator kuat** Pengintaian layar (T3), overlay |
| `clipboard-swap` | Penukar alamat kripto di clipboard, isi clipboard dikirim keluar | Membaca clipboard, tujuan tersembunyi |
| `spyware-exfil` | Spyware: kontak, log panggilan, lokasi, mikrofon → server IP | **Indikator kuat** Data dikirim ke tujuan tersembunyi (IP publik di kelas pengirim, berkas tidak tepercaya) |
| `call-forward` | Pencurian lewat pengalihan panggilan (USSD `**21*`) + SMS | Kode pengalihan panggilan, Pencurian OTP |
| `dropper` | Dropper: DEX tersembunyi di aset, dimuat dinamis, perintah shell | Memuat kode tambahan, kode tersembunyi |
| `device-admin` | Admin perangkat mengunci/menghapus + ikon tersembunyi (modus ransomware) | Admin perangkat, ikon tersembunyi |
| `control-adware` (pembanding) | Aplikasi gratis biasa dengan SDK iklan/analitik, tanpa pencurian | **Bukan** indikator kuat; SDK iklan/analitik sebagai informasi |
| `control-clean` (pembanding) | Aplikasi catatan biasa yang hanya menghubungi server HTTPS sendiri | **Bukan** indikator kuat; tanpa tujuan tersembunyi |

Setiap baris di atas dikunci oleh `ScamSamplesTest` (JVM), jadi perubahan pada mesin analisis yang memecah salah satu deteksi langsung ketahuan.

## Cara menguji di HP

1. Bangun APK (atau unduh dari pengembang).
2. Pasang PackPeek, buka, ketuk **Periksa berkas**, pilih salah satu `dist/*.apk`. **Jangan pasang sampelnya.**
3. Gulir ke bawah, ketuk **Analisis mendalam**, lalu periksa jejak data, teks yang disamarkan, dan kode tersembunyi.
4. Pada dua pembanding (`control-*`), seharusnya **tidak** muncul indikator kuat. Kalau muncul, itu salah tuduh yang perlu dilaporkan.
