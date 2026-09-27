package io.distrikv.storage.log;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Milestone 1.2. A checklist, not a finished suite — write the bodies as you implement
 * {@link LogSegment}, deleting the {@code @Disabled} from each as you go.
 *
 * <p>Use {@link TempDir}: JUnit creates a fresh directory per test and deletes it afterwards, so
 * your tests never depend on each other's leftovers. Do close your channels, though — an open
 * handle makes the cleanup fail on some platforms, and a test suite that leaks temp directories
 * is a test suite you'll eventually start ignoring.
 */
@Disabled("milestone 1.2 — delete this annotation per test as you implement LogSegment")
class LogSegmentTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("fileNameFor zero-pads so lexicographic order matches numeric order")
    void fileNaming() {
        // Assert 000001.data for 1 and 000042.data for 42. Then assert that sorting the strings
        // for ids 9 and 10 puts them in numeric order — the property the whole recovery path
        // silently depends on.
    }

    @Test
    @DisplayName("segmentIdFrom round-trips fileNameFor")
    void segmentIdRoundTrip() {
    }

    @Test
    @DisplayName("segmentIdFrom rejects a file that isn't a segment")
    void segmentIdRejectsJunk() {
        // e.g. "notes.txt", "0001.hint", "".
    }

    @Test
    @DisplayName("append returns the offset the bytes landed at")
    void appendReturnsOffset() {
        // First append → 0. Second → length of the first. Third → sum of the first two.
        // This is the contract KeyDir depends on; get it wrong and every read is off by a record.
    }

    @Test
    @DisplayName("append then read at the returned offset gives the record back")
    void appendThenRead() {
    }

    @Test
    @DisplayName("sizeBytes tracks appends and matches the file on disk")
    void sizeTracking() {
        // Compare against Files.size(path). If they disagree you have a buffering bug, and it
        // will surface later as recovery walking off the end of a file.
    }

    @Test
    @DisplayName("a read-only segment refuses appends")
    void readOnlyRefusesAppend() {
        // Should throw, not silently no-op. Once compaction exists in phase 4, a stray append to
        // a segment being merged is a data-loss bug, and you want it loud.
    }

    @Test
    @DisplayName("records survive close and reopen")
    void reopen() {
        // Write three records, close, openReadOnly, read all three at their remembered offsets.
    }

    @Test
    @DisplayName("createActive refuses to clobber an existing file")
    void refusesToOverwrite() {
        // Opening a store twice on the same directory must not silently truncate segment 1.
        // (Which raises a question worth writing down: should a store take a lock file? What do
        // real databases do here, and what happens today if you run two JVMs on one data dir?)
    }

    @Test
    @DisplayName("reading past the end of the segment fails cleanly")
    void readPastEnd() {
    }
}
