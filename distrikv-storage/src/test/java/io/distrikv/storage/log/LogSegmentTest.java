package io.distrikv.storage.log;

import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.Key;
import io.distrikv.storage.StorageException;
import io.distrikv.storage.record.Record;
import io.distrikv.storage.record.RecordCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 1.2.
 *
 * <p>Uses {@link TempDir}: JUnit creates a fresh directory per test and deletes it afterwards, so
 * tests never depend on each other's leftovers. Channels are closed in every test — an open
 * handle makes the cleanup fail on some platforms, and a suite that leaks temp directories is a
 * suite you eventually start ignoring.
 */
class LogSegmentTest {

    private static final long TS = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private static Record record(String key, String value) {
        return Record.value(Key.ofUtf8(key), value.getBytes(StandardCharsets.UTF_8), TS);
    }

    @Test
    @DisplayName("fileNameFor zero-pads so lexicographic order matches numeric order")
    void fileNaming() {
        assertThat(LogSegment.fileNameFor(1)).isEqualTo("000001.data");
        assertThat(LogSegment.fileNameFor(42)).isEqualTo("000042.data");

        // The property the whole recovery path silently depends on: sorting the names as strings
        // must give the same order as sorting the ids as numbers.
        List<String> names = new ArrayList<>(
                List.of(LogSegment.fileNameFor(10), LogSegment.fileNameFor(9)));
        names.sort(String::compareTo);
        assertThat(names).containsExactly("000009.data", "000010.data");
    }

