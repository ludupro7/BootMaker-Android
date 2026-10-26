package id.bootmaker.cok;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java implementation of the Amlogic SD bootcard layout, mirroring
 * {@code aml-bootcard.py} (which was derived from a Ghidra decompile of
 * the original BootcardMaker) and the Rust native core in
 * {@code native/src/lib.rs}. Both implementations derive from the same
 * layout table, documented in the README; the Java copy exists so the
 * direct-write path and the standalone test harness can build and check
 * the same bytes without JNI.
 *
 * <p>Layout (sector = 512 bytes):</p>
 * <ul>
 *   <li>Sector 0: first 446 bytes of the u-boot file, then the 16-byte
 *       partition entry at offset 446, then 0x55AA at offset 510.</li>
 *   <li>Sectors 1..8191: the rest of the u-boot file (file offset 512
 *       onward). The u-boot file may be at most 4 MiB, so it always
 *       fits before the partition.</li>
 *   <li>Sector 8192 (4 MiB) onward: one FAT32 partition, label BOOT
 *       by default. The partition is formatted by the minimal FAT32
 *       writer below (no mkfs.vfat on Android).</li>
 * </ul>
 *
 * <p>This class has no Android imports, so it compiles and runs under a
 * plain JDK; see {@code tests/BootcardLayoutTest.java}.</p>
 */
public final class CardLayout {
    /** Bytes per sector. */
    public static final int SECTOR = 512;
    /** First sector of the FAT partition (4 MiB). */
    public static final int PART_START_SECTOR = 8192;
    /** Partition type byte: FAT32 LBA. */
    public static final int PART_TYPE_FAT32 = 0x0B;
    /** Smallest acceptable u-boot file (one sector). */
    public static final int MIN_UBOOT = 512;
    /** Largest acceptable u-boot file (the 4 MiB reserved area). */
    public static final int MAX_UBOOT = 4 * 1024 * 1024;
    /** Default FAT volume label. */
    public static final String DEFAULT_LABEL = "BOOT";
    /** Fixed FAT volume serial number (deterministic, not random). */
    public static final long VOLUME_ID = 0x20261026L;
    /** FAT32 requires at least this many clusters. */
    public static final int FAT32_MIN_CLUSTERS = 65525;
    /** FAT32 cluster-count ceiling. */
    public static final long FAT32_MAX_CLUSTERS = 268435445L;
    /** Reserved sectors at the start of the FAT32 partition. */
    public static final int FAT_RESERVED_SECTORS = 32;
    /** Number of FAT copies. */
    public static final int FAT_COUNT = 2;
    /** Root directory cluster number. */
    public static final int FAT_ROOT_CLUSTER = 2;

    private CardLayout() {
    }

    // ------------------------------------------------------------ MBR

    /**
     * Builds the 512-byte MBR for a target of {@code totalSectors}
     * sectors. Bytes [0:446] are zero here; the caller overwrites them
     * with the u-boot head (see {@link #buildSector0}). The partition
     * entry at 446 is: status 0x80, dummy CHS FE FF FF, type 0x0B,
     * dummy CHS FE FF FF, LBA start 8192, sector count
     * {@code totalSectors - 8192}; bytes 510..512 are 0x55AA.
     *
     * @throws IllegalArgumentException if the target is not larger
     *         than the 4 MiB reserved area.
     */
    public static byte[] buildMbr(long totalSectors) {
        long partSectors = totalSectors - PART_START_SECTOR;
        if (partSectors <= 0) {
            throw new IllegalArgumentException(
                    "Target too small (must be larger than 4 MiB).");
        }
        byte[] mbr = new byte[SECTOR];
        int off = 446;
        mbr[off] = (byte) 0x80;
        mbr[off + 1] = (byte) 0xFE;
        mbr[off + 2] = (byte) 0xFF;
        mbr[off + 3] = (byte) 0xFF;
        mbr[off + 4] = (byte) PART_TYPE_FAT32;
        mbr[off + 5] = (byte) 0xFE;
        mbr[off + 6] = (byte) 0xFF;
        mbr[off + 7] = (byte) 0xFF;
        putU32(mbr, off + 8, PART_START_SECTOR);
        putU32(mbr, off + 12, partSectors);
        mbr[510] = (byte) 0x55;
        mbr[511] = (byte) 0xAA;
        return mbr;
    }

