package io.distrikv.storage;

import java.io.Closeable;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * The engine's public API. Everything above this line — the gRPC service in Phase 2, the Raft
 * state machine in Phase 3 — talks only to this interface.
 *
 * <p>Keeping that true is what will let you write a second engine later (an LSM tree, as a
 * stretch goal) and benchmark it against this one by swapping one line. It's also what keeps
 * Phase 3 debuggable: when a cluster loses a write, being able to say "the engine is proven
 * correct by Phase 1's tests, so the bug is in Raft" is worth a great deal at 1am.
 *
 * <h2>A decision I made for you, which you should revisit</h2>
 *
 * <p>{@link #get} returns {@code Optional<byte[]>}. This is ROADMAP.md Phase 1 decision #3, and
 * I've picked one so you have something that compiles. The case against it: it allocates an
 * {@code Optional} wrapper on every single read, on the hottest path in the system, and
 * high-performance stores generally return {@code null} or write into a caller-supplied buffer
 * for exactly that reason. The case for it: a {@code null} return that means "absent" is
 * indistinguishable from a bug, and this interface will be called by code you haven't written
 * yet.
 *
 * <p>Either answer is defensible. An unexamined one isn't — so if you change it, put the
 * reasoning in {@code docs/decisions/}, and if you keep it, put that reasoning there too.
 */
public interface KeyValueStore extends Closeable {

    /**
     * The current value for {@code key}, or empty if it was never written, was deleted, or has
     * expired.
     *
     * <p>Those three cases being indistinguishable to the caller is intentional — but notice it's
     * a choice, and a store that wanted to support "has this key ever existed" queries would need
     * to expose more.
     */
    Optional<byte[]> get(Key key);

    /** Writes {@code value} for {@code key}, replacing any previous value. */
    void put(Key key, byte[] value);

    /**
     * Writes a value that expires after {@code ttl}.
     *
     * <p>Phase 4. The header field is already reserved in {@link io.distrikv.storage.record.RecordHeader},
     * so the on-disk format doesn't have to change when you get here — which is the whole reason
     * it's reserved now. Format migrations on a store that holds real data are genuinely
     * unpleasant, and the cheapest time to avoid one is before you've written any data at all.
     */
    default void put(Key key, byte[] value, Duration ttl) {
        throw new UnsupportedOperationException("TTL arrives in milestone 4.5");
    }

    /**
     * Deletes {@code key}.
     *
     * @return true if the key had a live value before this call
     */
    boolean delete(Key key);

    /** Whether {@code key} currently has a live value. */
    boolean contains(Key key);

    /** Number of live keys. */
    int size();

    /**
     * A snapshot of the live key set.
     *
     * <p>Fine at Phase 1 scale and a liability later: it materialises every key in the store into
     * a set. Phase 2's {@code Scan} RPC is server-streaming precisely so it doesn't have to do
     * this. Worth noticing now that an API can be correct and still be the wrong shape.
     */
    Set<Key> keys();

    /** Forces everything written so far to the physical device. */
    void sync();

    @Override
    void close();
}
