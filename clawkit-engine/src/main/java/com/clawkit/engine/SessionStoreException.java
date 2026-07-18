package com.clawkit.engine;

/** Exception carrying a stable, machine-readable Session error code. */
public final class SessionStoreException extends RuntimeException {
    private final SessionError error;
    private final String sessionId;

    public SessionStoreException(SessionError error, String sessionId, String message) {
        super(message);
        this.error = java.util.Objects.requireNonNull(error, "error required");
        this.sessionId = sessionId;
    }

    public SessionStoreException(SessionError error, String sessionId, String message, Throwable cause) {
        super(message, cause);
        this.error = java.util.Objects.requireNonNull(error, "error required");
        this.sessionId = sessionId;
    }

    public SessionError error() { return error; }
    public String sessionId() { return sessionId; }
}
