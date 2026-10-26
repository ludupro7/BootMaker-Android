package id.bootmaker.cok;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Storage Access Framework helpers. SAF gives us file descriptors (no
 * storage permission needed), and {@link Os#pread} lets us read at
 * arbitrary offsets — exactly what the streaming writers need.
 */
public final class SafIo {
    private SafIo() {
    }

    /** Opens a document for reading. */
    public static ParcelFileDescriptor openInput(ContentResolver resolver, Uri uri)
            throws IOException {
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) {
            throw new FileNotFoundException("Cannot open document: " + uri);
        }
        return pfd;
    }

    /** Opens a document for writing (truncate). */
    public static ParcelFileDescriptor openOutput(ContentResolver resolver, Uri uri)
            throws IOException {
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "w");
        if (pfd == null) {
            throw new FileNotFoundException("Cannot open document for writing: " + uri);
        }
        return pfd;
    }

    /** Best-effort size of a document, or -1 when unknown. */
    public static long querySize(ContentResolver resolver, Uri uri, ParcelFileDescriptor pfd) {
        long sz = pfd.getStatSize();
        if (sz >= 0) {
            return sz;
        }
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        }
        return -1;
    }

    /** {@link SeekableSource} backed by a SAF document. */
    public static final class SafSource implements SeekableSource {
        private final ParcelFileDescriptor pfd;
        private final long size;

        public SafSource(ContentResolver resolver, Uri uri) throws IOException {
            ParcelFileDescriptor opened = openInput(resolver, uri);
            pfd = opened;
            long sz = querySize(resolver, uri, pfd);
            if (sz < 0) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                throw new IOException("Cannot determine size of document: " + uri);
            }
            size = sz;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int readAt(long offset, byte[] buf, int off, int len) throws IOException {
            if (offset >= size) {
                return -1;
            }
            int want = (int) Math.min(len, size - offset);
            try {
                int n = Os.pread(pfd.getFileDescriptor(), buf, off, want, offset);
                if (n < 0) {
                    throw new IOException("pread failed at offset " + offset);
                }
                return n;
            } catch (ErrnoException e) {
                throw new IOException("pread failed at offset " + offset + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() throws IOException {
            pfd.close();
        }
    }

    /** InputStream view over a slice of a {@link SeekableSource}. */
    public static final class SourceInputStream extends java.io.InputStream {
        private final SeekableSource src;
        private long pos;
        private long remaining;

        public SourceInputStream(SeekableSource src, long offset, long length) {
            this.src = src;
            this.pos = offset;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, remaining);
            int n = src.readAt(pos, b, off, want);
            if (n < 0) {
                return -1;
            }
            pos += n;
            remaining -= n;
            return n;
        }
    }
}
