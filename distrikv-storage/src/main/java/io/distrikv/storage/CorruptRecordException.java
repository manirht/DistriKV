package io.distrikv.storage;

/**
 * Thrown when bytes on disk do not decode into a valid record: checksum mismatch, impossible
 * length field, or a truncated record at the tail of a segment.
 *
 * <p>Two very different situations produce this, and your recovery code must treat them
 * differently:
 *
 * <ul>
 *   <li><b>A torn record at the very end of the active segment.</b> Expected and benign — the
 *       process died mid-append. Recovery truncates it and carries on.
 *   <li><b>A bad record in the middle of a segment.</b> Real corruption: bit rot, a bad disk,
 *       or a bug in your own writer. This must not be silently skipped, because doing so
 *       resurrects an older version of a key and violates your durability promise.
 * </ul>
 *
 * <p>Think about how {@code LogReplayer} distinguishes these. It is milestone 1.5's central
 * problem, not an afterthought.
 */
public class CorruptRecordException extends StorageException {

    private static final long serialVersionUID = 1L;

    public CorruptRecordException(String message) {
        super(message);
    }

    public CorruptRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
