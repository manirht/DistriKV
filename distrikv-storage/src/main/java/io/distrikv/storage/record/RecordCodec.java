package io.distrikv.storage.record;

import io.distrikv.storage.CorruptRecordException;

import java.nio.ByteBuffer;

/**
 * Milestone 1.1 — translates {@link Record} to and from its on-disk bytes.
 *
 * <p><b>This is yours to implement.</b> Start here, because everything downstream depends on the
 * byte format being right, and because {@code RecordCodecTest} already has real assertions
 * waiting for you. Run it now: it fails. Make it pass.
 *
 * <p>The layout is in {@link RecordHeader}. Work in {@link ByteBuffer} rather than raw index
 * arithmetic — it's less error-prone, and learning its position/limit model properly now will
 * save you in Phase 3 when you're framing Raft RPCs.
 *
 * <h2>Hints, in the order you'll want them</h2>
 *
 * <ol>
 *   <li>{@code ByteBuffer.allocate(n)} gives you a buffer with position 0 and limit n. After you
 *       write into it, position is at the end and limit is still n. A reader needs position 0 and
 *       limit = what you wrote. {@code flip()} does exactly that transformation. Forgetting it is
 *       the classic NIO bug, and the symptom is reads that return nothing or zeros.
 *   <li>The CRC covers bytes {@code [CRC_COVERED_FROM, end)} — so you have to write the rest of
 *       the record <em>first</em>, compute the checksum over it, then go back and fill in the CRC
 *       at offset 0. {@code putInt(index, value)} writes at an absolute index without touching
 *       position, which is what you want here.
 *   <li>{@link java.util.zip.CRC32} takes a {@code ByteBuffer} in {@code update()} — no need to
 *       copy into an intermediate array. Note that it consumes the buffer's remaining bytes, so
 *       mind the position before and after. Also: {@code CRC32.getValue()} returns a
 *       {@code long} holding an unsigned 32-bit value; you're storing it in 4 bytes, so a cast
 *       to {@code int} is correct but comparing them later needs care.
 *   <li>On decode, validate the length fields <em>before</em> trusting them. A corrupt
 *       {@code keyLen} of 2 billion will otherwise get you an {@code OutOfMemoryError} instead of
 *       a clean {@link CorruptRecordException}. Never allocate based on an unverified number read
 *       off a disk — that instinct generalises well beyond this project.
 *   <li>Check the CRC before you interpret anything. That's what it's for.
 * </ol>
 *
 * <p>When you're done, my review will ask: is {@code encode} allocating more than it needs to?
 * Is the decode path validating <em>everything</em> that could be wrong? Does a record whose
 * value is zero bytes long work, and is it distinguishable from a tombstone?
 */
public final class RecordCodec {

    private RecordCodec() {
        // static only
    }

    /**
     * Returns the exact number of bytes {@link #encode} will produce for {@code record}.
     *
     * <p>Callers need this to decide whether the active segment has room before appending, so it
     * must agree with {@code encode} exactly. Implement it without encoding anything.
     */
    public static int encodedLength(Record record) {
        throw new UnsupportedOperationException("TODO milestone 1.1 — encodedLength");
    }

    /**
     * Serialises {@code record} into a newly allocated buffer, ready to be written to a channel.
     *
     * @return a buffer whose position is 0 and whose limit is {@link #encodedLength}, i.e.
     *         already flipped and safe to hand straight to {@code FileChannel.write}
     */
    public static ByteBuffer encode(Record record) {
        throw new UnsupportedOperationException("TODO milestone 1.1 — encode");
    }

    /**
     * Deserialises one record from {@code buffer}, starting at its current position.
     *
     * <p>On success the buffer's position is left immediately after the record, so a caller can
     * decode a run of records from one buffer in a loop — which is exactly what
     * {@code LogReplayer} will want in milestone 1.5. Design for that now.
     *
     * @throws CorruptRecordException if the buffer holds fewer bytes than the record claims, if a
     *         length field is impossible, or if the CRC does not match. The message should say
     *         which of those it was — you will be reading these messages during crash tests, and
     *         "corrupt record" alone tells you nothing.
     */
    public static Record decode(ByteBuffer buffer) {
        throw new UnsupportedOperationException("TODO milestone 1.1 — decode");
    }
}
