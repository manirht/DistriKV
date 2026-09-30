package io.distrikv.storage;

import io.distrikv.storage.index.KeyDir;
import io.distrikv.storage.index.ValueLocation;
import io.distrikv.storage.log.LogSegment;
import io.distrikv.storage.recovery.LogReplayer;
import io.distrikv.storage.record.Record;
import io.distrikv.storage.record.RecordCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Milestones 1.4, 1.6 and 1.7 — the engine. Wires the codec, the segments and the KeyDir into a
 * working store.
 *
 * <h2>put</h2>
 *
 * <pre>
 *   1. validate key/value sizes against the config
 *   2. build a Record with the current timestamp
 *   3. encode it
 *   4. roll the segment over first if this record wouldn't fit
 *   5. append → get back an offset
 *   6. fsync, if the policy says so
 *   7. update the KeyDir
 * </pre>
 *
 * <p>Step 7 comes after step 6, and the order is not arbitrary. If the index were updated before
 * the data was durable, a reader could be served a value that a subsequent power loss destroys —
 * having already been acknowledged to a client. Making the index visible only once the data
 * behind it is safe is the whole discipline of write-ahead logging, and the same principle
 * applies to Raft's persistent state in milestone 3.1.
 *
 * <h2>Concurrency</h2>
 *
 * <p>One {@link ReentrantReadWriteLock}. Writers hold the write lock across append → fsync →
 * index update, so the gap between the log and the index is never observable. Readers hold the
 * read lock only for the index lookup and then do their disk read outside it, which is safe
 * because written bytes are immutable, every segment stays open for the store's lifetime, and
 * positional reads do not touch a channel's shared position. Full reasoning, and the
 * consequences to carry into Phase 4, are in {@code docs/decisions/006}.
 */
public final class BitcaskStore implements KeyValueStore {

    /** Name of the lock file that stops two processes sharing one data directory. */
    private static final String LOCK_FILE_NAME = "LOCK";

    private final StoreConfig config;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final KeyDir keyDir;

    /**
     * Every segment, by id, all open. Kept open deliberately: a reader that has taken a
     * {@link ValueLocation} under the read lock must be able to finish its read outside the lock
     * without the file being closed underneath it. The cost is one file descriptor per segment,
     * which makes segment count an operational limit — see decision 006.
     */
    private final Map<Integer, LogSegment> segments;

    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final LogReplayer.ReplayResult replayResult;

    /**
     * The segment being appended to, or {@code null} until the first write.
     *
     * <p>Created lazily so that opening and closing a store without writing leaves no empty
     * segment behind. Otherwise every open/close cycle would add a file, and since all segments
     * stay open, a long-lived process that reopens repeatedly would eventually exhaust its file
     * descriptors.
     */
    private LogSegment activeSegment;

    private int nextSegmentId;

    /**
     * Monotonic guard for record timestamps.
     *
     * <p>Wall-clock time can move backwards under an NTP adjustment, and {@code putIfNewer}
     * resolves duplicate keys by timestamp — so a backwards step could make a newer record look
     * older during recovery. Clamping to {@code max(last, now)} removes that within one running
     * store; across a restart, segment ordering resolves it. See decision 002.
     */
    private long lastTimestampMillis;

    private boolean closed;

    private BitcaskStore(
            StoreConfig config,
            KeyDir keyDir,
            Map<Integer, LogSegment> segments,
            FileChannel lockChannel,
            FileLock directoryLock,
            LogReplayer.ReplayResult replayResult) {
        this.config = config;
        this.keyDir = keyDir;
        this.segments = segments;
        this.lockChannel = lockChannel;
        this.directoryLock = directoryLock;
        this.replayResult = replayResult;
        this.nextSegmentId = replayResult.nextSegmentId();
    }

