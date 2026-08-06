package com.minigoogle.cluster;

/**
 * Thrown when a linearizable cluster operation (read or write) is attempted on
 * a node that is not the current leader. Callers can redirect to the returned
 * leader id when one is known.
 */
public class NotLeaderException extends RuntimeException {

    private final String leaderId;

    public NotLeaderException(String leaderId, String message) {
        super(message);
        this.leaderId = leaderId;
    }

    public NotLeaderException(String leaderId, String message, Throwable cause) {
        super(message, cause);
        this.leaderId = leaderId;
    }

    public NotLeaderException(String message) {
        this(null, message);
    }

    /**
     * @return The node ID of the current leader, or {@code null} when it is
     *         not yet known.
     */
    public String getLeaderId() {
        return leaderId;
    }
}
