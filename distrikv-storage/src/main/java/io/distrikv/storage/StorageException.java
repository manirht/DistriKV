package io.distrikv.storage;

/**
 * Base type for everything that can go wrong inside the storage engine.
 *
 * <p>Deliberately unchecked. The engine sits on a hot path and almost every I/O failure here
 * is unrecoverable at the call site — if the data file can't be read, no caller of
 * {@code get} has a sensible fallback. Callers that genuinely can recover (the gRPC layer in
 * Phase 2, mapping failures to status codes) catch this explicitly.
 *
 * <p>That is a real design decision with real trade-offs. Write down in
 * {@code docs/decisions/} whether you agree with it.
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
