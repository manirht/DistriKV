package io.distrikv.storage.index;

import io.distrikv.storage.Key;

import java.util.Set;

/**
 * Milestone 1.3 — the in-memory index: {@code Key → ValueLocation}.
 *
 * <p><b>Yours to implement.</b> It is a thin wrapper over a {@link java.util.Map}, and that is
 * entirely the point: keep it boring. Every clever thing you might do here (custom open
 * addressing, off-heap storage, memory-mapped index) belongs in Phase 5 after you've measured,
 * not now.
 *
 * <h2>Build it single-threaded first</h2>
 *
 * <p>Use a plain {@code HashMap} for 1.3 and don't think about concurrency yet. You'll revisit
 * this in milestone 1.7, and when you do, the interesting question is <em>not</em> "should I swap
 * in a {@code ConcurrentHashMap}". It's this: a {@code put} has to append to the log <em>and</em>
 * update this index, and those two steps are not one atomic operation. What can a concurrent
 * reader observe in between, and is that acceptable? A {@code ConcurrentHashMap} makes each
 * individual map operation safe while doing nothing about that gap. Getting clear on the
 * difference between "thread-safe data structure" and "correct concurrent algorithm" is the
 * actual lesson of 1.7, and it's worth more than the code.
 *
 * <h2>Why {@link #putIfNewer} exists</h2>
 *
 * <p>During recovery the replayer walks records in the order it finds them and must end up with
 * each key pointing at its <em>newest</em> record. If you scan segments oldest-to-newest and
 * records front-to-back within each, plain {@code put} is enough — later writes naturally
 * overwrite earlier ones. So why bother with {@code putIfNewer}?
 *
 * <p>Because that ordering is an assumption, and it's an assumption that Phase 4 breaks: once
 * compaction is producing merged segments, "highest segment id" no longer means "most recent
 * data". Making the timestamp comparison explicit now means recovery stays correct then, instead
 * of failing in a way that looks like random data loss months later. Deciding what your code is
 * allowed to assume is most of what designing a storage engine is.
 *
 * <p>(There's a trap in it too: wall-clock timestamps can tie, or even go backwards under NTP
 * adjustment. What should {@code putIfNewer} do on an exact tie, and can you defend it?)
 */
public interface KeyDir {

    /** Creates the standard single-threaded implementation. */
    static KeyDir create() {
        throw new UnsupportedOperationException("TODO milestone 1.3 — implement HashMapKeyDir");
    }

    /** The current location for {@code key}, or {@code null} if the store has no live value. */
    ValueLocation get(Key key);

    /** Unconditionally points {@code key} at {@code location}. */
    void put(Key key, ValueLocation location);

    /**
     * Points {@code key} at {@code location} only if it is newer than what's already indexed.
     *
     * @return true if the index was changed
     */
    boolean putIfNewer(Key key, ValueLocation location);

    /**
     * Drops {@code key} from the index.
     *
     * <p>Note what this does <em>not</em> do: it does not write anything to disk. Removing a key
     * from the index is how a delete becomes visible to readers; appending the tombstone is how
     * it survives a restart. {@code BitcaskStore.delete} needs both, and the order matters.
     *
     * @return true if the key was present
     */
    boolean remove(Key key);

    /** Number of live keys. */
    int size();

    /**
     * A snapshot of the live keys.
     *
     * <p>"Snapshot" is doing real work in that sentence. Returning the map's own key set would
     * hand callers a live view that throws {@code ConcurrentModificationException} the moment
     * anyone writes during iteration. Decide what you're returning and say so in the javadoc of
     * your implementation.
     */
    Set<Key> keys();
}
