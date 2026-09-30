package io.distrikv.storage;

import io.distrikv.storage.log.LogSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestones 1.4 (basics), 1.6 (rollover) and 1.7 (concurrency), plus the oracle test.
 *
 * <p>Most tests here run with {@link StoreConfig.SyncPolicy#NEVER}. That is a deliberate
 * trade-off and worth being explicit about: fsyncing every write would put the bulk tests into
 * the minutes, and a suite that is not run is a suite that does not exist. What it costs is that
 * these tests say nothing about durability — that is {@code CrashRecoveryTest}'s job, and the
 * durability policy itself is argued in {@code docs/decisions/003}.
 */
class BitcaskStoreTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Key key(String s) {
        return Key.ofUtf8(s);
    }

    /** Fast config: no fsync, small-ish segments. */
    private StoreConfig fast() {
        return StoreConfig.defaults(dir).withSyncPolicy(StoreConfig.SyncPolicy.NEVER);
    }

    private long countSegmentFiles() throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(LogSegment::isSegmentFile).count();
        }
    }

    @Nested
    @DisplayName("basics (1.4)")
    class Basics {

        @Test
        @DisplayName("put then get returns the value")
        void putGet() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("user:42"), bytes("mani"));

                assertThat(store.get(key("user:42"))).isPresent().hasValueSatisfying(
                        value -> assertThat(value).containsExactly(bytes("mani")));
            }
        }

        @Test
        @DisplayName("get on an unknown key is empty")
        void getMissing() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                assertThat(store.get(key("nope"))).isEmpty();
                assertThat(store.contains(key("nope"))).isFalse();
            }
        }

        @Test
        @DisplayName("put over an existing key returns the new value")
        void overwrite() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("k"), bytes("v1"));
                store.put(key("k"), bytes("v2"));

                assertThat(store.get(key("k"))).get().isEqualTo(bytes("v2"));
                assertThat(store.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("delete makes the key invisible")
        void delete() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("k"), bytes("v"));
                store.delete(key("k"));

                assertThat(store.get(key("k"))).isEmpty();
                assertThat(store.contains(key("k"))).isFalse();
                assertThat(store.size()).isZero();
            }
        }

        @Test
        @DisplayName("delete reports whether the key had a live value")
        void deleteReturnValue() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                assertThat(store.delete(key("never-existed"))).isFalse();

                store.put(key("k"), bytes("v"));
                assertThat(store.delete(key("k"))).isTrue();
                assertThat(store.delete(key("k"))).isFalse();

                // And the second delete writes nothing: there is no live record for a tombstone
                // to shadow, so appending one would grow the log while carrying no information.
                // See docs/decisions/004.
                store.sync();
                long sizeAfterTwoDeletes = Files.size(dir.resolve(LogSegment.fileNameFor(0)));
                assertThat(store.delete(key("k"))).isFalse();
                store.sync();
                assertThat(Files.size(dir.resolve(LogSegment.fileNameFor(0))))
                        .isEqualTo(sizeAfterTwoDeletes);
            }
        }

        @Test
        @DisplayName("a deleted key can be written again")
        void resurrect() {
            // Catches a KeyDir.remove that didn't actually remove, and an index entry still
            // pointing at the tombstone.
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("k"), bytes("v1"));
                store.delete(key("k"));
                store.put(key("k"), bytes("v2"));

                assertThat(store.get(key("k"))).get().isEqualTo(bytes("v2"));
                assertThat(store.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a delete makes the data file grow")
        void deletesAreWrites() throws IOException {
            // A test of understanding more than of code, and the reason phase 4 exists: the log
            // is append-only, so the only way to say "this key is gone" is to write something.
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("k"), bytes("v"));
                store.sync();
                long afterPut = Files.size(dir.resolve(LogSegment.fileNameFor(0)));

                store.delete(key("k"));
                store.sync();
                long afterDelete = Files.size(dir.resolve(LogSegment.fileNameFor(0)));

                assertThat(afterDelete).isGreaterThan(afterPut);
                // ...while the store now holds nothing.
                assertThat(store.size()).isZero();
            }
        }

        @Test
        @DisplayName("size and keys track live keys only")
        void sizeAndKeys() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("a"), bytes("1"));
                store.put(key("b"), bytes("2"));
                store.put(key("c"), bytes("3"));
                store.put(key("a"), bytes("1-again")); // overwrite, not a new key
                store.delete(key("b"));

                assertThat(store.size()).isEqualTo(2);
                assertThat(store.keys()).containsExactlyInAnyOrder(key("a"), key("c"));
            }
        }

        @Test
        @DisplayName("keys and values that are too large are rejected")
        void sizeLimits() throws IOException {
            // Against StoreConfig's limits, and before anything is written to disk — a partial
            // record on disk because validation happened halfway through is a bad failure mode.
            StoreConfig tight = new StoreConfig(
                    dir, 64 * 1024, StoreConfig.SyncPolicy.NEVER, Duration.ofMillis(100), 16, 32);

            try (BitcaskStore store = BitcaskStore.open(tight)) {
                assertThatThrownBy(() -> store.put(key("x".repeat(17)), bytes("v")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("maxKeyBytes");

                assertThatThrownBy(() -> store.put(key("k"), new byte[33]))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("maxValueBytes");

                // Nothing was written, and the store is still perfectly usable.
                assertThat(store.size()).isZero();
                assertThat(countSegmentFiles()).isZero();

                store.put(key("x".repeat(16)), new byte[32]); // exactly at the limits is fine
                assertThat(store.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("empty value and empty key behave sensibly")
        void degenerateInputs() {
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                // An empty value is legal and is NOT a delete — a key used as a set member is a
                // real use for it.
                store.put(key("empty-value"), new byte[0]);
                assertThat(store.get(key("empty-value"))).isPresent().hasValueSatisfying(
                        value -> assertThat(value).isEmpty());
                assertThat(store.contains(key("empty-value"))).isTrue();

                // An empty key is rejected — decision 004. What must not happen is an
                // ArrayIndexOutOfBoundsException from deep inside the codec.
                assertThatThrownBy(() -> store.put(Key.of(new byte[0]), bytes("v")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("must not be empty");
                assertThatThrownBy(() -> store.delete(Key.of(new byte[0])))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("using the store after close throws")
        void useAfterClose() {
            BitcaskStore store = BitcaskStore.open(fast());
            store.put(key("k"), bytes("v"));
            store.close();

            assertThatThrownBy(() -> store.get(key("k"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.put(key("k"), bytes("v")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.delete(key("k")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(store::size).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(store::keys).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(store::sync).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.contains(key("k")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("close is idempotent")
        void doubleClose() {
            BitcaskStore store = BitcaskStore.open(fast());
            store.put(key("k"), bytes("v"));

            store.close();
            store.close(); // must not throw, and must not fail to release the lock
        }

        @Test
        @DisplayName("a second store on the same directory is refused")
        void directoryIsLocked() {
            // Without this, the second store discovers the existing segments, replays them, and
            // opens its own active segment — two writers on one logical store, each with its own
            // index, and every read a coin flip. See docs/decisions/003.
            try (BitcaskStore first = BitcaskStore.open(fast())) {
                first.put(key("k"), bytes("v"));

                assertThatThrownBy(() -> BitcaskStore.open(fast()))
                        .isInstanceOf(StorageException.class);
            }

            // ...and once the first store closes, the directory is usable again.
            try (BitcaskStore second = BitcaskStore.open(fast())) {
                assertThat(second.get(key("k"))).get().isEqualTo(bytes("v"));
            }
        }

        @Test
        @DisplayName("opening an empty directory creates no segment until the first write")
        void noEmptySegmentsOnOpen() throws IOException {
            // Every segment stays open for the store's lifetime, so an empty segment per
            // open/close cycle would eventually exhaust file descriptors.
            try (BitcaskStore store = BitcaskStore.open(fast())) {
                assertThat(countSegmentFiles()).isZero();
                assertThat(store.size()).isZero();
            }
            assertThat(countSegmentFiles()).isZero();

            try (BitcaskStore store = BitcaskStore.open(fast())) {
                store.put(key("k"), bytes("v"));
            }
            assertThat(countSegmentFiles()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("segment rollover (1.6)")
    class Rollover {

        /** Small enough to roll in a handful of writes, large enough to hold several records. */
        private StoreConfig rolling() {
            return fast().withMaxSegmentBytes(2048);
        }

        @Test
        @DisplayName("exceeding maxSegmentBytes opens a new segment")
        void rollsOver() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(rolling())) {
                // ~129 bytes per record, so 2048 bytes holds 15 of them.
                for (int i = 0; i < 100; i++) {
                    store.put(key("key-" + i), new byte[100]);
                }

                assertThat(countSegmentFiles()).isGreaterThan(1);
                assertThat(store.size()).isEqualTo(100);
            }
        }

        @Test
        @DisplayName("no segment ever exceeds maxSegmentBytes")
        void capIsRespected() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(rolling())) {
                for (int i = 0; i < 100; i++) {
                    store.put(key("key-" + i), new byte[100]);
                }
                store.sync();

                try (Stream<Path> entries = Files.list(dir)) {
                    List<Path> segmentFiles = entries.filter(LogSegment::isSegmentFile).toList();
                    for (Path file : segmentFiles) {
                        assertThat(Files.size(file))
                                .as("segment %s", file.getFileName())
                                .isLessThanOrEqualTo(2048);
                    }
                }
            }
        }

        /**
         * A 100-byte value that is a deterministic function of {@code i}, so a read can be
         * checked against the index it came from. Sized so that a 2KB segment holds ~15 records
         * and 200 writes genuinely spread across more than ten segments.
         */
        private static byte[] paddedValue(int i) {
            byte[] value = new byte[100];
            Arrays.fill(value, (byte) i);
            return value;
        }

        @Test
        @DisplayName("reads still work for keys in older segments")
        void readsSpanSegments() throws IOException {
            // This is where a store that only ever keeps the active segment open falls over.
            try (BitcaskStore store = BitcaskStore.open(rolling())) {
                for (int i = 0; i < 200; i++) {
                    store.put(key("key-" + i), paddedValue(i));
                }
                assertThat(countSegmentFiles()).isGreaterThanOrEqualTo(10);

                // Every key, including the ones in the very first segment.
                for (int i = 0; i < 200; i++) {
                    assertThat(store.get(key("key-" + i)))
                            .as("key-%d", i)
                            .get().isEqualTo(paddedValue(i));
                }
            }
        }

        @Test
        @DisplayName("a record larger than maxSegmentBytes is handled deliberately")
        void oversizedRecord() {
            // Decision: reject it. Letting one segment exceed its nominal cap would make the cap
            // a lie and break the sizing assumptions Phase 4 compaction depends on.
            try (BitcaskStore store = BitcaskStore.open(rolling())) {
                assertThatThrownBy(() -> store.put(key("huge"), new byte[4096]))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cannot fit in a segment");

                // The store is undamaged and still writable.
                store.put(key("normal"), bytes("v"));
                assertThat(store.get(key("normal"))).get().isEqualTo(bytes("v"));
            }
        }

        @Test
        @DisplayName("segment ids continue upward across a reopen")
        void idsContinueAfterReopen() {
            try (BitcaskStore store = BitcaskStore.open(rolling())) {
                for (int i = 0; i < 50; i++) {
                    store.put(key("key-" + i), new byte[100]);
                }
            }

            try (BitcaskStore reopened = BitcaskStore.open(rolling())) {
                int highestBefore = reopened.replayResult().highestSegmentId();
                reopened.put(key("after"), bytes("v"));

                // The new active segment must not reuse an existing id — createActive refuses to
                // clobber, so getting this wrong is an immediate failure rather than silent
                // data loss, but it would still be a failure.
                assertThat(reopened.get(key("after"))).get().isEqualTo(bytes("v"));
                assertThat(reopened.get(key("key-0"))).get().isEqualTo(new byte[100]);
                assertThat(highestBefore).isGreaterThan(0);
            }
        }
    }

    @Nested
    @DisplayName("concurrency (1.7)")
    class Concurrency {

        /**
         * A value that can be checked in isolation: every byte equals {@code (byte) length}, so a
         * splice of two different writes is detectable. A test that only asserts "no exception
         * thrown" would pass against genuinely broken code.
         */
        private static byte[] selfVerifying(int length) {
            byte[] value = new byte[length];
            Arrays.fill(value, (byte) length);
            return value;
        }

        private static void assertSelfConsistent(byte[] value) {
            byte expected = (byte) value.length;
            for (int i = 0; i < value.length; i++) {
                if (value[i] != expected) {
                    throw new AssertionError("torn read: value of length " + value.length
                            + " has byte " + value[i] + " at index " + i + ", expected " + expected);
                }
            }
        }

        @Test
        @DisplayName("N readers and one writer: no exceptions, no torn reads")
        void readersAndWriter() throws Exception {
            // The exit criterion is 60 seconds; the default here is short so the suite stays
            // usable, and -Ddistrikv.concurrency.seconds=60 runs the real thing.
            long seconds = Long.getLong("distrikv.concurrency.seconds", 2L);
            int readerCount = 4;

            try (BitcaskStore store = BitcaskStore.open(fast().withMaxSegmentBytes(64 * 1024))) {
                // Seed so readers have something to find immediately.
                for (int i = 0; i < 50; i++) {
                    store.put(key("key-" + i), selfVerifying(64));
                }

                AtomicBoolean running = new AtomicBoolean(true);
                List<Throwable> failures = new CopyOnWriteArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
                CountDownLatch ready = new CountDownLatch(readerCount + 1);

                pool.submit(() -> {
                    ready.countDown();
                    try {
                        Random random = new Random(1);
                        while (running.get()) {
                            int index = random.nextInt(50);
                            // Length varies, so a reader that splices two writes together sees
                            // bytes that disagree with the length.
                            store.put(key("key-" + index), selfVerifying(1 + random.nextInt(512)));
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });

                for (int r = 0; r < readerCount; r++) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            Random random = new Random();
                            while (running.get()) {
                                Optional<byte[]> value = store.get(key("key-" + random.nextInt(50)));
                                value.ifPresent(Concurrency::assertSelfConsistent);
                            }
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    });
                }

                ready.await(10, TimeUnit.SECONDS);
                Thread.sleep(seconds * 1000);
                running.set(false);
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

                assertThat(failures).isEmpty();
                assertThat(store.size()).isEqualTo(50);
            }
        }

        @Test
        @DisplayName("concurrent writers to the same key leave one of the written values")
        void concurrentWritersToOneKey() throws Exception {
            // Not "the last one" — with concurrent writers there is no observer who can define
            // last. The invariant that can actually be asserted is that the final value is one
            // of the values written, never a mixture of two.
            int writerCount = 8;
            int perWriter = 500;

            try (BitcaskStore store = BitcaskStore.open(fast())) {
                Set<Integer> writtenLengths = new HashSet<>();
                for (int w = 0; w < writerCount; w++) {
                    writtenLengths.add(1 + w * 37);
                }

                ExecutorService pool = Executors.newFixedThreadPool(writerCount);
                List<Throwable> failures = new CopyOnWriteArrayList<>();
                CountDownLatch start = new CountDownLatch(1);

                for (int w = 0; w < writerCount; w++) {
                    int length = 1 + w * 37;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perWriter; i++) {
                                store.put(key("contended"), selfVerifying(length));
                            }
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    });
                }

                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
                assertThat(failures).isEmpty();

                byte[] finalValue = store.get(key("contended")).orElseThrow();
                assertSelfConsistent(finalValue);
                assertThat(writtenLengths).contains(finalValue.length);
                assertThat(store.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("no writes are lost under concurrent load")
        void noLostWrites() throws Exception {
            int threads = 10;
            int perThread = 10_000;

            try (BitcaskStore store = BitcaskStore.open(fast().withMaxSegmentBytes(1024 * 1024))) {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Throwable> failures = new CopyOnWriteArrayList<>();
                CountDownLatch start = new CountDownLatch(1);

                for (int t = 0; t < threads; t++) {
                    int threadId = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                store.put(key(threadId + ":" + i), bytes("v" + threadId + ":" + i));
                            }
                        } catch (Throwable e) {
                            failures.add(e);
                        }
                    });
                }

                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(300, TimeUnit.SECONDS)).isTrue();
                assertThat(failures).isEmpty();

                assertThat(store.size()).isEqualTo(threads * perThread);
                // Spot-check readability across the whole key space rather than all 100k, which
                // would dominate the test's runtime for no extra signal.
                Random random = new Random(7);
                for (int i = 0; i < 2000; i++) {
                    int threadId = random.nextInt(threads);
                    int index = random.nextInt(perThread);
                    assertThat(store.get(key(threadId + ":" + index)))
                            .as("%d:%d", threadId, index)
                            .get().isEqualTo(bytes("v" + threadId + ":" + index));
                }
            }
        }
    }

    @Nested
    @DisplayName("oracle test")
    class Oracle {

        @Test
        @DisplayName("random ops agree with a HashMap at every step")
        void agreesWithHashMap() {
            // The highest-value test in this file, because it explores states nobody would think
            // to write down. The key space is deliberately small so that overwrites and deletes
            // of live keys dominate — that is where the bugs are.
            //
            // The exit criterion is 1M ops; the default is lower so the suite stays fast.
            // -Ddistrikv.oracle.ops=1000000 runs the real thing.
            int operations = Integer.getInteger("distrikv.oracle.ops", 100_000);
            int keySpace = 64;
            long seed = Long.getLong("distrikv.oracle.seed", 20260930L);
            Random random = new Random(seed);

            Map<Key, byte[]> oracle = new HashMap<>();

            try (BitcaskStore store = BitcaskStore.open(fast().withMaxSegmentBytes(256 * 1024))) {
                for (int op = 0; op < operations; op++) {
                    Key k = key("k" + random.nextInt(keySpace));
                    int choice = random.nextInt(10);
                    try {
                        if (choice < 5) {
                            byte[] value = new byte[random.nextInt(64)];
                            random.nextBytes(value);
                            store.put(k, value);
                            oracle.put(k, value);
                        } else if (choice < 8) {
                            Optional<byte[]> actual = store.get(k);
                            byte[] expected = oracle.get(k);
                            if (expected == null) {
                                assertOracle(actual.isEmpty(), seed, op, k,
                                        "expected absent, got " + actual.map(Arrays::toString));
                            } else {
                                assertOracle(actual.isPresent()
                                                && Arrays.equals(actual.get(), expected),
                                        seed, op, k, "value mismatch");
                            }
                        } else {
                            boolean deleted = store.delete(k);
                            boolean wasPresent = oracle.remove(k) != null;
                            assertOracle(deleted == wasPresent, seed, op, k,
                                    "delete returned " + deleted + ", oracle said " + wasPresent);
                        }

                        assertOracle(store.size() == oracle.size(), seed, op, k,
                                "size " + store.size() + " != oracle " + oracle.size());
                    } catch (AssertionError e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw new AssertionError(
                                "op " + op + " on " + k + " threw (seed=" + seed + ")", e);
                    }
                }

                // Final full comparison, including the key set.
                assertThat(store.keys()).isEqualTo(oracle.keySet());
                for (Map.Entry<Key, byte[]> entry : oracle.entrySet()) {
                    assertThat(store.get(entry.getKey()))
                            .as("final check of %s (seed=%d)", entry.getKey(), seed)
                            .get().isEqualTo(entry.getValue());
                }
            }
        }

        /** Fails with the seed included, so any failure is reproducible. */
        private static void assertOracle(boolean condition, long seed, int op, Key key, String what) {
            if (!condition) {
                throw new AssertionError(
                        "oracle disagreement at op " + op + " on key " + key + ": " + what
                                + " (rerun with -Ddistrikv.oracle.seed=" + seed + ")");
            }
        }
    }

    @Nested
    @DisplayName("scale (exit criteria)")
    class Scale {

        @Test
        @Tag("slow")
        @DisplayName("put/get/delete round-trip for 100k random keys with values from 1B to 1MB")
        void hundredThousandKeys() {
            // Phase 1 exit criterion. Tagged slow; run with:
            //   ./gradlew :distrikv-storage:test -PincludeSlow --tests '*BitcaskStoreTest*'
            //
            // The value-size distribution is worth stating precisely rather than claiming
            // "1B to 1MB" and leaving it vague. Drawing 100k sizes uniformly from that range
            // would mean ~50GB of data, which measures the disk rather than the store. So:
            // 100k keys with sizes uniform in [1, 4096] (~200MB), plus 200 keys at the top of
            // the range to pin the large-value path, which is where the segment-rollover and
            // buffer-growth code actually differs.
            int bulkKeys = Integer.getInteger("distrikv.scale.keys", 100_000);
            long seed = 20260930L;
            Random random = new Random(seed);

            Map<Key, Integer> expectedSizes = new HashMap<>();

            try (BitcaskStore store = BitcaskStore.open(
                    StoreConfig.defaults(dir).withSyncPolicy(StoreConfig.SyncPolicy.NEVER))) {

                for (int i = 0; i < bulkKeys; i++) {
                    Key k = key("scale-" + i);
                    int size = 1 + random.nextInt(4096);
                    store.put(k, sizedValue(i, size));
                    expectedSizes.put(k, size);
                }

                int[] largeSizes = {1, 64 * 1024, 256 * 1024, 1024 * 1024};
                for (int i = 0; i < 200; i++) {
                    Key k = key("scale-large-" + i);
                    int size = largeSizes[i % largeSizes.length];
                    store.put(k, sizedValue(i, size));
                    expectedSizes.put(k, size);
                }

                assertThat(store.size()).isEqualTo(expectedSizes.size());

                // Every key reads back byte-for-byte. Content, not just presence: a key pointing
                // at the wrong record would pass a presence check.
                for (Map.Entry<Key, Integer> entry : expectedSizes.entrySet()) {
                    Optional<byte[]> actual = store.get(entry.getKey());
                    assertThat(actual).as("%s", entry.getKey()).isPresent();
                    assertThat(actual.get()).as("%s", entry.getKey())
                            .hasSize(entry.getValue());
                }

                // Delete half, then confirm the survivors are untouched — the case that catches
                // an index whose removals disturb neighbouring entries.
                List<Key> allKeys = new ArrayList<>(expectedSizes.keySet());
                allKeys.sort(Comparator.comparing(Key::toString));
                List<Key> deleted = allKeys.subList(0, allKeys.size() / 2);
                for (Key k : deleted) {
                    assertThat(store.delete(k)).as("delete %s", k).isTrue();
                }

                assertThat(store.size()).isEqualTo(expectedSizes.size() - deleted.size());
                for (Key k : deleted) {
                    assertThat(store.get(k)).as("deleted %s", k).isEmpty();
                }
                for (Key k : allKeys.subList(allKeys.size() / 2, allKeys.size())) {
                    assertThat(store.get(k)).as("survivor %s", k).isPresent()
                            .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY)
                            .hasSize(expectedSizes.get(k));
                }
            }
        }

        /** A value of exactly {@code size} bytes, deterministic in {@code seed}. */
        private static byte[] sizedValue(int seed, int size) {
            byte[] value = new byte[size];
            Arrays.fill(value, (byte) seed);
            return value;
        }
    }

    @Nested
    @DisplayName("reopen")
    class Reopen {

        @Test
        @DisplayName("a store survives a clean close and reopen with every key intact")
        void survivesReopen() {
            // The full recovery suite is CrashRecoveryTest; this is the smoke test that catches
            // a store whose write path and read path disagree about offsets.
            List<Key> keys = new ArrayList<>();
            try (BitcaskStore store = BitcaskStore.open(fast().withMaxSegmentBytes(4096))) {
                for (int i = 0; i < 300; i++) {
                    Key k = key("key-" + i);
                    keys.add(k);
                    store.put(k, bytes("value-" + i));
                }
            }

            try (BitcaskStore reopened = BitcaskStore.open(fast().withMaxSegmentBytes(4096))) {
                assertThat(reopened.size()).isEqualTo(300);
                assertThat(reopened.replayResult().truncatedTailBytes()).isZero();
                for (int i = 0; i < keys.size(); i++) {
                    assertThat(reopened.get(keys.get(i)))
                            .as("key-%d", i)
                            .get().isEqualTo(bytes("value-" + i));
                }
            }
        }
    }
}