package io.distrikv.storage;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Milestones 1.4 (basics), 1.6 (rollover) and 1.7 (concurrency).
 *
 * <p>The {@code Oracle} test at the bottom is the highest-value test in this entire file, and
 * it's the one people skip. Write it.
 */
@Disabled("milestones 1.4 / 1.6 / 1.7 — delete per test as you implement")
class BitcaskStoreTest {

    @TempDir
    Path dir;

    @Nested
    @DisplayName("basics (1.4)")
    class Basics {

        @Test
        @DisplayName("put then get returns the value")
        void putGet() {
        }

        @Test
        @DisplayName("get on an unknown key is empty")
        void getMissing() {
        }

        @Test
        @DisplayName("put over an existing key returns the new value")
        void overwrite() {
        }

        @Test
        @DisplayName("delete makes the key invisible")
        void delete() {
        }

        @Test
        @DisplayName("delete reports whether the key had a live value")
        void deleteReturnValue() {
            // delete on a missing key → false. delete on a live key → true. delete twice → the
            // second is false. Worth also checking: does the second delete still append a
            // tombstone? Decide, and say why.
        }

        @Test
        @DisplayName("a deleted key can be written again")
        void resurrect() {
            // put, delete, put, get → the new value. Sounds trivial; it's the test that catches
            // a KeyDir.remove that didn't actually remove, and an index entry still pointing at
            // the tombstone.
        }

        @Test
        @DisplayName("a delete makes the data file grow")
        void deletesAreWrites() {
            // Assert Files.size() of the segment increased. This is a test of your understanding
            // more than of your code, and it's the reason phase 4 exists.
        }

        @Test
        @DisplayName("size and keys track live keys only")
        void sizeAndKeys() {
        }

        @Test
        @DisplayName("keys and values that are too large are rejected")
        void sizeLimits() {
            // Against StoreConfig's limits, and before anything is written to disk. A partial
            // record on disk because you validated halfway through is a bad failure mode.
        }

        @Test
        @DisplayName("empty value and empty key behave sensibly")
        void degenerateInputs() {
            // Decide whether a zero-length key is legal. Either answer is fine; an
            // ArrayIndexOutOfBoundsException from deep inside the codec is not.
        }

        @Test
        @DisplayName("using the store after close throws")
        void useAfterClose() {
        }

        @Test
        @DisplayName("close is idempotent")
        void doubleClose() {
        }
    }

    @Nested
    @DisplayName("segment rollover (1.6)")
    class Rollover {

        @Test
        @DisplayName("exceeding maxSegmentBytes opens a new segment")
        void rollsOver() {
            // Use a tiny maxSegmentBytes (a few KB) so this is fast. Assert the number of
            // .data files in the directory grows.
        }

        @Test
        @DisplayName("reads still work for keys in older segments")
        void readsSpanSegments() {
            // Write enough to fill ~10 segments, then read a key from each one. This is where a
            // store that only ever keeps the active segment open falls over.
        }

        @Test
        @DisplayName("a record larger than maxSegmentBytes is handled deliberately")
        void oversizedRecord() {
            // What should happen? Reject it, or let one segment exceed its nominal cap? Both are
            // real designs. Pick one and pin it here rather than discovering your answer by
            // accident during phase 5 benchmarking.
        }
    }

    @Nested
    @DisplayName("concurrency (1.7)")
    class Concurrency {

        @Test
        @DisplayName("N readers and one writer for 10s: no exceptions, no torn reads")
        void readersAndWriter() {
            // Each value should be self-verifying — e.g. value = key bytes repeated, or a value
            // carrying its own checksum. Then a reader can assert that whatever it read is a
            // *complete, consistent* value and not a splice of two different writes. A test that
            // only asserts "no exception thrown" will pass against genuinely broken code.
        }

        @Test
        @DisplayName("concurrent writers to the same key leave one of the written values")
        void concurrentWritersToOneKey() {
            // Not "the last one" — you have no way to define last. The invariant is that the
            // final value is one of the values actually written, never a mixture. Being precise
            // about which invariant you can actually assert is most of concurrent testing.
        }

        @Test
        @DisplayName("no writes are lost under concurrent load")
        void noLostWrites() {
            // 10 threads × 10k distinct keys → size() == 100k and every key readable.
        }
    }

    @Nested
    @DisplayName("oracle test — write this one")
    class Oracle {

        @Test
        @DisplayName("1M random ops agree with a HashMap at every step")
        void agreesWithHashMap() {
            // Generate a random sequence of put/get/delete over a small key space (a small space
            // is important — it forces overwrites and deletes of live keys, which is where the
            // bugs are). Apply each op to both your store and a HashMap<Key, byte[]>, and assert
            // they agree after every single one.
            //
            // Seed the Random explicitly and print the seed on failure, so a failure is
            // reproducible. A flaky test you can't reproduce is worse than no test.
            //
            // This one test will find more real bugs than everything above it combined, because
            // it explores states you would never think to write down. jqwik is on the test
            // classpath if you'd rather express it as a property with shrinking — that gives you
            // a minimal failing sequence instead of a 40,000-op log to read through.
        }
    }
}
