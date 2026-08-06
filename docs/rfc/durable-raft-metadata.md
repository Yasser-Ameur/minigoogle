# RFC: Durable Raft Metadata (currentTerm + votedFor)

- **Status:** Proposed
- **Milestone:** Phase 2, before full log replication / ClusterState / shard ownership and migration
- **Scope:** Persistence of Raft election metadata only (`currentTerm`, `votedFor`). No log replication, no ClusterState, no shard ownership or migration, no changes to search-index storage, no new dependencies.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `RaftConsensus` | `src/main/java/com/minigoogle/cluster/RaftConsensus.java` | `currentTerm` (int) and `votedFor` (String) are plain `volatile` fields initialized to `0` / `null` in the constructor (lines 111–112). They are **never written to disk**. Class javadoc: "This implementation covers the leader election portion of Raft." |
| `RaftConsensus` constructors | same file | Five constructors, the largest being `(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, RaftTransport, Supplier<List<String>>)`. No storage path, no persistence hook. |
| `ClusterNode` | `src/main/java/com/minigoogle/cluster/ClusterNode.java` | Line 119: `new RaftConsensus(nodeId, raftElectionTimeout, raftHeartbeat, 3, raftTransport, gossip::getLiveNodes)`. No storage directory is created, injected, or passed anywhere in the class. |
| `WriteAheadLog` | `src/main/java/com/minigoogle/storage/wal/WriteAheadLog.java` | Framed entries (`1-byte operationType + 4-byte length + payload`), `append()` uses `FileChannel.open(path, CREATE, APPEND)` + `channel.force(true)`, `readAll()` replays via mmap. **Latent bug:** `append()` first opens the path with `BinaryWriter`, which opens with `CREATE, WRITE, TRUNCATE_EXISTING` (line 30, an empty leftover block) — every append truncates the log to zero, so only the most recent entry survives. |
| `BinaryWriter` | `src/main/java/com/minigoogle/storage/serialization/BinaryWriter.java` | Opens with `CREATE, WRITE, TRUNCATE_EXISTING`. `close()` flushes but never calls `force()`. Not crash-consistent for direct target writes. |
| `BinaryReader` | `src/main/java/com/minigoogle/storage/serialization/BinaryReader.java` | `readInt/readLong/readByte/readString/hasRemaining` over a `ByteBuffer` (typically mmap). |
| `MetadataWriter` / `MetadataReader` / `Metadata` | `src/main/java/com/minigoogle/storage/metadata/` | The existing "compact binary metadata file" pattern (write primitives + length-prefixed strings, mmap read). Written directly to the target path — no temp file, no atomic rename. |
| `StorageLayout` | `src/main/java/com/minigoogle/storage/filesystem/StorageLayout.java` | Only `getBaseDirectory()`, `getShardDirectory(int)`, `getSegmentDirectory(int, String)`. No raft/cluster metadata path. |
| Tests | `src/test/java/com/minigoogle/cluster/RaftConsensusClusterTest.java`, `ClusterNodeIntegrationTest.java`, `ClusterTest.java` | 364 tests green. No test constructs a node with a storage directory; no raft-persistence test exists. `RaftConsensusClusterTest` uses the 6-argument constructor with a fake transport. |

Documentation vs. implementation:

- `ARCHITECTURE.md` Ch14 §7 *Consensus with Raft* — describes majority-commit, WAL replay, and durability. **Leader election is implemented; persistence is not.**
- `ARCHITECTURE.md` Ch14 §8 *Write-Ahead Logging (WAL)* — "Write WAL → Flush Disk → Apply Changes. After reboot, read WAL and replay." **Describes the end state, not the current code.**

**Conclusion: after a process restart, a node forgets the term it has seen and any vote it has cast. Raft's Election Safety can be violated across restarts today.**

---

## 2. Current Implementation

The full lifecycle of the two durable Raft fields, as it exists today:

