package io.distrikv.storage.record;

import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.Key;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Milestone 1.1 — translates {@link Record} to and from its on-disk bytes.
 *
 * <p>The layout is in {@link RecordHeader}; the reasoning behind every field is in
 * {@code docs/decisions/002-record-format.md}. Two properties of this class are relied on
 * elsewhere and should not be broken casually:
 *
 * <ul>
 *   <li>{@link #encodedLength} and {@link #encode} must never disagree, because the store calls
 *       the former to decide whether a record fits in the active segment before calling the
 *       latter.
 *   <li>{@link #decode} leaves the buffer positioned immediately after the record it read, so
 *       {@code LogReplayer} can decode a run of records from one buffer in a loop.
 * </ul>
 *
 * <h2>Why decode is structured the way it is</h2>
 *
 * <p>The order of operations matters and is not cosmetic: read the fixed-size header, bounds-check
 * the length fields, verify the CRC over exactly the span those lengths describe, and only then
 * allocate and interpret. Validating before allocating is what turns a corrupt 4-byte length
 * field into a {@link CorruptRecordException} instead of an {@code OutOfMemoryError}, and "never
 * allocate based on a number you read off a disk before checking it" generalises well beyond this
 * project.
 */
public final class RecordCodec {

    private RecordCodec() {
        // static only
    }

    /**
     * Returns the exact number of bytes {@link #encode} will produce for {@code record}.
     *
     * <p>Callers need this to decide whether the active segment has room before appending, so it
     * must agree with {@code encode} exactly.
     */
    public static int encodedLength(Record record) {
        return RecordHeader.HEADER_BYTES + record.key().length() + record.value().length;
    }

    /**
     * Serialises {@code record} into a newly allocated buffer, ready to be written to a channel.
     *
     * @return a buffer whose position is 0 and whose limit is {@link #encodedLength}, i.e.
     *         already flipped and safe to hand straight to {@code FileChannel.write}
     */
    public static ByteBuffer encode(Record record) {
        int keyLength = record.key().length();
        int valueLength = record.value().length;
        int totalLength = RecordHeader.HEADER_BYTES + keyLength + valueLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);

        // Skip the checksum field; it can only be computed once everything after it is written.
        buffer.position(RecordHeader.CRC_COVERED_FROM);
        buffer.putLong(record.timestampMillis());
        buffer.put(record.tombstone() ? RecordHeader.FLAG_TOMBSTONE : 0);
        buffer.putInt(keyLength);
        buffer.putInt(valueLength);
        buffer.putLong(record.expiresAtMillis());
        // backingArray() rather than toByteArray(): a write path that only reads the bytes and
        // never retains them is the sanctioned use of the no-copy accessor. See decision 004.
        buffer.put(record.key().backingArray());
        buffer.put(record.value());

        buffer.putInt(RecordHeader.CRC_OFFSET, checksum(buffer, RecordHeader.CRC_COVERED_FROM, totalLength));

        buffer.flip();
        return buffer;
    }

    /**
     * Deserialises one record from {@code buffer}, starting at its current position.
     *
     * <p>On success the buffer's position is left immediately after the record. On failure the
     * position is unchanged, so a caller that wants to retry or report an offset still knows
     * where the record started.
     *
     * @throws CorruptRecordException if the buffer holds fewer bytes than the record claims, if a
     *         length field is impossible, if an unrecognised flag bit is set, or if the CRC does
     *         not match
     */
    public static Record decode(ByteBuffer buffer) {
        // A view forced to big-endian, so a caller's little-endian buffer cannot silently
        // misread. Absolute gets below are indices into the buffer, unaffected by position.
        ByteBuffer source = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        int start = source.position();
        int available = source.remaining();

        if (available < RecordHeader.HEADER_BYTES) {
            throw new CorruptRecordException(
                    "truncated header: " + available + " bytes available, need at least "
                            + RecordHeader.HEADER_BYTES);
        }

        int storedChecksum = source.getInt(start + RecordHeader.CRC_OFFSET);
        long timestampMillis = source.getLong(start + RecordHeader.TIMESTAMP_OFFSET);
        byte flags = source.get(start + RecordHeader.FLAGS_OFFSET);
        int keyLength = source.getInt(start + RecordHeader.KEY_LEN_OFFSET);
        int valueLength = source.getInt(start + RecordHeader.VALUE_LEN_OFFSET);
        long expiresAtMillis = source.getLong(start + RecordHeader.EXPIRES_AT_OFFSET);

        if (keyLength < 0 || valueLength < 0) {
            throw new CorruptRecordException(
                    "impossible length field: keyLen=" + keyLength + ", valueLen=" + valueLength);
        }

        // Widened to long deliberately: two large ints would overflow an int sum and could wrap
        // back into a plausible-looking total.
        long totalLength = (long) RecordHeader.HEADER_BYTES + keyLength + valueLength;
        if (totalLength > available) {
            throw new CorruptRecordException(
                    "truncated record: claims " + totalLength + " bytes (header "
                            + RecordHeader.HEADER_BYTES + " + key " + keyLength + " + value "
                            + valueLength + "), only " + available + " available");
        }

        int recordLength = (int) totalLength;
        int computedChecksum = checksum(buffer, start + RecordHeader.CRC_COVERED_FROM, start + recordLength);
        if (computedChecksum != storedChecksum) {
            throw new CorruptRecordException(String.format(
                    "checksum mismatch: stored=0x%08x computed=0x%08x over %d bytes",
                    storedChecksum, computedChecksum, recordLength - RecordHeader.CRC_BYTES));
        }

        // Past this point the bytes are known-good, so the header can be trusted.
        boolean tombstone = (flags & RecordHeader.FLAG_TOMBSTONE) != 0;
        byte unknownFlags = (byte) (flags & ~RecordHeader.FLAG_TOMBSTONE);
        if (unknownFlags != 0) {
            // The CRC passed, so this was written deliberately by something that understood more
            // of the format than we do. There is no version field to negotiate with, and
            // interpreting the record as if the bit were absent risks serving a value a newer
            // writer considered dead. See decision 002.
            throw new CorruptRecordException(
                    String.format("unrecognised flag bits 0x%02x — record written by a newer format",
                            unknownFlags));
        }
        if (tombstone && valueLength != 0) {
            throw new CorruptRecordException("tombstone carries a " + valueLength + "-byte value");
        }
        if (tombstone && expiresAtMillis != RecordHeader.NO_EXPIRY) {
            throw new CorruptRecordException("tombstone carries an expiry: " + expiresAtMillis);
        }

        byte[] keyBytes = new byte[keyLength];
        byte[] value = new byte[valueLength];
        ByteBuffer body = buffer.duplicate();
        body.limit(start + recordLength).position(start + RecordHeader.HEADER_BYTES);
        body.get(keyBytes);
        body.get(value);

        buffer.position(start + recordLength);

        // Key.of copies. wrapNoCopy would be provably safe here — keyBytes is local and never
        // escapes — but ROADMAP.md asks for a profiler before taking that shortcut. See 004.
        return tombstone
                ? Record.tombstone(Key.of(keyBytes), timestampMillis)
                : new Record(Key.of(keyBytes), value, timestampMillis, expiresAtMillis, false);
    }

    /**
     * CRC32 over {@code buffer} in {@code [from, to)}, leaving the caller's buffer untouched.
     *
     * <p>{@code CRC32.update(ByteBuffer)} consumes the buffer's remaining bytes, hence the
     * duplicate: the position and limit being manipulated belong to a throwaway view.
     */
    private static int checksum(ByteBuffer buffer, int from, int to) {
        ByteBuffer covered = buffer.duplicate();
        // Limit first, then position: setting a limit below the current position would throw.
        covered.limit(to).position(from);
        CRC32 crc32 = new CRC32();
        crc32.update(covered);
        // getValue returns an unsigned 32-bit value in a long; the cast keeps the low 32 bits,
        // which is exactly what was stored, so comparing the ints is correct.
        return (int) crc32.getValue();
    }
}