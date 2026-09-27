package io.distrikv.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable, value-equal wrapper around a key's bytes.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>In Java, {@code byte[]} uses <em>identity</em> equality. Two distinct arrays holding the
 * same bytes are never {@code equals}, and their {@code hashCode}s differ. So this:
 *
 * <pre>{@code
 * Map<byte[], String> m = new HashMap<>();
 * m.put("a".getBytes(), "hello");
 * m.get("a".getBytes());   // → null. Always.
 * }</pre>
 *
 * <p>...compiles, runs, and silently never finds anything. It is the single most common bug in
 * hand-rolled Java key-value stores, and it is invisible in code review because the code looks
 * completely reasonable. Hence: keys are always a {@code Key}, never a raw {@code byte[]}.
 *
 * <h2>Why the defensive copies</h2>
 *
 * <p>{@link #of(byte[])} copies, and {@link #toByteArray()} copies back. If it didn't, a caller
 * could mutate the array after using it as a map key — the key's {@code hashCode} would change
 * while it sat in a bucket, and the entry would become permanently unreachable. That is a
 * genuinely horrible bug to debug. The copies are the price of immutability.
 *
 * <p>They also cost real allocations on the hot path — two per round-trip through the codec, one
 * on encode and one on decode. {@link #wrapNoCopy(byte[])} and {@link #backingArray()} exist as
 * the escape hatch for call sites that can prove the array is never mutated again (decoding a
 * record out of a buffer you just allocated, for example, where nothing else holds a reference
 * to it).
 *
 * <p>They are {@code public}, which deserves an explanation, because "public but don't use it"
 * is normally a smell. Java has no friend access: {@code RecordCodec} lives in a different
 * package, so package-private would put it out of reach of the one caller that most wants it.
 * The options are all imperfect — a shared {@code internal} package, a module-info with
 * selective exports, or a documented-unsafe public method. This is the third. If you'd rather
 * have one of the others, that's a reasonable call; make it deliberately and write it down.
 *
 * <p>Either way: don't reach for them yet. Write the codec with {@link #of(byte[])} and
 * {@link #toByteArray()}, get Phase 1 green, and come back in Phase 5 with a profiler. You may
 * well find the copies don't register next to the syscall. Knowing that for a fact is worth more
 * than saving them on a hunch.
 *
 * <p>This class is given to you complete. Read it and make sure you understand every line;
 * everything else in Phase 1 you write yourself.
 */
public final class Key {

    private final byte[] bytes;

    /** Cached because keys are hashed constantly and the bytes can never change. */
    private final int hash;

    private Key(byte[] bytes) {
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    /** Creates a key from a copy of {@code bytes}. */
    public static Key of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new Key(bytes.clone());
    }

    /** Convenience for tests and text keys. */
    public static Key ofUtf8(String s) {
        Objects.requireNonNull(s, "s");
        return new Key(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a key that takes ownership of {@code bytes} without copying.
     *
     * <p><b>Unsafe.</b> Only call this when you can prove nothing will ever mutate the array
     * again. If anything does, this key's {@code hashCode} changes while it is sitting in a hash
     * bucket and the entry becomes permanently unreachable — a bug that produces "the key
     * vanished" with no exception anywhere. Prefer {@link #of(byte[])} until you have profiled.
     */
    public static Key wrapNoCopy(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new Key(bytes);
    }

    /** Returns a copy of this key's bytes. */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /**
     * Direct, non-copying access to the key's bytes, for write paths that only read them.
     *
     * <p><b>Unsafe.</b> Callers must not mutate the returned array. See the class javadoc for
     * why this is public rather than package-private.
     */
    public byte[] backingArray() {
        return bytes;
    }

    public int length() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Key other)) {
            return false;
        }
        // Cheap reject before the array scan: unequal hashes cannot be equal keys.
        return this.hash == other.hash && Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Renders printable ASCII keys as text and anything else as hex, truncated.
     *
     * <p>Purely for debugging and log messages. You will spend a lot of Phase 3 reading logs;
     * a key that prints as {@code [B@1b6d3586} is worthless there.
     */
    @Override
    public String toString() {
        int shown = Math.min(bytes.length, 32);
        boolean printable = true;
        for (int i = 0; i < shown; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                printable = false;
                break;
            }
        }
        StringBuilder sb = new StringBuilder(shown * 2 + 16);
        if (printable) {
            sb.append(new String(bytes, 0, shown, StandardCharsets.US_ASCII));
        } else {
            sb.append("0x");
            for (int i = 0; i < shown; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
        }
        if (bytes.length > shown) {
            sb.append("…(").append(bytes.length).append("B)");
        }
        return sb.toString();
    }
}
