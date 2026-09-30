package io.distrikv.storage.index;

import io.distrikv.storage.Key;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The standard {@link KeyDir}: a {@link HashMap}, and deliberately nothing more.
 *
 * <h2>Not thread-safe, on purpose</h2>
 *
 * <p>This class does no locking and makes no attempt to be safe on its own. That is a decision,
 * not an omission. A {@code put} in {@code BitcaskStore} has to append to the log <em>and</em>
 * update this index, and those two steps are not one atomic operation — so swapping a
 * {@code ConcurrentHashMap} in here would make each map call individually safe while leaving the
 * gap between the append and the index update exactly as broken, and would advertise a safety
 * this index cannot actually provide. {@code BitcaskStore}'s write lock is what protects it. The
 * reasoning is in {@code docs/decisions/006}.
 *
 * <h2>Tie-breaking in {@link #putIfNewer}</h2>
 *
 * <p>An equal timestamp counts as newer, so the later-arriving record wins. Recovery therefore
 * has to scan in ascending {@code (segmentId, offset)} order, which {@code LogReplayer}
 * guarantees. The alternative — rejecting ties — would silently keep the <em>older</em> of two
 * records written in the same millisecond, which is common enough to matter. Full reasoning, and
 * the Phase 4 hazard it creates, are in {@code docs/decisions/002}.
 */
final class HashMapKeyDir implements KeyDir {

    private final Map<Key, ValueLocation> index = new HashMap<>();

    @Override
    public ValueLocation get(Key key) {
        return index.get(key);
    }

    @Override
    public void put(Key key, ValueLocation location) {
        index.put(key, location);
    }

    @Override
    public boolean putIfNewer(Key key, ValueLocation location) {
        ValueLocation existing = index.get(key);
        // >= and not >: an equal timestamp means the later-scanned record wins. See the class
        // javadoc.
        if (existing == null || location.timestampMillis() >= existing.timestampMillis()) {
            index.put(key, location);
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(Key key) {
        return index.remove(key) != null;
    }

    @Override
    public int size() {
        return index.size();
    }

    /**
     * An immutable copy of the live key set, taken at call time.
     *
     * <p>Returning {@code index.keySet()} would hand back a live view that throws
     * {@code ConcurrentModificationException} the moment anything writes during iteration. The
     * copy costs one allocation per key and is the reason {@code KeyValueStore.keys()} is
     * documented as unsuitable at scale — Phase 2's {@code Scan} RPC streams instead.
     */
    @Override
    public Set<Key> keys() {
        return Set.copyOf(index.keySet());
    }

    @Override
    public String toString() {
        return "KeyDir[" + index.size() + " keys]";
    }
}