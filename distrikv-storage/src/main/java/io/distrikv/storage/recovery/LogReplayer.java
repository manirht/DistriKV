package io.distrikv.storage.recovery;

import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.StorageException;
import io.distrikv.storage.index.KeyDir;
import io.distrikv.storage.index.ValueLocation;
import io.distrikv.storage.log.LogSegment;
import io.distrikv.storage.record.Record;
import io.distrikv.storage.record.RecordCodec;
import io.distrikv.storage.record.RecordHeader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Milestone 1.5 — rebuilds the {@link KeyDir} from the segment files at startup.
 *
 * <p>The algorithm is a walk of every segment in ascending id order, applying each record to the
 * index. The part that is actually hard is the last record of the last file, which after a
 * {@code kill -9} is routinely half-written — and which must be told apart from genuine
 * corruption, because the correct response to the two is opposite. The rule used here, and the
 * reasoning for it, are in {@code docs/decisions/005}:
 *
 * <ul>
 *   <li>A decode failure in the <b>highest-numbered</b> segment, for a record that cannot be
 *       fully contained before end-of-file, is a <b>torn tail</b>. The write was never
 *       acknowledged to anyone, so it is discarded, the file is truncated to the last good
 *       offset, and the byte count is reported.
 *   <li>Any other decode failure is <b>corruption</b> and throws. Skipping it would silently
 *       revert a key to an older value, which is data loss that nothing reports.
 * </ul>
 *
 * <p>Truncating rather than leaving the garbage in place is deliberate: if it stayed, the next
 * append would land after it, putting a permanently undecodable hole in the middle of a segment —
 * and by the rule above, every future recovery would then fail.
 */
public final class LogReplayer {

    /**
     * How much of a segment is read per syscall.
     *
     * <p>One read per record would make reopening a 1GB store take minutes instead of seconds,
     * and Phase 1's exit criteria require measuring that number. The buffer grows if it meets a
     * record too large for it — bounded by the remaining bytes in the file, since a record
     * cannot be longer than the file containing it, which is what keeps a corrupt length field
     * from turning into a huge allocation.
     */
    private static final int CHUNK_BYTES = 1024 * 1024;

    private LogReplayer() {
        // static only
    }

