# Penghitung Rakaat (Android, offline)

Hitung rakaat sholat lewat kamera depan. Tanpa internet, tanpa iklan, tanpa akun.

## Cara menjalankan
1. Buka folder ini di Android Studio (Ladybug atau lebih baru), tunggu Gradle sync.
2. Hubungkan HP Android (min. Android 8.0) dengan USB debugging, lalu Run.
3. Izinkan kamera dan lokasi (lokasi hanya untuk menghitung waktu sholat secara offline).

## Cara kerja singkat
- Tekan Mulai, letakkan HP, lalu berdiri diam sekitar 2 detik. HP bergetar singkat tanda siap.
  Jika 15 detik belum terdeteksi, HP bergetar tiga kali sebagai peringatan.
- Satu rakaat = 3 kali membungkuk (ruku, sujud, sujud). Duduk tegak lama (>= 8 detik)
  dipakai untuk menyelaraskan ulang hitungan jika ada gerakan yang meleset.
- Fardhu berhenti otomatis di target rakaat. Sunat berjalan sampai diketuk dua kali.

## Menyetel akurasi
Aktifkan "Mode uji" di layar awal untuk melihat tilt, dasar, dev, dan jumlah bungkukan.
Ambang ada di `RakaatEngine.kt` (companion object): ON_DEG, OFF_DEG, MIN_EPISODE_MS,
LOST_MS, UPRIGHT_SNAP_MS.

## Catatan
- Kode ini belum pernah dikompilasi atau diuji di perangkat. Versi pustaka di Gradle mungkin
  perlu disesuaikan oleh Android Studio.
- Waktu sholat memakai metode SINGAPORE (Subuh 20 derajat, Isya 18 derajat) sebagai pendekatan
  untuk Indonesia. Hari Jumat tersedia pilihan "Jumat" (2 rakaat).
- Suara tasbih memakai Text-to-Speech bawaan HP (bahasa Arab bila tersedia).