```
Startup:            currentTerm = 0, votedFor = null, state = FOLLOWER      (constructor, lines 109–112)
receiveVoteRequest:
  term >  currentTerm   -> currentTerm = term; votedFor = candidate; grant  (lines 165–172)
  term == currentTerm
      && votedFor null
      || votedFor==candidate -> votedFor = candidate; grant                 (lines 174–177)
  otherwise             -> deny                                              (line 179)
startElection:       currentTerm++; votedFor = self; send RequestVote       (lines 186–197)
stepDown(term):      currentTerm = term; votedFor = null                    (lines 315–322)
receiveHeartbeat:    if term >= currentTerm: currentTerm = term             (lines 149–157)
```

Observations:

- All four mutation points update plain fields. Nothing persists.
- There is no ordering guarantee binding a vote grant to a durable write — the "persist before replying to RequestVote" rule does not exist.
- Log replication is stubbed: `lastLogIndex`, `lastLogTerm`, `commitIndex` are all fixed at 0 (lines 44–47). Not touched by this RFC.
- The storage layer already contains the two patterns this RFC needs: a crash-consistent appendable `WriteAheadLog` (currently only usable for index operations) and a compact binary metadata-file pattern (`storage.metadata`). Neither is wired to Raft.

---

## 3. Weaknesses

1. **Double vote across a restart.** A node grants a vote in term N, crashes, and restarts with `currentTerm = 0`, `votedFor = null`. It can then grant a second vote in term N to a different candidate. Two different leaders can be elected in the same term — a direct violation of Raft Election Safety.
2. **Term regression across a restart.** A node that observed term 7 restarts at term 0. A stale candidate campaigning at term 3 can now win its vote, and the node will accept a term lower than one it previously participated in. Election Safety and the leadership-transfer rules of the consensus protocol break.
3. **No persistence hook.** `RaftConsensus` has no way to receive a storage path or store; the protocol layer is sealed off from the storage layer that already exists.
4. **Documentation drift.** Ch14 §7/§8 describe a durable system; the code is leader-election-only and fully volatile. This RFC's change makes the durability half of those chapters true (log replication remains future work).
5. **Latent WAL truncation bug.** If `WriteAheadLog.append` were reused for metadata today, the leftover `BinaryWriter` block would truncate the log on every append, and metadata would be lost. Reuse of the WAL requires fixing this first (Phase 3 will need the WAL regardless).

---

## 4. Alternative Designs

### A. Dedicated atomic `RaftMetadataStore` (recommended)

One small file per node holding `{ currentTerm, votedFor }`, following the existing `storage.metadata` binary-file pattern but made crash-consistent:

- Serialize to a temp file (e.g. `<dir>/raft-metadata.bin.tmp`) with `BinaryWriter`.
- `force(true)` the temp file, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
- `load()` reads with `BinaryReader` (mmap). Missing/empty file → `{0, null}`. Corrupt content or wrong magic/version → **fail-fast on startup** (a silent reset would re-enable double-voting).
- `RaftMetadataStore.inMemory()` no-op fallback for constructors/tests that pass no storage path.

Pros: smallest correct change; reuses the established `storage.metadata` pattern; no log growth (single record, overwrite); torn-write safe via atomic rename; fail-fast preserves safety. Cons: a new small class; does not yet reuse `WriteAheadLog`.

### B. Reuse `WriteAheadLog` for raft metadata

Append a metadata entry (`operationType` = raft-meta) on every term/vote change; replay on startup; compact later.

Pros: reuses an existing abstraction (aligned with the "reuse existing storage abstractions" rule); in Phase 3 the same log can carry Raft log entries, giving one unified durable stream. Cons: the truncation bug in `WriteAheadLog.append` must be fixed first; log grows unboundedly until compaction exists; semantically overloading the index-operation WAL with consensus metadata mixes concerns while log replication (Phase 3) is not yet implemented; replay error handling for a torn trailing entry is unproven. Better evaluated in Phase 3, when log entries actually exist.

### C. In-place overwrite of a single metadata file

Open the target with `FileChannel`, write, `force(true)`.

Pros: simplest possible write path. Cons: a crash mid-write leaves a torn file; `force` on an already-open target does not give the rename atomicity of A. Fail-fast detection mitigates but does not remove the corruption window. Rejected in favor of A.

### D. In-memory only (status quo)

Pros: zero work. Cons: fails every acceptance criterion in §8. Rejected.

