# RFC: Raft Log Compaction & State-Machine Snapshots

- **Status:** Proposed
- **Milestone:** Phase 5, after the replicated KV state machine (Phase 4); before follower-served reads and membership reconfiguration
- **Scope:** Add periodic state-machine snapshots (`raft-snapshot.bin`) with prefix compaction of the Raft log, restore from snapshot + tail on restart, and an InstallSnapshot RPC so a lagging follower catches up without replaying the full history. No protocol bump (a new RPC is additive; existing DTOs are untouched), no DTO change to existing messages, no follower-served reads, no membership changes.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `RaftLog` ctor | `src/main/java/com/minigoogle/cluster/RaftLog.java` line 49 | Replays the WAL as a log whose entries start at index 1; the javadoc (lines 14-15) pins `entries.get(index) == index`. **No log base index, so a compacted prefix cannot be represented and absolute indexes cannot survive prefix deletion.** |
| `RaftLog.truncateFrom` | line 135 | Drops entries at `index` or beyond, retaining the prefix `[1, index-1]`, and rewrites the WAL by clear + re-append. **Tail-only; the prefix-drop compaction needs is absent.** |
| `RaftLog.entriesFrom` / `termAt` / `payloadAt` | lines 163 / 87 / 98 | Position-based index arithmetic around a hard-wired index-0 sentinel; every accessor must learn a base offset. |
| `WriteAheadLog` | `src/main/java/com/minigoogle/storage/wal/WriteAheadLog.java` | `append` (fsync) / `readAll` / `clear` only; no truncate-to-index, but `RaftLog` already achieves tail truncation by clear + re-append. The same pattern covers prefix compaction. |
| `RaftConsensus.applyCommitted` | `src/main/java/com/minigoogle/cluster/RaftConsensus.java` line 711 | Runs under the consensus lock after every commit advance: applies `[lastApplied+1 .. newCommitIndex]`, then persists the watermark. **The natural, single hook for a periodic snapshot trigger.** |
| `RaftConsensus.restoreAppliedState` | line 734 | Rebuilds the state machine by re-applying `[1 .. lastApplied]` **from the whole log — O(n) restart that grows with the log.** |
| `RaftConsensus.sendAppendEntries` | line 784 | Starts at `nextIndex - 1` and sends `entriesFrom(next, ...)`. **No fallback when `nextIndex` falls below the leader's first retained index** — a leader that compacted could not advance a lagging follower. |
| `StateMachine` | `src/main/java/com/minigoogle/cluster/StateMachine.java` | Only `apply(LogEntry)`. No way to capture or replace the state machine's state. Single implementer in the codebase: `ReplicatedKeyValueStore`. |
| `ReplicatedKeyValueStore` | `src/main/java/com/minigoogle/cluster/state/ReplicatedKeyValueStore.java` line 31 | Applies decoded `KvCommand`s to a `ConcurrentHashMap`; **no snapshot/restore** of the map. |
| `RaftAppliedStore` | `src/main/java/com/minigoogle/storage/metadata/RaftAppliedStore.java` | `RAPP` v1, temp + fsync + atomic rename, missing file → 0, corrupt file fails fast, `inMemory()` no-op. **The exact atomic-store pattern a snapshot store should mirror.** |
| `RaftTransport` | `src/main/java/com/minigoogle/cluster/transport/RaftTransport.java` line 10 | Only `sendRequestVote` + `sendAppendEntries`. **No InstallSnapshot RPC**, so a compacted leader has no way to bring a lagging follower forward. |
| `RaftHandler` | `src/main/java/com/minigoogle/cluster/transport/http/RaftHandler.java` lines 38 / 57 | Handles only `/request-vote` and `/append-entries`; a third path follows the same dispatch. |
| `HttpRaftTransport` | `src/main/java/com/minigoogle/cluster/transport/http/HttpRaftTransport.java` lines 51-58 | Maps each Raft RPC to a POST path via a shared `sendPost`; a third RPC mirrors the pattern (plus a per-type `stampMetadata` overload). |
| `ClusterNode` | `src/main/java/com/minigoogle/cluster/ClusterNode.java` lines 260-261 | Registers only `request-vote` + `append-entries` protected contexts. |
| `StorageLayout` | `src/main/java/com/minigoogle/storage/filesystem/StorageLayout.java` lines 44-62 | `raft-metadata.bin`, `raft-log.bin`, `raft-applied.bin`. **No snapshot path.** |
| `ClusterProtocol` | `src/main/java/com/minigoogle/cluster/transport/ClusterProtocol.java` line 20 | `PROTOCOL_VERSION = 1`; new message types are additive and keep v1, matching the Phase 4 precedent. |
| `AppendEntriesRequest` | `src/main/java/com/minigoogle/cluster/transport/dto/AppendEntriesRequest.java` | Record DTO pattern (`protocolVersion, requestId, correlationId, sourceNodeId, timestamp, ...`) to mirror for `InstallSnapshotRequest/Response`. |
| `ARCHITECTURE.md` Ch14 | line 15394 | "Snapshotting/compaction of the replicated log, follower-served reads, and membership reconfiguration" — listed as not yet implemented. |
| Test fakes | `RaftConsensusApplyTest` line 285, `RaftConsensusReplicationTest` line 273, `RaftConsensusReadTest` line 174, `RaftConsensusClusterTest` line 145, `RaftConsensusPersistenceTest` line 133 | Each defines its own `RaftTransport` implementation; all five must learn `sendInstallSnapshot`. |
| `RaftLogTest` | `src/test/java/com/minigoogle/cluster/RaftLogTest.java` | Pins the 1-based invariants that re-basing must preserve when no compaction has happened. |

