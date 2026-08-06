package com.minigoogle.cluster.state;

import com.minigoogle.cluster.LogEntry;
import com.minigoogle.cluster.StateMachine;

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
