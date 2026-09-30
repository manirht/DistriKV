package io.distrikv.storage.recovery;

import io.distrikv.storage.BitcaskStore;
import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.Key;
import io.distrikv.storage.StoreConfig;
import io.distrikv.storage.log.LogSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 1.5 — and the reason the whole phase exists.
 *
 * <p>Everything else in Phase 1 can be green while the store still loses data. These are the
 * tests that prove the engine keeps its promise.
 *
 * <h2>Which tests use a real kill, and which don't</h2>
 *
 * <p>{@link HardKill#acknowledgedWritesSurvive} forks {@link CrashWriter}, reads its
 * acknowledgements, and SIGKILLs it — there is no substitute for that, because the claim being
 * tested is about a real process dying at an arbitrary instant.
 *
 * <p>The torn-tail tests instead damage the file deliberately. That is a considered choice: a
 * kill only <em>sometimes</em> lands mid-record, so asserting {@code truncatedTailBytes() > 0}
 * after one would be flaky, and a flaky test gets muted and then deleted. Planting the exact
 * damage makes the assertion deterministic while exercising precisely the same recovery path.
 * The kill test then asserts the property that <em>is</em> deterministic: nothing acknowledged
 * is ever lost, whether or not the tail happened to tear.
 *
 * <p>One caveat that explains a confusing result, worth understanding rather than working
 * around: SIGKILL kills the process, but the page cache belongs to the kernel, which is still
 * running. Writes that never reached the device still survive, so these tests pass even under
 * {@code SyncPolicy.NEVER}. Genuine power-loss testing needs a VM that can be hard-reset or a
 * fault-injecting filesystem. Knowing exactly what a test does and does not prove is part of the
 * skill.
 */
class CrashRecoveryTest {

    @TempDir
    Path dir;

    private StoreConfig config() {
        return StoreConfig.defaults(dir).withSyncPolicy(StoreConfig.SyncPolicy.NEVER);
    }

    private StoreConfig rollingConfig() {
        return config().withMaxSegmentBytes(2048);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Key key(String s) {
        return Key.ofUtf8(s);
    }

    private List<Path> segmentFiles() throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(LogSegment::isSegmentFile).sorted().toList();
        }
    }

    @Nested
    @DisplayName("clean restart")
    class CleanRestart {

        @Test
        @DisplayName("a closed and reopened store has every key")
        void reopenAfterClose() {
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 500; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }

            try (BitcaskStore reopened = BitcaskStore.open(config())) {
                assertThat(reopened.size()).isEqualTo(500);
                for (int i = 0; i < 500; i++) {
                    assertThat(reopened.get(key("k" + i))).as("k%d", i)
                            .get().isEqualTo(bytes("v" + i));
                }
            }
        }

        @Test
        @DisplayName("deletes survive a restart")
        void tombstonesSurvive() {
            // If the replayer applies records in the wrong order, or ignores tombstones, this is
            // what catches it.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                store.put(key("stays"), bytes("v"));
                store.put(key("goes"), bytes("v"));
                store.delete(key("goes"));
            }

            try (BitcaskStore reopened = BitcaskStore.open(config())) {
                assertThat(reopened.get(key("goes"))).isEmpty();
                assertThat(reopened.get(key("stays"))).isPresent();
                assertThat(reopened.size()).isEqualTo(1);
                assertThat(reopened.replayResult().tombstonesApplied()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a delete of a key written in an earlier segment survives")
        void tombstoneAcrossSegments() {
            // The tombstone lands in a later segment than the value it kills, so recovery only
            // gets this right if it applies segments in ascending order.
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                store.put(key("victim"), new byte[100]);
                for (int i = 0; i < 100; i++) {
                    store.put(key("filler-" + i), new byte[100]);
                }
                store.delete(key("victim"));
            }

            try (BitcaskStore reopened = BitcaskStore.open(rollingConfig())) {
                assertThat(reopened.get(key("victim"))).isEmpty();
                assertThat(reopened.size()).isEqualTo(100);
            }
        }

        @Test
        @DisplayName("the newest value wins after a restart")
        void newestWins() {
            // Across a rollover boundary on purpose: a store that gets this right within one
            // segment and wrong across two is the common failure.
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                for (int version = 1; version <= 10; version++) {
                    store.put(key("k"), bytes("v" + version));
                    // Push the next version into a new segment.
                    for (int i = 0; i < 20; i++) {
                        store.put(key("filler-" + version + "-" + i), new byte[100]);
                    }
                }
            }

            try (BitcaskStore reopened = BitcaskStore.open(rollingConfig())) {
                assertThat(reopened.replayResult().segmentsScanned()).isGreaterThan(1);
                assertThat(reopened.get(key("k"))).get().isEqualTo(bytes("v10"));
            }
        }

        @Test
        @DisplayName("ReplayResult reports no truncation after a clean shutdown")
        void noTruncationWhenClean() {
            // Paired with tornTailIsDiscarded: together they prove the torn-tail path exists AND
            // doesn't fire spuriously.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 100; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }

            try (BitcaskStore reopened = BitcaskStore.open(config())) {
                assertThat(reopened.replayResult().truncatedTailBytes()).isZero();
                assertThat(reopened.replayResult().liveKeys()).isEqualTo(100);
            }
        }

        @Test
        @DisplayName("an empty directory opens as an empty store")
        void emptyDirectory() {
            try (BitcaskStore store = BitcaskStore.open(config())) {
                assertThat(store.size()).isZero();
                assertThat(store.keys()).isEmpty();
                assertThat(store.replayResult().segmentsScanned()).isZero();
                assertThat(store.replayResult().highestSegmentId())
                        .isEqualTo(LogReplayer.ReplayResult.NO_SEGMENTS);
                assertThat(store.replayResult().nextSegmentId()).isZero();
            }
        }

        @Test
        @DisplayName("a zero-byte segment file is handled")
        void zeroLengthSegment() throws IOException {
            // Genuinely produced by crashing after rollover creates the file and before anything
            // is appended.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                store.put(key("k"), bytes("v"));
            }
            Files.createFile(dir.resolve(LogSegment.fileNameFor(5)));

            try (BitcaskStore reopened = BitcaskStore.open(config())) {
                assertThat(reopened.replayResult().segmentsScanned()).isEqualTo(2);
                assertThat(reopened.replayResult().truncatedTailBytes()).isZero();
                assertThat(reopened.get(key("k"))).get().isEqualTo(bytes("v"));

                // And the store keeps working, writing past the empty segment's id.
                reopened.put(key("k2"), bytes("v2"));
                assertThat(reopened.get(key("k2"))).get().isEqualTo(bytes("v2"));
            }
        }

        @Test
        @DisplayName("gaps in segment ids are normal, not corruption")
        void segmentIdGaps() throws IOException {
            // Phase 4 compaction merges segments and deletes its inputs, so 1, 2, 5 is the
            // expected steady state afterwards. A store that demands contiguity works today and
            // refuses to open after the first compaction.
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                for (int i = 0; i < 100; i++) {
                    store.put(key("k" + i), new byte[100]);
                }
            }

            List<Path> before = segmentFiles();
            assertThat(before).hasSizeGreaterThan(3);
            // Delete a middle segment, as compaction eventually will. The keys it held go with
            // it — this test is about the id gap being tolerated, not about data survival.
            Files.delete(before.get(1));

            try (BitcaskStore reopened = BitcaskStore.open(rollingConfig())) {
                assertThat(reopened.replayResult().segmentsScanned()).isEqualTo(before.size() - 1);
                assertThat(reopened.size()).isPositive();
            }
        }
    }

    @Nested
    @DisplayName("kill -9")
    class HardKill {

        /** How many acknowledgements to collect before killing the child. */
        private static final int ACKS_BEFORE_KILL =
                Integer.getInteger("distrikv.crash.acks", 400);

        @Test
        @DisplayName("every acknowledged write survives a forced kill")
        void acknowledgedWritesSurvive() throws Exception {
            // The central test of phase 1.
            long lastAck = runAndKillWriter();
            assertThat(lastAck).isGreaterThanOrEqualTo(ACKS_BEFORE_KILL - 1);

            try (BitcaskStore recovered = BitcaskStore.open(
                    StoreConfig.defaults(dir).withMaxSegmentBytes(4L * 1024 * 1024))) {
                for (long i = 0; i <= lastAck; i++) {
                    assertThat(recovered.get(CrashWriter.keyFor(i)))
                            .as("acknowledged key %d must have survived", i)
                            .get().isEqualTo(CrashWriter.valueFor(i, 8 * 1024));
                }
            }
        }

        @Test
        @DisplayName("the store is writable again after a forced kill")
        void writableAfterKill() throws Exception {
            long lastAck = runAndKillWriter();

            StoreConfig recoverConfig =
                    StoreConfig.defaults(dir).withMaxSegmentBytes(4L * 1024 * 1024);
            try (BitcaskStore recovered = BitcaskStore.open(recoverConfig)) {
                recovered.put(key("after-crash"), bytes("still-works"));
            }
            try (BitcaskStore again = BitcaskStore.open(recoverConfig)) {
                assertThat(again.get(key("after-crash"))).get().isEqualTo(bytes("still-works"));
                assertThat(again.get(CrashWriter.keyFor(lastAck))).isPresent();
                // Second recovery finds a clean log: the first one truncated any torn tail.
                assertThat(again.replayResult().truncatedTailBytes()).isZero();
            }
        }

        /**
         * Forks {@link CrashWriter}, waits for {@link #ACKS_BEFORE_KILL} acknowledgements, then
         * SIGKILLs it.
         *
         * @return the highest sequence number the child acknowledged
         */
        private long runAndKillWriter() throws Exception {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(
                    java.toString(),
                    "-cp", System.getProperty("java.class.path"),
                    CrashWriter.class.getName(),
                    dir.toString(),
                    "8192");
            builder.redirectErrorStream(false);
            Process process = builder.start();

            long lastAck = -1;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (lastAck < ACKS_BEFORE_KILL - 1 && (line = reader.readLine()) != null) {
                    lastAck = Long.parseLong(line.trim());
                }
            } finally {
                // destroyForcibly is SIGKILL on Linux and macOS: no shutdown hooks, no flush,
                // no cleanup. Exactly the scenario needed.
                process.destroyForcibly();
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(lastAck)
                    .as("child process produced no acknowledgements — check its stderr")
                    .isNotNegative();
            return lastAck;
        }
    }

    @Nested
    @DisplayName("torn tail")
    class TornTail {

        /** Appends {@code count} bytes of plausible-looking garbage to the newest segment. */
        private long plantTornTail(int count) throws IOException {
            List<Path> segments = segmentFiles();
            Path newest = segments.get(segments.size() - 1);
            long sizeBefore = Files.size(newest);
            byte[] garbage = new byte[count];
            // Not zeros: an all-zero tail would have a zero keyLen and valueLen, which decodes
            // to a plausible header and would be caught only by the checksum. Random-ish bytes
            // exercise the length-validation path instead.
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) (i * 37 + 11);
            }
            Files.write(newest, garbage, StandardOpenOption.APPEND);
            assertThat(Files.size(newest)).isEqualTo(sizeBefore + count);
            return sizeBefore;
        }

        @Test
        @DisplayName("a torn tail record is discarded and reported")
        void tornTailIsDiscarded() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 50; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }
            long goodBytes = plantTornTail(37);

            try (BitcaskStore recovered = BitcaskStore.open(config())) {
                assertThat(recovered.replayResult().truncatedTailBytes()).isEqualTo(37);
                assertThat(recovered.size()).isEqualTo(50);
                for (int i = 0; i < 50; i++) {
                    assertThat(recovered.get(key("k" + i))).as("k%d", i).isPresent();
                }
            }

            // The garbage is gone from the file, not merely ignored.
            List<Path> segments = segmentFiles();
            assertThat(Files.size(segments.get(segments.size() - 1))).isEqualTo(goodBytes);
        }

        @Test
        @DisplayName("a tail shorter than a header is discarded")
        void tailShorterThanAHeader() throws IOException {
            // The other shape of torn write: the file ends part way through the fixed header, so
            // there aren't even enough bytes to read a length field.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                store.put(key("k"), bytes("v"));
            }
            long goodBytes = plantTornTail(11);

            try (BitcaskStore recovered = BitcaskStore.open(config())) {
                assertThat(recovered.replayResult().truncatedTailBytes()).isEqualTo(11);
                assertThat(recovered.get(key("k"))).get().isEqualTo(bytes("v"));
            }
            List<Path> segments = segmentFiles();
            assertThat(Files.size(segments.get(segments.size() - 1))).isEqualTo(goodBytes);
        }

        @Test
        @DisplayName("the store is writable again after recovering from a torn tail")
        void writableAfterRecovery() throws IOException {
            // Catches failing to truncate the garbage: the next append lands after the torn
            // bytes, putting a permanently undecodable hole in the middle of a segment — and a
            // bad record mid-segment means every future recovery fails.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                store.put(key("before"), bytes("v"));
            }
            plantTornTail(37);

            try (BitcaskStore recovered = BitcaskStore.open(config())) {
                assertThat(recovered.replayResult().truncatedTailBytes()).isPositive();
                recovered.put(key("after"), bytes("v2"));
            }

            // The critical assertion: a *third* open still works. If the torn bytes had been
            // left in place, the record written above would sit behind them and this would throw.
            try (BitcaskStore again = BitcaskStore.open(config())) {
                assertThat(again.replayResult().truncatedTailBytes()).isZero();
                assertThat(again.get(key("before"))).get().isEqualTo(bytes("v"));
                assertThat(again.get(key("after"))).get().isEqualTo(bytes("v2"));
            }
        }

        @Test
        @DisplayName("a torn tail in the newest of several segments is still just a torn tail")
        void tornTailWithOlderSegmentsPresent() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                for (int i = 0; i < 100; i++) {
                    store.put(key("k" + i), new byte[100]);
                }
            }
            assertThat(segmentFiles()).hasSizeGreaterThan(3);
            plantTornTail(50);

            try (BitcaskStore recovered = BitcaskStore.open(rollingConfig())) {
                assertThat(recovered.replayResult().truncatedTailBytes()).isEqualTo(50);
                assertThat(recovered.size()).isEqualTo(100);
            }
        }
    }

    @Nested
    @DisplayName("real corruption")
    class Corruption {

        @Test
        @DisplayName("a corrupt record mid-segment is detected, not silently skipped")
        void midSegmentCorruptionDetected() throws IOException {
            // What must NOT happen is quietly carrying on, because then the key reverts to an
            // older value and nothing anywhere reports a problem. See docs/decisions/005.
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                for (int i = 0; i < 200; i++) {
                    store.put(key("k" + i), new byte[100]);
                }
            }

            List<Path> segments = segmentFiles();
            assertThat(segments).hasSizeGreaterThan(2);
            Path firstSegment = segments.get(0);

            // Flip one bit in the middle of the first segment — not the newest, so this cannot
            // be a torn write.
            try (RandomAccessFile file = new RandomAccessFile(firstSegment.toFile(), "rw")) {
                long position = Files.size(firstSegment) / 2;
                file.seek(position);
                int original = file.readUnsignedByte();
                file.seek(position);
                file.write(original ^ 0x01);
            }

            assertThatThrownBy(() -> BitcaskStore.open(rollingConfig()))
                    .isInstanceOf(CorruptRecordException.class)
                    .hasMessageContaining("corruption in segment");
        }

        @Test
        @DisplayName("a truncated non-final segment is treated as corruption, not a torn tail")
        void truncatedOlderSegment() throws IOException {
            // The distinction this pins down is the whole subtlety of 1.5: a short final segment
            // is a crash, a short earlier segment is damage.
            try (BitcaskStore store = BitcaskStore.open(rollingConfig())) {
                for (int i = 0; i < 200; i++) {
                    store.put(key("k" + i), new byte[100]);
                }
            }

            List<Path> segments = segmentFiles();
            assertThat(segments).hasSizeGreaterThan(2);
            Path firstSegment = segments.get(0);

            try (RandomAccessFile file = new RandomAccessFile(firstSegment.toFile(), "rw")) {
                file.setLength(Files.size(firstSegment) - 30);
            }

            assertThatThrownBy(() -> BitcaskStore.open(rollingConfig()))
                    .isInstanceOf(CorruptRecordException.class)
                    .hasMessageContaining("not the newest segment");
        }

        @Test
        @DisplayName("a corrupt record followed by more bytes is damage, even in the newest segment")
        void corruptionFollowedByMoreBytesIsNotATornTail() throws IOException {
            // The case that separates the two halves of the torn-tail rule. Being the newest
            // segment is not enough on its own: a torn write truncates the file, so it cannot
            // leave a complete record with bytes after it. Something appended successfully after
            // this record, which proves the record was whole when written — so it is damaged.
            //
            // Getting this wrong is not a cosmetic misclassification. Treating it as a torn tail
            // truncates the file at the bad record and silently discards every record after it,
            // which is precisely the "key reverts to an older value with nothing reporting it"
            // failure that docs/decisions/005 exists to prevent.
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 50; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }

            Path segment = segmentFiles().get(0);
            long sizeBefore = Files.size(segment);
            try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
                // Inside the first record, which has 49 records after it.
                file.seek(8);
                int original = file.readUnsignedByte();
                file.seek(8);
                file.write(original ^ 0x01);
            }

            assertThatThrownBy(() -> BitcaskStore.open(config()))
                    .isInstanceOf(CorruptRecordException.class)
                    .hasMessageContaining("damaged, not torn");

            // And nothing was truncated on the way out: a failed open must not destroy evidence.
            assertThat(Files.size(segment)).isEqualTo(sizeBefore);
        }

        @Test
        @DisplayName("a corrupt final record with nothing after it IS a torn tail")
        void corruptFinalRecordIsATornTail() throws IOException {
            // The other side of the same rule, and the reason the check is a conjunction rather
            // than either condition alone. A write that was only partly flushed can leave a
            // full-length record whose contents fail the checksum — but only ever as the last
            // thing in the newest file.
            //
            // Fixed-width keys and values so the final record's offset is computable: each
            // record is HEADER_BYTES + 2 + 2 = 33 bytes.
            int recordLength = 29 + 2 + 2;
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 3; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }

            Path segment = segmentFiles().get(0);
            assertThat(Files.size(segment)).isEqualTo(3L * recordLength);
            long finalRecordStart = 2L * recordLength;

            try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
                // Inside the final record, past its checksum field so the checksum itself is
                // intact and the mismatch is what detects the damage.
                long position = finalRecordStart + 8;
                file.seek(position);
                int original = file.readUnsignedByte();
                file.seek(position);
                file.write(original ^ 0x01);
            }

            try (BitcaskStore recovered = BitcaskStore.open(config())) {
                assertThat(recovered.replayResult().truncatedTailBytes()).isEqualTo(recordLength);
                assertThat(recovered.size()).isEqualTo(2);
                assertThat(recovered.get(key("k0"))).get().isEqualTo(bytes("v0"));
                assertThat(recovered.get(key("k1"))).get().isEqualTo(bytes("v1"));
                assertThat(recovered.get(key("k2"))).isEmpty();
            }

            assertThat(Files.size(segment)).isEqualTo(finalRecordStart);
        }

        @Test
        @DisplayName("a failed open does not leave the data directory locked")
        void failedOpenReleasesTheLock() throws IOException {
            try (BitcaskStore store = BitcaskStore.open(config())) {
                for (int i = 0; i < 10; i++) {
                    store.put(key("k" + i), bytes("v" + i));
                }
            }
            // Damage an early record so replay throws rather than truncating — see
            // corruptionFollowedByMoreBytesIsNotATornTail for why the trailing records matter.
            Path segment = segmentFiles().get(0);
            try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
                file.seek(8);
                int original = file.readUnsignedByte();
                file.seek(8);
                file.write(original ^ 0x01);
            }

            assertThatThrownBy(() -> BitcaskStore.open(config()))
                    .isInstanceOf(CorruptRecordException.class);

            // If open() had kept the lock on the way out, this would fail with "another process
            // already has ... open" instead of the real problem — and the real problem would be
            // undiagnosable.
            assertThatThrownBy(() -> BitcaskStore.open(config()))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }

    @Nested
    @DisplayName("recovery performance")
    class Performance {

        @Test
        @Tag("slow")
        @DisplayName("measure and record the reopen time for a 1GB store")
        void measureReopenTime() {
            // Not a pass/fail assertion — a measurement, and a Phase 1 exit criterion. Tagged
            // slow so it stays out of the normal build; run with:
            //   ./gradlew :distrikv-storage:test -PincludeSlow --tests '*CrashRecoveryTest*'
            // Milestone 4.3's hint files should cut this by 10x or more, and without the
            // "before" number there is no way to show that they did.
            long targetBytes = Long.getLong("distrikv.reopen.bytes", 1024L * 1024 * 1024);
            int valueBytes = Integer.getInteger("distrikv.reopen.valueBytes", 1024);

            StoreConfig big = StoreConfig.defaults(dir)
                    .withSyncPolicy(StoreConfig.SyncPolicy.NEVER)
                    .withMaxSegmentBytes(64L * 1024 * 1024);

            long written = 0;
            int keyCount = 0;
            long writeStart = System.nanoTime();
            try (BitcaskStore store = BitcaskStore.open(big)) {
                byte[] value = new byte[valueBytes];
                while (written < targetBytes) {
                    Key k = key("reopen-key-" + keyCount);
                    store.put(k, value);
                    written += 29L + k.length() + valueBytes;
                    keyCount++;
                }
                store.sync();
            }
            Duration writeTime = Duration.ofNanos(System.nanoTime() - writeStart);

            long reopenStart = System.nanoTime();
            int liveKeys;
            int segmentsScanned;
            try (BitcaskStore reopened = BitcaskStore.open(big)) {
                liveKeys = reopened.size();
                segmentsScanned = reopened.replayResult().segmentsScanned();
            }
            Duration reopenTime = Duration.ofNanos(System.nanoTime() - reopenStart);

            System.out.printf(
                    "%n=== Phase 1 reopen measurement ===%n"
                            + "  bytes written    : %,d (%.2f GB)%n"
                            + "  keys             : %,d%n"
                            + "  value size       : %,d B%n"
                            + "  segments         : %d%n"
                            + "  write time       : %,d ms%n"
                            + "  REOPEN TIME      : %,d ms%n"
                            + "  replay rate      : %.1f MB/s%n"
                            + "  per-key replay   : %.2f us%n%n",
                    written, written / (1024.0 * 1024 * 1024), keyCount, valueBytes,
                    segmentsScanned, writeTime.toMillis(), reopenTime.toMillis(),
                    (written / (1024.0 * 1024)) / Math.max(1, reopenTime.toMillis()) * 1000,
                    reopenTime.toNanos() / 1000.0 / Math.max(1, keyCount));

            assertThat(liveKeys).isEqualTo(keyCount);
        }
    }
}
