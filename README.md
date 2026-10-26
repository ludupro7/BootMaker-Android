# BootMakar

A port of Amlogic BootcardMaker (V1.0.1-130918_Beta) for rooted
Android devices. Display name **BootMakar**, package
**`id.bootmaker.cok`**, version 0.1.0 (versionCode 1).

BootMakar makes an SD card bootable on Amlogic SoCs: it writes the
u-boot bootloader into the card's reserved area and formats one FAT32
partition (label `BOOT` by default) starting at 4 MiB — the layout the
Amlogic ROM bootloader expects when booting from SD.

> **ROOT-ONLY.** BootMakar requires a rooted device. On startup it
> runs `su -c id` and requires uid 0. Without root, every write path
> is disabled and the app says so plainly — there is no silent
> fallback and no pretend mode. This is deliberate: writing directly
> to a physical SD card's block device is impossible for an
> unprivileged Android app.

## Modes

- **Direct SD card (primary, default).** Writes the partition,
  u-boot and FAT format straight to the block device
  (e.g. `/dev/mmcblk1`) through the root shell. Only devices whose
  `/sys/block/.../removable` flag is `1` are offered; internal
  storage and system-mounted devices are refused outright. All
  mounted partitions of the target are unmounted first; any unmount
  failure aborts before writing.
- **Image file (secondary, rooted users only).** Builds a complete
  bootcard `.img` at a chosen size (512M / 1G / 2G / 4G) and saves it
  through the Storage Access Framework. The `.img` is then written to
  a card with Rufus, balenaEtcher or `dd`. This mode exists for
  preparing or sharing images — it is **not** a no-root path: the app
  as a whole still requires root.

## UI (replicates the original)

Title "BootMakar", "Choose disk" dropdown, u-boot `.bin` picker
(SAF document picker, original filter `*.bin`), the
"To Partition and Format SD-card:" section, a label field (default
`BOOT`), "Make" / "Cancel" buttons, an "About BootMakar" dialog, and
a log panel that uses the original's messages ("start to format the
disk!", "format canceled!", "fatal error!Cannot format SD-card!").
Language picker (English / Bahasa Indonesia, default English) and
theme spinner (system / light / dark) follow the UBT-project pattern.
Landscape orientation, phone + leanback launchers, fullscreen.

## Card layout (ground truth)

Sector = 512 bytes. This table is implemented identically in the
Rust native core (`native/src/lib.rs`) and the pure-Java
`CardLayout` (used by the direct path and the test harness).

| Region | Contents |
|---|---|
| Sector 0 | First 446 bytes of the u-boot file, then the 16-byte partition entry at offset 446, then `0x55AA` at offset 510 |
| Sectors 1..8191 | Rest of the u-boot file (file offset 512 onward) |
| Sector 8192 (4 MiB) onward | One FAT32 partition, label `BOOT` |

Partition entry (16 bytes, little-endian): status `0x80`
(bootable), dummy CHS `FE FF FF`, type `0x0B` (FAT32 LBA), dummy
CHS `FE FF FF`, LBA start `8192`, sector count
`totalSectors - 8192`. Example pinned by the test: for
`totalSectors = 1,000,000` the count is `991,808 = 0xF2240`, so the
entry is `80 FE FF FF 0B FE FF FF 00 20 00 00 40 22 0F 00`.

FAT32 format (Android has no `mkfs.vfat`, so this is a minimal
built-in formatter): boot sector with `BOOTCARD` OEM name, 512
bytes/sector, 32 reserved sectors, 2 FATs, media `0xF8`, hidden
sectors 8192, root cluster 2, FSInfo sector 1, backup boot sector 6,
backup FSInfo sector 7, `FAT32   ` signature, volume label field,
fixed volume serial `0x20261026`. Sectors-per-cluster is the largest
power of two (up to 128) that still leaves at least 65525 clusters
(e.g. a 512M image: spc 8, 1014 FAT sectors, 129790 clusters). FAT[0]
= `0x0FFFFFF8`, FAT[1] = `0x0FFFFFFF`, FAT[2] = `0x0FFFFFFF` (root
directory end-of-chain); the root directory holds a volume-label
entry and is otherwise empty.

## Safety (fail-closed everywhere)

- Direct writes require typing the **exact device name** in a
  confirmation dialog, plus a warning that all content on the target
  will be erased. Image writes get a warning dialog first (the SAF
  save picker is a second confirmation).
