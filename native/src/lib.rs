//! Native core for BootMakar (package `id.bootmaker.cok`).
//!
//! Implements the Amlogic SD bootcard layout, mirroring
//! `aml-bootcard.py` (derived from a Ghidra decompile of the original
//! BootcardMaker) and the pure-Java `CardLayout` class. Both sides
//! derive from the same layout table; see the README for the full
//! table.
//!
//! Layout (sector = 512 bytes):
//! * Sector 0: first 446 bytes of the u-boot file, then the 16-byte
//!   partition entry at offset 446, then 0x55AA at offset 510.
//! * Sectors 1..8191: the rest of the u-boot file (file offset 512
//!   onward). The u-boot file is at most 4 MiB, so it always fits.
//! * Sector 8192 (4 MiB) onward: one FAT32 partition, label BOOT by
//!   default, written by the minimal FAT32 formatter below (Android
//!   has no mkfs.vfat).
//!
//! File descriptors follow the UBT pattern: Java detaches a
//! ParcelFileDescriptor and Rust takes ownership (the File closes it
//! on return). Every call therefore needs freshly opened descriptors.

use jni::objects::JClass;
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

const SECTOR: usize = 512;
const SECTOR_U64: u64 = 512;
const PART_START_SECTOR: u64 = 8192;
const PART_TYPE_FAT32: u8 = 0x0B;
const MIN_UBOOT: u64 = 512;
const MAX_UBOOT: u64 = 4 * 1024 * 1024;
const VOLUME_ID: u32 = 0x2026_1026;
const FAT32_MIN_CLUSTERS: u64 = 65525;
const FAT32_MAX_CLUSTERS: u64 = 268435445;
const FAT_RESERVED_SECTORS: u64 = 32;
const FAT_COUNT: u64 = 2;
const FAT_ROOT_CLUSTER: u32 = 2;
const CHUNK_LEN: usize = 64 * 1024;