**Conclusion: Phase 4 made committed entries drive a state machine, but the log is never reclaimed. Every node stores the full history forever, restart cost is O(n), and there is no primitive (state-machine snapshot, log base index, or InstallSnapshot RPC) for reclaiming it. Phase 5 adds the missing reclamation machinery.**

---

## 2. Current Implementation

The log lifecycle, as it exists today:

```
Leader append (RaftConsensus.appendEntry):
  index = log.append(currentTerm, payload)          // fsynced to raft-log.bin
  sendHeartbeats() -> AppendEntries to followers

Commit (advanceCommit / receiveAppendEntries):
  commitIndex advances -> applyCommitted:
    for i in [lastApplied+1 .. commitIndex]:
      stateMachine.apply(logEntryAt(i))             // KV map update
    lastApplied = commitIndex
    appliedStore.persist(lastApplied)               // raft-applied.bin

Restart (RaftConsensus ctor -> restoreAppliedState):
  RaftLog(wal).replay()                             // whole WAL, 1-based
  lastApplied = appliedStore.load()
  for i in [1 .. lastApplied]:
    stateMachine.apply(logEntryAt(i))               // O(n) replay of EVERYTHING
```

Observations:

- **Nothing is ever deleted.** `raft-log.bin` grows without bound; each restart replays the entire log into the KV map even though only the committed prefix is ever applied.
- The KV map is the whole replicated state, so a snapshot of the applied state is small and self-contained: the map plus the last applied index/term.
- A follower is always catchable today only because the leader retains every entry from index 1. The moment the leader drops a prefix, `sendAppendEntries` has nothing to send for `nextIndex` below the first retained index, and no other RPC exists.

---

## 3. Weaknesses

1. **Unbounded log growth.** `raft-log.bin` grows forever; disk usage is O(total writes), and startup replays O(total writes) per node.
2. **O(n) restart.** `restoreAppliedState` re-applies `[1 .. lastApplied]` from the full log; restart time scales with history, not with state size.
3. **No state-machine snapshot primitive.** `StateMachine` cannot serialize or replace its state, and `ReplicatedKeyValueStore` has no `snapshot`/`restore`.
4. **No log base index.** `RaftLog` hard-codes `entries.get(index) == index`. Compaction needs an offset so absolute indexes survive prefix deletion.
5. **No InstallSnapshot RPC.** With compaction in place, a lagging or rejoining follower whose `nextIndex` falls below the leader's first retained index would stall forever.
6. **No durable home for applied state.** Nothing persists the KV map, so the log (and only the log) can rebuild it.

---

## 4. Alternative Designs

### A. Leader-driven periodic snapshots + prefix compaction + InstallSnapshot RPC (recommended)

`RaftConsensus` triggers a snapshot every `snapshotInterval` applied entries: capture the KV map + `lastIncludedIndex`/`lastIncludedTerm` into a durable `raft-snapshot.bin`, then compact the log prefix (a re-based `RaftLog` keeps absolute indexes). Restart restores the snapshot and replays only the tail. A leader whose follower's `nextIndex` falls at or below its first retained index sends an InstallSnapshot RPC carrying the snapshot.

