package com.minigoogle.storage.metadata;

/**
 * Raft election metadata that must survive a node restart: the highest term
 * observed by this node, and the candidate it voted for in that term
 * ({@code null} if no vote has been cast yet).
 */
public record RaftMetadata(int currentTerm, String votedFor) {

    /**
     * @return The state of a node that has never participated in an election.
     */
    public static RaftMetadata empty() {
        return new RaftMetadata(0, null);
    }
}
