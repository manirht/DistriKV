package io.distrikv.storage.index;

/**
 * Where a key's current value physically lives. The value half of the in-memory index.
 *
 * <p>This is the whole trick of Bitcask: RAM holds only {@code key → location}, never values, so
 * a {@code get} is one index lookup plus exactly one disk read. No tree to descend, no bloom
 * filter, no layered lookup. Predictable, and that predictability is the design's selling point.
 *
 * <p>It's also the design's hard limit. Every key that has ever been written and not deleted
 * costs you RAM forever, whether it's read once a second or never again. Work it out for your
 * own numbers: with 64-byte keys, roughly how much heap does this index consume at 100 million
 * keys, once you account for the {@code Key} object header, its {@code byte[]}, this record, and
 * {@code HashMap.Node} overhead? That figure is the honest capacity of your store, and ROADMAP.md
 * asks you to compute it because you should be able to answer it in an interview without
 * hesitating.
 *
 * @param segmentId     which segment file holds the record
 * @param offset        byte offset of the record's first byte within that segment
 * @param recordLength  total encoded length, so a reader can do a single sized read
 * @param timestampMillis the record's timestamp; the replayer needs it to resolve which of two
 *                        records for the same key is newer (milestone 1.5)
 */
public record ValueLocation(
        int segmentId,
        long offset,
        int recordLength,
        long timestampMillis) {

    public ValueLocation {
        if (segmentId < 0) {
            throw new IllegalArgumentException("segmentId must be >= 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (recordLength <= 0) {
            throw new IllegalArgumentException("recordLength must be > 0");
        }
    }
}
