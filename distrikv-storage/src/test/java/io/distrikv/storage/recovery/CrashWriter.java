package io.distrikv.storage.recovery;

import io.distrikv.storage.BitcaskStore;
import io.distrikv.storage.Key;
import io.distrikv.storage.StoreConfig;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A child process for {@link CrashRecoveryTest} to kill.
 *
 * <p>Writes keys forever and prints each one to stdout <em>after</em> the {@code put} has
 * returned. That printed line is the acknowledgement, and it is what makes the test meaningful:
 * every line the parent reads is a key the store promised to keep, so every one of them must be
 * readable after a {@code kill -9}. Printing before the put would prove nothing.
 *
 * <p>Runs with {@link StoreConfig.SyncPolicy#EVERY_WRITE}, because that is the policy whose
 * promise the test is checking. Note carefully what the test does and does not prove: SIGKILL
 * destroys this process, but the page cache belongs to the kernel, which keeps running — so
 * these keys would survive even under {@code NEVER}. Proving durability against power loss needs
 * a machine you can hard-reset. What this harness genuinely proves is that recovery handles a
 * torn tail without losing anything that was acknowledged.
 *
 * <p>Usage: {@code CrashWriter <dataDir> [valueBytes]}
 */
public final class CrashWriter {

    private CrashWriter() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: CrashWriter <dataDir> [valueBytes]");
            System.exit(2);
        }
        Path dataDir = Path.of(args[0]);
        int valueBytes = args.length > 1 ? Integer.parseInt(args[1]) : 8 * 1024;

        StoreConfig config = StoreConfig.defaults(dataDir)
                .withSyncPolicy(StoreConfig.SyncPolicy.EVERY_WRITE)
                // Small enough that a run of a few thousand keys rolls over several times, so
                // recovery is exercised across segment boundaries rather than in one file.
                .withMaxSegmentBytes(4L * 1024 * 1024);

        PrintStream out = System.out;
        try (BitcaskStore store = BitcaskStore.open(config)) {
            for (long i = 0; ; i++) {
                store.put(keyFor(i), valueFor(i, valueBytes));
                // The acknowledgement. Flushed immediately so the parent sees it without
                // waiting for a buffer to fill.
                out.println(i);
                out.flush();
            }
        }
    }

    /** The key for sequence number {@code i}. Shared with the test so both agree. */
    public static Key keyFor(long i) {
        return Key.ofUtf8("crash-key-" + i);
    }

    /**
     * A value that is a deterministic function of {@code i}, so the test can verify content and
     * not merely presence — a recovered key pointing at the wrong record would otherwise pass.
     */
    public static byte[] valueFor(long i, int length) {
        byte[] value = new byte[length];
        Arrays.fill(value, (byte) i);
        return value;
    }
}
