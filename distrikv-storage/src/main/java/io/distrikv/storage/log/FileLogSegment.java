package io.distrikv.storage.log;

import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.StorageException;
import io.distrikv.storage.record.Record;
import io.distrikv.storage.record.RecordCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The {@link LogSegment} implementation: one file, one {@link FileChannel}.
 *
 * <p>Package-private on purpose — callers go through {@code LogSegment.createActive} and
 * {@code LogSegment.openReadOnly}, so the concrete type never leaks and can be replaced (with a
 * memory-mapped variant, say, in Phase 5) without touching anything above.
 *
 * <p>Thread safety: {@link #read} is safe to call from any number of threads concurrently,
 * because it uses only the positional form of {@code FileChannel.read}, which does not touch the
 * channel's shared position. {@link #append} is <em>not</em> safe to call concurrently and relies
 * on {@code BitcaskStore}'s write lock to serialise it. See {@code docs/decisions/006}.
 */
final class FileLogSegment implements LogSegment {

    private final int id;
    private final Path path;
    private final FileChannel channel;
    private final boolean readOnly;

    /**
     * Bytes written so far, i.e. the offset the next append lands at. Cached rather than calling
     * {@code channel.size()} on every append, and kept exactly in step with the file so that
     * {@code sizeBytes()} and {@code Files.size(path)} can never disagree.
     */
    private long sizeBytes;

    private boolean closed;

    private FileLogSegment(int id, Path path, FileChannel channel, boolean readOnly, long sizeBytes) {
        this.id = id;
        this.path = path;
        this.channel = channel;
        this.readOnly = readOnly;
        this.sizeBytes = sizeBytes;
    }

    /** Creates a new, empty, writable segment. Fails if the file already exists. */
    static FileLogSegment createActive(Path dir, int segmentId) {
        if (segmentId < 0) {
            throw new IllegalArgumentException("segmentId must be >= 0, was " + segmentId);
        }
        Path file = dir.resolve(LogSegment.fileNameFor(segmentId));
        try {
            Files.createDirectories(dir);
            // CREATE_NEW rather than CREATE: opening a store twice on one directory must not
            // silently truncate an existing segment. (The store also holds a lock file, which is
            // the real defence — see docs/decisions/003.)
            FileChannel channel = FileChannel.open(
                    file,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            // A new file's *existence* is directory metadata, and force() on the file says
            // nothing about it. Harmless to skip in Phase 1; load-bearing at milestone 4.2.
            syncDirectory(dir);
            return new FileLogSegment(segmentId, file, channel, false, 0L);
        } catch (IOException e) {
            throw new StorageException("could not create segment " + file, e);
        }
    }

    /** Opens an existing segment read-only. */
    static FileLogSegment openReadOnly(Path file) {
        int segmentId = LogSegment.segmentIdFrom(file);
        try {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            return new FileLogSegment(segmentId, file, channel, true, channel.size());
        } catch (IOException e) {
            throw new StorageException("could not open segment " + file, e);
        }
    }

    /**
     * fsyncs a directory so that entries created in it survive a crash.
     *
     * <p>Best-effort: some platforms refuse to open a directory as a channel at all, and there is
     * no portable alternative in the JDK. Where it fails there is nothing to fall back to, so the
     * failure is swallowed rather than taking down a write that otherwise succeeded — but that
     * does mean the guarantee in {@code docs/decisions/003} is weaker on those platforms than on
     * Linux and macOS, which is worth knowing before trusting it.
     */
    private static void syncDirectory(Path dir) {
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Platform does not support it. Deliberately ignored; see above.
        }
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public long append(ByteBuffer encoded) {
        if (readOnly) {
            throw new StorageException("segment " + id + " is read-only and cannot be appended to");
        }
        requireOpen();

        long offset = sizeBytes;
        long writePosition = offset;
        try {
            // write() is not guaranteed to take the whole buffer in one call. Looping is not
            // paranoia: a short write that goes unnoticed puts half a record on disk.
            while (encoded.hasRemaining()) {
                int written = channel.write(encoded, writePosition);
                if (written <= 0) {
                    throw new StorageException(
                            "channel accepted no bytes while appending to segment " + id);
                }
                writePosition += written;
            }
        } catch (IOException e) {
            throw new StorageException("append to segment " + id + " failed at offset " + offset, e);
        }
        sizeBytes = writePosition;
        return offset;
    }

    @Override
    public Record read(long offset, int length) {
        requireOpen();
        if (offset < 0) {
            throw new CorruptRecordException("negative offset " + offset + " in segment " + id);
        }
        if (length <= 0) {
            throw new CorruptRecordException("non-positive length " + length + " in segment " + id);
        }
        if (offset + length > sizeBytes) {
            throw new CorruptRecordException(
                    "read past end of segment " + id + ": offset " + offset + " + length " + length
                            + " exceeds size " + sizeBytes);
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);
        long readPosition = offset;
        try {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, readPosition);
                if (read < 0) {
                    throw new CorruptRecordException(
                            "unexpected end of segment " + id + " at offset " + readPosition
                                    + "; wanted " + length + " bytes from " + offset);
                }
                readPosition += read;
            }
        } catch (IOException e) {
            throw new StorageException(
                    "read from segment " + id + " at offset " + offset + " failed", e);
        }
        buffer.flip();
        return RecordCodec.decode(buffer);
    }

    @Override
    public void sync() {
        if (readOnly) {
            // Nothing of ours is buffered, and forcing a read-only channel promises nothing.
            return;
        }
        requireOpen();
        try {
            // force(true), not force(false): we only ever append, so the file length is metadata
            // that recovery depends on. See docs/decisions/003.
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("fsync of segment " + id + " failed", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!readOnly && channel.isOpen()) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new StorageException("fsync of segment " + id + " during close failed", e);
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                throw new StorageException("closing segment " + id + " failed", e);
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("segment " + id + " is closed");
        }
    }

    @Override
    public String toString() {
        return "LogSegment[" + path.getFileName() + ", " + sizeBytes + "B"
                + (readOnly ? ", read-only" : ", active") + (closed ? ", closed" : "") + "]";
    }
}