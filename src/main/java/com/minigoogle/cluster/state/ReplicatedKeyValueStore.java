package com.minigoogle.cluster.state;

import com.minigoogle.cluster.LogEntry;
import com.minigoogle.cluster.StateMachine;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A replicated, in-memory key-value state machine driven by the Raft log.
 *
 * <p>{@link #apply} decodes a {@link KvCommand} from each committed entry and
 * applies it to the local map, so every node converges to the same state. The
 * store also tracks the highest applied index and completes waiters for it:
 * because the leader applies an entry only after a quorum commit, completing
 * the waiter is the commit-ack signal for a write.
 *
 * <p>Rebuilding the map from the deterministic committed prefix reproduces the
 * same state, which is why a restarted node with a persisted log + applied
 * watermark resumes correctly.
 */
public class ReplicatedKeyValueStore implements StateMachine {

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();
    private final AtomicInteger appliedIndex = new AtomicInteger();

    @Override
    public void apply(LogEntry entry) {
        KvCommand.DecodedCommand command = KvCommand.decode(entry.payload());
        if (command.op() == KvCommand.OP_PUT) {
            store.put(command.key(), command.value());
        } else {
            store.remove(command.key());
        }
        appliedIndex.set(entry.index());
        CompletableFuture<Void> waiter = waiters.remove(entry.index());
        if (waiter != null) {
            waiter.complete(null);
        }
    }

    /**
     * @return The value stored for {@code key}, or {@code null} if absent.
     */
    public byte[] get(String key) {
        return store.get(key);
    }

    /**
     * @return The highest log index applied so far.
     */
    public int getAppliedIndex() {
        return appliedIndex.get();
    }

    @Override
    public boolean isSnapshotable() {
        return true;
    }

    /**
     * Captures the full map. Big-endian framing mirrors {@link KvCommand}:
     * {@code [4-byte count][ (2-byte key length)(key UTF-8)(4-byte value length)(value) ]*}.
     * Iteration order is irrelevant because {@link #restore(byte[])} rebuilds
     * the same map on any node.
     */
    @Override
    public byte[] snapshot() {
        Map<String, byte[]> copy = new HashMap<>(store);
        int size = 4;
        for (Map.Entry<String, byte[]> entry : copy.entrySet()) {
            size += 2 + entry.getKey().getBytes(StandardCharsets.UTF_8).length + 4 + entry.getValue().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(copy.size());
        for (Map.Entry<String, byte[]> entry : copy.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            buffer.putShort((short) key.length);
            buffer.put(key);
            buffer.putInt(entry.getValue().length);
            buffer.put(entry.getValue());
        }
        return buffer.array();
    }

    /**
     * Replaces the map with the contents of a {@link #snapshot()}. Decoding is
     * strict: malformed data fails fast rather than silently producing a
     * divergent map. The applied-index counter resets to 0; the consensus layer
     * governs {@code lastApplied} and re-applies the tail above the snapshot.
     */
    @Override
    public void restore(byte[] snapshot) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(snapshot);
            int count = buffer.getInt();
            if (count < 0) {
                throw new IllegalArgumentException("Negative entry count in snapshot");
            }
            Map<String, byte[]> rebuilt = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                int keyLength = Short.toUnsignedInt(buffer.getShort());
                byte[] keyBytes = new byte[keyLength];
                buffer.get(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                int valueLength = buffer.getInt();
                if (valueLength < 0) {
                    throw new IllegalArgumentException("Negative value length in snapshot");
                }
                byte[] value = new byte[valueLength];
                buffer.get(value);
                rebuilt.put(key, value);
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes after snapshot");
            }
            store.clear();
            store.putAll(rebuilt);
            appliedIndex.set(0);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Malformed snapshot", e);
        }
    }

    /**
     * Registers a handle that completes when the entry at {@code index} is
     * applied. If the entry is already applied, the returned future is already
     * complete.
     */
    public CompletableFuture<Void> awaitCommit(int index) {
        if (appliedIndex.get() >= index) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> existing = waiters.putIfAbsent(index, future);
        if (existing != null) {
            return existing;
        }
        if (appliedIndex.get() >= index) {
            if (waiters.remove(index, future)) {
                future.complete(null);
            }
        }
        return future;
    }
}