Pros: bounds disk and restart cost; standard Raft (paper §7); reuses the Phase 2 atomic-store pattern; the snapshot doubles as the log base so absolute indexes survive restarts. Cons: snapshot + WAL rewrite run under the consensus lock (cheap for the in-memory KV); a new RPC and a new file.

### B. Keep the whole leader log; catch up lagging followers by full AppendEntries replay

Snapshot the KV on the leader and on restart, but never compact the leader's log; followers catch up by replaying everything (possibly from a snapshot they receive).

Pros: no `nextIndex`/`firstIndex` interplay; simpler. Cons: **leader disk still grows without bound**, which is the very problem this milestone exists to solve; followers replay O(n) over the wire each time they fall behind. Rejected on the primary goal (bounded storage).

### C. Operator-driven cluster-level snapshot/restore tooling outside consensus

A separate admin utility dumps/loads the KV outside the Raft path.

Pros: no consensus changes. Cons: operates on a layer that must never mutate replicated state out-of-band (divergent state machines, wrong layer); does not bound the log automatically; requires external orchestration. Rejected on correctness and fit.

### D. Rewrite the log in place, dropping applied entries, with no separate snapshot file

On restart, rely on the rewritten WAL plus the applied watermark.

Pros: one file. Cons: **incorrect** — after dropping `[1 .. lastApplied]` the WAL cannot rebuild the state machine (the entries that produced the applied state are gone), so a restart loses the state. The snapshot file is precisely what makes prefix deletion recoverable. Rejected on correctness.

### E. Take snapshots on a dedicated thread

Decouple snapshot capture + WAL rewrite from the consensus lock so a large state machine never stalls heartbeats.

Pros: production-shaped. Cons: the snapshot must be internally consistent with `commitIndex`, requiring a freeze/copy hand-off; the KV snapshot here is a map serialization, and the WAL rewrite is bounded by the interval. Matches the Phase 4 rationale that deferred async apply to Phase 9+. Deferred.

---

## 5. Comparison

| Criterion | A. Snapshot + compact + InstallSnapshot | B. Keep full leader log | C. Out-of-band tooling | D. In-place rewrite, no file | E. Async snapshot |
|---|---|---|---|---|---|
| Bounds leader disk | **yes** | no | no | yes | yes |
| Bounds restart cost | **yes** (state + tail) | no | no | **broken** | yes |
| Rebuilds state after prefix drop | yes (snapshot file) | n/a | n/a | **no** | yes |
| Catches up lagging followers | **yes (InstallSnapshot)** | yes (slow replay) | no | n/a | yes |
| Reuses atomic-store pattern | yes | n/a | n/a | n/a | yes |
| Raft RPC processing impact | small (one new RPC) | none | none | n/a | none (extra thread) |
| Correct under crash mid-compaction | **yes** (snapshot covers committed prefix) | yes | no | no | yes |
| Code churn / risk | medium / low | low / low | medium / medium | low / **wrong** | medium / medium |

---

## 6. Recommended Design (Alternative A)

### 6.1 Snapshot record and store

`com.minigoogle.cluster.RaftSnapshot` — `record RaftSnapshot(int lastIncludedIndex, int lastIncludedTerm, byte[] data)`.

`com.minigoogle.storage.metadata.RaftSnapshotStore` — mirrors `RaftAppliedStore` exactly: magic `"RSNP"` (`0x52534E50`), version 1, temp + fsync + atomic rename, `inMemory()` no-op, missing/empty file → `null`, corrupt file fails fast.

```
File: [magic 4][version 1][lastIncludedIndex 4][lastIncludedTerm 4][dataLength 4][data]
```

`StorageLayout.getRaftSnapshotPath()` → `raft-snapshot.bin`.

**Why the snapshot is not deletable once compaction has happened:** the snapshot carries the log base. A compacted `raft-log.bin` tail is meaningless without `lastIncludedIndex`; deleting `raft-snapshot.bin` after compaction would mis-base the log on the next restart. Operators reclaim snapshot space by deleting `raft-snapshot.bin` **and** `raft-log.bin` together and letting the node rejoin from scratch (see §9).

### 6.2 State-machine snapshotting

`StateMachine` gains default methods so the interface stays additive:

