# RFC: Replicated Key-Value State Machine & Linearizable Cluster Operations

- **Status:** Proposed
- **Milestone:** Phase 4, after full log replication; before index-command state machine, log compaction/snapshots, and membership reconfiguration
- **Scope:** Apply Raft-committed log entries to an in-memory key-value state machine, add leader-only linearizable writes that acknowledge only after a quorum commit, add leader-served linearizable reads behind a read-index barrier, and expose `put`/`get`/`delete` on `ClusterNode`. No protocol bump, no DTO change, no follower-served reads, no snapshots, no membership changes.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `RaftConsensus.appendEntry` | `src/main/java/com/minigoogle/cluster/RaftConsensus.java` line 373 | Leader-only. Appends `term = currentTerm` to the log, calls `sendHeartbeats()`, returns the index. **Does not wait for commit; the caller gets no quorum ack.** |
| `RaftConsensus.advanceCommit` | line 506 | Advances `commitIndex` for a current-term entry at a strict majority of `matchIndex`es. **Only mutates `commitIndex`; nothing consumes the committed entries.** |
| `RaftConsensus.receiveAppendEntries` | lines 314-316 | Follower advances `commitIndex = Math.min(leaderCommit, lastIndex)`. **Again, nothing consumes the committed range.** |
| `RaftConsensus.getCommitIndex` | line 400 | Read only by tests and by `sendAppendEntries` (propagating `leaderCommit`). The rest of the system cannot observe committed entries. |
| `RaftLog` | `src/main/java/com/minigoogle/cluster/RaftLog.java` | Has the building blocks a consumer needs (`payloadAt`, `entriesFrom`, `snapshot`) but none is used. Entries carry an opaque `byte[]` payload with **no op-type discriminator**. |
| `RaftConsensus` 8-arg ctor | line 175 | `(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log)` — the insertion point for a state machine and an applied-index store. |
| `ClusterNode` | `src/main/java/com/minigoogle/cluster/ClusterNode.java` | Public API is `start`/`stop`/`getGossip`/`getRaft`/`getRing`/`getServer`. **No client-facing read or write.** The only write path is `getRaft().appendEntry(byte[])`, which is fire-and-forget. |
| `RaftMetadataStore` | `src/main/java/com/minigoogle/storage/metadata/RaftMetadataStore.java` | Crash-consistent `currentTerm` + `votedFor`, format `magic "RMET" + version 1`, temp+fsync+atomic-rename. Precedent for a second small atomic store for the apply watermark. `RaftConsensusPersistenceTest` pins the current file contract. |
| `StorageLayout` | `src/main/java/com/minigoogle/storage/filesystem/StorageLayout.java` | Has `getRaftMetadataPath()` (`raft-metadata.bin`) and `getRaftLogPath()` (`raft-log.bin`). No path for an applied-index watermark. |
| `BinaryWriter`/`BinaryReader` | `src/main/java/com/minigoogle/storage/serialization/` | Big-endian primitives and length-prefixed strings, but file-channel based — not usable for in-memory command encoding, which will use `ByteBuffer` directly. |
| `ARCHITECTURE.md` Ch14 | line ~15386 | "Applying the committed log to a state machine (nothing consumes the log yet), snapshotting/compaction, and membership reconfiguration" — listed as pending. |
| Tests | `src/test/java/com/minigoogle/cluster/` | Replication tests (`RaftConsensusReplicationTest`) assert commit via `getCommitIndex()`, never via applied state. `ClusterNodeIntegrationTest` line 164 waits on `getCommitIndex()`. There is no key-value store anywhere in the codebase. |

**Conclusion: Phase 3 made commit real but observable only through an accessor. The committed log feeds nothing, writes are un-acknowledged, and `ClusterNode` offers clients no way to read or write the cluster.**

---

## 2. Current Implementation

The write path, as it exists today:

```
Client -> (none) : ClusterNode exposes no write method.

Leader "append" (RaftConsensus.appendEntry):
  if state != LEADER: throw IllegalStateException("Only the leader may append...")
  index = log.append(currentTerm, payload)   // durable locally (fsync)
  sendHeartbeats()                           // replicate async, fire-and-forget
  return index                               // RETURNED BEFORE COMMIT

Follower (receiveAppendEntries):
  log-consistency check, append/truncate tail
  commitIndex = min(leaderCommit, lastIndex) // number moves, nothing applies

Leader commit (advanceCommit):
  commitIndex = n                            // number moves, nothing applies
```

Observations:

