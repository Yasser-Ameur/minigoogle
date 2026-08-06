package com.minigoogle.cluster;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable set of Raft cluster members derived from committed config-change
 * entries.
 *
 * <p>Members are held in insertion order ({@link LinkedHashSet}) so that a
 * config serializes deterministically into {@code raft-config.bin} and into
 * snapshots, but equality is order-insensitive: two configs with the same
 * member set are equal regardless of insertion order.
 *
 * <p>The quorum for a config is {@code size() / 2 + 1}, the Raft strict
 * majority. The empty config ({@link #EMPTY}) has no meaningful quorum; a
 * consensus node with an empty config runs in bootstrap mode and derives its
 * quorum from the peer supplier / cluster size instead.
 */
public final class ClusterConfiguration {

    /** The empty configuration: no established members. */
    public static final ClusterConfiguration EMPTY = new ClusterConfiguration(Set.of());

    private final Set<String> members;

    private ClusterConfiguration(Collection<String> members) {
        this.members = Set.copyOf(members);
    }

    /**
     * Creates a config from the given member IDs, ignoring {@code null} and
     * empty values and de-duplicating.
     */
    public static ClusterConfiguration of(Collection<String> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return EMPTY;
        }
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (String id : memberIds) {
            if (id != null && !id.isEmpty()) {
                filtered.add(id);
            }
        }
        return filtered.isEmpty() ? EMPTY : new ClusterConfiguration(filtered);
    }

    /** Creates a config from the given member IDs. */
    public static ClusterConfiguration of(String... memberIds) {
        return of(Arrays.asList(memberIds));
    }

    /** @return An unmodifiable view of the member IDs, in insertion order. */
    public Set<String> members() {
        return members;
    }

    /** @return The number of members. */
    public int size() {
        return members.size();
    }

    /**
     * @return The Raft strict majority of this config ({@code size() / 2 + 1}).
     *         The empty config returns 1, which is meaningless; callers must
     *         only use this once a config is established.
     */
    public int majority() {
        return size() / 2 + 1;
    }

    /** @return Whether {@code nodeId} is a member. */
    public boolean contains(String nodeId) {
        return members.contains(nodeId);
    }

    /** @return Whether this config has no members. */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** @return A new config with {@code nodeId} added (idempotent). */
    public ClusterConfiguration plus(String nodeId) {
        if (nodeId == null || members.contains(nodeId)) {
            return this;
        }
        LinkedHashSet<String> updated = new LinkedHashSet<>(members);
        updated.add(nodeId);
        return new ClusterConfiguration(updated);
    }

    /** @return A new config with {@code nodeId} removed (idempotent). */
    public ClusterConfiguration minus(String nodeId) {
        if (nodeId == null || !members.contains(nodeId)) {
            return this;
        }
        LinkedHashSet<String> updated = new LinkedHashSet<>(members);
        updated.remove(nodeId);
        return updated.isEmpty() ? EMPTY : new ClusterConfiguration(updated);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClusterConfiguration that)) {
            return false;
        }
        return members.equals(that.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(members);
    }

    @Override
    public String toString() {
        return "ClusterConfiguration" + members;
    }
}