```java
default boolean isSnapshotable() { return false; }
default byte[] snapshot() { throw new UnsupportedOperationException("State machine does not support snapshots"); }
default void restore(byte[] snapshot) { throw new UnsupportedOperationException("State machine does not support snapshots"); }
```

`ReplicatedKeyValueStore` overrides all three. Snapshot encoding (big-endian, mirroring `KvCommand`):

```
[4-byte count][ (2-byte key length)(key UTF-8)(4-byte value length)(value) ]*
```

`restore` decodes strictly (malformed data fails fast), replaces the map atomically, and resets the applied-index counter to 0 (the watermark the consensus restores governs `lastApplied`; the counter is only a waiter-completion hint). The default `isSnapshotable() == false` is the guard that prevents a non-snapshot-aware state machine from silently receiving an empty snapshot on a follower.

### 6.3 `RaftLog` re-basing

New fields `private int baseIndex;` and `private int baseTerm;` (default 0). The existing `RaftLog(wal)` ctor delegates to a new `RaftLog(wal, int baseIndex, int baseTerm)` so the index-0 sentinel becomes the entry `(baseIndex, baseTerm)`.

- `firstIndex() = baseIndex + 1` — the first retained 1-based absolute index.
- `lastIndex() = baseIndex + (entries.size() - 1)`.
- `termAt(index)`: `index == baseIndex` → `baseTerm`; `index < baseIndex` → `0` (below the base, so a stale AppendEntries fails the consistency check and triggers InstallSnapshot); otherwise position-based.
- `payloadAt(index)`: `null` at or below `baseIndex`.
- `append(term, payload)`: new index `= baseIndex + entries.size()`.
- `entriesFrom(fromIndex, max)`: clamps `start` to `firstIndex()`.
- `truncateFrom(index)`: re-expressed in absolute space — the guard becomes `index <= firstIndex() || index > lastIndex()` (so an index at `firstIndex()` drops the whole tail, which post-compaction is legal), and it drops absolute indexes `>= index`; WAL rewrite keeps the retained tail. Needed for correct divergence handling on a re-based log.
- `compact(int snapshotIndex, int snapshotTerm)`: prefix-drop primitive. Sets `baseIndex/baseTerm = snapshotIndex/snapshotTerm`, retains the absolute tail `[snapshotIndex+1 .. lastIndex()]`, rewrites the WAL with the retained tail. No-op when `snapshotIndex <= baseIndex`.
- `resetTo(int snapshotIndex, int snapshotTerm)`: full replace for an installed snapshot that does not match the local log — drops every entry, clears the WAL, sets the base.

**Crash safety:** the rewrite is clear + re-append (the pattern `truncateFrom` already uses), so a crash mid-rewrite loses at most the tail beyond the snapshot. Because `lastApplied == commitIndex` in-lock, everything ≤ `lastIncludedIndex` is committed and covered by the snapshot; the lost tail is uncommitted. No committed entry is ever lost.

### 6.4 `RaftConsensus`

- New 12-arg ctor `(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log, stateMachine, appliedStore, snapshotStore, snapshotInterval)`; the existing 10-arg ctor delegates with `null, 0`. New fields `snapshotStore`, `snapshotInterval`, and `lastSnapshotIndex` (restored from the loaded snapshot at startup).
- **Trigger:** `maybeSnapshot()` runs at the end of `applyCommitted`, after the watermark is persisted. It fires when `stateMachine != null && stateMachine.isSnapshotable() && snapshotStore != null && lastApplied - lastSnapshotIndex >= snapshotInterval`. It saves `new RaftSnapshot(lastApplied, log.termAt(lastApplied), stateMachine.snapshot())` **first**, then sets `lastSnapshotIndex = lastApplied` and `log.compact(lastApplied, term)`.
- **Restart:** `restoreAppliedState()` loads the snapshot first; if present, `log.compact(lastIncludedIndex, lastIncludedTerm)` (so the replayed WAL tail is re-based correctly) and `stateMachine.restore(data)`; then `lastApplied = max(appliedStore.load(), lastIncludedIndex)` and, when a state machine exists, applies the tail `[lastIncludedIndex+1 .. lastApplied]`. This is what makes restart O(state + tail) instead of O(history).
- **Leader fallback:** `sendAppendEntries(peer)` short-circuits to `sendInstallSnapshot(peer)` when `nextIndex <= log.firstIndex()`. `sendInstallSnapshot` loads the leader's snapshot and sends an `InstallSnapshotRequest`; `onInstallSnapshotResponse` steps down on a higher term, sets `matchIndex[peer] = lastIncludedIndex` and `nextIndex[peer] = lastIncludedIndex + 1` on success, and calls `advanceCommit()` (the snapshot may carry the follower past `commitIndex`).
- **Follower install:** `receiveInstallSnapshot(leaderId, term, lastIncludedIndex, lastIncludedTerm, data)`:
  - `term < currentTerm` → reject.
  - Higher term → update `currentTerm`, persist metadata, fail pending barriers, step to follower.
  - `matches = log.lastIndex() >= lastIncludedIndex && log.termAt(lastIncludedIndex) == lastIncludedTerm`; `matches` → `log.compact(...)`, else → `log.resetTo(...)`.
  - Save the snapshot, `stateMachine.restore(data)`, `lastApplied = max(lastApplied, N)`, `commitIndex = max(commitIndex, N)`, persist the watermark, reschedule the election timeout, reply success.

