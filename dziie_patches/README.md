# Panduan Inject/Merge Patch ke Aperture Camera Base Baru

Folder ini berisi backup commit-commit custom dari branch sebelumnya (`dziie-id`) yang bisa di-inject ke base Aperture Camera yang baru (misalnya base 23.2).

## Apa Saja Isinya?

1. **`0001` - `0019` (`.patch` file):**
   Ini adalah patch terpisah untuk setiap commit yang pernah Anda buat (dari mulai commit `sapphire/n` hingga `Fix zoom dan eksposur`). Cocok digunakan jika Anda ingin memasukkan perubahan satu per satu dan mempertahankan riwayat commit-nya.

2. **`master_all_changes.diff`:**
   Ini adalah file master yang berisi **semua** perubahan dari commit-commit di atas yang digabung jadi satu. Cocok jika Anda ingin meng-apply semuanya sekaligus dalam satu kali perintah tanpa mempedulikan riwayat commit satuan.

3. **`aperture.jks`:**
   Ini adalah file keystore untuk signing aplikasi. Jangan lupa di-copy ke folder `app/` di base yang baru agar signature APK tetap sama.

---

## Cara Menggunakan Patch (Pilih Salah Satu Cara)

### Cara 1: Menggunakan Master Diff (Paling Cepat, Gabungan Semua Perubahan)
Gunakan cara ini jika Anda hanya ingin semua perubahannya masuk, tidak perlu riwayat commit satuan.

1. Buka terminal di folder project base Aperture yang baru.
2. Jalankan perintah berikut:
   ```bash
   git apply /path/ke/folder/dziie_patches/master_all_changes.diff
   ```
3. Jika berhasil, semua file yang terpengaruh akan berubah (modified). Anda tinggal melakukan `git commit` dengan pesan baru.

### Cara 2: Menggunakan File `.patch` Terpisah (Mempertahankan Riwayat Commit)
Gunakan cara ini jika Anda ingin setiap perubahan masuk sebagai commit terpisah, lengkap dengan pesan commit aslinya (misal "Fix zoom dan eksposur", dll).

1. Buka terminal di folder project base Aperture yang baru.
2. Jalankan perintah berikut untuk meng-apply semua patch sekaligus secara berurutan:
   ```bash
   git am /path/ke/folder/dziie_patches/*.patch
   ```
3. Jika ada *conflict* (bentrok kode karena base 23.2 sudah banyak berubah), proses `git am` akan berhenti.
   - Buka file yang conflict (biasanya ditandai di IDE Anda), lalu perbaiki secara manual.
   - Setelah diperbaiki, jalankan: `git add .` lalu `git am --continue`

---

## Jangan Lupa Keystore!

Copy file `aperture.jks` dari folder ini dan letakkan di dalam folder `app/` pada project base Anda yang baru.

```bash
cp /path/ke/folder/dziie_patches/aperture.jks app/aperture.jks
```

## Konfigurasi Keystore

Jika Anda perlu melakukan signing manual (atau mengecek konfigurasinya), berikut adalah rincian password yang ada di `app/build.gradle.kts`:

- **Store Password:** `android`
- **Key Alias:** `androiddebugkey`
- **Key Password:** `android`
