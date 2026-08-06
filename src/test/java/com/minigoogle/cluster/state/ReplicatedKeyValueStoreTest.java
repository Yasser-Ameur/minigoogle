package com.minigoogle.cluster.state;

import com.minigoogle.cluster.LogEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the replicated key-value state machine. */
class ReplicatedKeyValueStoreTest {

    private static final byte[] V1 = "v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2 = "v2".getBytes(StandardCharsets.UTF_8);

    @Test
    void testApplyPutThenGet() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));

        assertArrayEquals(V1, store.get("k"));
        assertEquals(1, store.getAppliedIndex());
    }

    @Test
    void testApplyPutOverwrites() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        store.apply(new LogEntry(2, 1, KvCommand.encodePut("k", V2)));

        assertArrayEquals(V2, store.get("k"));
    }

    @Test
    void testApplyDeleteRemoves() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        store.apply(new LogEntry(2, 1, KvCommand.encodeDelete("k")));

        assertNull(store.get("k"));
    }

    @Test
    void testDeleteAbsentKeyIsNoOp() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodeDelete("missing")));

        assertNull(store.get("missing"));
        assertEquals(1, store.getAppliedIndex());
    }

    @Test
    void testGetBeforeAnyApplyIsNull() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        assertNull(store.get("k"));
        assertEquals(0, store.getAppliedIndex());
    }

    @Test
    void testAwaitCommitCompletesOnApply() throws Exception {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        CompletableFuture<Void> future = store.awaitCommit(1);

        assertFalse(future.isDone());
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));

        future.get(2, TimeUnit.SECONDS);
        assertTrue(future.isDone());
    }

    @Test
    void testAwaitCommitAlreadyApplied() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));

        CompletableFuture<Void> future = store.awaitCommit(1);
        assertTrue(future.isDone());
    }

    @Test
    void testAwaitCommitFutureForAppliedIndexAfterRegistration() throws Exception {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        CompletableFuture<Void> future = store.awaitCommit(2);
        assertFalse(future.isDone());

        store.apply(new LogEntry(1, 1, KvCommand.encodePut("a", V1)));
        assertFalse(future.isDone(), "Index 1 must not complete the waiter for index 2");

        store.apply(new LogEntry(2, 1, KvCommand.encodePut("b", V2)));
        future.get(2, TimeUnit.SECONDS);
    }

    @Test
    void testApplyRebuildsDeterministicState() {
        ReplicatedKeyValueStore first = new ReplicatedKeyValueStore();
        first.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        first.apply(new LogEntry(2, 1, KvCommand.encodePut("j", V2)));
        first.apply(new LogEntry(3, 2, KvCommand.encodeDelete("k")));

        ReplicatedKeyValueStore rebuilt = new ReplicatedKeyValueStore();
        rebuilt.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        rebuilt.apply(new LogEntry(2, 1, KvCommand.encodePut("j", V2)));
        rebuilt.apply(new LogEntry(3, 2, KvCommand.encodeDelete("k")));

        assertNull(rebuilt.get("k"));
        assertArrayEquals(V2, rebuilt.get("j"));
        assertEquals(3, rebuilt.getAppliedIndex());
    }

    @Test
    void testIsSnapshotable() {
        assertTrue(new ReplicatedKeyValueStore().isSnapshotable());
    }

    @Test
    void testSnapshotRoundTrip() {
        ReplicatedKeyValueStore source = new ReplicatedKeyValueStore();
        source.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        source.apply(new LogEntry(2, 1, KvCommand.encodePut("j", V2)));
        source.apply(new LogEntry(3, 2, KvCommand.encodeDelete("k")));

        byte[] snapshot = source.snapshot();

        ReplicatedKeyValueStore restored = new ReplicatedKeyValueStore();
        restored.restore(snapshot);
        assertNull(restored.get("k"));
        assertArrayEquals(V2, restored.get("j"));
        assertEquals(0, restored.getAppliedIndex(), "restore resets the applied-index counter");
    }

    @Test
    void testSnapshotOfEmptyStore() {
        ReplicatedKeyValueStore source = new ReplicatedKeyValueStore();
        byte[] snapshot = source.snapshot();

        ReplicatedKeyValueStore restored = new ReplicatedKeyValueStore();
        restored.restore(snapshot);
        assertNull(restored.get("k"));
        assertEquals(0, restored.getAppliedIndex());
    }

    @Test
    void testRestoreReplacesPriorState() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        store.apply(new LogEntry(1, 1, KvCommand.encodePut("old", V1)));

        ReplicatedKeyValueStore source = new ReplicatedKeyValueStore();
        source.apply(new LogEntry(1, 1, KvCommand.encodePut("new", V2)));

        store.restore(source.snapshot());
        assertNull(store.get("old"));
        assertArrayEquals(V2, store.get("new"));
    }

    @Test
    void testRestoreMalformedSnapshotFailsFast() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        assertThrows(IllegalArgumentException.class, () -> store.restore(new byte[]{0, 0, 0, 5}));
        assertThrows(IllegalArgumentException.class, () -> store.restore(new byte[]{0, 0, 0, 0, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> store.restore(KvCommand.encodePut("k", V1)));
    }

    @Test
    void testRestoreCorruptedAfterValidHeaderFailsFast() {
        ReplicatedKeyValueStore source = new ReplicatedKeyValueStore();
        source.apply(new LogEntry(1, 1, KvCommand.encodePut("k", V1)));
        byte[] snapshot = source.snapshot();
        byte[] truncated = new byte[snapshot.length - 1];
        System.arraycopy(snapshot, 0, truncated, 0, truncated.length);

        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        assertThrows(IllegalArgumentException.class, () -> store.restore(truncated));
    }
}