    @Test
    @DisplayName("segmentIdFrom round-trips fileNameFor")
    void segmentIdRoundTrip() {
        for (int id : new int[] {0, 1, 9, 10, 42, 999_999, 1_000_000, Integer.MAX_VALUE}) {
            Path file = dir.resolve(LogSegment.fileNameFor(id));
            assertThat(LogSegment.segmentIdFrom(file)).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("segmentIdFrom rejects a file that isn't a segment")
    void segmentIdRejectsJunk() {
        for (String name : new String[] {
                "notes.txt",      // not ours at all
                "0001.hint",      // phase 4's hint files live in the same directory
                "000001.hint",
                "1.data",         // too few digits: not something fileNameFor can produce
                "00001.data",
                "data",
                "000001.data.tmp",
                "abcdef.data"}) {
            assertThatThrownBy(() -> LogSegment.segmentIdFrom(dir.resolve(name)))
                    .as("name '%s'", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("append returns the offset the bytes landed at")
    void appendReturnsOffset() {
        // This is the contract ValueLocation depends on; get it wrong and every read is off by a
        // record.
        Record first = record("a", "1");
        Record second = record("bb", "22");
        Record third = record("ccc", "333");

        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            long firstOffset = segment.append(RecordCodec.encode(first));
            long secondOffset = segment.append(RecordCodec.encode(second));
            long thirdOffset = segment.append(RecordCodec.encode(third));

            assertThat(firstOffset).isZero();
            assertThat(secondOffset).isEqualTo(RecordCodec.encodedLength(first));
            assertThat(thirdOffset)
                    .isEqualTo(RecordCodec.encodedLength(first) + RecordCodec.encodedLength(second));
        }
    }

    @Test
    @DisplayName("append then read at the returned offset gives the record back")
    void appendThenRead() {
        Record original = record("user:42", "mani");

        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            long offset = segment.append(RecordCodec.encode(original));

            assertThat(segment.read(offset, RecordCodec.encodedLength(original)))
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("sizeBytes tracks appends and matches the file on disk")
    void sizeTracking() throws IOException {
        // If these disagree there's a buffering bug, and it surfaces later as recovery walking
        // off the end of a file.
        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            assertThat(segment.sizeBytes()).isZero();
            assertThat(Files.size(segment.path())).isZero();

            long expected = 0;
            for (int i = 0; i < 5; i++) {
                Record r = record("key" + i, "value" + i);
                segment.append(RecordCodec.encode(r));
                expected += RecordCodec.encodedLength(r);

                assertThat(segment.sizeBytes()).isEqualTo(expected);
                assertThat(Files.size(segment.path())).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("a read-only segment refuses appends")
    void readOnlyRefusesAppend() {
        // Must throw, not silently no-op. Once compaction exists in phase 4, a stray append to a
        // segment being merged is a data-loss bug, and it should be loud.
        Path file;
        try (LogSegment active = LogSegment.createActive(dir, 1)) {
            active.append(RecordCodec.encode(record("k", "v")));
            file = active.path();
        }

        try (LogSegment readOnly = LogSegment.openReadOnly(file)) {
            assertThat(readOnly.isReadOnly()).isTrue();
            assertThatThrownBy(() -> readOnly.append(RecordCodec.encode(record("k2", "v2"))))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("read-only");
        }
    }

    @Test
    @DisplayName("records survive close and reopen")
    void reopen() {
        List<Record> written = List.of(record("a", "1"), record("bb", "22"), record("ccc", "333"));
        List<Long> offsets = new ArrayList<>();

        Path file;
        try (LogSegment segment = LogSegment.createActive(dir, 7)) {
            for (Record r : written) {
                offsets.add(segment.append(RecordCodec.encode(r)));
            }
            file = segment.path();
        }

        try (LogSegment reopened = LogSegment.openReadOnly(file)) {
            assertThat(reopened.id()).isEqualTo(7);
            for (int i = 0; i < written.size(); i++) {
                assertThat(reopened.read(offsets.get(i), RecordCodec.encodedLength(written.get(i))))
                        .isEqualTo(written.get(i));
            }
        }
    }

    @Test
    @DisplayName("createActive refuses to clobber an existing file")
    void refusesToOverwrite() {
        // Opening a store twice on the same directory must not silently truncate segment 1.
        // Note this is only a partial defence: a second process would discover segment 1, replay
        // it, and open segment 2 as its own active segment, at which point two writers share one
        // logical store. The real fix is the data-directory lock in BitcaskStore.open — see
        // docs/decisions/003.
        try (LogSegment first = LogSegment.createActive(dir, 1)) {
            first.append(RecordCodec.encode(record("k", "v")));

            assertThatThrownBy(() -> LogSegment.createActive(dir, 1))
                    .isInstanceOf(StorageException.class);

            // And the original file is untouched.
            assertThat(first.sizeBytes()).isEqualTo(RecordCodec.encodedLength(record("k", "v")));
        }
    }

    @Test
    @DisplayName("reading past the end of the segment fails cleanly")
    void readPastEnd() {
        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            Record r = record("k", "v");
            long offset = segment.append(RecordCodec.encode(r));
            int length = RecordCodec.encodedLength(r);

            // One byte beyond the last record.
            assertThatThrownBy(() -> segment.read(offset, length + 1))
                    .isInstanceOf(CorruptRecordException.class)
                    .hasMessageContaining("past end");

            // Entirely beyond the file.
            assertThatThrownBy(() -> segment.read(segment.sizeBytes(), length))
                    .isInstanceOf(CorruptRecordException.class);

            // Nonsense arguments are also a clean failure, not an AIOOBE from deep inside NIO.
            assertThatThrownBy(() -> segment.read(-1, length))
                    .isInstanceOf(CorruptRecordException.class);
            assertThatThrownBy(() -> segment.read(0, 0))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }

    @Test
    @DisplayName("an empty segment reads as zero bytes and reopens cleanly")
    void emptySegment() throws IOException {
        // Produced for real by crashing straight after rollover creates the file. Milestone 1.5
        // has to cope with these, so 1.2 should make them ordinary rather than special.
        Path file;
        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            file = segment.path();
            assertThat(segment.sizeBytes()).isZero();
        }

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isZero();

        try (LogSegment reopened = LogSegment.openReadOnly(file)) {
            assertThat(reopened.sizeBytes()).isZero();
        }
    }

    @Test
    @DisplayName("close is idempotent and a closed segment refuses work")
    void closeIsIdempotent() {
        LogSegment segment = LogSegment.createActive(dir, 1);
        segment.append(RecordCodec.encode(record("k", "v")));

        segment.close();
        segment.close(); // must not throw

        assertThatThrownBy(() -> segment.append(RecordCodec.encode(record("k2", "v2"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> segment.read(0, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a 1MB value survives a round-trip through the file")
    void largeRecord() {
        // The append loop exists because write() may take fewer bytes than offered. A record
        // large enough to provoke a short write is the only way to exercise it.
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        Record original = Record.value(Key.ofUtf8("big"), big, TS);

        try (LogSegment segment = LogSegment.createActive(dir, 1)) {
            long offset = segment.append(RecordCodec.encode(original));
            segment.sync();

            assertThat(segment.read(offset, RecordCodec.encodedLength(original)))
                    .isEqualTo(original);
        }
    }
}