    /**
     * Finds every segment file in {@code dataDir}, sorted by ascending segment id.
     *
     * <p>Sorted numerically, not lexicographically. The zero-padded names make those agree today,
     * so this looks cosmetic — but {@code Files.list} guarantees no ordering at all, and relying
     * on the padding is the kind of implicit coupling that breaks when someone reasonably changes
     * the naming. Getting it wrong produces a store that loses its most recent writes while
     * passing every other test.
     *
     * <p>Gaps in the ids are normal rather than corruption: Phase 4 compaction merges segments
     * and deletes its inputs, so 1, 2, 5 is the expected steady state afterwards.
     */
    public static List<Path> discoverSegments(Path dataDir) {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dataDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(LogSegment::isSegmentFile)
                    .sorted(Comparator.comparingInt(LogSegment::segmentIdFrom))
                    .toList();
        } catch (IOException e) {
            throw new StorageException("could not list segments in " + dataDir, e);
        }
    }

    /**
     * Replays {@code segments} into {@code keyDir} and reports what happened.
     *
     * <p>{@code segments} must be in ascending id order — {@link #discoverSegments} guarantees
     * it. The ordering is load-bearing twice over: it is what makes a later value override an
     * earlier tombstone for the same key, and it is the tie-break that
     * {@link KeyDir#putIfNewer} relies on when two records share a timestamp.
     *
     * @return a summary, so {@code BitcaskStore.open} can log it and tests can assert on it.
     *         Recovery that reports nothing is recovery you cannot debug — and it has to be
     *         debugged from a log, after a crash that will not reproduce.
     */
    public static ReplayResult replay(List<Path> segments, KeyDir keyDir) {
        long recordsApplied = 0;
        long tombstonesApplied = 0;
        long truncatedTailBytes = 0;
        int highestSegmentId = ReplayResult.NO_SEGMENTS;

        for (int i = 0; i < segments.size(); i++) {
            Path file = segments.get(i);
            boolean isLastSegment = i == segments.size() - 1;
            SegmentReplay outcome = replaySegment(file, keyDir, isLastSegment);

            recordsApplied += outcome.recordsApplied;
            tombstonesApplied += outcome.tombstonesApplied;
            truncatedTailBytes += outcome.truncatedBytes;
            highestSegmentId = Math.max(highestSegmentId, LogSegment.segmentIdFrom(file));
        }

        return new ReplayResult(
                segments.size(),
                recordsApplied,
                tombstonesApplied,
                keyDir.size(),
                truncatedTailBytes,
                highestSegmentId);
    }

    /** Per-file tallies, so {@link #replay} stays readable. */
    private record SegmentReplay(long recordsApplied, long tombstonesApplied, long truncatedBytes) {
    }

    private static SegmentReplay replaySegment(Path file, KeyDir keyDir, boolean isLastSegment) {
        int segmentId = LogSegment.segmentIdFrom(file);
        long recordsApplied = 0;
        long tombstonesApplied = 0;

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            // A zero-byte segment is valid, not an error: it is what a crash straight after
            // rollover creates the file and before anything is appended leaves behind.
            if (fileSize == 0) {
                return new SegmentReplay(0, 0, 0);
            }

            // Invariant held throughout the loop: the buffer's remaining bytes are exactly the
            // file's bytes starting at recordStart. decode() advances the buffer's position by
            // the record length and recordStart advances by the same amount, so a successful
            // decode preserves it for free; a refill re-establishes it from recordStart.
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.BIG_ENDIAN);
            buffer.limit(0); // nothing buffered yet
            long recordStart = 0;

            while (recordStart < fileSize) {
                long remainingInFile = fileSize - recordStart;

                // 1. Enough bytes buffered to even read a header?
                if (buffer.remaining() < RecordHeader.HEADER_BYTES) {
                    buffer = refill(channel, buffer, recordStart, RecordHeader.HEADER_BYTES);
                }
                if (buffer.remaining() < RecordHeader.HEADER_BYTES) {
                    // The file itself ends inside a header, so there is nothing left to read.
                    // The file stops inside the header, so whatever this was, it reached the
                    // end of the file.
                    return finishTail(file, segmentId, isLastSegment, recordStart, fileSize, true,
                            recordsApplied, tombstonesApplied,
                            "file ends " + remainingInFile + " bytes into a "
                                    + RecordHeader.HEADER_BYTES + "-byte header");
                }

                // 2. How long does the record claim to be? Read straight from the header rather
                //    than guessing, so the loop cannot spin refilling for a record that will
                //    never decode.
                long claimedLength = claimedLength(buffer);

                if (claimedLength < 0 || claimedLength > remainingInFile) {
                    // Either an impossible length field, or a record that cannot fit in what is
                    // left of the file. No allocation happens on this path, which is what keeps
                    // a corrupt 4-byte length from becoming a 2GB allocation request.
                    // Both shapes reach the end of the file: a record that claims more than
                    // remains cannot be followed by anything, and an unparseable length leaves
                    // no way to know where the record ended — see finishTail on why that
                    // ambiguity resolves to "torn" in the newest segment.
                    return finishTail(file, segmentId, isLastSegment, recordStart, fileSize, true,
                            recordsApplied, tombstonesApplied,
                            claimedLength < 0
                                    ? "impossible length field in header"
                                    : "record claims " + claimedLength + " bytes but only "
                                            + remainingInFile + " remain in the file");
                }

                // 3. Buffered enough of it to decode? Growth is bounded by the check above.
                if (buffer.remaining() < claimedLength) {
                    buffer = refill(channel, buffer, recordStart, (int) claimedLength);
                    if (buffer.remaining() < claimedLength) {
                        // Only reachable if the file shrank underneath us mid-recovery.
                        return finishTail(file, segmentId, isLastSegment, recordStart,
                                fileSize, true, recordsApplied, tombstonesApplied,
                                "could not read " + claimedLength + " bytes at offset "
                                        + recordStart);
                    }
                }

                // 4. Every length is plausible and the bytes are present, so any failure from
                //    here is a checksum mismatch: real damage, or a partially-written tail.
                int positionBefore = buffer.position();
                Record record;
                try {
                    record = RecordCodec.decode(buffer);
                } catch (CorruptRecordException e) {
                    // Every length checked out and the bytes were all present, so this is a
                    // checksum failure. Whether it is damage or a partly-flushed final write
                    // turns entirely on whether anything was written after it: a torn write
                    // leaves nothing behind it, so a record with bytes following it was
                    // definitely complete when it was written.
                    boolean reachesEndOfFile = recordStart + claimedLength >= fileSize;
                    return finishTail(file, segmentId, isLastSegment, recordStart, fileSize,
                            reachesEndOfFile, recordsApplied, tombstonesApplied, e.getMessage());
                }
                int recordLength = buffer.position() - positionBefore;

                if (record.tombstone()) {
                    keyDir.remove(record.key());
                    tombstonesApplied++;
                } else {
                    ValueLocation location = new ValueLocation(
                            segmentId, recordStart, recordLength, record.timestampMillis());
                    if (keyDir.putIfNewer(record.key(), location)) {
                        recordsApplied++;
                    }
                }
                recordStart += recordLength;
            }

            return new SegmentReplay(recordsApplied, tombstonesApplied, 0);
        } catch (IOException e) {
            throw new StorageException("could not replay segment " + file, e);
        }
    }

    /**
     * Decides what a decode failure at {@code recordStart} means, and acts on it.
     *
     * <p>This is the whole subtlety of milestone 1.5 in one method: a short final segment is a
     * crash, a short earlier segment is damage.
     */
    private static SegmentReplay finishTail(
            Path file,
            int segmentId,
            boolean isLastSegment,
            long recordStart,
            long fileSize,
            boolean reachesEndOfFile,
            long recordsApplied,
            long tombstonesApplied,
            String reason) {

        long discarded = fileSize - recordStart;

        // Two independent conditions, and both are required. The segment has to be the newest,
        // because only the newest was open for append when the process died. And the bad record
        // has to run to the end of the file, because a torn write truncates — it cannot leave a
        // complete record followed by more bytes. Dropping either check turns a single flipped
        // bit into the silent loss of everything after it, which is the exact failure
        // docs/decisions/005 exists to prevent.
        if (!isLastSegment || !reachesEndOfFile) {
            throw new CorruptRecordException(
                    "corruption in segment " + segmentId + " (" + file.getFileName()
                            + ") at offset " + recordStart + " of " + fileSize + ": " + reason
                            + (isLastSegment
                                    ? ". The record is followed by " + (fileSize - recordStart)
                                            + " more bytes, so a later append succeeded and this"
                                            + " record was complete when written — it is damaged,"
                                            + " not torn."
                                    : ". This is not the newest segment, so it cannot be a torn"
                                            + " write —") 
                            + " the log is damaged and recovery refuses to guess.");
        }

        // The newest segment, ending in bytes that do not decode: the process died mid-append.
        // Nothing was acknowledged, so the bytes are dropped and the file is truncated so the
        // next append does not land behind an undecodable hole.
        truncate(file, recordStart);
        return new SegmentReplay(recordsApplied, tombstonesApplied, discarded);
    }

    private static void truncate(Path file, long size) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            // The truncation itself has to be durable, or the next recovery meets the same
            // garbage again.
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException(
                    "could not truncate torn tail of " + file + " to " + size + " bytes", e);
        }
    }

    /**
     * The length the record at {@code buffer}'s position claims to be, or {@code -1} if the
     * header's length fields are impossible.
     *
     * <p>Reads the header fields directly rather than going through the codec, because the whole
     * point is to find out whether a decode is even worth attempting. Absolute gets, so the
     * buffer's position is untouched.
     */
    private static long claimedLength(ByteBuffer buffer) {
        int position = buffer.position();
        int keyLength = buffer.getInt(position + RecordHeader.KEY_LEN_OFFSET);
        int valueLength = buffer.getInt(position + RecordHeader.VALUE_LEN_OFFSET);
        if (keyLength < 0 || valueLength < 0) {
            return -1;
        }
        return (long) RecordHeader.HEADER_BYTES + keyLength + valueLength;
    }

    /**
     * Refills {@code buffer} so that it holds the bytes from {@code fromOffset} onwards, growing
     * it if {@code needed} does not fit.
     *
     * @return the buffer to use from now on — the same one, or a larger replacement
     */
    private static ByteBuffer refill(FileChannel channel, ByteBuffer buffer, long fromOffset, int needed)
            throws IOException {
        ByteBuffer target = buffer;
        if (needed > target.capacity()) {
            // Bounded by the caller, which only asks for a length the file is long enough to
            // contain.
            target = ByteBuffer.allocate(needed).order(ByteOrder.BIG_ENDIAN);
        }
        target.clear();
        long readOffset = fromOffset;
        while (target.hasRemaining()) {
            int read = channel.read(target, readOffset);
            if (read < 0) {
                break; // end of file; the caller decides whether that is fatal
            }
            readOffset += read;
        }
        target.flip();
        return target;
    }

    /**
     * What recovery found.
     *
     * @param segmentsScanned    how many files were read
     * @param recordsApplied     records that updated the index
     * @param tombstonesApplied  deletes replayed
     * @param liveKeys           keys in the index afterwards
     * @param truncatedTailBytes bytes discarded from a torn final record; {@code 0} on a clean
     *                           shutdown. Assert this is 0 in a clean-restart test and non-zero
     *                           in a kill test — otherwise there is no evidence the torn-tail
     *                           path ever actually runs, and a test that never exercises its
     *                           code is just decoration.
     * @param highestSegmentId   so the store knows what id to give the next active segment, or
     *                           {@link #NO_SEGMENTS} if the directory held none
     */
    public record ReplayResult(
            int segmentsScanned,
            long recordsApplied,
            long tombstonesApplied,
            int liveKeys,
            long truncatedTailBytes,
            int highestSegmentId) {

        /** {@link #highestSegmentId} when there was nothing to replay, so the next id is 0. */
        public static final int NO_SEGMENTS = -1;

        /** The id the store should give its next active segment. */
        public int nextSegmentId() {
            return highestSegmentId + 1;
        }
    }
}