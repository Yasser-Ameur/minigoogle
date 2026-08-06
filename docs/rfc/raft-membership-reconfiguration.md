# RFC: Raft Membership Reconfiguration (Dynamic Add / Remove Server)

- **Status:** Implemented
- **Milestone:** Phase 6, after snapshot-driven log compaction; before follower-served reads and distributed transactions
- **Scope:** Add/remove cluster members through the replicated log; make the Raft quorum follow a durable committed configuration instead of gossip liveness; one-server-at-a-time changes (no joint consensus); persist the committed config, carry it in snapshots, and expose `addNode`/`removeNode` on `ClusterNode`. Protocol stays v1; no new endpoint (the existing InstallSnapshot RPC gains a config field); gossip keeps its role for discovery, failure detection, and the consistent-hash ring.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `RaftConsensus.peerSupplier` | `src/main/java/com/minigoogle/cluster/RaftConsensus.java` line 75 | The peer set is a `Supplier<List<String>>`; every RPC fan-out (`requestVotesFromPeers` line 631, `sendHeartbeats` line 503, `sendAppendEntries` line 887, `prepareReadBarrier` line 535) and the commit logic iterate the supplier output. |
| `RaftConsensus.peers()` | line 1070 | Returns `peerSupplier.get()` minus self. **Who Raft contacts and who Raft counts are the same thing.** |
| `RaftConsensus.majorityThreshold()` | line 1083 | `known.size() / 2 + 1` over `{self} ∪ peerSupplier.get()` **computed fresh on every call**. The quorum is a live snapshot of whatever the supplier returns at that instant. |
| `ClusterNode` raft wiring | `src/main/java/com/minigoogle/cluster/ClusterNode.java` line 312 | `peerSupplier = gossip::getLiveNodes` (GossipProtocol line 209: nodes currently `ALIVE`). **Raft's quorum is therefore gossip liveness.** |
| `GossipProtocol` | `src/main/java/com/minigoogle/cluster/GossipProtocol.java` lines 163–217 | Failure detection flips a node ALIVE→SUSPECT→DEAD; `getLiveNodes()` shrinks as nodes are suspected. Nothing distinguishes "temporarily unreachable" from "removed by an operator." |
| `RaftConsensus.appendEntry` | line 487 | The only write path. Payload is opaque `byte[]`; the KV layer frames `KvCommand` inside (op `0x01` PUT, `0x02` DELETE — `state/KvCommand.java` lines 21–22). **No op type for a config change exists.** |
| `RaftConsensus.applyCommitted` | line 761 | Drains `[lastApplied+1 .. commitIndex]` into the state machine. There is no interception point for entries that must update consensus state (a config) rather than KV state. |
| `RaftConsensus` becomeLeader / vote paths | lines 468, 439–460 | Initialize `nextIndex`/`matchIndex` from `peers()`, grant/win elections on `majorityThreshold()` — all supplier-driven. |
| `RaftSnapshot` / `RaftSnapshotStore` | `RaftSnapshot.java` line 12; `RaftSnapshotStore.java` lines 28–35 | `RSNP` file v1: magic + version + `lastIncludedIndex` + `lastIncludedTerm` + data. **No config is carried in a snapshot**, so a new node that catches up after its ADD entry was compacted would never learn the membership it joined. |
| `InstallSnapshotRequest` | `src/main/java/com/minigoogle/cluster/transport/dto/InstallSnapshotRequest.java` | Carries term, lastIncludedIndex, lastIncludedTerm, data — no config. |
| `StorageLayout` | `src/main/java/com/minigoogle/storage/filesystem/StorageLayout.java` | Has `getRaftMetadataPath()`, `getRaftLogPath()`, `getRaftAppliedPath()`, `getRaftSnapshotPath()`. **No config path.** |
| `RaftMetadataStore`/`RaftAppliedStore`/`RaftSnapshotStore` | `src/main/java/com/minigoogle/storage/metadata/` | The established atomic-store pattern (magic + version + temp + fsync + atomic rename, `inMemory()` no-op) is the precedent a config store would mirror. |
| `ConsistentHashRing` + `RingMembershipListener` | `ConsistentHashRing.java`; `src/main/java/com/minigoogle/cluster/RingMembershipListener.java` | Gossip join/leave drives data placement. **Independent of Raft quorum; this phase does not change it.** |
| Tests | `src/test/java/com/minigoogle/cluster/` | All consensus tests construct a fixed `peerSupplier`; the KV integration test (`ClusterNodeKvIntegrationTest` line 312 wiring) seeds gossip and waits for live-set convergence. No test asserts a quorum that outlives a gossip DEAD transition. |

