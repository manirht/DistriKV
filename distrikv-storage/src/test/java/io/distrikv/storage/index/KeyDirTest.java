package io.distrikv.storage.index;

import io.distrikv.storage.Key;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 1.3. Short, because {@link KeyDir} should be short.
 *
 * <p>The one test here that matters more than it looks is {@link #twoDistinctArraysWithSameBytes}.
 * It's the regression test for the {@code byte[]} identity-equality trap, and if anyone ever
 * "optimises" {@code Key} away in favour of raw arrays, it's the test that will stop them.
 */
class KeyDirTest {

    private static final long TS = 1_700_000_000_000L;

    private KeyDir keyDir;

    @BeforeEach
    void setUp() {
        keyDir = KeyDir.create();
    }

    private static ValueLocation at(long offset, long timestampMillis) {
        return new ValueLocation(1, offset, 64, timestampMillis);
    }

    private static Key key(String s) {
        return Key.of(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("put then get returns the location")
    void putGet() {
        ValueLocation location = at(0, TS);
        keyDir.put(key("a"), location);

        assertThat(keyDir.get(key("a"))).isEqualTo(location);
        assertThat(keyDir.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("get on an unknown key returns null")
    void getMissing() {
        assertThat(keyDir.get(key("nope"))).isNull();
        assertThat(keyDir.size()).isZero();
    }

    @Test
    @DisplayName("put overwrites an existing location")
    void putOverwrites() {
        keyDir.put(key("a"), at(0, TS));
        ValueLocation newer = at(128, TS + 1);
        keyDir.put(key("a"), newer);

        assertThat(keyDir.get(key("a"))).isEqualTo(newer);
        assertThat(keyDir.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("put ignores the timestamp — that's putIfNewer's job")
    void putIsUnconditional() {
        // Worth pinning: the two methods differ only in this, and a `put` that quietly became
        // conditional would break the write path in a way no other test would notice.
        keyDir.put(key("a"), at(128, TS));
        ValueLocation older = at(0, TS - 10_000);
        keyDir.put(key("a"), older);

        assertThat(keyDir.get(key("a"))).isEqualTo(older);
    }

    @Test
    @DisplayName("two distinct arrays holding the same bytes are the same key")
    void twoDistinctArraysWithSameBytes() {
        // Key.of("abc".getBytes()) and Key.of("abc".getBytes()) are different array objects. Put
        // with one, get with the other. With raw byte[] keys this returns null, always — see
        // Key's javadoc.
        byte[] first = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] second = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(first).isNotSameAs(second);

        ValueLocation location = at(0, TS);
        keyDir.put(Key.of(first), location);

        assertThat(keyDir.get(Key.of(second))).isEqualTo(location);
    }

    @Test
    @DisplayName("mutating the caller's array after Key.of does not corrupt the index")
    void defensiveCopy() {
        byte[] raw = {1, 2, 3};
        Key k = Key.of(raw);
        ValueLocation location = at(0, TS);
        keyDir.put(k, location);

        raw[0] = 99; // if Key held this array, the entry would now be unreachable forever

        assertThat(keyDir.get(Key.of(new byte[] {1, 2, 3}))).isEqualTo(location);
        assertThat(keyDir.get(Key.of(new byte[] {99, 2, 3}))).isNull();
    }

    @Test
    @DisplayName("putIfNewer accepts a newer timestamp")
    void putIfNewerAccepts() {
        keyDir.put(key("a"), at(0, TS));
        ValueLocation newer = at(128, TS + 1);

        assertThat(keyDir.putIfNewer(key("a"), newer)).isTrue();
        assertThat(keyDir.get(key("a"))).isEqualTo(newer);
    }

    @Test
    @DisplayName("putIfNewer accepts an absent key")
    void putIfNewerOnAbsentKey() {
        ValueLocation location = at(0, TS);

        assertThat(keyDir.putIfNewer(key("fresh"), location)).isTrue();
        assertThat(keyDir.get(key("fresh"))).isEqualTo(location);
    }

    @Test
    @DisplayName("putIfNewer rejects an older timestamp")
    void putIfNewerRejects() {
        ValueLocation current = at(128, TS);
        keyDir.put(key("a"), current);

        assertThat(keyDir.putIfNewer(key("a"), at(0, TS - 1))).isFalse();
        assertThat(keyDir.get(key("a"))).isEqualTo(current);
    }

    @Test
    @DisplayName("putIfNewer on an equal timestamp behaves the way you documented")
    void putIfNewerTie() {
        // Documented behaviour: an equal timestamp counts as newer, so the later-arriving record
        // wins and recovery must scan in ascending (segmentId, offset) order. Rejecting ties
        // would keep the older of two records written in the same millisecond, which happens
        // routinely. See docs/decisions/002; this test is what pins the choice.
        keyDir.put(key("a"), at(0, TS));
        ValueLocation sameTimestampLaterOffset = at(128, TS);

        assertThat(keyDir.putIfNewer(key("a"), sameTimestampLaterOffset)).isTrue();
        assertThat(keyDir.get(key("a"))).isEqualTo(sameTimestampLaterOffset);
    }

    @Test
    @DisplayName("remove drops the key and reports whether it was there")
    void remove() {
        keyDir.put(key("a"), at(0, TS));

        assertThat(keyDir.remove(key("a"))).isTrue();
        assertThat(keyDir.get(key("a"))).isNull();
        assertThat(keyDir.size()).isZero();

        assertThat(keyDir.remove(key("a"))).isFalse();
    }

    @Test
    @DisplayName("keys() is a snapshot — writing during iteration does not blow up")
    void keysIsSnapshot() {
        keyDir.put(key("a"), at(0, TS));
        keyDir.put(key("b"), at(128, TS));

        Set<Key> snapshot = keyDir.keys();
        assertThat(snapshot).containsExactlyInAnyOrder(key("a"), key("b"));

        // Iterating the snapshot while mutating the index must not throw
        // ConcurrentModificationException, which is exactly what returning the map's own keySet
        // would do.
        int seen = 0;
        for (Key ignored : snapshot) {
            keyDir.put(key("added-" + seen), at(256 + seen, TS));
            seen++;
        }

        assertThat(seen).isEqualTo(2);
        assertThat(snapshot).hasSize(2);   // the snapshot did not grow
        assertThat(keyDir.size()).isEqualTo(4); // but the index did
    }
}