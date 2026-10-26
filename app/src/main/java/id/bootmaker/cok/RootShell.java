package id.bootmaker.cok;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * All privileged operations go through here, and only here. The app
 * process itself is never root; every block-device touch runs as
 * {@code su -c ...} and every failure aborts the job before any write
 * (fail-closed).
 *
 * <p>Design rules, mirroring {@code aml-bootcard.py}:</p>
 * <ul>
 *   <li>Only devices whose {@code /sys/block/.../removable} flag is
 *       {@code 1} are ever offered as targets. Internal storage
 *       (eMMC, UFS) is refused even if the user somehow selects it.</li>
 *   <li>A device mounted at /, /system, /system_root, /data or
 *       /vendor is refused outright.</li>
 *   <li>All mounted partitions of the target are unmounted first; any
 *       unmount failure aborts before writing.</li>
 *   <li>Writes use {@code dd ... conv=notrunc} at explicit sector
 *       offsets, streamed in 64 KiB chunks — never a whole-device
 *       rewrite, never a whole image in RAM.</li>
 * </ul>
 */
public final class RootShell {
    /** Chunk size for all streaming copies. */
    public static final int CHUNK = 64 * 1024;
    private static final long SU_TIMEOUT_MS = 15_000;

    private RootShell() {
    }

    /** One block device as seen through /sys/block. */
    public static final class BlockDevice {
        public final String name;
        public final String dev;
        public final String model;
        public final long sizeBytes;
        public final boolean removable;

        BlockDevice(String name, String model, long sizeBytes, boolean removable) {
            this.name = name;
            this.dev = "/dev/" + name;
            this.model = model;
            this.sizeBytes = sizeBytes;
            this.removable = removable;
        }

        @Override
        public String toString() {
            String size = sizeBytes > 0 ? (sizeBytes / 1024 / 1024) + " MiB" : "unknown size";
            return name + " (" + size + (model.isEmpty() ? "" : ", " + model) + ")";
        }
    }

    /** One mounted filesystem. */
    public static final class Mount {
        public final String source;
        public final String point;

        Mount(String source, String point) {
            this.source = source;
            this.point = point;
        }
    }

    /** Result of a finished {@code su} command. */
    public static final class ExecResult {
        public final int exit;
        public final String out;
        public final String err;

