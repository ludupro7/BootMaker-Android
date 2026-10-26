package id.bootmaker.cok;

/**
 * JNI bridge to the Rust native core (crate {@code bootcard}, library
 * {@code libbootcard.so}, built by the preBuild cargo-ndk hook).
 *
 * <p>File descriptors follow the UBT pattern: the Java side detaches a
 * {@link android.os.ParcelFileDescriptor} and the Rust side takes
 * ownership of the fd (it closes it on return). Every native call
 * therefore needs a freshly opened descriptor.</p>
 *
 * <p>If the library cannot be loaded, {@link #isAvailable()} is false
 * and the engine refuses to run — fail-closed, never a silent
 * Java-only fallback for the actual write.</p>
 */
public final class NativeCore {
    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("bootcard");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private NativeCore() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Validates a u-boot file. Returns {@code "VALID_UBOOT: N bytes"}
     * or {@code "INVALID: reason"}. Consumes {@code fd}.
     */
    public static native String validateUboot(int fd, long size);

    /**
     * Builds the 512-byte MBR for a target of {@code totalSectors}
     * sectors, or {@code null} when the target is too small.
     */
    public static native byte[] buildMbr(long totalSectors);

    /**
     * Writes a complete bootcard to {@code outFd}: sector 0 (u-boot
     * head + partition table), the u-boot remainder, then the minimal
     * FAT32 format at sector 8192, and sizes the file to
     * {@code totalSectors} sectors (sparse). Consumes both fds.
     * Returns {@code "OK: ..."} or {@code "ERROR: ..."}.
     */
    public static native String writeBootcard(int ubootFd, int outFd,
            long totalSectors, String label);

    /**
     * Verifies a written card/image against a u-boot file: MBR
     * signature, partition entry, u-boot head and remainder, FAT
     * signature and label. Consumes both fds. Returns
     * {@code "PASS: ..."} or {@code "FAIL: ..."}.
     */
    public static native String verifyBootcard(int fd, int ubootFd, String label);
}