**Conclusion: membership is entirely soft.** A node is a "member" while gossip says it is alive; the quorum is re-derived from liveness on every call. If node-3 is partitioned from node-1/node-2, gossip marks it DEAD, the majority on 1/2 shrinks to 2 — and node-3 still believes it is in a 3-node cluster with majority 2. Two disjoint quorums (1+2 and 2+3) can both be satisfied, so **split-brain is possible today**. There is also no operator-visible way to add or remove a server.

---

## 2. Current Implementation

The write/read/election path, as it exists today:

```
Leader append (appendEntry):
  state != LEADER        -> throw IllegalStateException
  index = log.append(currentTerm, payload)     // payload = KV frame [0x01/0x02]...
  sendHeartbeats()       // replicate to peers() = gossip live nodes

Commit (advanceCommit):
  threshold = majorityThreshold()              // fresh from peerSupplier each call
  for n in (lastIndex .. commitIndex]: if term(n)==currentTerm && countMatches(n)>=threshold
      commitIndex = n; applyCommitted(n)       // drain into KV state machine only

Follower (receiveAppendEntries):
  log-consistency check, append/truncate, commitIndex = min(leaderCommit, lastIndex)

Election:
  majorityThreshold() governs win; peers() = supplier output = gossip live nodes
```

Quorum arithmetic, today:

```
majorityThreshold() = |{self} ∪ peerSupplier.get()| / 2 + 1
peers()             = peerSupplier.get() - {self}
```

Both are recomputed per call, so the quorum tracks gossip liveness in real time.

---

## 3. Weaknesses

1. **Quorum is liveness, not membership.** Raft's safety rests on quorum intersection: any two quorums must share a member. A quorum that shrinks when gossip marks a server DEAD breaks that invariant — the classic "shrunken quorum" split-brain (a 3-node cluster where one node is unreachable can, today, elect two leaders).
2. **No operator-visible add/remove.** The only way to "remove" a node is to let it time out; the only way to "add" one is to start a process that gossips. Neither is a committed, durable decision, so a removed node that later reappears re-enters the cluster as a member without anyone consenting.
3. **No durable config.** Nothing records which servers the cluster is *supposed* to be. A restarted node cannot distinguish "my cluster is 3 nodes and one is down" from "my cluster is 2 nodes." Bootstrap is re-guessed from gossip every start.
4. **Config changes would be lost by compaction.** Even if config changes were logged, the Phase 5 snapshot compacts away log prefix — a new node catching up after its ADD entry was compacted would never see it. Config must travel with the snapshot.
5. **No interception in the apply path.** `applyCommitted` feeds every entry to the KV state machine. A config entry is consensus state, not KV state; it must be consumed by the consensus layer and never forwarded to the KV.

---

## 4. Alternative Designs

### A. Committed config in the log + one-server-at-a-time transitions + config in snapshots (recommended)

Config changes are ordinary log entries (`[0x03][ADD/REMOVE][node-id]`). The committed config — the set of member IDs — is derived by the consensus layer as it applies committed entries, persisted to a small atomic store (`raft-config.bin`), and included in every snapshot. Quorum is computed over the committed config (and, during a pending change, the stricter of old/new majorities). Only one change may be pending at a time; the configs differ by one server, so their quorums always intersect — no joint consensus needed. A leader that commits its own removal steps down.