    /** Overlays the first 446 bytes of a u-boot file onto an MBR. */
    public static byte[] buildSector0(byte[] mbr, byte[] head446) {
        if (head446.length != 446) {
            throw new IllegalArgumentException("u-boot head must be 446 bytes.");
        }
        byte[] sector0 = mbr.clone();
        System.arraycopy(head446, 0, sector0, 0, 446);
        return sector0;
    }

    /** Parsed MBR partition entry, for verification. */
    public static final class MbrInfo {
        public final int status;
        public final int type;
        public final long lba;
        public final long count;
        public final boolean hasSignature;

        MbrInfo(int status, int type, long lba, long count, boolean hasSignature) {
            this.status = status;
            this.type = type;
            this.lba = lba;
            this.count = count;
            this.hasSignature = hasSignature;
        }
    }

    /** Parses the partition entry out of a 512-byte sector 0. */
    public static MbrInfo parseMbr(byte[] sector0) {
        if (sector0.length != SECTOR) {
            throw new IllegalArgumentException("sector 0 must be 512 bytes.");
        }
        int status = sector0[446] & 0xFF;
        int type = sector0[450] & 0xFF;
        long lba = getU32(sector0, 454);
        long count = getU32(sector0, 458);
        boolean sig = (sector0[510] & 0xFF) == 0x55 && (sector0[511] & 0xFF) == 0xAA;
        return new MbrInfo(status, type, lba, count, sig);
    }

    // ------------------------------------------------------- validation

    /**
     * Validates a u-boot file size. Returns {@code null} when the size
     * is acceptable, otherwise a human-readable reason.
     */
    public static String validateUbootSize(long size) {
        if (size < MIN_UBOOT) {
            return "u-boot file too small (< 512 bytes) — not a whole u-boot.";
        }
        if (size > MAX_UBOOT) {
            return "u-boot file larger than the 4 MiB reserved area — does not fit.";
        }
        return null;
    }

