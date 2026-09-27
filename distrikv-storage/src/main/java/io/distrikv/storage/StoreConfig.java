package io.distrikv.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a {@link BitcaskStore}.
 *
 * <p>Given to you complete — it's plumbing. But read {@link SyncPolicy} carefully, because
 * choosing between those options <em>is</em> the durability decision from ROADMAP.md Phase 1,
 * and it's the one that will show up in your Phase 5 benchmark curve.
 *
 * @param dataDir         directory holding segment files. Created if absent.
 * @param maxSegmentBytes the active segment rolls over once it reaches this size (milestone 1.6)
 * @param syncPolicy      what "durable" means for this store
 * @param syncInterval    only meaningful for {@link SyncPolicy#INTERVAL}
 * @param maxKeyBytes     reject larger keys rather than writing a record you can't read back
 * @param maxValueBytes   same, for values
 */
public record StoreConfig(
        Path dataDir,
        long maxSegmentBytes,
        SyncPolicy syncPolicy,
        Duration syncInterval,
        int maxKeyBytes,
        int maxValueBytes) {

    /**
     * When the engine asks the OS to actually put bytes on the physical device.
     *
     * <p>The thing to internalise: a successful {@code write()} does <b>not</b> mean your data
     * is safe. It means the kernel copied your bytes into the page cache. If the machine loses
     * power a moment later, those bytes are gone — and the {@code put()} call already returned
     * successfully to your caller. Only {@code FileChannel.force()} (i.e. {@code fsync}) moves
     * the guarantee to the device. Read danluu.com/file-consistency/ before you pick.
     */
    public enum SyncPolicy {
        /**
         * fsync on every write before {@code put} returns.
         *
         * <p>Strongest promise: if {@code put} returned, the write survives power loss. Also
         * dramatically the slowest — an fsync is hundreds of microseconds even on NVMe, so this
         * caps you at roughly single-digit thousands of writes/sec no matter how fast your code
         * is. This is the correct default for a store that claims durability.
         */
        EVERY_WRITE,

        /**
         * fsync on a timer.
         *
         * <p>Bounded data loss: at most {@code syncInterval} of acknowledged writes. This is
         * what most real systems actually run in production, and you should be able to state the
         * promise out loud: "we may lose up to 100ms of acknowledged writes on power loss."
         */
        INTERVAL,

        /**
         * Never fsync; rely on the OS to flush eventually.
         *
         * <p>Fastest, and it makes no durability promise whatsoever beyond process crash (the
         * page cache survives a {@code kill -9}, which is why your Phase 1 crash tests still
         * pass under this policy — a useful thing to understand). Fine for caches and
         * benchmarks, never for a Raft log.
         */
        NEVER
    }

    public StoreConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(syncPolicy, "syncPolicy");
        Objects.requireNonNull(syncInterval, "syncInterval");
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be > 0");
        }
        if (maxKeyBytes <= 0 || maxValueBytes <= 0) {
            throw new IllegalArgumentException("max key/value sizes must be > 0");
        }
        if (syncInterval.isNegative()) {
            throw new IllegalArgumentException("syncInterval must not be negative");
        }
    }

    /** Sensible starting point: 64MB segments, fsync every write, 4KB keys, 16MB values. */
    public static StoreConfig defaults(Path dataDir) {
        return new StoreConfig(
                dataDir,
                64L * 1024 * 1024,
                SyncPolicy.EVERY_WRITE,
                Duration.ofMillis(100),
                4 * 1024,
                16 * 1024 * 1024);
    }

    public StoreConfig withSyncPolicy(SyncPolicy policy) {
        return new StoreConfig(dataDir, maxSegmentBytes, policy, syncInterval, maxKeyBytes, maxValueBytes);
    }

    public StoreConfig withSyncInterval(Duration interval) {
        return new StoreConfig(dataDir, maxSegmentBytes, syncPolicy, interval, maxKeyBytes, maxValueBytes);
    }

    public StoreConfig withMaxSegmentBytes(long bytes) {
        return new StoreConfig(dataDir, bytes, syncPolicy, syncInterval, maxKeyBytes, maxValueBytes);
    }
}