- `commitIndex` is a dead number everywhere except tests. A client cannot ask "has my write committed?" and nothing can be read back.
- `appendEntry` acknowledges before the entry is on a majority, so a "successful" append can be lost if the leader fails before replicating.
- Reads have no relationship to commit state; `ClusterNode` has no read at all.
- A write sent to a follower throws `IllegalStateException` with no way for the caller to learn who the leader is (`getCurrentLeader()` exists on `RaftConsensus` but the exception doesn't carry it).

---

## 3. Weaknesses

1. **The committed log is consumed by nothing.** Phase 3 built the pipeline up to commit; the log ends at `commitIndex`. No state machine, no applied entries, no read-back of anything the cluster committed.
2. **Writes are acknowledged before commit.** `appendEntry` returns immediately. A caller cannot distinguish "durably replicated on a majority" from "in the leader's local buffer", so acknowledged writes can silently disappear on leader failure.
3. **No client-facing read/write API.** `ClusterNode` exposes internals (`getRaft()`, `getRing()`) but no operation. The system has nothing a client can actually do with the replicated log.
4. **No leader discovery for clients.** A write against a follower raises an opaque `IllegalStateException`; the caller cannot redirect. Linearizable operations require the leader.
5. **No command framing.** Payloads are opaque bytes. An apply pipeline needs an op-type discriminator (`PUT` vs `DELETE`) to interpret entries deterministically across the cluster.
6. **No durable commit watermark.** `commitIndex` resets to 0 on restart. Without a persisted `lastApplied`, a rebuilt state machine would be stale until the next commit and, worse, would mis-apply if it started from the whole log (uncommitted tail entries get truncated by a future leader after being applied — poison).

---

## 4. Alternative Designs

### A. In-consensus apply hook + KV state machine + read-index barrier (recommended)

Wire a `StateMachine` into `RaftConsensus`: every time `commitIndex` advances (leader in `advanceCommit`, follower in `receiveAppendEntries`), drain `[lastApplied+1 .. commitIndex]` in order and call `stateMachine.apply(entry)`. Persist `lastApplied` to a new small atomic store. Add a read-index barrier for linearizable leader reads. Add `put`/`get`/`delete` to `ClusterNode` that block until commit (writes) or the barrier (reads).

Pros: Raft-correct (apply strictly follows commit, on every node, deterministically); reuses the Phase 2 atomic-store pattern for the watermark; no wire change; linearizable writes and reads; client API with leader redirect. Cons: apply runs on the consensus lock (fine while the KV apply is trivial); a new small file; one extra round trip for reads when the quorum is stale.

### B. Extend `RaftMetadataStore` to also persist the commit watermark

Bump the `RMET` file to version 2 and store `commitIndex` alongside `currentTerm`/`votedFor`.

Pros: one file instead of two; no new store class. Cons: changes the Phase 2 file contract that `RaftConsensusPersistenceTest` pins, mixes election metadata with apply progress, and forces a format migration with no reader for the old layout. Rejected: the two-phase stores already exist; a dedicated `raft-applied.bin` is additive and leaves v1 untouched.

### C. No watermark; rebuild the KV by applying the whole replayed log at startup

On restart, apply `[1 .. lastIndex]` and assume that equals the committed prefix.

Pros: no new file. Cons: **incorrect** — an uncommitted tail entry applied at startup is later truncated by a new leader's AppendEntries, leaving the state machine with garbage it can never un-apply; and an acked write could vanish after a full-cluster restart (no linearizability). Rejected on correctness.

### D. Follower-served reads with a client redirect protocol

Add a `redirect`/`leader` internal endpoint so any node can forward or answer reads.

Pros: lower read latency distribution. Cons: new internal endpoint + wire surface, read-forwarding backpressure; the milestone only needs leader-served reads. Rejected; followers surface `NotLeaderException` carrying the leader id instead. Defer to Phase 9+.

### E. Apply asynchronously on a dedicated single-thread executor

Decouple apply from the consensus lock so a slow state machine never stalls heartbeats.

Pros: production-shaped. Cons: ordering/visibility complexity — reads must synchronize with the apply thread; the KV apply here is a map put, microseconds. In-lock synchronous apply guarantees reads observe applied state with zero extra synchronization. Defer to Phase 9+ when the state machine gets heavier.

---

## 5. Comparison

| Criterion | A. In-consensus apply + KV | B. Extend RMET v2 | C. Rebuild whole log | D. Follower reads | E. Async apply |
|---|---|---|---|---|---|
| Linearizable writes (ack on commit) | yes | yes | yes | yes | yes |
| Linearizable reads (read-index barrier) | yes | yes | yes | leader-only | yes |
| Correct after restart (no stale/mis-applied state) | yes | yes | **no** | yes | yes |
| Phase 2 `RMET` v1 file contract | untouched | **bumped** | untouched | untouched | untouched |
| Raft RPC processing impact | negligible | n/a | n/a | n/a | none (extra thread) |
| New wire surface | none | none | none | **one endpoint** | none |
| Code churn / risk | medium / low | medium / low | low / **wrong** | medium / medium | medium / medium |

---

## 6. Recommended Design (Alternative A)

### 6.1 Command framing (inside the existing opaque payload)

`RaftLog.toFrame` (wire framing `[4-byte term][payload]`) is unchanged; the **payload** gets a 1-byte op discriminator. Protocol stays v1.

```
PUT    = [0x01][2-byte key length][key UTF-8][4-byte value length][value]
DELETE = [0x02][2-byte key length][key UTF-8]
```

Keys ≤ 65535 bytes; values ≤ 2^31−1 bytes. Encoded/decoded with `ByteBuffer` (big-endian), mirroring `BinaryWriter`/`BinaryReader` conventions but in-memory.

### 6.2 New types

`com.minigoogle.cluster.StateMachine`:

```java
public interface StateMachine {
    /** Invoked once per committed entry, in increasing index order, single-threaded. */
    void apply(LogEntry entry);
}
```

`com.minigoogle.cluster.NotLeaderException extends RuntimeException` — carries `leaderId` (may be `null` when unknown), exposes `getLeaderId()`.

`com.minigoogle.cluster.state.KvCommand` — op constants `PUT = 0x01`, `DELETE = 0x02`; `encodePut(String key, byte[] value)`, `encodeDelete(String key)`; strict decode (malformed payload fails fast).

`com.minigoogle.cluster.state.ReplicatedKeyValueStore implements StateMachine`:
- Backed by `ConcurrentHashMap<String, byte[]>`.
- `apply(LogEntry)` decodes the command and applies it, then completes any waiter for that index.
- `byte[] get(String key)`; `void put(String key, byte[] value)`; `void delete(String key)` (local operations used by the apply path and by reads).
- `CompletableFuture<Void> awaitCommit(int index)` — completed by `apply`; this is the leader's write-ack channel (the leader applies only after a quorum commit).

`com.minigoogle.storage.metadata.RaftAppliedStore` — mirrors `RaftMetadataStore` exactly (magic `"RAPP"`, version 1, temp+fsync+atomic-rename, `inMemory()` no-op, missing file → 0, corrupt file fails fast). `StorageLayout.getRaftAppliedPath()` → `raft-applied.bin`.

### 6.3 `RaftConsensus`

- New 10-arg ctor `(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log, StateMachine stateMachine, RaftAppliedStore appliedStore)`, both new params nullable; the existing 8-arg ctor delegates with `null, null` (behavior byte-for-byte identical when either is null).
- New field `private int lastApplied` (default 0).
- After every `commitIndex` advance — the leader in `advanceCommit()` and the follower in `receiveAppendEntries` — drain `[lastApplied+1 .. commitIndex]` in index order: `stateMachine.apply(log entry)` for each (skipped when `stateMachine == null`), then `lastApplied = commitIndex`, then `appliedStore.persist(lastApplied)` (skipped when null). Because all mutators are `synchronized` and apply is in-lock, reads issued after a write ack always observe the applied effect.
- Startup rebuild: after the `RaftLog` replays its WAL, if `appliedStore != null` and `stateMachine != null`, load `lastApplied` and synchronously apply the deterministic prefix `[1 .. lastApplied]`. This is why committed writes survive a full-cluster restart.
- Read-index barrier: `boolean prepareReadBarrier()` — leader-only; starts an internal barrier round, sends empty AppendEntries (current term) to every follower, and returns `true` once a strict majority of that round (self + peers) ack `success` with the current term; returns `false` on timeout. With no transport (single node) it returns immediately. This is the linearizability guard that stops a partitioned leader from serving stale reads.
- New accessor `int getLastApplied()`.

### 6.4 `ClusterNode` (client-facing API)

- `void put(String key, byte[] value)` — leader-only. Frames `KvCommand.encodePut`, calls `raft.appendEntry(frame)`, then blocks on `stateMachine.awaitCommit(index)` (bounded wait). Acknowledges only after quorum commit + local apply.
- `void delete(String key)` — same, with `encodeDelete`.
- `byte[] get(String key)` — leader-only. Calls `raft.prepareReadBarrier()`; on success returns `stateMachine.get(key)`; on barrier failure or non-leader throws `NotLeaderException`.
- All three throw `NotLeaderException` (carrying `raft.getCurrentLeader()`) when the node is not the leader, so a client can redirect.
- Wiring: a new constructor overload accepts the KV state machine and threads it into `RaftConsensus`; the storage-directory path builds a real `RaftAppliedStore` (path from `StorageLayout`), the in-memory path uses in-memory variants. Existing constructors keep the old behavior (no state machine).

---

## 7. Migration Plan

1. Add `StateMachine`, `NotLeaderException`, `KvCommand`, `ReplicatedKeyValueStore`, `RaftAppliedStore`, `StorageLayout.getRaftAppliedPath()`; unit tests for command round-trip, KV semantics, and the applied store (commit `m1`). Pure additive; nothing wired.
2. Wire the state machine + applied store into `RaftConsensus` (10-arg ctor, `lastApplied`, drain-and-apply on leader and follower commit advances, startup prefix rebuild); new `RaftConsensusApplyTest` (fake transport): entries applied once in order on leader and followers, a truncated uncommitted tail is never applied, the applied watermark survives a restart, and null-state-machine behavior is byte-for-byte identical to Phase 3 (commit `m2`).
3. Add `prepareReadBarrier()` + new `RaftConsensusReadTest` (silent/partitioned peers refuse reads, quorum ack serves reads); add `ClusterNode` `put`/`get`/`delete` with blocking commit ack and `NotLeaderException`; new `ClusterNodeKvIntegrationTest` (real HTTP 3-node cluster): leader `put` commits and `get` returns the value, follower operations raise `NotLeaderException` carrying the leader, a node restarted on its storage directory rebuilds the KV from the log + watermark, a re-elected leader resumes serving reads/writes (commit `m3`).
4. Update `ARCHITECTURE.md` Ch14 (state-machine apply + linearizable reads/writes implemented, `raft-applied.bin` in the storage tree), run the full suite, produce the impact summary, commit (commit `m4`), push to both repos.

---

## 8. Acceptance Criteria

- After `leader.put(k, v)` returns, `leader.get(k)` returns `v`; the write acknowledged only after a quorum commit and local apply.
- A follower's `put`/`get`/`delete` throws `NotLeaderException` whose `getLeaderId()` matches the actual leader; a client can redirect and succeed.
- Entries are applied exactly once, in index order, on the leader and on every follower; an uncommitted tail later truncated by a new leader is never applied.
- With real storage: kill and restart every node; previously acked key-value pairs are served by the new leader after re-election (watermark + deterministic prefix rebuild).
- A leader that has lost its quorum (peers silent) refuses linearizable reads rather than serving stale data; a leader with a quorum serves them.
- Protocol stays v1; no DTO change; `RaftMetadataStore` `RMET` v1 file untouched; existing 406 tests stay green; all new tests green; `.\gradlew.bat build -x test` succeeds.

---

## 9. Rollback

- `m1` is pure additive (new types, no call sites changed) — safe to keep or revert alone.
- `m2`–`m3` revert in reverse order (ClusterNode API → read barrier → consensus apply → KV/command/store types). No wire-format change, so a mixed rollout interoperates: old nodes store new frames without applying them, new nodes apply them; `NotLeaderException` is additive.
- `raft-applied.bin` is an operator-deletable cache: deleting it means a node rebuilds from the next committed index (reads before that are stale, never corrupted). The log and `RMET` file are unaffected.
- Deterministic KV apply makes the watermark a performance/availability optimization, not a correctness dependency, once a commit happens; the watermark's only strict role is serving pre-crash committed state immediately after restart.

---

## 10. Estimated Impact

- **Files changed:** 5 new main (`StateMachine`, `NotLeaderException`, `KvCommand`, `ReplicatedKeyValueStore`, `RaftAppliedStore`); 3 edited main (`RaftConsensus`, `ClusterNode`, `StorageLayout`); ~6 new test files.
- **Lines:** ≈ 550–700 added; ≈ 0 deleted.
- **LoC delta:** +600ish on the ~14,000-line codebase ≈ +4%.
- **Testing cost:** +35–45 tests, +10–20 min suite time, total ≈ 445–450 tests.
- **Dependencies:** none new; reuses `ByteBuffer`, `ConcurrentHashMap`, and the Phase 2 atomic-store pattern.
- **Behavior deltas:** writes now acknowledge only after quorum commit; leader reads are linearizable behind a read-index barrier; followers surface `NotLeaderException` with the leader id; the committed log finally drives a state machine. No visible change to search fan-out or the demo REST API.
- **Docs:** `ARCHITECTURE.md` Ch14 §7 (apply) + storage-layout tree (`raft-applied.bin`); the "nothing consumes the log yet" note is replaced by "committed entries apply to an in-memory KV state machine"; snapshots/compaction/membership remain pending.