        ExecResult(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    /** Runs {@code su -c command} and captures text output. */
    public static ExecResult execSu(String command, long timeoutMs) throws IOException {
        Process proc = new ProcessBuilder("su", "-c", command).start();
        StreamDrain outDrain = new StreamDrain(proc.getInputStream());
        StreamDrain errDrain = new StreamDrain(proc.getErrorStream());
        outDrain.start();
        errDrain.start();
        try {
            if (!waitFor(proc, timeoutMs)) {
                proc.destroyForcibly();
                throw new IOException("su command timed out: " + command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for su.", e);
        }
        try {
            outDrain.join(2000);
            errDrain.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ExecResult(proc.exitValue(), outDrain.text(), errDrain.text());
    }

    /** True only when {@code su -c id} reports uid 0. */
    public static boolean isRooted() {
        try {
            ExecResult r = execSu("id", SU_TIMEOUT_MS);
            return r.exit == 0 && r.out.contains("uid=0");
        } catch (IOException e) {
            return false;
        }
    }

    /** Lists block devices via /sys/block (loop/ram/zram skipped). */
    public static List<BlockDevice> listBlockDevices() throws IOException {
        String script = "for d in /sys/block/*; do "
                + "n=$(basename $d); "
                + "case $n in loop*|ram*|zram*) continue;; esac; "
                + "s=$(cat $d/size 2>/dev/null); "
                + "r=$(cat $d/removable 2>/dev/null); "
                + "m=$(cat $d/device/model 2>/dev/null); "
                + "echo \"$n|$s|$r|$m\"; done";
        ExecResult r = execSu(script, SU_TIMEOUT_MS);
        if (r.exit != 0) {
            throw new IOException("Cannot list block devices: " + firstLine(r.err));
        }
        List<BlockDevice> out = new ArrayList<>();
        for (String line : r.out.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            long sectors = parseLong(parts[1].trim(), 0);
            boolean removable = "1".equals(parts.length > 2 ? parts[2].trim() : "");
            String model = parts.length > 3 ? parts[3].trim() : "";
            out.add(new BlockDevice(name, model, sectors * CardLayout.SECTOR, removable));
        }
        return out;
    }

    /** Mounted filesystems belonging to {@code devName}. */
    public static List<Mount> mountedFor(String devName) throws IOException {
        ExecResult r = execSu("cat /proc/mounts", SU_TIMEOUT_MS);
        if (r.exit != 0) {
            throw new IOException("Cannot read /proc/mounts: " + firstLine(r.err));
        }
        List<Mount> out = new ArrayList<>();
        String prefix = "/dev/" + devName;
        for (String line : r.out.split("\n")) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String src = parts[0];
            if (src.equals(prefix) || src.startsWith(prefix + "p")
                    || (src.startsWith(prefix)
                            && src.length() > prefix.length()
                            && Character.isDigit(src.charAt(prefix.length())))) {
                out.add(new Mount(src, parts[1]));
            }
        }
        return out;
    }

    /** True when the device backs a system-critical mount point. */
    public static boolean isSystemMount(String devName) throws IOException {
        for (Mount m : mountedFor(devName)) {
            String p = m.point;
            if (p.equals("/") || p.equals("/system") || p.equals("/system_root")
                    || p.equals("/data") || p.equals("/vendor")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unmounts every mounted partition of the device. Returns
     * {@code null} on success, otherwise an error message; the caller
     * must abort on any non-null return.
     */
    public static String unmountDevice(String devName) throws IOException {
        for (Mount m : mountedFor(devName)) {
            ExecResult r = execSu("umount '" + m.source + "'", SU_TIMEOUT_MS);
            if (r.exit != 0) {
                return "Failed to unmount " + m.source + " from " + m.point
                        + ": " + firstLine(r.err.isEmpty() ? r.out : r.err)
                        + " — unmount it manually first.";
            }
        }
        return null;
    }

    /** Byte progress callback for a single dd stream. */
    public interface ByteProgress {
        void onBytes(long writtenSoFar);
    }

    /**
     * Streams {@code length} bytes from {@code in} to the block device
     * at {@code sector} via {@code dd ... conv=notrunc}. The final
     * partial block (when {@code length} is not a multiple of 512) is
     * written as-is by dd; that is fine because the next region starts
     * far beyond it.
     */
    public static void ddWrite(String dev, long sector, InputStream in, long length,
            CardEngine.CancelCheck cancel, ByteProgress progress) throws IOException {
        Process proc = new ProcessBuilder("su", "-c",
                "dd of='" + dev + "' bs=512 seek=" + sector + " conv=notrunc").start();
        StreamDrain drain = new StreamDrain(proc.getErrorStream());
        drain.start();
        byte[] buf = new byte[CHUNK];
        long remaining = length;
        long written = 0;
        try (OutputStream stdin = proc.getOutputStream()) {
            while (remaining > 0) {
                if (cancel != null && cancel.isCancelled()) {
                    proc.destroyForcibly();
                    throw new CardEngine.CardCancelledException();
                }
                int want = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new IOException("Short input while writing to " + dev);
                }
                stdin.write(buf, 0, n);
                remaining -= n;
                written += n;
                if (progress != null) {
                    progress.onBytes(written);
                }
            }
            stdin.flush();
        } catch (CardEngine.CardCancelledException e) {
            throw e;
        } catch (IOException e) {
            proc.destroyForcibly();
            throw e;
        }
        try {
            if (!waitFor(proc, 60_000)) {
                proc.destroyForcibly();
                throw new IOException("dd write timed out on " + dev);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during dd write.", e);
        }
        try {
            drain.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (proc.exitValue() != 0) {
            throw new IOException("dd write failed on " + dev + ": " + firstLine(drain.text()));
        }
    }

    /** Reads exactly {@code sectorCount} sectors from the device. */
    public static byte[] ddRead(String dev, long sector, int sectorCount) throws IOException {
        Process proc = new ProcessBuilder("su", "-c",
                "dd if='" + dev + "' bs=512 skip=" + sector + " count=" + sectorCount).start();
        StreamDrain errDrain = new StreamDrain(proc.getErrorStream());
        errDrain.start();
        byte[] out = new byte[sectorCount * CardLayout.SECTOR];
        int filled = 0;
        try (InputStream stdout = proc.getInputStream()) {
            while (filled < out.length) {
                int n = stdout.read(out, filled, out.length - filled);
                if (n < 0) {
                    break;
                }
                filled += n;
            }
        }
        try {
            if (!waitFor(proc, 60_000)) {
                proc.destroyForcibly();
                throw new IOException("dd read timed out on " + dev);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during dd read.", e);
        }
        try {
            errDrain.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (proc.exitValue() != 0 || filled != out.length) {
            throw new IOException("dd read failed on " + dev + " (got " + filled
                    + " of " + out.length + " bytes): " + firstLine(errDrain.text()));
        }
        return out;
    }

    /** A streaming dd read (for large regions such as the u-boot tail). */
    public static final class DdReadSession implements AutoCloseable {
        private final Process proc;
        private final StreamDrain errDrain;

        private DdReadSession(Process proc, StreamDrain errDrain) {
            this.proc = proc;
            this.errDrain = errDrain;
        }

        public InputStream stream() {
            return proc.getInputStream();
        }

        /** Waits for dd and returns its exit code. */
        public int finish() throws IOException {
            try {
                if (!waitFor(proc, 120_000)) {
                    proc.destroyForcibly();
                    throw new IOException("dd read timed out.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during dd read.", e);
            }
            try {
                errDrain.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return proc.exitValue();
        }

        @Override
        public void close() {
            try {
                proc.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Opens a streaming dd read starting at {@code sector}. */
    public static DdReadSession openDdRead(String dev, long sector) throws IOException {
        Process proc = new ProcessBuilder("su", "-c",
                "dd if='" + dev + "' bs=512 skip=" + sector).start();
        StreamDrain errDrain = new StreamDrain(proc.getErrorStream());
        errDrain.start();
        return new DdReadSession(proc, errDrain);
    }

    // ------------------------------------------------------------ helpers

    private static boolean waitFor(Process proc, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue();
                return true;
            } catch (IllegalThreadStateException notDone) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String line = (nl >= 0 ? s.substring(0, nl) : s).trim();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static final class StreamDrain extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StreamDrain(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    buf.write(chunk, 0, n);
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return buf.toString();
        }
    }
}
