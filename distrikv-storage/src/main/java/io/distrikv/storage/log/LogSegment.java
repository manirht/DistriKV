package io.distrikv.storage.log;

import io.distrikv.storage.record.Record;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Milestone 1.2 — one data file on disk. Append at the end, read at an offset.
 *
 * <p><b>Yours to implement.</b> A segment is either <em>active</em> (open for append, at most one
 * per store) or <em>immutable</em> (closed for writing, read-only forever). That distinction is
 * the reason compaction is possible at all in Phase 4: immutable files can be merged in the
 * background without any coordination with the write path, because nothing will ever change them.
 * Enforce it in code — make {@link #append} throw on a read-only segment rather than trusting
 * callers to remember.
 *
 * <h2>File naming</h2>
 *
 * <p>{@code 000001.data}, zero-padded so lexicographic order matches numeric order. That sounds
 * cosmetic and isn't: {@code Files.list()} gives you no ordering guarantee, and sorting
 * {@code 10.data} against {@code 9.data} as strings puts them the wrong way round. Recovery
 * depends on processing segments in the right order, so getting this wrong produces a store that
 * loses the most recent writes and looks fine in every other test.
 *
 * <h2>Which I/O API</h2>
 *
 * <p>Use {@link java.nio.channels.FileChannel}. Two things to know:
 *
 * <ul>
 *   <li>{@code write(ByteBuffer)} is <b>not</b> guaranteed to write the whole buffer in one call.
 *       It returns how many bytes it took. Loop until the buffer has no remaining bytes, or you
 *       will one day write half a record and spend an evening blaming your CRC code.
 *   <li>{@code read(ByteBuffer, position)} is the positional form — it doesn't touch the
 *       channel's own position, which makes it safe to call from several reader threads on the
 *       same channel. That property is what makes milestone 1.7 tractable, so prefer it over
 *       seek-then-read from the start.
 * </ul>
 *
 * <p>You'll be tempted by {@code MappedByteBuffer} because mmap sounds faster. Resist it for now:
 * you can't unmap it deterministically on the JVM, which makes deleting compacted files in Phase
 * 4 genuinely painful on some platforms, and a SIGBUS on a truncated mapping kills the process
 * with no exception to catch. Benchmark it in Phase 5 if you're curious — that's the right time.
 */
public interface LogSegment extends Closeable {

    /** Creates a new, empty, writable segment in {@code dir}. Fails if the file exists. */
    static LogSegment createActive(Path dir, int segmentId) {
        throw new UnsupportedOperationException("TODO milestone 1.2 — createActive");
    }

    /** Opens an existing segment read-only. */
    static LogSegment openReadOnly(Path file) {
        throw new UnsupportedOperationException("TODO milestone 1.2 — openReadOnly");
    }

    /** {@code 000042.data} for id 42. */
    static String fileNameFor(int segmentId) {
        return String.format("%06d.data", segmentId);
    }

    /**
     * Parses the segment id back out of a file name.
     *
     * @throws IllegalArgumentException if the name isn't a segment file
     */
    static int segmentIdFrom(Path file) {
        throw new UnsupportedOperationException("TODO milestone 1.2 — segmentIdFrom");
    }

    int id();

    Path path();

    /** Current size in bytes — i.e. the offset the next append would land at. */
    long sizeBytes();

    boolean isReadOnly();

    /**
     * Appends the already-encoded record bytes and returns the offset they were written at.
     *
     * <p>Takes encoded bytes rather than a {@link Record} on purpose: a segment shouldn't know
     * anything about the record format. Keeping that boundary clean is what lets you change the
     * on-disk layout later without touching this class.
     *
     * @return the byte offset of the first byte written
     * @throws io.distrikv.storage.StorageException if this segment is read-only, or on I/O failure
     */
    long append(ByteBuffer encoded);

    /**
     * Reads and decodes the record of {@code length} bytes at {@code offset}.
     *
     * @throws io.distrikv.storage.CorruptRecordException if the bytes don't decode or the CRC fails
     */
    Record read(long offset, int length);

    /**
     * Forces buffered writes to the physical device ({@code fsync}).
     *
     * <p>This is the only method here that makes a durability promise, and it's worth being
     * precise about what it promises. {@code force(false)} flushes file data but not necessarily
     * the file's metadata; {@code force(true)} flushes both. Since you're appending, the file
     * length is metadata that a reader needs after a crash — so think about which one you
     * actually need, and write the reasoning down.
     *
     * <p>Separate and easy to miss: making a <em>new file's</em> existence durable requires
     * fsyncing its <em>directory</em>, not the file. That doesn't matter much in Phase 1 and
     * matters enormously in milestone 4.2, where getting it wrong can lose data permanently.
     */
    void sync();

    @Override
    void close();
}