Pros: Raft-correct (quorum intersection is guaranteed by construction); operator-facing add/remove; durable across restart; composes with the Phase 5 snapshot/install machinery (config rides in the snapshot); one small file. Cons: one new DTO field (`InstallSnapshotRequest.config`) and a `RSNP` v1→v2 bump; the bootstrap config must be established explicitly before the first change.

### B. Joint consensus (Raft paper §6)

Formal old+new transitional configurations with two-phase votes (`C_{old,new}` requiring a majority of both, then `C_new`).

Pros: canonical; allows arbitrary concurrent changes. Cons: significantly more state (two vote tallies, three configs alive at once) and far more test surface. Rejected: the codebase only ever needs one-server-at-a-time changes; the one-at-a-time rule gives the identical safety property (quorum intersection) with a fraction of the machinery.

### C. Config as a side table, gossip continues to drive quorum

Persist membership separately but keep `majorityThreshold()` derived from gossip liveness.

Pros: minimal consensus change. Cons: fixes persistence but not the safety bug — a partitioned majority can still shrink below what other nodes assume. Rejected on correctness: the entire point is to decouple quorum from liveness.

### D. Etcd-style in-log ConfChange with a joint-transition window

Reuse etcd's `ConfChange` mechanics wholesale, including allowing arbitrary changes queued back-to-back.

Pros: battle-tested. Cons: etcd's model assumes its own replication loop, `Message` enum, and HardState; transplanting it would rewrite `RaftConsensus` around config transitions rather than adding a clean, additive mechanism on top of the existing log apply path. Rejected: scope.

---

## 5. Comparison

| Criterion | A. Committed config + one-at-a-time | B. Joint consensus | C. Side table, liveness quorum | D. Etcd-style |
|---|---|---|---|---|
| Quorum independent of gossip liveness | yes | yes | **no** | yes |
| Quorum intersection guaranteed | yes | yes | **no** | yes |
| Operator add/remove | yes | yes | partial | yes |
| Durable across restart | yes | yes | yes | yes |
| Survives log compaction (config in snapshot) | yes | yes | yes | yes |
| New wire surface | 1 field on InstallSnapshotRequest | vote/append changes | none | large |
| Consensus churn / risk | additive / medium | high / high | low / **unsafe** | high / high |
| Test surface | small | large | small | large |

---

## 6. Recommended Design (Alternative A)

### 6.1 Config-change framing (inside the existing opaque payload)

`RaftLog.toFrame` (wire framing `[4-byte term][payload]`) is unchanged; the **payload** gets a third op discriminator. Protocol stays v1. `KvCommand` keeps `0x01`/`0x02` untouched.

```
CONFIG = [0x03][1-byte op: ADD=0x01 / REMOVE=0x02][2-byte node-id length][node-id UTF-8]
```

Node IDs ≤ 65535 bytes. Encoded/decoded with `ByteBuffer` (big-endian); strict decode fails fast on malformed frames.

### 6.2 New types

`com.minigoogle.cluster.ClusterConfiguration` — immutable ordered set of member node IDs. API: `Set<String> members()`, `int size()`, `int majority()` (= `size()/2 + 1`), `boolean contains(String)`, `ClusterConfiguration plus(String)`, `ClusterConfiguration minus(String)`, `boolean isEmpty()`. Equality over the member set (order-insensitive).

`com.minigoogle.cluster.ConfigChange` — `enum ChangeType { ADD, REMOVE }`; constants `OP_ADD = 0x01`, `OP_REMOVE = 0x02`; `encode(ConfigChange)`, `decode(byte[])`, `boolean isConfigFrame(byte[])` (op byte `0x03`), accessors `type()` / `nodeId()`.

