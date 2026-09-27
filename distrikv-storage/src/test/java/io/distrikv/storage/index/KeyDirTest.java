package io.distrikv.storage.index;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Milestone 1.3. Short, because {@link KeyDir} should be short.
 *
 * <p>The one test here that matters more than it looks is {@code twoDistinctArraysWithSameBytes}.
 * It's the regression test for the {@code byte[]} identity-equality trap, and if you ever
 * "optimise" {@code Key} away in favour of raw arrays, it's the test that will stop you.
 */
@Disabled("milestone 1.3 — delete this annotation per test as you implement KeyDir")
class KeyDirTest {

    @Test
    @DisplayName("put then get returns the location")
    void putGet() {
    }

    @Test
    @DisplayName("get on an unknown key returns null")
    void getMissing() {
    }

    @Test
    @DisplayName("put overwrites an existing location")
    void putOverwrites() {
    }

    @Test
    @DisplayName("two distinct arrays holding the same bytes are the same key")
    void twoDistinctArraysWithSameBytes() {
        // Key.of("abc".getBytes()) and Key.of("abc".getBytes()) are different array objects.
        // Put with one, get with the other. This must work. Read Key's javadoc on why it
        // wouldn't if keys were raw byte[].
    }

    @Test
    @DisplayName("mutating the caller's array after Key.of does not corrupt the index")
    void defensiveCopy() {
        // byte[] raw = {1,2,3}; Key k = Key.of(raw); keyDir.put(k, loc); raw[0] = 99;
        // keyDir.get(Key.of(new byte[]{1,2,3})) must still find it.
    }

    @Test
    @DisplayName("putIfNewer accepts a newer timestamp")
    void putIfNewerAccepts() {
    }

    @Test
    @DisplayName("putIfNewer rejects an older timestamp")
    void putIfNewerRejects() {
    }

    @Test
    @DisplayName("putIfNewer on an equal timestamp behaves the way you documented")
    void putIfNewerTie() {
        // There is no universally right answer. Pick one, document it in the implementation's
        // javadoc, and pin it here — because recovery correctness depends on it and a decision
        // that only exists in your head will be reversed by accident in six months.
    }

    @Test
    @DisplayName("remove drops the key and reports whether it was there")
    void remove() {
    }

    @Test
    @DisplayName("keys() is a snapshot — writing during iteration does not blow up")
    void keysIsSnapshot() {
    }
}