### 6.5 Transport and wiring

- DTOs `InstallSnapshotRequest(int protocolVersion, String requestId, String correlationId, String sourceNodeId, long timestamp, String leaderId, int term, int lastIncludedIndex, int lastIncludedTerm, byte[] data)` and `InstallSnapshotResponse(int protocolVersion, String requestId, String correlationId, String sourceNodeId, long timestamp, int term, boolean success)`, both `implements ClusterMessage`, mirroring `AppendEntriesRequest`.
- `RaftTransport.sendInstallSnapshot(String, InstallSnapshotRequest)`.
- `HttpRaftTransport` maps it to `POST /cluster/v1/raft/install-snapshot` with a `stampMetadata` overload; `RaftHandler` dispatches the new path (validate + `AuthFilter` + respond, identical to the other two).
- `ClusterNode`: new constructor overload threading `snapshotStore` and `snapshotInterval` into `RaftConsensus`, and a third protected context registration for `/cluster/v1/raft/install-snapshot`. The storage-directory convenience path builds a real `RaftSnapshotStore`.
- `ClusterProtocol` stays v1: only new message types are added; existing layouts are untouched.

### 6.6 Trigger policy

Interval in entries since the last snapshot (default `10_000`; tests use small values like 4–5 for determinism). Size-based triggers need byte accounting of the log; time-based triggers are nondeterministic for tests. Both rejected for this milestone.

---

## 7. Migration Plan