`com.minigoogle.storage.metadata.RaftConfigurationStore` — mirrors `RaftMetadataStore`/`RaftAppliedStore` exactly: magic `"RCON"`, version 1, temp+fsync+atomic-rename, `inMemory()` no-op, missing file → empty config, corrupt file fails fast. `StorageLayout.getRaftConfigPath()` → `raft-config.bin`. Stores the member list of the last committed config.

### 6.3 `RaftSnapshot` / `RaftSnapshotStore` — carry the config

`RaftSnapshot` becomes `(int lastIncludedIndex, int lastIncludedTerm, byte[] data, ClusterConfiguration config)` (nullable → empty). `RaftSnapshotStore` writes `RSNP` **v2**: v1 fields plus `configCount (4 bytes)` + `configNodeIds (length-prefixed strings)`. The reader accepts v2 and also reads v1 files (config = empty), so an operator's existing `raft-snapshot.bin` from this session's Phase 5 release stays loadable. A snapshot with no config (or whose config is empty) simply contributes nothing to membership.

`InstallSnapshotRequest` gains a `List<String> config` field (additive; old request bodies without the field deserialize as empty — Jackson tolerant to a missing list under the project's `ObjectMapper` config used by `HttpRaftTransport`/`RaftHandler`). Protocol stays v1. `AppendEntriesRequest` gains the same `List<String> config` field (additive, same tolerance), so a bootstrapping node that has never seen a config entry adopts the leader's committed config during ordinary log replication — not only via snapshot.

### 6.4 `RaftConsensus` — config state and config-driven quorum

New fields:

```java
private volatile ClusterConfiguration committedConfig; // restored from store; may be empty
private volatile ConfigChange pendingChange;            // non-null while a change is uncommitted
private volatile boolean configEstablished;             // true once a config exists
```

`initializeConfig(List<String> members)` — establishes the bootstrap config (self + seeds) and persists it. Allowed only when no config has ever been committed (guards against clobbering a live cluster). Callers that never call it keep today's bootstrap mode byte-for-byte.

`appendConfigChange(ConfigChange change)` — leader-only; throws `IllegalStateException` on a non-leader and rejects a second change while `pendingChange != null` (one-server-at-a-time). Appends `ConfigChange.encode(change)`; the effective config for quorum from this instant is `committedConfig ± change`:

```
effectiveMajority() = committedConfig.isEmpty()
    ? (legacy majorityThreshold() from peerSupplier/clusterSize)
    : max(committedConfig.majority(), targetConfig.majority())   // equal when nothing pending
```

Replication fan-out (`peers()`) during a pending change covers `committedConfig ∪ targetConfig` (the new server must receive the change and catch up; the removed server still gets heartbeats until the change commits). This is safe because the old and new configs differ by exactly one server, so every majority of one intersects every majority of the other (quorum intersection holds without joint consensus).

As built: when a REMOVE commits, the leader sends one final AppendEntries to the removed member — before it drops from `peers()` — so the removed follower learns the new commit index and applies the removal itself; otherwise it would keep its stale config and count itself forever.

`applyCommitted(int)` — before draining to the state machine, examine each newly committed entry with `ConfigChange.isConfigFrame(payload)`:
- On a config frame: decode it, set `committedConfig = committedConfig ± change`, clear `pendingChange`, persist `committedConfig` to `RaftConfigurationStore`, and **do not forward the entry to the KV state machine**.
- If this node is the leader and the new config does not contain it, send one final `sendHeartbeats()` round carrying the new commit index (so the remaining members apply the removal immediately rather than waiting for the next leader's current-term entry) and then `stepDown(currentTerm)` (the removal has committed; the node stops serving). `stepDown` keeps the term so a re-election in the same term is possible.
- KV frames are forwarded exactly as today.

`receiveAppendEntries` / `receiveInstallSnapshot` — unchanged acceptance rules, plus: a node that commits a config removing itself (as a follower) simply stops campaigning and stops counting; a node not in the config that receives messages from a config member forwards its state but never participates in quorum. Because `majorityThreshold()` reads `committedConfig` (not gossip), a follower's commit and a leader's commit use the same number.

`maybeSnapshot()` — include `committedConfig` in the `RaftSnapshot` it writes. `receiveInstallSnapshot` — adopt `InstallSnapshotRequest.config` into `committedConfig` and persist it (a new server that catches up after its ADD entry was compacted still learns the config it joined); the installed snapshot already carries it for the durable store.

Restart rebuild — `RaftConfigurationStore.load()` restores `committedConfig` in the constructor (alongside `restoreMetadata`/`restoreAppliedState`). A node restarted on its storage directory therefore knows its cluster before gossip converges, and `majorityThreshold()` is stable from the first moment.

New accessors: `ClusterConfiguration getCommittedConfig()`, `boolean isConfigEstablished()`, `String getConfigChangeStatus()` (idle / pending-op-node).

### 6.5 `ClusterNode` — operator API

- `initializeConfig(List<String> members)` — delegates to `raft.initializeConfig`. The assembly layer calls this with the seed set (the KV integration test's `seedPeer` call sites get a matching `initializeConfig`).
- `void addNode(String nodeId)` / `void removeNode(String nodeId)` — leader-only; frame `ConfigChange`, call `raft.appendConfigChange`, then block until the config entry is applied (reuse the `awaitCommit` pattern). `NotLeaderException` (carrying the leader id) on a non-leader or commit timeout, exactly like `put`/`get`/`delete`.
- New constructor overloads thread a `RaftConfigurationStore`; the storage-directory path builds a real one from `StorageLayout.getRaftConfigPath()`, the in-memory path uses `RaftConfigurationStore.inMemory()`.

Operational note (documented, not implemented): adding `node-4` requires its URI in the `NodeDirectory` and a gossip seed before `addNode`; removing a node leaves its `NodeDirectory` entry in place but it is no longer a quorum member.

### 6.6 What does NOT change

- Gossip remains the discovery/failure-detection layer and still drives the consistent-hash ring for data placement (`RingMembershipListener`).
- `RaftLog`, WAL framing, `KvCommand`, `RaftMetadataStore` (`RMET` v1), `RaftAppliedStore` (`RAPP` v1) are untouched.
- Legacy bootstrap mode (no `initializeConfig` call) is byte-for-byte the current behavior, so every existing consensus test stays green.
- Protocol version stays v1; no new endpoint.

---

## 7. Migration Plan

1. Add `ClusterConfiguration`, `ConfigChange`, `RaftConfigurationStore`, `StorageLayout.getRaftConfigPath()`; unit tests for config arithmetic/round-trip and the store (commit `m1`). Pure additive; nothing wired.
2. Wire config state into `RaftConsensus`: `initializeConfig`, `appendConfigChange`, config interception in `applyCommitted`, effective-config quorum (`majorityThreshold`/`peers` use committed+target configs when established, else legacy), one-at-a-time rejection, leader step-down on self-removal; new `RaftConsensusConfigChangeTest` + `RaftConsensusConfigurationTest` (fake transport): a config entry applies to all nodes, quorum follows the committed config and ignores gossip-shrink, a second change while one is pending is rejected, a leader that removes itself steps down and the survivors re-elect, a removed follower stops counting, config survives restart via the store (commit `m2`).
3. Carry config in snapshots: `RaftSnapshot` v2 field, `RaftSnapshotStore` v2 read/write with v1 fallback, `InstallSnapshotRequest.config` + `HttpRaftTransport`/`RaftHandler` threading, `receiveInstallSnapshot` config adoption; add `ClusterNode.initializeConfig`/`addNode`/`removeNode`; new `ClusterNodeMembershipIntegrationTest` (real HTTP): 3-node cluster establishes config, `addNode` brings in a 4th fresh process (caught up, in every node's committed config, majority becomes 3), `removeNode` shrinks the quorum, node-3 death with config established does NOT shrink the majority (assert 2), a node restarted on its storage directory restores its config, and a leader removed by a commit steps down (commit `m3`).
4. Update `ARCHITECTURE.md` Ch14 (config-driven quorum + membership reconfiguration implemented, `raft-config.bin` in the storage tree), run the full suite, produce the impact summary, commit (commit `m4`), push to both repos.

---

## 8. Acceptance Criteria

- In a real-HTTP 3-node cluster with an established config `{1,2,3}`, killing node-3 (gossip DEAD) leaves `majorityThreshold()` at 2 on the survivors — the quorum does not shrink, and the survivors cannot form a quorum without each other.
- `leader.addNode("node-4")` returns only after the ADD commits; node-4 then appears in every node's committed config, receives replication, and the cluster majority becomes 3. `leader.removeNode("node-4")` returns only after the REMOVE commits and shrinks the majority back to 2.
- A second config change while one is pending is rejected (`IllegalStateException`), not queued.
- Removing the current leader commits and steps that node down; the remaining members elect a new leader and keep serving without a restart.
- A node restarted on its storage directory restores its committed config from `raft-config.bin` before gossip converges, and its majority is stable immediately.
- A new node added to a cluster whose log has been compacted catches up via InstallSnapshot and adopts the config it joined (the snapshot and the RPC both carry it).
- `RaftSnapshotStore` still reads `RSNP` v1 files written by the Phase 5 release (config empty), and round-trips v2.
- Protocol stays v1; `KvCommand` ops `0x01`/`0x02` unchanged; `RMET`/`RAPP` v1 files untouched; the full suite stays green (562 tests at implementation time, 0 failures); `.\gradlew.bat build -x test` succeeds.

---

## 9. Rollback

- `m1` is pure additive (new types, no call sites changed) — safe to keep or revert alone.
- `m2`–`m3` revert in reverse order (ClusterNode API → snapshot v2/config field → consensus config state → types). The wire format gains one *new* field on InstallSnapshotRequest and a *version bump* on `RSNP`; old nodes reading a v2 snapshot file fail fast (the store already raises on unknown versions), so a mixed rollout keeps v1 files readable and old nodes simply see no config (bootstrap mode). Config entries would be opaque payloads to an old node, stored and replicated but not applied — exactly like KV entries are to a pre-Phase-4 node.
- `raft-config.bin` is an operator-deletable cache like `raft-applied.bin`: deleting it returns the node to bootstrap mode for the next `initializeConfig`. The log, `RMET`, `RAPP`, and `RSNP` files are unaffected.
- The quorum behavior change is opt-in: until a caller invokes `initializeConfig` (or a config change commits), the consensus runs the exact Phase 5 logic.

---

## 10. Estimated Impact

- **Files changed:** 3 new main (`ClusterConfiguration`, `ConfigChange`, `RaftConfigurationStore`); 6 edited main (`RaftConsensus`, `ClusterNode`, `RaftSnapshot`, `RaftSnapshotStore`, `InstallSnapshotRequest`, `StorageLayout`); ~3 new test files plus the existing fakes touched where constructor signatures grow.
- **Lines:** ≈ 900–1,200 added; ≈ 20 deleted (snapshot v2 read path replaces the v1 body).
- **LoC delta:** +1,000ish on the ~17,000-line codebase ≈ +6%.
- **Testing cost:** 562 tests total, all green.
- **Dependencies:** none new; reuses `ByteBuffer`, the atomic-store pattern, and the existing transport/handler wiring.
- **Behavior deltas:** quorum decouples from gossip liveness once a config is established; `ClusterNode` gains `addNode`/`removeNode`; snapshots carry the config; `RaftConfigurationStore` is a new file in the storage layout. No visible change to search fan-out, the demo REST API, or gossip-driven shard placement.
- **Docs:** `ARCHITECTURE.md` Ch14 §7 (quorum/membership), the storage-layout tree (`raft-config.bin`), and the "membership reconfiguration … future work" note are replaced by the implementation status; follower-served reads remain the only pending Raft item.
