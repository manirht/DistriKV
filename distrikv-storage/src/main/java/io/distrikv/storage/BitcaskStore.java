package io.distrikv.storage;

import java.util.Optional;
import java.util.Set;

/**
 * Milestone 1.4 — the engine. Wires the codec, the segments and the KeyDir into a working store.
 *
 * <p><b>Yours to implement.</b> Do 1.1, 1.2 and 1.3 first; this class should then be short, and
 * if it isn't, logic has ended up here that belonged in one of them.
 *
 * <h2>put</h2>
 *
 * <pre>
 *   1. validate key/value sizes against the config
 *   2. build a Record with the current timestamp
 *   3. encode it
 *   4. roll the segment over first if this record wouldn't fit (milestone 1.6)
 *   5. append → get back an offset
 *   6. fsync, if the policy says so
 *   7. update the KeyDir
 * </pre>
 *
 * <p>Step 7 comes after step 6, and the order is not arbitrary. Suppose you updated the index
 * before the data was durable: a reader would see the new value, the machine would lose power,
 * and after restart the value would be gone — having already been served to a client that
 * believed it. Making the index visible only once the data behind it is safe is the entire
 * discipline of write-ahead logging, and it's the same principle you'll apply to Raft's
 * persistent state in milestone 3.1. Get comfortable with it here where it's cheap.
 *
 * <h2>delete</h2>
 *
 * <p>Append a tombstone, then remove the key from the index — same ordering, same reason. And
 * notice that a delete makes the store <em>bigger</em>. That's not a flaw to fix; it's the
 * trade-off of an append-only design, and compaction is the payment plan.
 *
 * <h2>get</h2>
 *
 * <p>Index lookup, then one positional read at the recorded offset, then decode. If the record
 * turns out to be a tombstone or expired, return empty — and think about whether the index should
 * ever contain a pointer to a tombstone in the first place.
 *
 * <h2>Concurrency (milestone 1.7 — ignore until then)</h2>
 *
 * <p>Write 1.4 single-threaded and get the tests green. When you come back:
 * {@link java.util.concurrent.locks.ReentrantReadWriteLock} is the natural fit, because appends
 * must serialise against each other while reads needn't. But before you reach for it, work out
 * what a reader can see if it grabs a {@code ValueLocation} from the index and then does its disk
 * read while a rollover is happening. Is the segment it wants still open? That question, not the
 * choice of lock class, is the substance of 1.7.
 */
public final class BitcaskStore implements KeyValueStore {

    private BitcaskStore() {
        // use open(...)
    }

    /**
     * Opens (or creates) a store in {@code config.dataDir()}.
     *
     * <p>Creates the directory if needed, discovers existing segments, replays them via
     * {@code LogReplayer} to rebuild the index, then opens a new active segment for writing.
     *
     * <p>One thing to decide before you write it: if the directory contains a segment that fails
     * to decode in the middle, should {@code open} throw, or open in a degraded read-only mode, or
     * quarantine the file and continue? Real stores do all three under different configuration.
     * Pick one, and make sure the behaviour is obvious to a caller rather than surprising.
     */
    public static BitcaskStore open(StoreConfig config) {
        throw new UnsupportedOperationException("TODO milestone 1.4 — open");
    }

    @Override
    public Optional<byte[]> get(Key key) {
        throw new UnsupportedOperationException("TODO milestone 1.4 — get");
    }

    @Override
    public void put(Key key, byte[] value) {
        throw new UnsupportedOperationException("TODO milestone 1.4 — put");
    }

    @Override
    public boolean delete(Key key) {
        throw new UnsupportedOperationException("TODO milestone 1.4 — delete");
    }

    @Override
    public boolean contains(Key key) {
        throw new UnsupportedOperationException("TODO milestone 1.4 — contains");
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException("TODO milestone 1.4 — size");
    }

    @Override
    public Set<Key> keys() {
        throw new UnsupportedOperationException("TODO milestone 1.4 — keys");
    }

    @Override
    public void sync() {
        throw new UnsupportedOperationException("TODO milestone 1.4 — sync");
    }

    /**
     * Flushes, fsyncs, and closes every open segment.
     *
     * <p>Must be idempotent, and must not lose data if called while writes are in flight. Also:
     * what should a {@code get} after {@code close} do? Deciding that a closed store throws
     * {@code IllegalStateException} rather than returning empty is a small thing that will save
     * you real confusion in Phase 2, where the server's shutdown hook makes this a live race.
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("TODO milestone 1.4 — close");
    }
}