1. `RaftSnapshot`, `RaftSnapshotStore`, `StorageLayout.getRaftSnapshotPath()`, `StateMachine` defaults, `ReplicatedKeyValueStore.snapshot()`/`restore()`; unit tests for the store (round-trip, missing file → null, corrupt fails fast, in-memory no-op) and the KV snapshot round-trip (commit `m1`). Pure additive; nothing wired.
2. `RaftLog` re-basing: base fields, new ctor, `firstIndex`, `compact`, `resetTo`, absolute-space `truncateFrom`; new `RaftLogCompactionTest` (absolute indexes survive prefix deletion, WAL persistence across a restart with a base, whole-tail truncation post-compaction). Existing `RaftLogTest` stays green (no compaction ⇒ byte-for-byte old behavior) (commit `m2`).
3. `RaftConsensus`: snapshot trigger + restore + `sendInstallSnapshot`/`receiveInstallSnapshot` + `nextIndex <= firstIndex` fallback; DTOs; transport/handler/node wiring; update the five `RaftTransport` fakes; new `RaftConsensusSnapshotTest` (fake transport + real KV + temp-dir stores: snapshot persisted and log compacted past the interval; restart rebuilds from snapshot + tail; a crash mid-compaction loses nothing committed) and `RaftConsensusInstallSnapshotTest` (lagging follower receives a snapshot, log replaced, state restored, tail applies after; stale/lower-term snapshot rejected; higher term steps the receiver down) (commit `m3`).
4. `ClusterNodeSnapshotIntegrationTest` (real HTTP 3-node, stable-leader wait from Phase 4): commit past a small interval on real storage, restart the leader on its directory and read back committed state; stop a follower, keep the rest writing past the interval, restart it and assert it converges to the full KV (via InstallSnapshot when its `nextIndex` falls below the leader's `firstIndex`). Update `ARCHITECTURE.md` Ch14 §7/§8/§9 implementation status and the storage-layout tree (`raft-snapshot.bin`), run the full suite, produce the impact summary, commit (commit `m4`), push to both repos.

---

## 8. Acceptance Criteria

- After more than `snapshotInterval` entries commit on real storage, `raft-snapshot.bin` exists with `lastIncludedIndex >= snapshotInterval` and the leader's `raft-log.bin` is compacted (`firstIndex() > 1`).
- A node restarted on its storage directory rebuilds the KV from snapshot + tail (not by replaying the whole log) and serves the pre-restart committed state.
- A lagging/rejoining follower converges to the full committed KV — via InstallSnapshot when its `nextIndex` falls at or below the leader's `firstIndex` — and applies subsequent writes.
- A snapshot carrying a term lower than the receiver's term is rejected; a higher-term InstallSnapshot steps the receiver down to follower and persists the term.
- A crash at any point during snapshotting or WAL rewrite loses no committed entry (snapshot + watermark + metadata survive; the lost tail is uncommitted).
- No data-loss path via a non-snapshot-aware state machine: `isSnapshotable() == false` never snapshots and never restores.
- Protocol stays v1; existing DTOs and the `RMET`/`RAPP` v1 files are untouched; existing 452 tests stay green; all new tests green; `.\gradlew.bat build -x test` succeeds.

---

## 9. Rollback

- `m1` and `m2` are additive and self-contained (new types, re-based `RaftLog` still byte-for-byte identical when nothing compacts) — safe to keep or revert alone.
- `m3`–`m4` revert in reverse order (integration test → node/handler/transport wiring → consensus snapshot logic → `RaftLog` re-basing → snapshot/state-machine types).
- Existing wire messages are unchanged, so a mixed rollout interoperates as long as **snapshotting is disabled** (`snapshotInterval` left at its default disabled wiring / no `snapshotStore`) until every node in the cluster has upgraded: a node that compacted and then sends InstallSnapshot to an un-upgraded peer gets a 404 back, and that peer would stall until the leader stopped compacting. The recommended downgrade is: run with snapshots disabled on all nodes, then roll back the binaries.
- `raft-snapshot.bin` is a **rebuildable cache only while `raft-log.bin` still contains the pre-compaction prefix**. Once the log is compacted, the snapshot carries the log base and must not be deleted alone (see §6.1). To reclaim snapshot space safely, delete both files and let the node rejoin from scratch.
- `raft-applied.bin` remains deletable as before: the snapshot + deterministic tail re-apply reproduce the same state.

---

## 10. Estimated Impact

- **Files changed:** 4 new main (`RaftSnapshot`, `RaftSnapshotStore`, `InstallSnapshotRequest`, `InstallSnapshotResponse`); 9 edited main (`RaftLog`, `RaftConsensus`, `StateMachine`, `ReplicatedKeyValueStore`, `StorageLayout`, `RaftTransport`, `HttpRaftTransport`, `RaftHandler`, `ClusterNode`); ~5 new test files (`RaftSnapshotStoreTest`, `RaftLogCompactionTest`, `RaftConsensusSnapshotTest`, `RaftConsensusInstallSnapshotTest`, `ClusterNodeSnapshotIntegrationTest`); edits to the five existing `RaftTransport` fakes and `HttpRaftTransportTest`.
- **Lines:** ≈ 900–1100 added; ≈ 0 deleted.
- **LoC delta:** +1,000ish on the ~16,000-line codebase ≈ +6%.
- **Testing cost:** +40–50 tests, +10–15 min suite time, total ≈ 490–500 tests.
- **Dependencies:** none new; reuses `ByteBuffer`, the atomic-store pattern, and the existing `sendPost` transport path.
- **Behavior deltas:** the Raft log is now bounded (compacted prefix + in-flight tail); restart is O(state + tail) instead of O(history); lagging followers catch up via InstallSnapshot; restart rebuild from a compacted log no longer requires a full log. No change to the client API (`put`/`get`/`delete` signatures are unchanged), search fan-out, or the demo REST API.
- **Docs:** `ARCHITECTURE.md` Ch14 §7/§8/§9 implementation status (snapshots/compaction implemented) + storage-layout tree (`raft-snapshot.bin`); the "Snapshotting/compaction ... not yet implemented" line is reduced to "follower-served reads and membership reconfiguration."
