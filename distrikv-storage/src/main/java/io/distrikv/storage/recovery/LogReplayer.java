package io.distrikv.storage.recovery;

import io.distrikv.storage.index.KeyDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Milestone 1.5 — rebuilds the {@link KeyDir} from the segment files at startup.
 *
 * <p><b>Yours to implement, and this is the milestone that decides whether your store is
 * actually crash-safe.</b> Everything before it is plumbing you can test in isolation. This is
 * where the store either keeps its promises or quietly breaks them.
 *
 * <h2>The algorithm</h2>
 *
 * <pre>
 *   for each segment, in ascending id order:
 *       offset = 0
 *       while offset &lt; fileSize:
 *           decode the record at offset
 *           if it's a tombstone → keyDir.remove(key)
 *           else                → keyDir.putIfNewer(key, location)
 *           offset += encodedLength
 * </pre>
 *
 * <h2>The part that is actually hard</h2>
 *
 * <p>The last record in the last segment may be a torn write: the process died between the
 * {@code write} and the data reaching disk, so the file ends mid-record. This is the normal case
 * after {@code kill -9}, not an exotic one, and your recovery must handle it without drama.
 *
 * <p>So you have to distinguish two things that both surface as a decode failure:
 *
 * <ul>
 *   <li><b>A truncated record at the very end of the highest-numbered segment.</b> Expected.
 *       Discard it, truncate the file to the last good offset, carry on. The write was never
 *       acknowledged to any client, so dropping it loses nothing anyone was promised.
 *   <li><b>A bad record anywhere else.</b> Real corruption. Skipping it silently is the worst
 *       possible response, because the previous record for that key is still in the log and the
 *       key will quietly revert to an older value — data loss that no test will catch and no user
 *       will be able to explain. Fail loudly.
 * </ul>
 *
 * <p>Truncating the file rather than leaving the garbage there is worth doing deliberately: if
 * you leave it, the next append lands after it, and now there's an undecodable hole permanently
 * in the middle of a segment — which by the rule above means every future recovery fails. Small
 * decision, large consequence.
 *
 * <h2>Questions my review will ask</h2>
 *
 * <ul>
 *   <li>What if a segment file is zero bytes long? (You'll produce these — a crash right after
 *       rollover does it.)
 *   <li>What if there are gaps in the segment ids, say 1, 2, 5? Is that corruption, or is it
 *       normal after Phase 4 compaction deletes merged segments?
 *   <li>Are you reading these files one record at a time with a syscall each, or buffering? At
 *       1GB the difference is minutes versus seconds, and you're going to measure the reopen time
 *       for Phase 1's exit criteria — so you'll see it.
 *   <li>A tombstone must remove the key. But what if a <em>newer</em> value record for that key
 *       exists in a <em>later</em> segment? Walk through why the ordering saves you here, and why
 *       it stops saving you once compaction exists.
 * </ul>
 */
public final class LogReplayer {

    private LogReplayer() {
        // static only
    }

    /**
     * Finds every segment file in {@code dataDir}, sorted by ascending segment id.
     *
     * <p>Sort numerically, not lexicographically. The zero-padded names make those agree, but
     * relying on that is exactly the kind of implicit coupling that breaks when someone
     * reasonably changes the format later.
     */
    public static List<Path> discoverSegments(Path dataDir) {
        throw new UnsupportedOperationException("TODO milestone 1.5 — discoverSegments");
    }

    /**
     * Replays {@code segments} into {@code keyDir} and reports what happened.
     *
     * @return a summary, so {@code BitcaskStore.open} can log it and your tests can assert on it.
     *         Recovery that reports nothing is recovery you can't debug — and you will need to
     *         debug it, from a log, after a crash you can't reproduce.
     */
    public static ReplayResult replay(List<Path> segments, KeyDir keyDir) {
        throw new UnsupportedOperationException("TODO milestone 1.5 — replay");
    }

    /**
     * What recovery found.
     *
     * @param segmentsScanned    how many files were read
     * @param recordsApplied     records that updated the index
     * @param tombstonesApplied  deletes replayed
     * @param liveKeys           keys in the index afterwards
     * @param truncatedTailBytes bytes discarded from a torn final record; {@code 0} on a clean
     *                           shutdown. Assert this is 0 in your clean-restart test and
     *                           non-zero in your kill test — otherwise you have no evidence your
     *                           torn-tail handling ever actually runs, and a test that never
     *                           exercises its code is just decoration.
     * @param highestSegmentId   so the store knows what id to give the next active segment
     */
    public record ReplayResult(
            int segmentsScanned,
            long recordsApplied,
            long tombstonesApplied,
            int liveKeys,
            long truncatedTailBytes,
            int highestSegmentId) {
    }
}
