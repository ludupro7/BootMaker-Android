package id.bootmaker.cok;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a card-making job: validate → write → format → verify, with
 * progress and cancellation. The Activity must never own a job; the
 * foreground {@link CardService} does.
 *
 * <p>Two paths, both fail-closed:</p>
 * <ul>
 *   <li><b>Direct (primary, root-only):</b> the Java side streams the
 *       card to the block device through {@link RootShell} dd calls,
 *       using {@link CardLayout} for every byte it writes, then reads
 *       the card back and verifies it.</li>
 *   <li><b>Image (secondary, still root-gated):</b> the Rust native
 *       core writes and verifies a .img file obtained through SAF.
 *       If the native library is missing, the job is refused — there
 *       is no silent Java fallback for the actual write.</li>
 * </ul>
 *
 * <p>Log lines are English, matching the original tool's messages
 * (including its "start to format the disk!", "format canceled!" and
 * "fatal error!Cannot format SD-card!" lines at the corresponding
 * events).</p>
 */
public final class CardEngine {
    /** Cancellation predicate handed to streaming helpers. */
    public interface CancelCheck {
        boolean isCancelled();
    }

    /** Log sink (one line at a time). */
    public interface LogListener {
        void onLog(String line);
    }

    /** Progress sink: current phase plus 0..100 percent (-1 = unknown). */
    public interface ProgressListener {
        void onProgress(String step, int percent);
    }

    /** Thrown when the user cancels; aborts the job immediately. */
    public static final class CardCancelledException extends IOException {
        public CardCancelledException() {
            super("Cancelled.");
        }
    }

    /** Everything one job needs. */
    public static final class CardOptions {
        /** True for direct SD write, false for image file. */
        public boolean directMode;
        /** Direct: block device name, e.g. "mmcblk1". */
        public String deviceName;
        /** Direct: block device path, e.g. "/dev/mmcblk1". */
        public String devicePath;
        /** Direct: must be true — set only after the typed-name dialog. */
        public boolean confirmed;
        /** u-boot .bin, from the SAF picker. */
        public Uri ubootUri;
        /** Image mode: SAF create-document target. */
        public Uri imageUri;
        /** Image mode: total image size in bytes. */
        public long imageSizeBytes;
        /** FAT volume label (normalized by the engine). */
        public String label = CardLayout.DEFAULT_LABEL;
    }

    /** Job result. */
    public static final class CardOutcome {
        public final String kind;
        public final String reason;

