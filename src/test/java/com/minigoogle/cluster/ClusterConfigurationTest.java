package com.minigoogle.cluster;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the immutable Raft cluster configuration. */
class ClusterConfigurationTest {

    @Test
    void testEmptyConfiguration() {
        ClusterConfiguration empty = ClusterConfiguration.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertEquals(1, empty.majority());
        assertFalse(empty.contains("node-1"));
    }

    @Test
    void testMajorityArithmetic() {
        assertEquals(1, ClusterConfiguration.of("a").majority());
        assertEquals(2, ClusterConfiguration.of("a", "b").majority());
        assertEquals(2, ClusterConfiguration.of("a", "b", "c").majority());
        assertEquals(3, ClusterConfiguration.of("a", "b", "c", "d").majority());
        assertEquals(3, ClusterConfiguration.of("a", "b", "c", "d", "e").majority());
    }

    @Test
    void testOfFiltersNullAndEmpty() {
        ClusterConfiguration config = ClusterConfiguration.of(java.util.Arrays.asList("a", null, "", "b"));
        assertEquals(Set.of("a", "b"), config.members());
    }

    @Test
    void testOfDeDuplicates() {
        ClusterConfiguration config = ClusterConfiguration.of("a", "b", "a");
        assertEquals(2, config.size());
        assertEquals(Set.of("a", "b"), config.members());
    }

    @Test
    void testPlusAddsMember() {
        ClusterConfiguration config = ClusterConfiguration.of("a", "b");
        ClusterConfiguration updated = config.plus("c");
        assertEquals(Set.of("a", "b", "c"), updated.members());
        assertEquals(Set.of("a", "b"), config.members(), "The original config must be unchanged");
    }

    @Test
    void testPlusIsIdempotent() {
        ClusterConfiguration config = ClusterConfiguration.of("a");
        assertSame(config, config.plus("a"));
        assertSame(config, config.plus(null));
    }

    @Test
    void testMinusRemovesMember() {
        ClusterConfiguration config = ClusterConfiguration.of("a", "b", "c");
        ClusterConfiguration updated = config.minus("b");
        assertEquals(Set.of("a", "c"), updated.members());
        assertEquals(Set.of("a", "b", "c"), config.members(), "The original config must be unchanged");
    }

    @Test
    void testMinusIsIdempotent() {
        ClusterConfiguration config = ClusterConfiguration.of("a");
        assertSame(config, config.minus("z"));
        assertSame(config, config.minus(null));
    }

    @Test
    void testMinusToEmptyReturnsEmpty() {
        ClusterConfiguration config = ClusterConfiguration.of("a");
        ClusterConfiguration emptied = config.minus("a");
        assertTrue(emptied.isEmpty());
        assertSame(ClusterConfiguration.EMPTY, emptied);
    }

    @Test
    void testEqualityIsOrderInsensitive() {
        ClusterConfiguration config1 = ClusterConfiguration.of("a", "b", "c");
        ClusterConfiguration config2 = ClusterConfiguration.of("c", "b", "a");
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testEqualsAndHashCode() {
        ClusterConfiguration config = ClusterConfiguration.of("a", "b");
        assertEquals(config, config);
        assertNotEquals(config, ClusterConfiguration.of("a", "b", "c"));
        assertNotEquals(config, null);
        assertNotEquals(config, "a");
        assertEquals(config.hashCode(), ClusterConfiguration.of("a", "b").hashCode());
    }

    @Test
    void testMembersReturnsUnmodifiableView() {
        ClusterConfiguration config = ClusterConfiguration.of("a", "b");
        assertThrows(UnsupportedOperationException.class, () -> config.members().add("c"));
    }
}