- Validation before any write: u-boot must be >= 512 bytes and
  <= 4 MiB; target/image must be > 4 MiB; the FAT32 geometry must
  yield >= 65525 clusters. Any violation aborts with a clear message
  and nothing is written.
- Cancel (Stop) halts the job between chunks; a cancelled target is
  left incomplete and must be rewritten fully before use.
- Every write is followed by verification: MBR signature, partition
  entry, u-boot head and remainder byte-for-byte, FAT signature and
  label. A failed verification rejects the card/image.

## How it is built

- Java app (programmatic UI, no XML layouts) + Rust native core
  (`libbootcard.so`, crate `bootcard`), same pattern as the
  UBT-Android project. JNI names use the real package:
  `Java_id_bootmaker_cok_NativeCore_*`.
- minSdk 28, targetSdk 35, compileSdk 35, AGP 8.7.3, Gradle 8.9,
  Java 17, full Gradle wrapper included. The app's `preBuild` hook
  runs `cargo ndk -t armeabi-v7a -t arm64-v8a -o
  app/src/main/jniLibs build --release`.
- The repository already contains `.github/workflows/build-apk.yml`
  (adapted UBT workflow, including the setup-android fix: it pins
  `packages: platform-tools` because Google removed the legacy
  `tools` package). Push the project contents to a repo root and the
  workflow builds `app-debug` automatically.
- Local build (needs Android SDK + NDK + Rust + cargo-ndk):
  `cargo ndk -t armeabi-v7a -t arm64-v8a -o app/src/main/jniLibs build --release`
  then `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Launcher icon: adaptive icon, background `@color/bootmakar_launcher_background`,
  foreground `drawable-xxxhdpi/ic_launcher_foreground.png` (432x432,
  bold "BM" letters rendered with Pillow + DejaVu Bold; regenerate
  with `python3 tools/make_images.py`, which also renders the
  1280x720 leanback banner with the full name "BootMakar").

## Source map

- `app/src/main/java/id/bootmaker/cok/MainActivity.java` — UI
- `app/src/main/java/id/bootmaker/cok/CardEngine.java` — job
  orchestration (validate → write → format → verify, cancel)
- `app/src/main/java/id/bootmaker/cok/CardService.java` — foreground
  service (dataSync) + partial wakelock + broadcasts
- `app/src/main/java/id/bootmaker/cok/RootShell.java` — `su`
  detection, `/sys/block` listing, unmount, dd streaming
- `app/src/main/java/id/bootmaker/cok/CardLayout.java` — pure-Java
  layout (MBR + FAT32), no Android imports
- `app/src/main/java/id/bootmaker/cok/NativeCore.java` — JNI bridge
- `app/src/main/java/id/bootmaker/cok/SafIo.java` — SAF helpers
- `native/src/lib.rs` — Rust core: validateUboot, buildMbr,
  writeBootcard, verifyBootcard (+ unit tests)
- `tests/BootcardLayoutTest.java` — standalone JDK test harness:
  `javac -d /tmp/bootcard-test
  app/src/main/java/id/bootmaker/cok/CardLayout.java
  tests/BootcardLayoutTest.java && java -cp /tmp/bootcard-test
  id.bootmaker.cok.BootcardLayoutTest`

## Known gaps (honest)

- **NOT hardware-tested.** No write has ever run on a device from
  this app; direct mode in particular needs a real rooted phone +
  SD card test. Test order: scan → list disks → write an image first
  → verify → only then a direct write to a spare card.
- **Rust was not compiled in this VM** (no cargo/rustc here); the
  GitHub Actions workflow builds it. Rust unit tests in `lib.rs`
  cover the same bytes the Java harness pins.
- **FAT32 only.** The Python port also offered FAT16; this port does
  not (the original tool's standard card is FAT32).
- **u-boot validation is size-only.** Amlogic u-boot files carry no
  universal magic, so a wrong-but-plausible-size file passes the
  gate; the byte-for-byte verify after writing is the real check.
- **Fixed volume serial** (`0x20261026`), not random per card.
- **Image-mode `set_len`** depends on the SAF provider supporting
  truncate/extend; a provider that refuses it fails the job with a
  clear error instead of producing a short image.
- **Root shell behavior varies** by su implementation
  (Magisk/KernelSU) and by Android version: `su` prompts, mount
  namespaces, and vold re-mounting can interfere with the unmount
  step; any such failure aborts the job rather than writing anyway.
- The `su` binary itself is never bundled or installed by this app;
  the device must already be rooted.
