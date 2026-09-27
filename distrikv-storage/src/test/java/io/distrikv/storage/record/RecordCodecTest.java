package io.distrikv.storage.record;

import io.distrikv.storage.CorruptRecordException;
import io.distrikv.storage.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 1.1. These are real, live tests — not stubs. Run them now; they all fail with
 * {@code UnsupportedOperationException}. Your job is to make them pass.
 *
 * <p>Work through them roughly top to bottom. The round-trip tests fall out of a straightforward
 * implementation; the {@code Corruption} and {@code HostileInput} groups are the ones that will
 * make you go back and restructure {@code decode}, which is the point of having them.
 *
 * <p>These are not the complete test suite for a codec — they're the floor. When they're green,
 * ask yourself what you'd add, and add it. Then ask me for the 1.1 review.
 */
class RecordCodecTest {

    private static final long TS = 1_700_000_000_000L;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("a value record survives encode → decode unchanged")
        void valueRecord() {
            Record original = Record.value(Key.ofUtf8("user:42"), bytes("mani"), TS);

            Record decoded = RecordCodec.decode(RecordCodec.encode(original));

            assertThat(decoded).isEqualTo(original);
        }

        @Test
        @DisplayName("a tombstone survives encode → decode and stays a tombstone")
        void tombstone() {
            Record original = Record.tombstone(Key.ofUtf8("user:42"), TS);

            Record decoded = RecordCodec.decode(RecordCodec.encode(original));

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.tombstone()).isTrue();
        }

        @Test
        @DisplayName("an empty value is not the same thing as a tombstone")
        void emptyValueIsNotATombstone() {
            Record empty = Record.value(Key.ofUtf8("k"), new byte[0], TS);

            Record decoded = RecordCodec.decode(RecordCodec.encode(empty));

            assertThat(decoded.tombstone()).isFalse();
            assertThat(decoded.value()).isEmpty();
            assertThat(decoded).isNotEqualTo(Record.tombstone(Key.ofUtf8("k"), TS));
        }

        @Test
        @DisplayName("expiresAt is preserved (reserved now, used in phase 4)")
        void expiry() {
            Record original = Record.expiring(Key.ofUtf8("session"), bytes("v"), TS, TS + 60_000);

            Record decoded = RecordCodec.decode(RecordCodec.encode(original));

            assertThat(decoded.expiresAtMillis()).isEqualTo(TS + 60_000);
            assertThat(decoded.hasExpiry()).isTrue();
        }

        @Test
        @DisplayName("keys and values are arbitrary bytes, not text")
        void binarySafe() {
            // 0x00 in the middle, and 0xFF 0xFE which is not valid UTF-8. If any part of your
            // codec goes via String, this test is how you find out.
            byte[] rawKey = {0x00, (byte) 0xFF, 0x41, 0x00, (byte) 0xFE};
            byte[] rawValue = {(byte) 0x80, 0x00, (byte) 0xC0, (byte) 0xAF};

            Record original = Record.value(Key.of(rawKey), rawValue, TS);
            Record decoded = RecordCodec.decode(RecordCodec.encode(original));

            assertThat(decoded.key()).isEqualTo(Key.of(rawKey));
            assertThat(decoded.value()).containsExactly(rawValue);
        }

        @Test
        @DisplayName("a 1MB value round-trips")
        void largeValue() {
            byte[] big = new byte[1024 * 1024];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i * 31);
            }

            Record original = Record.value(Key.ofUtf8("big"), big, TS);
            Record decoded = RecordCodec.decode(RecordCodec.encode(original));

            assertThat(decoded.value()).containsExactly(big);
        }
    }

    @Nested
    @DisplayName("length accounting")
    class Lengths {

        @Test
        @DisplayName("encodedLength equals header + key + value")
        void matchesFormat() {
            Record r = Record.value(Key.ofUtf8("abc"), bytes("defgh"), TS);

            assertThat(RecordCodec.encodedLength(r))
                    .isEqualTo(RecordHeader.HEADER_BYTES + 3 + 5);
        }

        @Test
        @DisplayName("encodedLength agrees with what encode actually produces")
        void agreesWithEncode() {
            // These two must never disagree: the store calls encodedLength to decide whether a
            // record fits in the active segment before it appends. If encode produces more than
            // encodedLength promised, you overflow the segment; if less, you leave a gap.
            Record r = Record.value(Key.ofUtf8("some-key"), bytes("some-value"), TS);

            assertThat(RecordCodec.encode(r).remaining()).isEqualTo(RecordCodec.encodedLength(r));
        }

        @Test
        @DisplayName("encode returns a flipped buffer, ready to write")
        void encodeReturnsFlippedBuffer() {
            ByteBuffer encoded = RecordCodec.encode(Record.value(Key.ofUtf8("k"), bytes("v"), TS));

            assertThat(encoded.position()).isZero();
            assertThat(encoded.limit()).isEqualTo(RecordHeader.HEADER_BYTES + 2);
        }

        @Test
        @DisplayName("decode leaves the buffer positioned at the next record")
        void decodeAdvancesPosition() {
            // This is what LogReplayer will rely on in milestone 1.5: decode records from one
            // buffer in a loop. Build the API for it now.
            Record first = Record.value(Key.ofUtf8("a"), bytes("1"), TS);
            Record second = Record.value(Key.ofUtf8("bb"), bytes("22"), TS + 1);

            ByteBuffer a = RecordCodec.encode(first);
            ByteBuffer b = RecordCodec.encode(second);
            ByteBuffer both = ByteBuffer.allocate(a.remaining() + b.remaining());
            both.put(a).put(b).flip();

            assertThat(RecordCodec.decode(both)).isEqualTo(first);
            assertThat(RecordCodec.decode(both)).isEqualTo(second);
            assertThat(both.hasRemaining()).isFalse();
        }
    }

    @Nested
    @DisplayName("corruption detection")
    class Corruption {

        @Test
        @DisplayName("a flipped bit in the value is caught by the CRC")
        void corruptValue() {
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            int valueStart = RecordHeader.HEADER_BYTES + 3;
            encoded.put(valueStart, (byte) (encoded.get(valueStart) ^ 0x01));

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("a flipped bit in the key is caught by the CRC")
        void corruptKey() {
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            int keyStart = RecordHeader.HEADER_BYTES;
            encoded.put(keyStart, (byte) (encoded.get(keyStart) ^ 0x01));

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("a corrupt CRC field itself is caught")
        void corruptChecksumField() {
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            encoded.putInt(RecordHeader.CRC_OFFSET, 0xDEADBEEF);

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("a corrupt timestamp is caught — the CRC covers the header too")
        void corruptTimestamp() {
            // Worth noticing why this test exists: it's the test that fails if you compute the
            // CRC over only the key and value. The header is data, and silently-wrong metadata
            // is worse than a silently-wrong value, because recovery makes decisions with it.
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            encoded.putLong(RecordHeader.TIMESTAMP_OFFSET, 999L);

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }

    @Nested
    @DisplayName("hostile input")
    class HostileInput {

        @Test
        @DisplayName("a buffer too short to hold even a header fails cleanly")
        void shorterThanHeader() {
            ByteBuffer tiny = ByteBuffer.allocate(RecordHeader.HEADER_BYTES - 1);

            assertThatThrownBy(() -> RecordCodec.decode(tiny))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("a record truncated mid-value fails cleanly — this is the kill -9 case")
        void truncatedRecord() {
            // Exactly what LogReplayer meets at the tail of the active segment after a crash.
            // It must be an ordinary, well-typed failure, not a BufferUnderflowException that
            // escapes and takes the whole startup down.
            ByteBuffer full = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("a-long-value-here"), TS));
            ByteBuffer torn = ByteBuffer.allocate(full.remaining() - 5);
            full.limit(torn.capacity());
            torn.put(full).flip();

            assertThatThrownBy(() -> RecordCodec.decode(torn))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("an absurd keyLen must not be trusted — no OutOfMemoryError")
        void absurdKeyLength() {
            // The rule this enforces: never allocate based on a number you read off a disk
            // before you have validated it. A corrupt 4-byte length field is a 2GB allocation
            // request, and "the JVM died" is a much worse failure mode than an exception.
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            encoded.putInt(RecordHeader.KEY_LEN_OFFSET, Integer.MAX_VALUE);

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }

        @Test
        @DisplayName("a negative valueLen fails cleanly")
        void negativeValueLength() {
            ByteBuffer encoded = RecordCodec.encode(
                    Record.value(Key.ofUtf8("key"), bytes("value"), TS));

            encoded.putInt(RecordHeader.VALUE_LEN_OFFSET, -7);

            assertThatThrownBy(() -> RecordCodec.decode(encoded))
                    .isInstanceOf(CorruptRecordException.class);
        }
    }
}
