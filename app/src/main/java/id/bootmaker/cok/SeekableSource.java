package id.bootmaker.cok;

import java.io.Closeable;
import java.io.IOException;

/**
 * A randomly readable byte source (a file, a document, a device
 * region). Reads never load the whole source into RAM: callers stream
 * in 64 KiB chunks.
 */
public interface SeekableSource extends Closeable {
    /** Total size in bytes. */
    long size();

    /**
     * Reads up to {@code len} bytes at {@code offset} into
     * {@code buf[off..]}. Returns the number of bytes read, or -1 at
     * end of source.
     */
    int readAt(long offset, byte[] buf, int off, int len) throws IOException;
}
