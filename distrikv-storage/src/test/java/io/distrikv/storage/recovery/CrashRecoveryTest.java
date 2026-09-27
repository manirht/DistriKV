package io.distrikv.storage.recovery;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Milestone 1.5 — and the reason the whole phase exists.
 *
 * <p>Everything else in Phase 1 can be green while the store still loses data on power loss. The
 * tests in this file are the ones that actually prove the engine keeps its promise, and they are
 * the exit criteria you should refuse to skip.
 *
 * <h2>How to test a crash in Java</h2>
 *
 * <p>You cannot {@code kill -9} the JVM running your test suite, so use a child process:
 *
 * <ol>
 *   <li>Write a small {@code main} — put it in {@code src/test/java} — that opens a store in a
 *       directory given as {@code args[0]}, writes keys in a predictable sequence, and prints
 *       each key to stdout <em>after</em> the {@code put} returns. That printed line is the
 *       acknowledgement, and it's what makes the test meaningful: every key it printed is a key
 *       the store promised to keep.
 *   <li>From the test, launch it with {@link ProcessBuilder} (the current JVM's
 *       {@code java} binary is at {@code System.getProperty("java.home") + "/bin/java"}, and
 *       {@code System.getProperty("java.class.path")} gets you the classpath).
 *   <li>Read its stdout until you've seen a few thousand acknowledgements, then
 *       {@code process.destroyForcibly()}. On Linux and macOS that's SIGKILL — no shutdown hooks,
 *       no flush, no cleanup. Exactly the scenario you need.
 *   <li>Reopen the store in the test JVM and assert every acknowledged key reads back correctly.
 * </ol>
 *
 * <p>This harness takes an afternoon and you will reuse it in Phase 3 for killing Raft nodes and
 * in Phase 4 for killing compaction mid-merge. Build it properly.
 *
 * <p>One caveat worth understanding, because it explains a confusing result you're about to get:
 * SIGKILL kills your <em>process</em>, but the page cache belongs to the <em>kernel</em>, which
 * is still running. So writes that never reached disk still survive, and these tests pass even
 * under {@code SyncPolicy.NEVER}. Genuine power-loss testing needs a VM you can hard-reset, or
 * a fault-injecting filesystem. Knowing precisely what your test does and does not prove is part
 * of the skill — and it's the sort of distinction that separates people who have built storage
 * from people who have read about it.
 */
@Disabled("milestone 1.5 — delete per test as you implement LogReplayer")
class CrashRecoveryTest {

    @TempDir
    Path dir;

    @Nested
    @DisplayName("clean restart")
    class CleanRestart {

        @Test
        @DisplayName("a closed and reopened store has every key")
        void reopenAfterClose() {
        }

        @Test
        @DisplayName("deletes survive a restart")
        void tombstonesSurvive() {
            // put k, delete k, close, reopen → get(k) is empty. If your replayer applies records
            // in the wrong order, or ignores tombstones, this is what catches it.
        }

        @Test
        @DisplayName("the newest value wins after a restart")
        void newestWins() {
            // put k=v1, put k=v2, ..., put k=v10 across a rollover boundary, then reopen.
            // Must be v10. Force the rollover with a tiny maxSegmentBytes — a store that gets
            // this right within one segment and wrong across two is the common failure.
        }

        @Test
        @DisplayName("ReplayResult reports no truncation after a clean shutdown")
        void noTruncationWhenClean() {
            // truncatedTailBytes == 0. Pair this with torn-tail below: together they prove the
            // torn-tail path exists AND doesn't fire spuriously.
        }

        @Test
        @DisplayName("an empty directory opens as an empty store")
        void emptyDirectory() {
        }

        @Test
        @DisplayName("a zero-byte segment file is handled")
        void zeroLengthSegment() {
            // You will genuinely produce these: crash immediately after rollover creates the
            // file and before anything is appended.
        }
    }

    @Nested
    @DisplayName("kill -9")
    class HardKill {

        @Test
        @DisplayName("every acknowledged write survives a forced kill")
        void acknowledgedWritesSurvive() {
            // The central test of phase 1. If this passes for 100k keys, you have built a
            // crash-safe storage engine, and that is a genuinely uncommon thing to have done.
        }

        @Test
        @DisplayName("a torn tail record is discarded and reported")
        void tornTailIsDiscarded() {
            // After a kill mid-append: the store opens, and ReplayResult.truncatedTailBytes() > 0.
            // If it's always 0, your kill isn't landing mid-record — make the values larger so
            // the write window is wider.
        }

        @Test
        @DisplayName("the store is writable again after recovering from a torn tail")
        void writableAfterRecovery() {
            // Reopen, put a new key, close, reopen again, read it. This is the test that catches
            // failing to truncate the garbage: the next append lands after the torn bytes, and
            // now there's an undecodable hole permanently in the middle of a segment.
        }
    }

    @Nested
    @DisplayName("real corruption")
    class Corruption {

        @Test
        @DisplayName("a corrupt record mid-segment is detected, not silently skipped")
        void midSegmentCorruptionDetected() {
            // Write 1000 keys, close, flip a bit in the middle of the file with a RandomAccessFile,
            // reopen. Whatever you decided open() should do — throw, or degrade — assert it does
            // exactly that. What must NOT happen is quietly carrying on, because then the key
            // reverts to an older value and nothing anywhere reports a problem.
        }

        @Test
        @DisplayName("a truncated non-final segment is treated as corruption, not a torn tail")
        void truncatedOlderSegment() {
            // The distinction this pins down is the whole subtlety of 1.5: a short final segment
            // is a crash, a short earlier segment is damage.
        }
    }

    @Nested
    @DisplayName("recovery performance")
    class Performance {

        @Test
        @DisplayName("measure and record the reopen time for a 1GB store")
        void measureReopenTime() {
            // Not a pass/fail assertion — a measurement. Print it and write it in
            // docs/decisions/. In milestone 4.3 hint files should cut it by 10x or more, and
            // without the "before" number you can't show that they did.
            //
            // Tag it so it doesn't run on every build: @Tag("slow"), excluded by default in
            // surefire.
        }
    }
}
