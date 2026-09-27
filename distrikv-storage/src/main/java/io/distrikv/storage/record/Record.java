package io.distrikv.storage.record;

import io.distrikv.storage.Key;

import java.util.Arrays;
import java.util.Objects;

/**
 * One logical entry in the log: a key with either a value (a write) or a tombstone (a delete).
 *
 * <p>Given to you complete. Two things in here are worth your attention:
 *
 * <p><b>1. Why {@code equals} and {@code hashCode} are overridden.</b> A record's auto-generated
 * {@code equals} would compare {@code value} with {@code ==}, because it's a {@code byte[]} — the
 * same identity-equality trap that {@link Key} exists to avoid. Two records holding identical
 * bytes would compare unequal, and every round-trip assertion in your codec tests would fail for
 * reasons that have nothing to do with your codec. Records let you override these; here we must.
 *
 * <p><b>2. Deletes are writes.</b> A tombstone is a real record appended to the log, not the
 * absence of one. It has to be: the log is append-only, so the only way to say "this key is gone"
 * is to write something newer than the record that created it. This is why a store under heavy
 * delete load still grows, and why Phase 4 compaction exists.
 */
public record Record(
        Key key,
        byte[] value,
        long timestampMillis,
        long expiresAtMillis,
        boolean tombstone) {

    public Record {
        Objects.requireNonNull(key, "key");
        if (tombstone) {
            if (value != null && value.length != 0) {
                throw new IllegalArgumentException("a tombstone must not carry a value");
            }
            value = EMPTY;
        } else {
            Objects.requireNonNull(value, "value (use Record.tombstone(...) for deletes)");
        }
    }

    private static final byte[] EMPTY = new byte[0];

    /** A normal write with no expiry. */
    public static Record value(Key key, byte[] value, long timestampMillis) {
        return new Record(key, value, timestampMillis, RecordHeader.NO_EXPIRY, false);
    }

    /** A write that expires at {@code expiresAtMillis} (epoch millis). Used from Phase 4. */
    public static Record expiring(Key key, byte[] value, long timestampMillis, long expiresAtMillis) {
        return new Record(key, value, timestampMillis, expiresAtMillis, false);
    }

    /** A deletion marker. */
    public static Record tombstone(Key key, long timestampMillis) {
        return new Record(key, EMPTY, timestampMillis, RecordHeader.NO_EXPIRY, true);
    }

    public boolean hasExpiry() {
        return expiresAtMillis != RecordHeader.NO_EXPIRY;
    }

    /**
     * Whether this record is expired as of {@code nowMillis}.
     *
     * <p>Note that an expired record is still physically present in the log — this is the "lazy
     * expiry" half of TTL. Reclaiming the space is compaction's job (milestone 4.5).
     */
    public boolean isExpiredAt(long nowMillis) {
        return hasExpiry() && nowMillis >= expiresAtMillis;
    }

    /** True if this record makes the key unreadable: a delete, or an expired value. */
    public boolean isDeadAt(long nowMillis) {
        return tombstone || isExpiredAt(nowMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Record other)) {
            return false;
        }
        return timestampMillis == other.timestampMillis
                && expiresAtMillis == other.expiresAtMillis
                && tombstone == other.tombstone
                && key.equals(other.key)
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(key, timestampMillis, expiresAtMillis, tombstone);
        return 31 * result + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Record[key=" + key
                + ", valueLen=" + value.length
                + ", ts=" + timestampMillis
                + (hasExpiry() ? ", expiresAt=" + expiresAtMillis : "")
                + (tombstone ? ", TOMBSTONE" : "")
                + "]";
    }
}