fn put_u16(buf: &mut [u8], off: usize, value: u16) {
    buf[off..off + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(buf: &mut [u8], off: usize, value: u32) {
    buf[off..off + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_u64_as_u32(buf: &mut [u8], off: usize, value: u64) {
    buf[off..off + 4].copy_from_slice(&(value as u32).to_le_bytes());
}

fn get_u32(buf: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(buf[off..off + 4].try_into().unwrap())
}

/// Normalizes a FAT volume label: upper case, at most 11 chars.
fn normalize_label(label: &str) -> String {
    let clean = label.trim().to_uppercase();
    if clean.is_empty() {
        return "BOOT".to_string();
    }
    clean.chars().take(11).collect()
}

fn label_field(label: &str) -> [u8; 11] {
    let mut field = [b' '; 11];
    let norm = normalize_label(label);
    let raw = norm.as_bytes();
    field[..raw.len()].copy_from_slice(raw);
    field
}

/// Builds the 512-byte MBR (head bytes zero; the caller overlays the
/// u-boot head). Returns an error when the target is not larger than
/// the 4 MiB reserved area.
fn build_mbr(total_sectors: u64) -> Result<[u8; SECTOR], String> {
    let part_sectors = total_sectors
        .checked_sub(PART_START_SECTOR)
        .filter(|v| *v > 0)
        .ok_or_else(|| "Target too small (must be larger than 4 MiB).".to_string())?;
    let mut mbr = [0u8; SECTOR];
    let off = 446;
    mbr[off] = 0x80;
    mbr[off + 1] = 0xFE;
    mbr[off + 2] = 0xFF;
    mbr[off + 3] = 0xFF;
    mbr[off + 4] = PART_TYPE_FAT32;
    mbr[off + 5] = 0xFE;
    mbr[off + 6] = 0xFF;
    mbr[off + 7] = 0xFF;
    put_u64_as_u32(&mut mbr, off + 8, PART_START_SECTOR);
    put_u64_as_u32(&mut mbr, off + 12, part_sectors);
    mbr[510] = 0x55;
    mbr[511] = 0xAA;
    Ok(mbr)
}

struct FatGeometry {
    sectors_per_cluster: u64,
    fat_sectors: u64,
    clusters: u64,
    data_start_sector: u64,
}

/// Solves the FAT32 geometry: the largest power-of-two
/// sectors-per-cluster (up to 128) that still leaves at least 65525
/// clusters. Mirrors `CardLayout.fatGeometry` exactly.
fn fat_geometry(part_sectors: u64) -> Result<FatGeometry, String> {
    if part_sectors == 0 {
        return Err("Partition size must be positive.".to_string());
    }
    for spc in [128u64, 64, 32, 16, 8, 4, 2, 1] {
        let mut fat_sectors: u64 = 0;
        let mut clusters: i64 = -1;
        for _ in 0..50 {
            let avail = part_sectors as i64
                - FAT_RESERVED_SECTORS as i64
                - (FAT_COUNT as i64) * (fat_sectors as i64);
            if avail < 0 {
                clusters = -1;
                break;
            }
            clusters = avail / (spc as i64);
            let need = (clusters as u64 + 2) * 4;
            let next = need.div_ceil(SECTOR_U64);
            if next == fat_sectors {
                break;
            }
            fat_sectors = next;
        }
        if clusters >= FAT32_MIN_CLUSTERS as i64 && clusters as u64 <= FAT32_MAX_CLUSTERS {
            return Ok(FatGeometry {
                sectors_per_cluster: spc,
                fat_sectors,
                clusters: clusters as u64,
                data_start_sector: FAT_RESERVED_SECTORS + FAT_COUNT * fat_sectors,
            });
        }
    }
    Err("Partition too small for FAT32 (needs at least 65525 clusters).".to_string())
}

fn build_boot_sector(part_sectors: u64, label: &str, g: &FatGeometry) -> [u8; SECTOR] {
    let mut bs = [0u8; SECTOR];
    bs[0] = 0xEB;
    bs[1] = 0x58;
    bs[2] = 0x90;
    bs[3..11].copy_from_slice(b"BOOTCARD");
    put_u16(&mut bs, 11, SECTOR as u16);
    bs[13] = g.sectors_per_cluster as u8;
    put_u16(&mut bs, 14, FAT_RESERVED_SECTORS as u16);
    bs[16] = FAT_COUNT as u8;
    put_u16(&mut bs, 17, 0);
    put_u16(&mut bs, 19, 0);
    bs[21] = 0xF8;
    put_u16(&mut bs, 22, 0);
    put_u16(&mut bs, 24, 63);
    put_u16(&mut bs, 26, 255);
    put_u64_as_u32(&mut bs, 28, PART_START_SECTOR);
    put_u64_as_u32(&mut bs, 32, part_sectors);
    put_u64_as_u32(&mut bs, 36, g.fat_sectors);
    put_u16(&mut bs, 40, 0);
    put_u16(&mut bs, 42, 0);
    put_u32(&mut bs, 44, FAT_ROOT_CLUSTER);
    put_u16(&mut bs, 48, 1);
    put_u16(&mut bs, 50, 6);
    bs[64] = 0x80;
    bs[65] = 0;
    bs[66] = 0x29;
    put_u32(&mut bs, 67, VOLUME_ID);
    bs[71..82].copy_from_slice(&label_field(label));
    bs[82..90].copy_from_slice(b"FAT32   ");
    bs[510] = 0x55;
    bs[511] = 0xAA;
    bs
}

fn build_fsinfo(free_clusters: u64) -> [u8; SECTOR] {
    let mut fs = [0u8; SECTOR];
    put_u32(&mut fs, 0, 0x4161_5252);
    put_u32(&mut fs, 484, 0x6141_7272);
    put_u64_as_u32(&mut fs, 488, free_clusters);
    put_u64_as_u32(&mut fs, 492, 3);
    put_u32(&mut fs, 508, 0xAA55_0000);
    fs
}

fn build_fat_sector(index: u64) -> [u8; SECTOR] {
    let mut fat = [0u8; SECTOR];
    if index == 0 {
        put_u32(&mut fat, 0, 0x0FFF_FFF8);
        put_u32(&mut fat, 4, 0x0FFF_FFFF);
        put_u32(&mut fat, 8, 0x0FFF_FFFF);
    }
    fat
}

fn build_root_dir_sector(label: &str) -> [u8; SECTOR] {
    let mut dir = [0u8; SECTOR];
    dir[0..11].copy_from_slice(&label_field(label));
    dir[11] = 0x08;
    dir
}

fn write_zeros(file: &mut File, sectors: u64) -> std::io::Result<()> {
    let zeros = [0u8; CHUNK_LEN];
    let mut left = sectors * SECTOR_U64;
    while left > 0 {
        let n = std::cmp::min(left, CHUNK_LEN as u64) as usize;
        file.write_all(&zeros[..n])?;
        left -= n as u64;
    }
    Ok(())
}

fn read_uboot(mut file: File) -> Result<Vec<u8>, String> {
    let mut buf = Vec::new();
    file.take(MAX_UBOOT + 1)
        .read_to_end(&mut buf)
        .map_err(|e| format!("could not read u-boot file ({e})"))?;
    if (buf.len() as u64) < MIN_UBOOT {
        return Err("u-boot file too small (< 512 bytes) — not a whole u-boot.".to_string());
    }
    if (buf.len() as u64) > MAX_UBOOT {
        return Err("u-boot file larger than the 4 MiB reserved area — does not fit.".to_string());
    }
    Ok(buf)
}

fn validate_uboot(fd: jint, reported_size: jlong) -> String {
    if fd < 0 {
        return "INVALID: no readable file descriptor".to_string();
    }
    // Ownership transfers from ParcelFileDescriptor.detachFd().
    let file = unsafe { File::from_raw_fd(fd) };
    match read_uboot(file) {
        Ok(uboot) => {
            if reported_size > 0 && reported_size as u64 != uboot.len() as u64 {
                return format!(
                    "INVALID: provider reports {reported_size} bytes but read {}",
                    uboot.len()
                );
            }
            format!("VALID_UBOOT: {} bytes", uboot.len())
        }
        Err(err) => format!("INVALID: {err}"),
    }
}

fn write_bootcard(uboot_fd: jint, out_fd: jint, total_sectors: u64, label: &str) -> String {
    if uboot_fd < 0 || out_fd < 0 {
        return "ERROR: no readable/writable file descriptor".to_string();
    }
    let uboot_file = unsafe { File::from_raw_fd(uboot_fd) };
    let mut out = unsafe { File::from_raw_fd(out_fd) };
    let uboot = match read_uboot(uboot_file) {
        Ok(v) => v,
        Err(e) => return format!("ERROR: {e}"),
    };
    let part_sectors = match total_sectors.checked_sub(PART_START_SECTOR) {
        Some(v) if v > 0 => v,
        _ => return "ERROR: Target too small (must be larger than 4 MiB).".to_string(),
    };
    let geometry = match fat_geometry(part_sectors) {
        Ok(g) => g,
        Err(e) => return format!("ERROR: {e}"),
    };
    let label = normalize_label(label);
    let mbr = match build_mbr(total_sectors) {
        Ok(m) => m,
        Err(e) => return format!("ERROR: {e}"),
    };
    let mut sector0 = mbr;
    sector0[..446].copy_from_slice(&uboot[..446]);
    let result: Result<(), String> = (|| {
        out.seek(SeekFrom::Start(0))
            .and_then(|_| out.write_all(&sector0))
            .map_err(|e| format!("write sector 0 failed ({e})"))?;
        if uboot.len() > SECTOR {
            out.seek(SeekFrom::Start(SECTOR_U64))
                .and_then(|_| out.write_all(&uboot[SECTOR..]))
                .map_err(|e| format!("write u-boot remainder failed ({e})"))?;
        }
        let boot = build_boot_sector(part_sectors, &label, &geometry);
        let fsinfo = build_fsinfo(geometry.clusters - 1);
        let mut reserved = vec![0u8; (FAT_RESERVED_SECTORS as usize) * SECTOR];
        reserved[..SECTOR].copy_from_slice(&boot);
        reserved[SECTOR..2 * SECTOR].copy_from_slice(&fsinfo);
        reserved[6 * SECTOR..7 * SECTOR].copy_from_slice(&boot);
        reserved[7 * SECTOR..8 * SECTOR].copy_from_slice(&fsinfo);
        out.seek(SeekFrom::Start(PART_START_SECTOR * SECTOR_U64))
            .and_then(|_| out.write_all(&reserved))
            .map_err(|e| format!("write FAT reserved area failed ({e})"))?;
        for copy in 0..FAT_COUNT {
            let fat_base = (PART_START_SECTOR + FAT_RESERVED_SECTORS + copy * geometry.fat_sectors)
                * SECTOR_U64;
            out.seek(SeekFrom::Start(fat_base))
                .and_then(|_| out.write_all(&build_fat_sector(0)))
                .map_err(|e| format!("write FAT failed ({e})"))?;
            write_zeros(&mut out, geometry.fat_sectors - 1)
                .map_err(|e| format!("write FAT zeros failed ({e})"))?;
        }
        let data_start =
            (PART_START_SECTOR + geometry.data_start_sector) * SECTOR_U64;
        out.seek(SeekFrom::Start(data_start))
            .and_then(|_| out.write_all(&build_root_dir_sector(&label)))
            .map_err(|e| format!("write root directory failed ({e})"))?;
        write_zeros(&mut out, geometry.sectors_per_cluster - 1)
            .map_err(|e| format!("write root directory zeros failed ({e})"))?;
        out.set_len(total_sectors * SECTOR_U64)
            .map_err(|e| format!("cannot size output to target ({e})"))?;
        let _ = out.sync_all();
        Ok(())
    })();
    match result {
        Ok(()) => format!(
            "OK: wrote {} bytes u-boot + FAT32 (label {}, {} sectors, {} clusters)",
            uboot.len(),
            label,
            total_sectors,
            geometry.clusters
        ),
        Err(e) => format!("ERROR: {e}"),
    }
}

fn verify_bootcard(fd: jint, uboot_fd: jint, label: &str) -> String {
    if fd < 0 || uboot_fd < 0 {
        return "FAIL: no readable file descriptor".to_string();
    }
    let mut file = unsafe { File::from_raw_fd(fd) };
    let mut uboot = unsafe { File::from_raw_fd(uboot_fd) };
    let fail = |msg: String| format!("FAIL: {msg}");
    let mut sector0 = [0u8; SECTOR];
    if file
        .seek(SeekFrom::Start(0))
        .and_then(|_| file.read_exact(&mut sector0))
        .is_err()
    {
        return fail("could not read sector 0".to_string());
    }
    if sector0[510] != 0x55 || sector0[511] != 0xAA {
        return fail("MBR signature (0x55AA) missing.".to_string());
    }
    let lba = get_u32(&sector0, 454);
    let ptype = sector0[450];
    if lba as u64 != PART_START_SECTOR {
        return fail(format!("Partition starts at sector {lba}, expected 8192."));
    }
    if ptype != PART_TYPE_FAT32 {
        return fail(format!("Partition type 0x{ptype:02x}, expected 0x0b (FAT32)."));
    }
    let mut head_card = [0u8; 446];
    let mut head_uboot = [0u8; 446];
    if file
        .seek(SeekFrom::Start(0))
        .and_then(|_| file.read_exact(&mut head_card))
        .is_err()
        || uboot
            .seek(SeekFrom::Start(0))
            .and_then(|_| uboot.read_exact(&mut head_uboot))
            .is_err()
    {
        return fail("could not read u-boot head for comparison".to_string());
    }
    if head_card != head_uboot {
        return fail("First 446 bytes of sector 0 do not match the u-boot file.".to_string());
    }
    if uboot.seek(SeekFrom::Start(SECTOR_U64)).is_err()
        || file.seek(SeekFrom::Start(SECTOR_U64)).is_err()
    {
        return fail("could not seek to u-boot remainder".to_string());
    }
    let mut buf_card = [0u8; CHUNK_LEN];
    let mut buf_uboot = [0u8; CHUNK_LEN];
    let mut rest_len: u64 = 0;
    loop {
        let n = match uboot.read(&mut buf_uboot) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) => return fail(format!("could not read u-boot remainder ({e})")),
        };
        rest_len += n as u64;
        match file.read_exact(&mut buf_card[..n]) {
            Ok(()) => {}
            Err(e) => return fail(format!("could not read card remainder ({e})")),
        }
        if buf_card[..n] != buf_uboot[..n] {
            return fail("u-boot content from sector 1 does not match.".to_string());
        }
    }
    let mut fat_boot = [0u8; SECTOR];
    if file
        .seek(SeekFrom::Start(PART_START_SECTOR * SECTOR_U64))
        .and_then(|_| file.read_exact(&mut fat_boot))
        .is_err()
    {
        return fail("could not read FAT boot sector".to_string());
    }
    if fat_boot[510] != 0x55 || fat_boot[511] != 0xAA {
        return fail("FAT boot sector signature missing.".to_string());
    }
    if &fat_boot[82..90] != b"FAT32   " {
        return fail("FAT32 signature not found at partition start.".to_string());
    }
    let found_label = String::from_utf8_lossy(&fat_boot[71..82]).trim().to_string();
    let want_label = normalize_label(label);
    if found_label != want_label {
        return fail(format!("FAT label is '{found_label}', expected '{want_label}'."));
    }
    format!(
        "PASS: MBR valid, u-boot matches ({} bytes + {} remainder), FAT32 label {}.",
        rest_len + SECTOR_U64,
        rest_len,
        want_label
    )
}

fn new_jstring(env: &JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_bootmaker_cok_NativeCore_validateUboot(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    size: jlong,
) -> jstring {
    let result =
        catch_unwind(AssertUnwindSafe(|| validate_uboot(fd, size))).unwrap_or_else(|_| {
            "INVALID: native validator encountered an internal error".to_string()
        });
    new_jstring(&env, result)
}

#[no_mangle]
pub extern "system" fn Java_id_bootmaker_cok_NativeCore_buildMbr(
    env: JNIEnv,
    _class: JClass,
    total_sectors: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| build_mbr(total_sectors as u64)));
    match result {
        Ok(Ok(mbr)) => match env.byte_array_from_slice(&mbr) {
            Ok(value) => **value,
            Err(_) => ptr::null_mut(),
        },
        _ => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_bootmaker_cok_NativeCore_writeBootcard(
    mut env: JNIEnv,
    _class: JClass,
    uboot_fd: jint,
    out_fd: jint,
    total_sectors: jlong,
    label: jni::objects::JString,
) -> jstring {
    let label_text = env
        .get_string(&label)
        .map(|s| s.into())
        .unwrap_or_else(|_| "BOOT".to_string());
    let result = catch_unwind(AssertUnwindSafe(|| {
        write_bootcard(uboot_fd, out_fd, total_sectors as u64, &label_text)
    }))
    .unwrap_or_else(|_| "ERROR: native writer encountered an internal error".to_string());
    new_jstring(&env, result)
}

#[no_mangle]
pub extern "system" fn Java_id_bootmaker_cok_NativeCore_verifyBootcard(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    uboot_fd: jint,
    label: jni::objects::JString,
) -> jstring {
    let label_text = env
        .get_string(&label)
        .map(|s| s.into())
        .unwrap_or_else(|_| "BOOT".to_string());
    let result = catch_unwind(AssertUnwindSafe(|| {
        verify_bootcard(fd, uboot_fd, &label_text)
    }))
    .unwrap_or_else(|_| "FAIL: native verifier encountered an internal error".to_string());
    new_jstring(&env, result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::os::fd::IntoRawFd;
    use std::path::PathBuf;

    fn temp_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("bootcard-{name}-{}", std::process::id()))
    }

    #[test]
    fn mbr_bytes_for_one_million_sectors() {
        let mbr = build_mbr(1_000_000).expect("valid MBR");
        // Entry: 80 FE FF FF 0B FE FF FF, LBA 8192, count 1,000,000-8192 = 991808 = 0xF2240.
        assert_eq!(
            &mbr[446..462],
            &[
                0x80, 0xFE, 0xFF, 0xFF, 0x0B, 0xFE, 0xFF, 0xFF, 0x00, 0x20, 0x00, 0x00, 0x40,
                0x22, 0x0F, 0x00
            ]
        );
        assert_eq!(&mbr[510..512], &[0x55, 0xAA]);
        assert!(mbr[..446].iter().all(|b| *b == 0));
    }

    #[test]
    fn mbr_rejects_undersized_target() {
        assert!(build_mbr(8192).is_err());
        assert!(build_mbr(8193).is_ok());
    }

    #[test]
    fn fat_geometry_for_512m_image() {
        let part = 1_048_576 - 8192;
        let g = fat_geometry(part).expect("valid geometry");
        assert_eq!(g.sectors_per_cluster, 8);
        assert_eq!(g.fat_sectors, 1014);
        assert_eq!(g.clusters, 129790);
        assert!(g.clusters >= FAT32_MIN_CLUSTERS);
    }

    #[test]
    fn boot_sector_fields() {
        let g = fat_geometry(1_048_576 - 8192).expect("geometry");
        let bs = build_boot_sector(1_048_576 - 8192, "BOOT", &g);
        assert_eq!(&bs[0..3], &[0xEB, 0x58, 0x90]);
        assert_eq!(&bs[3..11], b"BOOTCARD");
        assert_eq!(get_u32(&bs, 28), PART_START_SECTOR as u32);
        assert_eq!(&bs[82..90], b"FAT32   ");
        assert_eq!(&bs[71..82], b"BOOT       ");
        assert_eq!(&bs[510..512], &[0x55, 0xAA]);
    }

    #[test]
    fn fat_sector_zero_has_markers_and_rest_is_zero() {
        let first = build_fat_sector(0);
        assert_eq!(get_u32(&first, 0), 0x0FFF_FFF8);
        assert_eq!(get_u32(&first, 4), 0x0FFF_FFFF);
        assert_eq!(get_u32(&first, 8), 0x0FFF_FFFF);
        assert!(build_fat_sector(1).iter().all(|b| *b == 0));
    }

    #[test]
    fn fsinfo_signatures() {
        let fs = build_fsinfo(129789);
        assert_eq!(get_u32(&fs, 0), 0x4161_5252);
        assert_eq!(get_u32(&fs, 484), 0x6141_7272);
        assert_eq!(get_u32(&fs, 508), 0xAA55_0000);
    }

    #[test]
    fn label_normalization() {
        assert_eq!(normalize_label("boot"), "BOOT");
        assert_eq!(normalize_label(""), "BOOT");
        assert_eq!(normalize_label("averylonglabelname"), "AVERYLONGLA");
    }

    #[test]
    fn write_and_verify_roundtrip() {
        let mut uboot = vec![0u8; 4096];
        for (i, b) in uboot.iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        let uboot_path = temp_path("uboot.bin");
        let image_path = temp_path("card.img");
        File::create(&uboot_path)
            .expect("create uboot")
            .write_all(&uboot)
            .expect("write uboot");
        let total_sectors = 1_048_576u64;
        let uboot_fd = File::open(&uboot_path)
            .expect("open uboot")
            .into_raw_fd();
        let out_fd = File::create(&image_path)
            .expect("create image")
            .into_raw_fd();
        let written = write_bootcard(uboot_fd, out_fd, total_sectors, "BOOT");
        assert!(written.starts_with("OK:"), "{written}");
        let image_len = std::fs::metadata(&image_path).expect("image meta").len();
        assert_eq!(image_len, total_sectors * SECTOR_U64);
        let image_fd = File::open(&image_path)
            .expect("open image")
            .into_raw_fd();
        let uboot_fd2 = File::open(&uboot_path)
            .expect("open uboot again")
            .into_raw_fd();
        let verified = verify_bootcard(image_fd, uboot_fd2, "BOOT");
        assert!(verified.starts_with("PASS:"), "{verified}");
        std::fs::remove_file(uboot_path).ok();
        std::fs::remove_file(image_path).ok();
    }

    #[test]
    fn verify_rejects_tampered_head() {
        let uboot = vec![0xABu8; 1024];
        let uboot_path = temp_path("uboot2.bin");
        let image_path = temp_path("card2.img");
        File::create(&uboot_path)
            .expect("create uboot")
            .write_all(&uboot)
            .expect("write uboot");
        let uboot_fd = File::open(&uboot_path)
            .expect("open uboot")
            .into_raw_fd();
        let out_fd = File::create(&image_path)
            .expect("create image")
            .into_raw_fd();
        let written = write_bootcard(uboot_fd, out_fd, 1_048_576, "BOOT");
        assert!(written.starts_with("OK:"), "{written}");
        // Tamper one head byte in the image.
        let mut image = File::options()
            .read(true)
            .write(true)
            .open(&image_path)
            .expect("open image rw");
        image.seek(SeekFrom::Start(100)).expect("seek");
        image.write_all(&[0x00]).expect("tamper");
        drop(image);
        let image_fd = File::open(&image_path)
            .expect("open image")
            .into_raw_fd();
        let uboot_fd2 = File::open(&uboot_path)
            .expect("open uboot again")
            .into_raw_fd();
        let verified = verify_bootcard(image_fd, uboot_fd2, "BOOT");
        assert!(verified.starts_with("FAIL:"), "{verified}");
        std::fs::remove_file(uboot_path).ok();
        std::fs::remove_file(image_path).ok();
    }
}
