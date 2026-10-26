package id.bootmaker.cok;

/**
 * Standalone test harness for {@link CardLayout}. Pure Java, no
 * Android APIs: compile and run with a plain JDK:
 *
 * <pre>
 * javac -d /tmp/bootcard-test \
 *   app/src/main/java/id/bootmaker/cok/CardLayout.java \
 *   tests/BootcardLayoutTest.java
 * java -cp /tmp/bootcard-test id.bootmaker.cok.BootcardLayoutTest
 * </pre>
 *
 * Every check pins bytes derived from the layout formula
 * (count = totalSectors - 8192), not from any single example: for
 * totalSectors = 1,000,000 the count is 991,808 = 0xF2240, hence the
 * entry bytes below.
 */
public final class BootcardLayoutTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        checkMbrBytes();
        checkMbrRejections();
        checkUbootValidation();
        checkLabelNormalization();
        checkFatGeometry512M();
        checkBootSectorFields();
        checkFatSector();
        checkFsInfo();
        checkRootDirSector();
        checkSector0Overlay();
        System.out.println("BootcardLayoutTest: " + checks + " checks, "
                + failures + " failures.");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void checkMbrBytes() {
        byte[] mbr = CardLayout.buildMbr(1_000_000);
        byte[] expectedEntry = {
                (byte) 0x80, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF,
                (byte) 0x0B, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF,
                (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x00,
                (byte) 0x40, (byte) 0x22, (byte) 0x0F, (byte) 0x00};
        check("MBR entry bytes for 1,000,000 sectors",
                equalsRange(mbr, 446, expectedEntry));
        check("MBR signature 55 AA at 510",
                mbr[510] == (byte) 0x55 && mbr[511] == (byte) 0xAA);
        boolean headZero = true;
        for (int i = 0; i < 446; i++) {
            if (mbr[i] != 0) {
                headZero = false;
            }
        }
        check("MBR head area zero before overlay", headZero);
        CardLayout.MbrInfo info = CardLayout.parseMbr(mbr);
        check("parsed LBA == 8192", info.lba == 8192);
        check("parsed type == 0x0B", info.type == 0x0B);
        check("parsed count == 991808", info.count == 991808);
        check("parsed signature present", info.hasSignature);
    }

    private static void checkMbrRejections() {
        expectThrow("MBR rejects total 8192", new Runnable() {
            @Override
            public void run() {
                CardLayout.buildMbr(8192);
            }
        });
        boolean ok = true;
        try {
            CardLayout.buildMbr(8193);
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        check("MBR accepts total 8193", ok);
    }

    private static void checkUbootValidation() {
        check("u-boot 511 rejected", CardLayout.validateUbootSize(511) != null);
        check("u-boot 512 accepted", CardLayout.validateUbootSize(512) == null);
        check("u-boot 4 MiB accepted",
                CardLayout.validateUbootSize(4 * 1024 * 1024) == null);
        check("u-boot 4 MiB + 1 rejected",
                CardLayout.validateUbootSize(4 * 1024 * 1024 + 1) != null);
    }

    private static void checkLabelNormalization() {
        check("label boot -> BOOT", CardLayout.normalizeLabel("boot").equals("BOOT"));
        check("label empty -> BOOT", CardLayout.normalizeLabel("").equals("BOOT"));
        check("label null -> BOOT", CardLayout.normalizeLabel(null).equals("BOOT"));
        check("label truncated to 11",
                CardLayout.normalizeLabel("averylonglabelname").equals("AVERYLONGLA"));
    }

    private static void checkFatGeometry512M() {
        long part = 1_048_576 - 8192;
        CardLayout.FatGeometry g = CardLayout.fatGeometry(part);
        check("512M geometry spc == 8", g.sectorsPerCluster == 8);
        check("512M geometry fatSectors == 1014", g.fatSectors == 1014);
        check("512M geometry clusters == 129790", g.clusters == 129790);
        check("512M clusters >= 65525", g.clusters >= 65525);
        check("512M dataStart == 32 + 2*1014",
                g.dataStartSector == 32 + 2 * 1014);
    }

    private static void checkBootSectorFields() {
        long part = 1_048_576 - 8192;
        CardLayout.FatGeometry g = CardLayout.fatGeometry(part);
        byte[] bs = CardLayout.buildBootSector(part, "BOOT", g);
        check("boot jump EB 58 90",
                bs[0] == (byte) 0xEB && bs[1] == (byte) 0x58 && bs[2] == (byte) 0x90);
        check("boot OEM BOOTCARD", ascii(bs, 3, 8).equals("BOOTCARD"));
        check("boot bytes/sector 512", u16(bs, 11) == 512);
        check("boot spc 8", bs[13] == 8);
        check("boot reserved 32", u16(bs, 14) == 32);
        check("boot fats 2", bs[16] == 2);
        check("boot media F8", bs[21] == (byte) 0xF8);
        check("boot hidden 8192", u32(bs, 28) == 8192);
        check("boot total32 == partSectors", u32(bs, 32) == part);
        check("boot fat32size 1014", u32(bs, 36) == 1014);
        check("boot rootCluster 2", u32(bs, 44) == 2);
        check("boot fsInfo 1", u16(bs, 48) == 1);
        check("boot backupBoot 6", u16(bs, 50) == 6);
        check("boot label BOOT", ascii(bs, 71, 11).equals("BOOT       "));
        check("boot fstype FAT32", ascii(bs, 82, 8).equals("FAT32   "));
        check("boot signature 55 AA",
                bs[510] == (byte) 0x55 && bs[511] == (byte) 0xAA);
    }

    private static void checkFatSector() {
        byte[] first = CardLayout.buildFatSector(0);
        check("FAT[0] == 0x0FFFFFF8", u32(first, 0) == 0x0FFFFFF8L);
        check("FAT[1] == 0x0FFFFFFF", u32(first, 4) == 0x0FFFFFFFL);
        check("FAT[2] == 0x0FFFFFFF (root EOC)", u32(first, 8) == 0x0FFFFFFFL);
        byte[] second = CardLayout.buildFatSector(1);
        boolean zero = true;
        for (byte b : second) {
            if (b != 0) {
                zero = false;
            }
        }
        check("FAT sector 1 all zero", zero);
    }

    private static void checkFsInfo() {
        byte[] fs = CardLayout.buildFsInfoSector(129789);
        check("FSInfo RRaA", u32(fs, 0) == 0x41615252L);
        check("FSInfo rrAa", u32(fs, 484) == 0x61417272L);
        check("FSInfo free clusters", u32(fs, 488) == 129789);
        check("FSInfo next free 3", u32(fs, 492) == 3);
        check("FSInfo trail AA550000", u32(fs, 508) == 0xAA550000L);
    }

    private static void checkRootDirSector() {
        byte[] dir = CardLayout.buildRootDirSector("BOOT");
        check("root dir label entry", ascii(dir, 0, 11).equals("BOOT       "));
        check("root dir attr 0x08 (volume label)", dir[11] == (byte) 0x08);
    }

    private static void checkSector0Overlay() {
        byte[] mbr = CardLayout.buildMbr(1_000_000);
        byte[] head = new byte[446];
        for (int i = 0; i < 446; i++) {
            head[i] = (byte) (i & 0xFF);
        }
        byte[] sector0 = CardLayout.buildSector0(mbr, head);
        check("sector0 head passthrough", equalsRange(sector0, 0, head));
        check("sector0 entry intact after overlay",
                sector0[446] == (byte) 0x80 && sector0[450] == (byte) 0x0B);
        check("sector0 signature intact after overlay",
                sector0[510] == (byte) 0x55 && sector0[511] == (byte) 0xAA);
    }

    // ------------------------------------------------------------ helpers

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS: " + name);
        } else {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void expectThrow(String name, Runnable r) {
        checks++;
        try {
            r.run();
            failures++;
            System.out.println("FAIL: " + name + " (no exception)");
        } catch (IllegalArgumentException e) {
            System.out.println("PASS: " + name);
        }
    }

    private static boolean equalsRange(byte[] buf, int off, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (buf[off + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