### E. Directory-fsync approach

Write the file, then `force` the parent directory to guarantee the rename is durable.

Pros: textbook durability. Cons: directory fsync is not reliably available through the JDK on all platforms (Windows included); `ATOMIC_MOVE` + file `force` is the portable JDK idiom and sufficient for a single small record. Rejected as the mechanism, kept as a documented note.

---

## 5. Comparison

| Criterion | A. Atomic meta file | B. WAL reuse | C. In-place overwrite | D. In-memory | E. Dir-fsync |
|---|---|---|---|---|---|
| Survives process crash | yes | yes (after bug fix) | mostly | no | yes |
| Torn-write safe | yes (rename) | no (needs repair) | no | n/a | no |
| Restart restores term + vote | yes | yes | yes | no | yes |
| New dependencies | none | none | none | none | none |
| Reuses existing storage pattern | `storage.metadata` | `WriteAheadLog` | none | none | `storage.metadata` |
| Ready for Phase 3 log entries | separate file | unified log | separate | n/a | separate |
| Code churn | small | small + WAL fix + compaction | tiny | none | small |
| Fail-fast on corruption | yes | partial | yes | n/a | yes |
| Windows-portable | yes | yes | yes | yes | **no** |

---

## 6. Recommendation

Adopt **Alternative A**: a compact, atomic, fail-fast `RaftMetadataStore`, injected into `RaftConsensus` through a new constructor overload so existing constructors and the 364-test baseline are untouched.

Concretely:

1. **`RaftMetadataStore`** — new class in `com.minigoogle.storage.metadata`:
   - Constructor `RaftMetadataStore(Path file)`; static factory `inMemory()` (all methods no-op).
   - Record `RaftMetadata(int currentTerm, String votedFor)`.
   - File format: `magic (0x52 0x4D 0x45 0x54 "RMET")` + `version (byte 1)` + `currentTerm (int)` + `votedFor` as a 1-byte presence flag + length-prefixed UTF-8 string (via `BinaryWriter.writeString` / `BinaryReader.readString`). About 24 bytes.
   - `load()` → `RaftMetadata`; missing/empty file → `{0, null}`; magic/version/parse error → `IOException` so startup fails fast (never silently resets a vote).
   - `persist(int term, String votedFor)` → write temp file, `force(true)`, `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, creating parent directories as needed. Synchronized.
2. **`RaftConsensus` wiring** (additive only):
   - New 7-argument constructor adds `RaftMetadataStore store`; all five existing constructors delegate with `RaftMetadataStore.inMemory()`.
   - On construction/`start()`: `load()` and restore `currentTerm` and `votedFor`. Restarting node resumes as a follower with its remembered term and vote — it does **not** campaign on boot.
   - **Persist before replying, at exactly four points** (each already mutates the fields):
     - `receiveVoteRequest` — before returning `true` on the `term > currentTerm` grant path and on the `term == currentTerm` grant path (`persist(currentTerm, votedFor)` immediately after setting them).
     - `startElection` — `currentTerm++`, `votedFor = nodeId`, **persist before `requestVotesFromPeers()`**.
     - `stepDown` — persist the new term and `votedFor = null`.
     - `receiveHeartbeat` — persist only when `term` actually increases.
   - `receiveVote` / `becomeLeader` / deny paths change no metadata and persist nothing.
3. **`StorageLayout`** — add `getRaftMetadataPath()` returning e.g. `baseDirectory.resolve("raft-metadata.bin")`.
4. **`ClusterNode`** — new constructor overload accepting a storage directory `Path` (or a `StorageLayout`); creates a real `RaftMetadataStore` and passes it to Raft. Existing constructors keep the in-memory store, so every current test and the convenience API stay byte-for-byte unchanged. A restart keeps the same directory → recovered term and vote.
5. **Out of scope (explicit):** vote condition on candidate log up-to-dateness, `lastLogTerm` persistence, log replication, WAL reuse for consensus. The WAL truncation bug (`WriteAheadLog.append`, line 30) is recorded here and should be fixed in Phase 3 when the WAL gains real users.

---

## 7. Migration Plan

1. Add `RaftMetadata` record + `RaftMetadataStore` (+ unit tests: write/load round-trip, missing file → defaults, corrupt file → fail-fast, `inMemory()` no-op).
2. Add the 7-argument `RaftConsensus` constructor with restore-on-start and the four persist points. No existing signature changes → the 364-test baseline is untouched.
3. Add `StorageLayout.getRaftMetadataPath()`.
4. Add the `ClusterNode` storage-directory overload wiring a real store.
5. Add persistence tests:
   - `RaftMetadataStoreTest` (file-level).
   - `RaftConsensusPersistenceTest` (unit, fake transport): grant a vote in term N → persist before reply; restart with same store → term and vote restored; second candidate in term N is denied (no double vote); stale lower-term candidate is denied (term regression fixed); `startElection` term/vote persisted before RPCs; heartbeat higher term persists.
   - Integration: `ClusterNode` with a temp storage dir, elect a leader, stop, restart the node, assert `getRaft().getCurrentTerm()` and `votedFor` are restored and it will not grant a vote in a term ≤ stored term.
6. Update `ARCHITECTURE.md` Ch14 §7/§8 and `StorageLayout` chapter: raft election metadata is now durable via `RaftMetadataStore`; WAL/log replication remains future work. Also record the Phase-3 WAL truncation fix as a known item.
7. Full `gradlew test` (364 + new), `gradlew build -x test` (jar), commit, push.

---

## 8. Acceptance Criteria

- [ ] `currentTerm` and `votedFor` are persisted **before** `receiveVoteRequest` returns `true` on any grant path.
- [ ] `startElection` persists `currentTerm` and `votedFor = nodeId` before any RequestVote is sent.
- [ ] `stepDown` and higher-term heartbeats persist the new term before the response is processed.
- [ ] On restart, a node restores its stored `currentTerm` and `votedFor` and resumes as a follower.
- [ ] After restart, a node never grants a vote in a term ≤ stored term, except to the candidate it already voted for in that exact term (no double-vote, no term regression).
- [ ] Corrupt/malformed metadata file fails startup fast instead of silently resetting the vote.
- [ ] Existing five `RaftConsensus` constructors and `ClusterNode` constructors keep their current signatures; the 364-test baseline stays green unmodified.
- [ ] New unit + integration tests cover round-trip persistence, restart recovery, vote integrity, durable terms, and `ClusterNode` restart with a storage dir.
- [ ] `RaftConsensusClusterTest` and `ClusterNodeIntegrationTest` still pass.
- [ ] Full Gradle test suite passes and the runnable jar builds.

---

## 9. Rollback Strategy

- The change is additive and localised to `RaftConsensus`, `ClusterNode`, `StorageLayout`, and the new `RaftMetadataStore`/`RaftMetadata`.
- Existing constructor signatures are unchanged, so rollback = revert the commit; every pre-existing test and call site returns to the current state with no edits.
- The on-disk metadata file is disposable: delete it and restart. No data migration, no schema change, no index impact.
- Because the acceptance criteria are covered by tests, a regression is caught by the same suite that validates the change.
- The WAL truncation bug fix is deliberately **not** bundled here; it belongs to Phase 3, keeping this rollback zero-risk.

---

## 10. Estimated Impact

- **New files:** `RaftMetadataStore.java`, `RaftMetadata.java`, `RaftMetadataStoreTest.java`, `RaftConsensusPersistenceTest.java` (~4 files).
- **Modified files:** `RaftConsensus.java` (new overload + 4 persist calls + restore-on-start, ~30 lines), `StorageLayout.java` (one method), `ClusterNode.java` (one overload), `ARCHITECTURE.md` (2–3 sections).
- **Code churn:** small (~60–90 lines of production code). No new dependencies, no framework changes, no changes to existing public constructor signatures.
- **Behavior change:** leader-election metadata becomes durable for nodes constructed with a storage directory; all other constructors behave exactly as today.
- **Test impact:** 364 existing tests must remain green unchanged; ~10–15 new assertions across the new unit and integration tests.
- **Risk:** low — additive, isolated, fail-fast, with a clear rollback path. The only adjacent defect (WAL truncation) is explicitly deferred to Phase 3.