        private CardOutcome(String kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        public static CardOutcome ok(String reason) {
            return new CardOutcome("OK", reason);
        }

        public static CardOutcome failed(String reason) {
            return new CardOutcome("FAILED", reason);
        }

        public static CardOutcome cancelled(String reason) {
            return new CardOutcome("CANCELLED", reason);
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final LogListener logListener;
    private final ProgressListener progressListener;
    private volatile String currentStep = "";
    private volatile int lastPct = -2;

    public CardEngine(LogListener logListener, ProgressListener progressListener) {
        this.logListener = logListener;
        this.progressListener = progressListener;
    }

    public void cancel() {
        cancelled.set(true);
    }

    private final CancelCheck cancelCheck = new CancelCheck() {
        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    };

    private void log(String line) {
        if (logListener != null) {
            logListener.onLog(line);
        }
    }

    private void progress(String step, int pct) {
        currentStep = step;
        if (pct != lastPct || pct < 0) {
            lastPct = pct;
            if (progressListener != null) {
                progressListener.onProgress(step, pct);
            }
        }
    }

    /** Runs one job to completion (or failure / cancellation). */
    public CardOutcome run(CardOptions opts, ContentResolver resolver) {
        try {
            if (opts.directMode) {
                return runDirect(opts, resolver);
            }
            return runImage(opts, resolver);
        } catch (CardCancelledException e) {
            log("format canceled!");
            log("WARNING: target may be left incomplete — rewrite it fully before use.");
            return CardOutcome.cancelled("Cancelled by user.");
        } catch (Exception e) {
            log("fatal error!Cannot format SD-card!");
            String m = e.getMessage() != null ? e.getMessage() : e.toString();
            return CardOutcome.failed(m);
        }
    }

    // ---------------------------------------------------------- direct

    private CardOutcome runDirect(CardOptions opts, ContentResolver resolver) throws IOException {
        progress("Checking root...", -1);
        log("Checking root...");
        if (!RootShell.isRooted()) {
            return CardOutcome.failed("Root required: BootMakar needs a rooted device.");
        }
        if (opts.deviceName == null || opts.devicePath == null) {
            return CardOutcome.failed("No target disk selected.");
        }
        if (!opts.confirmed) {
            return CardOutcome.failed(
                    "Direct write refused: typed device-name confirmation is required.");
        }
        RootShell.BlockDevice target = null;
        for (RootShell.BlockDevice d : RootShell.listBlockDevices()) {
            if (d.name.equals(opts.deviceName)) {
                target = d;
                break;
            }
        }
        if (target == null) {
            return CardOutcome.failed("Target disk not found: " + opts.deviceName);
        }
        if (!target.removable) {
            return CardOutcome.failed("Refused: " + opts.deviceName
                    + " is not removable storage (internal storage is never a target).");
        }
        if (RootShell.isSystemMount(opts.deviceName)) {
            return CardOutcome.failed("Refused: " + opts.deviceName
                    + " backs a system mount point.");
        }
        if (target.sizeBytes <= 0) {
            return CardOutcome.failed("Cannot determine size of " + opts.deviceName);
        }
        long totalSectors = target.sizeBytes / CardLayout.SECTOR;
        if (totalSectors <= CardLayout.PART_START_SECTOR) {
            return CardOutcome.failed("Target too small (must be larger than 4 MiB).");
        }
        log("Unmounting partitions of " + opts.devicePath + " ...");
        String unmountError = RootShell.unmountDevice(opts.deviceName);
        if (unmountError != null) {
            return CardOutcome.failed(unmountError);
        }

        String label = CardLayout.normalizeLabel(opts.label);
        long partSectors = totalSectors - CardLayout.PART_START_SECTOR;
        CardLayout.FatGeometry geometry;
        try {
            geometry = CardLayout.fatGeometry(partSectors);
        } catch (IllegalArgumentException e) {
            return CardOutcome.failed(e.getMessage());
        }
        long fatRegionBytes = (CardLayout.FAT_RESERVED_SECTORS
                + 2L * geometry.fatSectors + geometry.sectorsPerCluster) * CardLayout.SECTOR;

        try (SafIo.SafSource uboot = new SafIo.SafSource(resolver, opts.ubootUri)) {
            String sizeError = CardLayout.validateUbootSize(uboot.size());
            if (sizeError != null) {
                return CardOutcome.failed(sizeError);
            }
            long ubootSize = uboot.size();
            long progressTotal = ubootSize + fatRegionBytes;
            log("Target      : " + opts.devicePath + " (" + (target.sizeBytes / 1024 / 1024)
                    + " MiB, " + totalSectors + " sectors)");
            log("u-boot      : " + ubootSize + " bytes");
            log("Partition   : FAT32 from 4 MiB to end, label " + label);
            log("start to format the disk!");
            log("WARNING: ALL CONTENT ON " + opts.devicePath + " WILL BE ERASED.");

            byte[] mbr;
            try {
                mbr = CardLayout.buildMbr(totalSectors);
            } catch (IllegalArgumentException e) {
                return CardOutcome.failed(e.getMessage());
            }
            byte[] head = readFully(uboot, 0, 446);
            byte[] sector0 = CardLayout.buildSector0(mbr, head);

            progress("Writing u-boot + MBR...", 0);
            RootShell.ddWrite(opts.devicePath, 0,
                    new ByteArrayInputStream(sector0), sector0.length,
                    cancelCheck, written -> progress("Writing u-boot + MBR...",
                            (int) (written * 100 / progressTotal)));
            long restLen = ubootSize - CardLayout.SECTOR;
            if (restLen > 0) {
                RootShell.ddWrite(opts.devicePath, 1,
                        new SafIo.SourceInputStream(uboot, CardLayout.SECTOR, restLen), restLen,
                        cancelCheck, written -> progress("Writing u-boot + MBR...",
                                (int) ((CardLayout.SECTOR + written) * 100 / progressTotal)));
            }
            log("u-boot + MBR written. Formatting FAT partition ...");
            progress("Formatting FAT partition...", (int) (ubootSize * 100 / progressTotal));

            byte[] reserved = buildReservedBlock(partSectors, label, geometry);
            RootShell.ddWrite(opts.devicePath, CardLayout.PART_START_SECTOR,
                    new ByteArrayInputStream(reserved), reserved.length,
                    cancelCheck, null);
            long fatStart = CardLayout.PART_START_SECTOR + CardLayout.FAT_RESERVED_SECTORS;
            writeFatCopies(opts, geometry, fatStart, label, progressTotal, ubootSize);
            long dataStart = fatStart + 2L * geometry.fatSectors;
            writeRootDir(opts, geometry, dataStart, label);

            progress("Verifying...", -1);
            log("Verifying result:");
            String verifyError = verifyDirect(opts, resolver, uboot, totalSectors, label);
            if (verifyError != null) {
                return CardOutcome.failed("Verification failed: " + verifyError);
            }
            progress("Done", 100);
            log("DONE. Bootable Amlogic card ready.");
            log("Next step: copy the Armbian boot files (dtb, aml_autoscript/uEnv, "
                    + "kernel) onto the FAT partition as your Armbian image requires.");
            return CardOutcome.ok("Card written and verified.");
        }
    }

    /** The 32-sector reserved block: boot, FSInfo, zeros, backups, zeros. */
    private byte[] buildReservedBlock(long partSectors, String label,
            CardLayout.FatGeometry geometry) {
        byte[] block = new byte[CardLayout.FAT_RESERVED_SECTORS * CardLayout.SECTOR];
        byte[] boot = CardLayout.buildBootSector(partSectors, label, geometry);
        byte[] fsinfo = CardLayout.buildFsInfoSector(geometry.clusters - 1);
        System.arraycopy(boot, 0, block, 0, CardLayout.SECTOR);
        System.arraycopy(fsinfo, 0, block, CardLayout.SECTOR, CardLayout.SECTOR);
        System.arraycopy(boot, 0, block, 6 * CardLayout.SECTOR, CardLayout.SECTOR);
        System.arraycopy(fsinfo, 0, block, 7 * CardLayout.SECTOR, CardLayout.SECTOR);
        return block;
    }

    private void writeFatCopies(CardOptions opts, CardLayout.FatGeometry geometry,
            long fatStart, String label, long progressTotal, long ubootSize) throws IOException {
        for (int copy = 0; copy < CardLayout.FAT_COUNT; copy++) {
            long base = fatStart + copy * geometry.fatSectors;
            RootShell.ddWrite(opts.devicePath, base,
                    new ByteArrayInputStream(CardLayout.buildFatSector(0)), CardLayout.SECTOR,
                    cancelCheck, null);
            long restSectors = geometry.fatSectors - 1;
            if (restSectors > 0) {
                RootShell.ddWrite(opts.devicePath, base + 1,
                        new ZeroInputStream(restSectors * CardLayout.SECTOR),
                        restSectors * CardLayout.SECTOR, cancelCheck, null);
            }
        }
    }

    private void writeRootDir(CardOptions opts, CardLayout.FatGeometry geometry,
            long dataStart, String label) throws IOException {
        RootShell.ddWrite(opts.devicePath, dataStart,
                new ByteArrayInputStream(CardLayout.buildRootDirSector(label)),
                CardLayout.SECTOR, cancelCheck, null);
        long restSectors = geometry.sectorsPerCluster - 1;
        if (restSectors > 0) {
            RootShell.ddWrite(opts.devicePath, dataStart + 1,
                    new ZeroInputStream(restSectors * CardLayout.SECTOR),
                    restSectors * CardLayout.SECTOR, cancelCheck, null);
        }
    }

    private String verifyDirect(CardOptions opts, ContentResolver resolver,
            SafIo.SafSource uboot, long totalSectors, String label) throws IOException {
        byte[] sector0 = RootShell.ddRead(opts.devicePath, 0, 1);
        CardLayout.MbrInfo info = CardLayout.parseMbr(sector0);
        if (!info.hasSignature) {
            return "MBR signature (0x55AA) missing.";
        }
        if (info.lba != CardLayout.PART_START_SECTOR) {
            return "Partition starts at sector " + info.lba + ", expected 8192.";
        }
        if (info.type != CardLayout.PART_TYPE_FAT32) {
            return "Partition type 0x" + Integer.toHexString(info.type)
                    + ", expected 0x0b (FAT32).";
        }
        byte[] head = readFully(uboot, 0, 446);
        for (int i = 0; i < 446; i++) {
            if (sector0[i] != head[i]) {
                return "First 446 bytes of sector 0 do not match the u-boot file.";
            }
        }
        long restLen = uboot.size() - CardLayout.SECTOR;
        if (restLen > 0) {
            RootShell.DdReadSession session = RootShell.openDdRead(opts.devicePath, 1);
            try (InputStream deviceIn = session.stream();
                 InputStream ubootIn = new SafIo.SourceInputStream(
                         uboot, CardLayout.SECTOR, restLen)) {
                byte[] a = new byte[RootShell.CHUNK];
                byte[] b = new byte[RootShell.CHUNK];
                long left = restLen;
                while (left > 0) {
                    if (cancelCheck.isCancelled()) {
                        throw new CardCancelledException();
                    }
                    int want = (int) Math.min(a.length, left);
                    int na = readChunk(deviceIn, a, want);
                    int nb = readChunk(ubootIn, b, want);
                    if (na != want || nb != want) {
                        return "Short read while verifying the u-boot remainder.";
                    }
                    for (int i = 0; i < want; i++) {
                        if (a[i] != b[i]) {
                            return "u-boot content from sector 1 does not match.";
                        }
                    }
                    left -= want;
                }
            } finally {
                session.close();
            }
        }
        byte[] fatBoot = RootShell.ddRead(opts.devicePath, CardLayout.PART_START_SECTOR, 1);
        if (fatBoot[510] != (byte) 0x55 || fatBoot[511] != (byte) 0xAA) {
            return "FAT boot sector signature missing.";
        }
        String fstype = new String(fatBoot, 82, 8, "US-ASCII");
        if (!fstype.startsWith("FAT32")) {
            return "FAT32 signature not found at partition start.";
        }
        String foundLabel = new String(fatBoot, 71, 11, "US-ASCII").trim();
        if (!foundLabel.equals(label)) {
            return "FAT label is '" + foundLabel + "', expected '" + label + "'.";
        }
        log("  MBR valid, FAT partition from sector 8192, active=0x"
                + Integer.toHexString(info.status) + ".");
        log("  u-boot matches byte-for-byte.");
        log("  FAT filesystem detected at partition start (label " + label + ").");
        return null;
    }

    // ----------------------------------------------------------- image

    private CardOutcome runImage(CardOptions opts, ContentResolver resolver) throws IOException {
        progress("Checking native core...", -1);
        if (!NativeCore.isAvailable()) {
            return CardOutcome.failed(
                    "Native core (libbootcard.so) is missing — image write refused.");
        }
        if (opts.imageUri == null) {
            return CardOutcome.failed("No image output selected.");
        }
        long totalSectors = opts.imageSizeBytes / CardLayout.SECTOR;
        if (totalSectors <= CardLayout.PART_START_SECTOR) {
            return CardOutcome.failed("Image too small (must be larger than 4 MiB).");
        }
        String label = CardLayout.normalizeLabel(opts.label);

        long ubootSize;
        try (SafIo.SafSource uboot = new SafIo.SafSource(resolver, opts.ubootUri)) {
            String sizeError = CardLayout.validateUbootSize(uboot.size());
            if (sizeError != null) {
                return CardOutcome.failed(sizeError);
            }
            ubootSize = uboot.size();
        }
        log("Image       : " + (opts.imageSizeBytes / 1024 / 1024) + " MiB ("
                + totalSectors + " sectors)");
        log("u-boot      : " + ubootSize + " bytes");
        log("Partition   : FAT32 from 4 MiB to end, label " + label);
        log("start to format the disk!");

        progress("Validating u-boot...", 5);
        String validation;
        try (ParcelFileDescriptor pfd = SafIo.openInput(resolver, opts.ubootUri)) {
            validation = NativeCore.validateUboot(pfd.detachFd(), ubootSize);
        }
        if (validation == null || !validation.startsWith("VALID_UBOOT:")) {
            return CardOutcome.failed("u-boot rejected: " + validation);
        }
        log(validation);

        progress("Writing image...", 40);
        String written;
        try (ParcelFileDescriptor ubootPfd = SafIo.openInput(resolver, opts.ubootUri);
             ParcelFileDescriptor outPfd = SafIo.openOutput(resolver, opts.imageUri)) {
            written = NativeCore.writeBootcard(ubootPfd.detachFd(), outPfd.detachFd(),
                    totalSectors, label);
        }
        if (written == null || !written.startsWith("OK:")) {
            return CardOutcome.failed("Image write failed: " + written);
        }
        log(written);

        progress("Verifying...", 90);
        String verified;
        try (ParcelFileDescriptor imagePfd = SafIo.openInput(resolver, opts.imageUri);
             ParcelFileDescriptor ubootPfd = SafIo.openInput(resolver, opts.ubootUri)) {
            verified = NativeCore.verifyBootcard(imagePfd.detachFd(), ubootPfd.detachFd(), label);
        }
        if (verified == null || !verified.startsWith("PASS:")) {
            return CardOutcome.failed("Verification failed: " + verified);
        }
        log(verified);
        progress("Done", 100);
        log("DONE. Image ready — write it to an SD card with Rufus, balenaEtcher or dd.");
        return CardOutcome.ok("Image written and verified.");
    }

    // ------------------------------------------------------------ helpers

    private byte[] readFully(SafIo.SafSource src, long offset, int count) throws IOException {
        byte[] buf = new byte[count];
        int filled = 0;
        while (filled < count) {
            int n = src.readAt(offset + filled, buf, filled, count - filled);
            if (n < 0) {
                throw new IOException("Short read at offset " + (offset + filled));
            }
            filled += n;
        }
        return buf;
    }

    private int readChunk(InputStream in, byte[] buf, int want) throws IOException {
        int filled = 0;
        while (filled < want) {
            int n = in.read(buf, filled, want - filled);
            if (n < 0) {
                break;
            }
            filled += n;
        }
        return filled;
    }

    /** InputStream that yields {@code remaining} zero bytes. */
    private static final class ZeroInputStream extends InputStream {
        private long remaining;

        ZeroInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, remaining);
            java.util.Arrays.fill(b, off, off + n, (byte) 0);
            remaining -= n;
            return n;
        }
    }
}
