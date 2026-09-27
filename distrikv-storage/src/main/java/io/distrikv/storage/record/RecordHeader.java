package io.distrikv.storage.record;

/**
 * The on-disk record layout, as constants.
 *
 * <pre>
 *  0        4          12      13       17       21        29
 *  +--------+----------+-------+--------+--------+---------+---------+-----------+
 *  | crc32  |timestamp | flags | keyLen | valLen |expiresAt|   key   |   value   |
 *  |   4B   |    8B    |  1B   |   4B   |   4B   |   8B    |  keyLen |  valLen   |
 *  +--------+----------+-------+--------+--------+---------+---------+-----------+
 *           |&lt;--------------------- CRC covers this --------------------------&gt;|
 * </pre>
 *
 * <p>All multi-byte fields are big-endian, which is {@link java.nio.ByteBuffer}'s default, so
 * you don't have to do anything to get it. (Worth knowing why it doesn't matter much here:
 * nothing in this format is compared as raw bytes across machines. It would matter a great deal
 * if you wanted lexicographic key ordering to fall out of byte ordering — which an LSM tree
 * does want. Another reason Bitcask is the easier first build.)
 *
 * <p>Constants are given; the encoding logic is yours. Before you write it, be ready to defend
 * every field — the review questions are listed in ROADMAP.md Phase 1.
 */
public final class RecordHeader {

    /** CRC32 of everything from {@link #CRC_COVERED_FROM} to the end of the value. */
    public static final int CRC_OFFSET = 0;
    public static final int CRC_BYTES = 4;

    /** Wall-clock millis when the record was written. */
    public static final int TIMESTAMP_OFFSET = 4;
    public static final int TIMESTAMP_BYTES = 8;

    /** Bit flags; see {@link #FLAG_TOMBSTONE}. */
    public static final int FLAGS_OFFSET = 12;
    public static final int FLAGS_BYTES = 1;

    public static final int KEY_LEN_OFFSET = 13;
    public static final int KEY_LEN_BYTES = 4;

    public static final int VALUE_LEN_OFFSET = 17;
    public static final int VALUE_LEN_BYTES = 4;

    /** Epoch millis after which this record is dead; {@code 0} means never expires. */
    public static final int EXPIRES_AT_OFFSET = 21;
    public static final int EXPIRES_AT_BYTES = 8;

    /** Total fixed header size, in bytes. */
    public static final int HEADER_BYTES = 29;

    /** The CRC is computed over the bytes starting here — i.e. everything but the CRC itself. */
    public static final int CRC_COVERED_FROM = CRC_OFFSET + CRC_BYTES;

    /** Set in {@link #FLAGS_OFFSET} when this record marks a deletion. */
    public static final byte FLAG_TOMBSTONE = 0x01;

    /** Sentinel in {@link #EXPIRES_AT_OFFSET} meaning "no expiry". */
    public static final long NO_EXPIRY = 0L;

    private RecordHeader() {
        // constants only
    }
}