    /**
     * Opens (or creates) a store in {@code config.dataDir()}.
     *
     * <p>Creates the directory if needed, takes an exclusive lock on it, discovers existing
     * segments, replays them to rebuild the index, and opens each one for reading.
     *
     * <p>On finding corruption in the middle of a segment this throws
     * {@link CorruptRecordException} rather than degrading to read-only or quarantining the file.
     * Both alternatives lose data silently — a skipped or quarantined record takes every key
     * whose newest version lived there back to an older value, with nothing reporting a problem.
     * A store that refuses to open gets fixed; a store that lies does not. See decision 005.
     *
     * @throws StorageException if another process already holds the data directory
     */
    public static BitcaskStore open(StoreConfig config) {
        Objects.requireNonNull(config, "config");
        Path dataDir = config.dataDir();

        FileChannel lockChannel = null;
        FileLock directoryLock = null;
        Map<Integer, LogSegment> segments = new HashMap<>();
        try {
            Files.createDirectories(dataDir);
            lockChannel = FileChannel.open(
                    dataDir.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            directoryLock = acquireDirectoryLock(lockChannel, dataDir);

            KeyDir keyDir = KeyDir.create();
            List<Path> segmentFiles = LogReplayer.discoverSegments(dataDir);
            // Replay first: it may truncate a torn tail, and the read-only handles opened below
            // must see the already-corrected file size.
            LogReplayer.ReplayResult result = LogReplayer.replay(segmentFiles, keyDir);

            for (Path file : segmentFiles) {
                LogSegment segment = LogSegment.openReadOnly(file);
                segments.put(segment.id(), segment);
            }

            return new BitcaskStore(config, keyDir, segments, lockChannel, directoryLock, result);
        } catch (IOException e) {
            closeQuietly(segments, lockChannel, directoryLock);
            throw new StorageException("could not open store in " + dataDir, e);
        } catch (RuntimeException e) {
            // Corruption, a failed lock, anything else: the directory must not stay locked by a
            // store that never came into existence.
            closeQuietly(segments, lockChannel, directoryLock);
            throw e;
        }
    }

    private static FileLock acquireDirectoryLock(FileChannel lockChannel, Path dataDir) {
        try {
            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                throw new StorageException(
                        "another process already has " + dataDir + " open (held "
                                + LOCK_FILE_NAME + ")");
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            // Same JVM, second store on the same directory. The OS reports this differently from
            // the cross-process case, but the answer is identical.
            throw new StorageException(
                    "this JVM already has a store open on " + dataDir, e);
        } catch (IOException e) {
            throw new StorageException("could not lock " + dataDir, e);
        }
    }

    private static void closeQuietly(
            Map<Integer, LogSegment> segments, FileChannel lockChannel, FileLock directoryLock) {
        for (LogSegment segment : segments.values()) {
            try {
                segment.close();
            } catch (RuntimeException ignored) {
                // Already failing; the original cause is the interesting one.
            }
        }
        try {
            if (directoryLock != null) {
                directoryLock.release();
            }
        } catch (IOException ignored) {
            // As above.
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // As above.
        }
    }

    /**
     * What the last recovery found. Non-null for the store's whole lifetime.
     *
     * <p>Exposed because recovery that reports nothing cannot be debugged, and because
     * {@code truncatedTailBytes} is the only evidence that the torn-tail path ever runs.
     */
    public LogReplayer.ReplayResult replayResult() {
        return replayResult;
    }

    @Override
    public Optional<byte[]> get(Key key) {
        Objects.requireNonNull(key, "key");

        ValueLocation location;
        LogSegment segment;
        lock.readLock().lock();
        try {
            requireOpen();
            location = keyDir.get(key);
            if (location == null) {
                return Optional.empty();
            }
            segment = segments.get(location.segmentId());
            if (segment == null) {
                throw new StorageException(
                        "index points at segment " + location.segmentId() + " for key " + key
                                + ", which is not open — the index and the log have diverged");
            }
        } finally {
            lock.readLock().unlock();
        }

        // Outside the lock on purpose. Written bytes never change and the segment stays open, so
        // this location remains valid however much the store is mutated meanwhile. A concurrent
        // delete simply means this read ordered before it, which is a legal linearisation.
        Record record = segment.read(location.offset(), location.recordLength());

        // The index never points at a tombstone (delete removes the entry), so this can only
        // fire for a record that has expired since it was written — lazy expiry, milestone 4.5.
        if (record.isDeadAt(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(record.value());
    }

    @Override
    public void put(Key key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        validateSizes(key, value.length);

        lock.writeLock().lock();
        try {
            requireOpen();
            Record record = Record.value(key, value, nextTimestamp());
            append(record);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(Key key) {
        Objects.requireNonNull(key, "key");
        validateKey(key);

        lock.writeLock().lock();
        try {
            requireOpen();
            if (keyDir.get(key) == null) {
                // No live record, so nothing for a tombstone to shadow. Writing one anyway would
                // let a loop of deletes over missing keys grow the log without bound while
                // carrying no information. See decision 004.
                return false;
            }
            append(Record.tombstone(key, nextTimestamp()));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(Key key) {
        Objects.requireNonNull(key, "key");

        lock.readLock().lock();
        try {
            requireOpen();
            // Index-only, which is exact today because nothing can set an expiry yet —
            // KeyValueStore.put(key, value, ttl) still throws. Milestone 4.5 has to revisit
            // this: once records can expire, an index hit no longer proves the key is live, and
            // this either reads the record or ValueLocation starts carrying expiresAt.
            return keyDir.get(key) != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            requireOpen();
            return keyDir.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<Key> keys() {
        lock.readLock().lock();
        try {
            requireOpen();
            return keyDir.keys();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void sync() {
        lock.writeLock().lock();
        try {
            requireOpen();
            if (activeSegment != null) {
                activeSegment.sync();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            List<RuntimeException> failures = new ArrayList<>();
            if (activeSegment != null) {
                try {
                    activeSegment.sync();
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }
            for (LogSegment segment : segments.values()) {
                try {
                    segment.close();
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }
            try {
                directoryLock.release();
            } catch (IOException e) {
                failures.add(new StorageException("could not release the data directory lock", e));
            }
            try {
                lockChannel.close();
            } catch (IOException e) {
                failures.add(new StorageException("could not close the lock file", e));
            }

            if (!failures.isEmpty()) {
                RuntimeException first = failures.get(0);
                for (int i = 1; i < failures.size(); i++) {
                    first.addSuppressed(failures.get(i));
                }
                throw first;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- write path, under the lock

    /** Appends {@code record}, honours the sync policy, then publishes it to the index. */
    private void append(Record record) {
        ByteBuffer encoded = RecordCodec.encode(record);
        int recordLength = encoded.remaining();

        LogSegment segment = segmentWithRoomFor(recordLength);
        long offset = segment.append(encoded);

        if (config.syncPolicy() == StoreConfig.SyncPolicy.EVERY_WRITE) {
            // Before the index update, never after: see the class javadoc.
            segment.sync();
        }

        if (record.tombstone()) {
            keyDir.remove(record.key());
        } else {
            keyDir.put(record.key(), new ValueLocation(
                    segment.id(), offset, recordLength, record.timestampMillis()));
        }
    }

    /**
     * The active segment, rolling over first if {@code recordLength} would not fit — milestone
     * 1.6.
     */
    private LogSegment segmentWithRoomFor(int recordLength) {
        if (recordLength > config.maxSegmentBytes()) {
            // Letting one segment exceed its cap would make the cap a lie and break the sizing
            // assumptions compaction depends on in Phase 4. With the default 4KB key and 16MB
            // value limits against a 64MB segment this is unreachable, so it is a guard against
            // a misconfiguration rather than a routine path.
            throw new IllegalArgumentException(
                    "record of " + recordLength + " bytes cannot fit in a segment of "
                            + config.maxSegmentBytes() + " bytes");
        }

        if (activeSegment == null) {
            activeSegment = openNewActiveSegment();
        } else if (activeSegment.sizeBytes() + recordLength > config.maxSegmentBytes()) {
            // The outgoing segment is fsynced but deliberately left open: a reader may be part
            // way through a read against it right now, outside the lock. Nothing appends to it
            // again because only activeSegment is ever appended to.
            activeSegment.sync();
            activeSegment = openNewActiveSegment();
        }
        return activeSegment;
    }

    private LogSegment openNewActiveSegment() {
        LogSegment segment = LogSegment.createActive(config.dataDir(), nextSegmentId);
        nextSegmentId++;
        segments.put(segment.id(), segment);
        return segment;
    }

    /** Clamped to never go backwards. See {@link #lastTimestampMillis}. */
    private long nextTimestamp() {
        lastTimestampMillis = Math.max(lastTimestampMillis, System.currentTimeMillis());
        return lastTimestampMillis;
    }

    // ------------------------------------------------------------------------------- validation

    private void validateSizes(Key key, int valueLength) {
        validateKey(key);
        if (valueLength > config.maxValueBytes()) {
            throw new IllegalArgumentException(
                    "value of " + valueLength + " bytes exceeds maxValueBytes "
                            + config.maxValueBytes());
        }
    }

    private void validateKey(Key key) {
        // Validated before anything is written, so a rejected operation can never leave a
        // partial record on disk.
        if (key.length() == 0) {
            // Almost always an uninitialised caller buffer or an empty string that should have
            // been caught upstream, and indistinguishable from "no key supplied". Empty *values*
            // stay legal. See decision 004.
            throw new IllegalArgumentException("a key must not be empty");
        }
        if (key.length() > config.maxKeyBytes()) {
            throw new IllegalArgumentException(
                    "key of " + key.length() + " bytes exceeds maxKeyBytes " + config.maxKeyBytes());
        }
    }

    private void requireOpen() {
        if (closed) {
            // Not "return empty": a closed store looking like an empty one turns shutdown into
            // what appears to be data loss. This becomes a live race in Phase 2, where the
            // server's shutdown hook runs alongside in-flight requests.
            throw new IllegalStateException("store is closed (" + config.dataDir() + ")");
        }
    }

    @Override
    public String toString() {
        return "BitcaskStore[" + config.dataDir() + ", " + segments.size() + " segments]";
    }
}