    /** Normalizes a FAT volume label: upper case, at most 11 chars. */
    public static String normalizeLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            return DEFAULT_LABEL;
        }
        String clean = label.trim().toUpperCase();
        return clean.length() > 11 ? clean.substring(0, 11) : clean;
    }

    // ------------------------------------------------------------- FAT32

    /** Solved FAT32 geometry for one partition. */
    public static final class FatGeometry {
        /** Sectors per cluster (power of two). */
        public final int sectorsPerCluster;
        /** Sectors per FAT table. */
        public final long fatSectors;
        /** Usable clusters. */
        public final long clusters;
        /** First data sector, relative to the partition start. */
        public final long dataStartSector;
        /** Partition size in sectors. */
        public final long partSectors;

        FatGeometry(int spc, long fatSectors, long clusters, long dataStart, long partSectors) {
            this.sectorsPerCluster = spc;
            this.fatSectors = fatSectors;
            this.clusters = clusters;
            this.dataStartSector = dataStart;
            this.partSectors = partSectors;
        }
    }

    /**
     * Solves the FAT32 geometry for a partition of {@code partSectors}
     * sectors. The sectors-per-cluster value is the largest power of
     * two (up to 128) that still leaves at least 65525 clusters, which
     * is what keeps the filesystem a valid FAT32 while keeping the FAT
     * tables small.
     *
     * @throws IllegalArgumentException if no valid geometry exists.
     */
    public static FatGeometry fatGeometry(long partSectors) {
        if (partSectors <= 0) {
            throw new IllegalArgumentException("Partition size must be positive.");
        }
        int[] candidates = {128, 64, 32, 16, 8, 4, 2, 1};
        for (int spc : candidates) {
            long fatSectors = 0;
            long clusters = -1;
            for (int iter = 0; iter < 50; iter++) {
                long avail = partSectors - FAT_RESERVED_SECTORS - FAT_COUNT * fatSectors;
                if (avail < 0) {
                    clusters = -1;
                    break;
                }
                clusters = avail / spc;
                long need = (clusters + 2) * 4L;
                long next = (need + SECTOR - 1) / SECTOR;
                if (next == fatSectors) {
                    break;
                }
                fatSectors = next;
            }
            if (clusters >= FAT32_MIN_CLUSTERS && clusters <= FAT32_MAX_CLUSTERS) {
                long dataStart = FAT_RESERVED_SECTORS + FAT_COUNT * fatSectors;
                return new FatGeometry(spc, fatSectors, clusters, dataStart, partSectors);
            }
        }
        throw new IllegalArgumentException(
                "Partition too small for FAT32 (needs at least 65525 clusters).");
    }

    /** Builds the 512-byte FAT32 boot sector for a partition. */
    public static byte[] buildBootSector(long partSectors, String label, FatGeometry g) {
        byte[] bs = new byte[SECTOR];
        bs[0] = (byte) 0xEB;
        bs[1] = (byte) 0x58;
        bs[2] = (byte) 0x90;
        putAscii(bs, 3, "BOOTCARD", 8);
        putU16(bs, 11, SECTOR);
        bs[13] = (byte) g.sectorsPerCluster;
        putU16(bs, 14, FAT_RESERVED_SECTORS);
        bs[16] = (byte) FAT_COUNT;
        putU16(bs, 17, 0);
        putU16(bs, 19, 0);
        bs[21] = (byte) 0xF8;
        putU16(bs, 22, 0);
        putU16(bs, 24, 63);
        putU16(bs, 26, 255);
        putU32(bs, 28, PART_START_SECTOR);
        putU32(bs, 32, partSectors);
        putU32(bs, 36, g.fatSectors);
        putU16(bs, 40, 0);
        putU16(bs, 42, 0);
        putU32(bs, 44, FAT_ROOT_CLUSTER);
        putU16(bs, 48, 1);
        putU16(bs, 50, 6);
        bs[64] = (byte) 0x80;
        bs[65] = 0;
        bs[66] = (byte) 0x29;
        putU32(bs, 67, VOLUME_ID);
        putAsciiPadded(bs, 71, normalizeLabel(label), 11);
        putAscii(bs, 82, "FAT32   ", 8);
        bs[510] = (byte) 0x55;
        bs[511] = (byte) 0xAA;
        return bs;
    }

    /** Builds the 512-byte FSInfo sector (partition sector 1). */
    public static byte[] buildFsInfoSector(long freeClusters) {
        byte[] fs = new byte[SECTOR];
        putU32(fs, 0, 0x41615252L);
        putU32(fs, 484, 0x61417272L);
        putU32(fs, 488, freeClusters);
        putU32(fs, 492, 3);
        putU32(fs, 508, 0xAA550000L);
        return fs;
    }

    /**
     * Builds one 512-byte sector of a FAT table. Sector 0 carries the
     * media descriptor and the end-of-chain markers for the two
     * reserved entries plus the root-directory cluster; every other
     * sector is zero (empty filesystem).
     */
    public static byte[] buildFatSector(long indexInFat) {
        byte[] fat = new byte[SECTOR];
        if (indexInFat == 0) {
            putU32(fat, 0, 0x0FFFFFF8L);
            putU32(fat, 4, 0x0FFFFFFFL);
            putU32(fat, 8, 0x0FFFFFFFL);
        }
        return fat;
    }

    /** Builds the first root-directory sector: a volume-label entry. */
    public static byte[] buildRootDirSector(String label) {
        byte[] dir = new byte[SECTOR];
        putAsciiPadded(dir, 0, normalizeLabel(label), 11);
        dir[11] = (byte) 0x08;
        return dir;
    }

    // -------------------------------------------------------- endianness

    public static void putU16(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
    }

    public static void putU32(byte[] buf, int off, long value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    public static long getU32(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24);
    }

    private static void putAscii(byte[] buf, int off, String text, int len) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            buf[off + i] = i < raw.length ? raw[i] : (byte) ' ';
        }
    }

    private static void putAsciiPadded(byte[] buf, int off, String text, int len) {
        putAscii(buf, off, text, len);
    }
}
