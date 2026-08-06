# RFC: Full Log Replication (Raft)

- **Status:** Proposed
- **Milestone:** Phase 3, before ClusterState / leader-controlled shard ownership / shard migration
- **Scope:** Raft log replication: leaders append to their local log, followers replicate, entries are durable before acknowledgment, and commit happens only after a majority. No state-machine apply (nothing consumes the log yet), no snapshotting, no membership reconfiguration, no distributed transactions, no protocol-version bump.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `AppendEntriesRequest` | `src/main/java/com/minigoogle/cluster/transport/dto/AppendEntriesRequest.java` | Already carries the full replication payload: `leaderId`, `term`, `prevLogIndex`, `prevLogTerm`, `entries` (`List<byte[]>`), `leaderCommit`. **Unused fields today.** |
| `RequestVoteRequest` | `.../dto/RequestVoteRequest.java` | Carries `lastLogIndex`, `lastLogTerm`. **Unused fields today.** |
| `AppendEntriesResponse` | `.../dto/AppendEntriesResponse.java` | Carries `term`, `success`. **`success` is hardcoded true by the handler.** |
| `RaftHandler` | `src/main/java/com/minigoogle/cluster/transport/http/RaftHandler.java` | request-vote path calls `raft.receiveVoteRequest(req.candidateId(), req.term())` — **drops `lastLogIndex`/`lastLogTerm`**. append-entries path calls `raft.receiveHeartbeat(req.leaderId(), req.term())` — **drops `prevLogIndex`/`prevLogTerm`/`entries`/`leaderCommit`** and replies `success = true` unconditionally. |
| `HttpRaftTransport` | `.../transport/http/HttpRaftTransport.java` | `stampMetadata` passes every field through unchanged (entries, prev indices, leaderCommit, lastLog*). The transport is transparent; the gap is entirely in the consensus/handler layer. |
| `RaftConsensus` | `src/main/java/com/minigoogle/cluster/RaftConsensus.java` | `lastLogIndex`, `lastLogTerm`, `commitIndex` are volatile ints fixed at 0. No log storage. `sendHeartbeats()` always sends `entries = List.of()`, `prevLogIndex = lastLogIndex`, `prevLogTerm = lastLogTerm`, `leaderCommit = commitIndex`. `onAppendEntriesResponse` only steps down on a higher term; ignores `success`. `receiveVoteRequest(candidateId, term)` has no log up-to-date check. No `getCommitIndex()` / `getLastLogIndex()` accessors. |
| `RaftMetadataStore` | `src/main/java/com/minigoogle/storage/metadata/RaftMetadataStore.java` | Phase 2: crash-consistent persistence of `currentTerm` + `votedFor`; wired into `RaftConsensus` (7-arg constructor) and `ClusterNode` (storage-directory overload). |
| `WriteAheadLog` | `src/main/java/com/minigoogle/storage/wal/WriteAheadLog.java` | Framed append (`1-byte operationType` + `4-byte length` + `payload`), `force(true)` fsync, mmap replay, `clear()`. **Latent bug:** `append()` opens the path with `BinaryWriter` first (an empty leftover block), which opens with `CREATE, WRITE, TRUNCATE_EXISTING` — every append truncates the log to zero, so only the most recent entry survives. Deferred to this phase by RFC `durable-raft-metadata.md` §9. No truncate-to-index support. |
| `StorageLayout` | `src/main/java/com/minigoogle/storage/filesystem/StorageLayout.java` | Has `getRaftMetadataPath()` (Phase 2). No raft-log path. |
| Tests | `src/test/java/com/minigoogle/cluster/RaftConsensusClusterTest.java`, `ClusterTest.java`, `ClusterNodeIntegrationTest.java` | 385 tests green at `21ee9f7`. No test asserts entry replication, commit, or rejection. The fake transport routes `sendAppendEntries` to `receiveHeartbeat`; `ClusterTest` calls `receiveVoteRequest(candidateId, term)` directly (the two-arg form must keep compiling). |

Documentation vs. implementation:

- `ARCHITECTURE.md` Ch14 §7 *Consensus with Raft* — the majority-commit flow ("Append to log → Replicate to majority → Only then commit") is **not implemented**; the wire message already models it, the behavior does not.
- `ARCHITECTURE.md` Ch14 §8 *WAL* — "Write WAL → Flush Disk → Apply Changes → Replay on recovery" describes the end state. The index WAL exists but is truncated-on-append; Raft entries are not written to it at all.

**Conclusion: the transport and wire format are already shaped for full log replication; the consensus layer performs none of it. There is no way to commit an entry, and no durability for entries.**

---

## 2. Current Implementation

The append/commit path, as it exists today:

```
Leader "append":
  (none — there is no API to append an entry)

Leader heartbeat (sendHeartbeats):
  AppendEntriesRequest {
    term = currentTerm,
    prevLogIndex = lastLogIndex (= 0), prevLogTerm = lastLogTerm (= 0),
    entries = [], leaderCommit = commitIndex (= 0) }
  -> follower

Follower (RaftHandler -> receiveHeartbeat):
  if term >= currentTerm: currentTerm/leader/state updated; persistMetadata() if term changed
  response { term = raft.getCurrentTerm(), success = true }      // ALWAYS true

Leader onAppendEntriesResponse:
  if resp.term() > currentTerm: stepDown
  // success is never inspected
```

Observations:

- `commitIndex` is permanently 0; nothing ever commits.
- `lastLogIndex`/`lastLogTerm` are permanently 0; the wire fields that exist for them are dead weight.
- Followers accept every AppendEntries regardless of `prevLogIndex`/`prevLogTerm`/`term`, so a stale leader receives unconditional success.
- Votes are granted without comparing candidate logs, so a node with a shorter log can be elected and then force committed entries to be discarded (Raft's core safety violation).
- Entries are never written to the WAL, and the WAL's `append()` would truncate them even if they were.

---

## 3. Weaknesses

1. **No commit possible.** `commitIndex` never advances; there is no path that turns a majority of acks into a commit. Any future consumer (shard ownership, cluster state, index ops) has nothing to read.
2. **No entry durability.** Entries are never persisted; a crash loses everything, and there is no replay path.
3. **No log-consistency check on AppendEntries.** A follower always returns `success = true`, so a leader believes a lagging or divergent follower is up to date; the "only commit after majority" rule is meaningless when "acked" does not mean "matched".
4. **No election restriction.** `receiveVoteRequest` ignores the candidate's `lastLogIndex`/`lastLogTerm`, so a candidate with a shorter/less-recent log can win and then cause committed entries to be overwritten — the exact safety property Raft exists to guarantee.
5. **`WriteAheadLog.append()` truncates.** The dead `BinaryWriter` block truncates the file before the real append, so even a wired-up WAL would retain only the last entry. This bug must be fixed here.
6. **Documentation drift.** Ch14 §7/§8 describe a replicating, durable system; the code replicates nothing. This RFC makes the replication half true (apply-to-storage remains future work).

---

## 4. Alternative Designs

### A. In-memory log + WAL-backed durability via `WriteAheadLog` (recommended)

Add a `RaftLog` (in `com.minigoogle.cluster`) holding an in-memory `List<LogEntry>` (1-based indexes) backed by `WriteAheadLog` for durability:

- Wire entry framing stays opaque to the transport: each `byte[]` in `entries` is `[4-byte int term][payload]`, framed by the consensus layer — **no DTO change, no protocol bump**.
- WAL payload = same `[term][payload]` bytes, written with a dedicated `operationType`.
- Append persists before returning; truncation on conflict rewrites the WAL (clear + re-append the retained prefix) — O(n), fine at milestone scale.

Pros: reuses `WriteAheadLog` (mission requirement); tiny framing; full durability; conflict truncation is simple and obviously correct. Cons: truncation rewrites the file rather than truncating in place; the WAL append bug must be fixed first.

### B. Append-only WAL with in-place truncate-to-index

Extend `WriteAheadLog` with a real `truncate(long lastIndex)` that trims the file tail without a full rewrite.

Pros: production-shaped; appends stay O(1). Cons: more code, byte-offset bookkeeping, and torn-tail handling on Windows (mapped read + truncate interaction) — more risk than a milestone needs. Defer to Phase 10 hardening.

### C. In-memory log only (no persistence)

Pros: least code. Cons: fails "never lose committed entries" — a committed entry acknowledged to a client would vanish on crash. Rejected.

### D. Single-file snapshot rewrite per change (extend the `RaftMetadataStore` pattern)

Serialize the whole log atomically on every append (temp + fsync + rename).

Pros: reuses the Phase 2 crash-consistency pattern. Cons: O(n) rewrite per entry forever; does not reuse `WriteAheadLog` as the mission requires. Rejected.

### E. Change the wire DTO to carry per-entry terms (`List<LogEntryDto>`)

Pros: term is explicit on the wire. Cons: incompatible message layout change → requires bumping `PROTOCOL_VERSION` to 2 and dual-version handling, contradicting the milestone scope. Rejected; term-in-payload framing (A) keeps protocol v1.

---

## 5. Comparison

| Criterion | A. WAL-backed log | B. In-place truncate | C. In-memory | D. Snapshot rewrite | E. DTO change |
|---|---|---|---|---|---|
| Reuses `WriteAheadLog` | yes | yes | no | no | n/a |
| Survives crash (committed entries) | yes | yes | no | yes | yes |
| Conflict truncation | rewrite (simple) | in-place (index mgmt) | n/a | rewrite | rewrite |
| Protocol version bump | no | no | no | no | **yes (v2)** |
| Append cost | O(1) + fsync | O(1) + fsync | O(1) | O(n) | O(1) |
| Code churn / risk | moderate / low | high / medium | tiny / n/a | low / low | high / high |
| Ready for Phase 10 (true WAL truncate) | migrate later | yes | no | no | n/a |

---

## 6. Recommended Design (Alternative A)

New class `RaftLog` in `com.minigoogle.cluster`:

```java
public final class LogEntry {
    public final int index;   // 1-based; index 0 is the empty-log sentinel
    public final int term;
    public final byte[] payload;
}

public final class RaftLog {
    // in-memory List<LogEntry>, index 0 = implicit dummy entry
    public RaftLog(WriteAheadLog wal);            // real, WAL-backed
    public RaftLog();                             // RaftLog.inMemory() — no WAL
    public int lastIndex();
    public int lastTerm();                        // term at lastIndex (0 when empty)
    public int termAt(int index);                 // 0 if index out of range
    public byte[] payloadAt(int index);
    public void append(int term, byte[] payload); // persists via WAL before returning
    public void truncateFrom(int index);          // drop index..end (rewrite WAL)
    public List<byte[]> entriesFrom(int fromIndex, int max); // wire bytes [term][payload]
    public static int termFromFrame(byte[] frame);           // first 4 bytes big-endian
    public static byte[] payloadFromFrame(byte[] frame);
    public static byte[] toFrame(int term, byte[] payload);
}
```

Wire framing (protocol stays v1): `frame = [int32 BE term][payload]`; `WriteAheadLog.append(OP_RAFT_ENTRY, frame)` persists each entry (opcode `0x01`); the log is a prefix of committed/retained entries re-appended on truncation.

`RaftConsensus` (new 8-arg constructor adds `RaftLog`; 7-arg delegates with `RaftLog.inMemory()`):

- `appendEntry(byte[] payload)` (leader only): persist `term = currentTerm`, then replicate to peers via `sendAppendEntries`; return the new index. On any non-trivial append, do not wait for a quorum (the synchronous ack-to-client path is Phase 5+); the milestone is about the commit pipeline, not the append API.
- `sendAppendEntries(node)` fills the previously empty fields: `prevLogIndex = nextIndex[node]-1`, `prevLogTerm = log.termAt(prevLogIndex)`, `entries = log.entriesFrom(nextIndex[node])`, `leaderCommit = commitIndex`.
- `onAppendEntriesResponse`: step down on higher term; on `success=false` decrement `nextIndex[node]` and resend; on success set `matchIndex[node] = prevLogIndex + entries.size()` and `advanceCommit()`.
- `receiveAppendEntries(leaderId, term, prevLogIndex, prevLogTerm, entries, leaderCommit)`: term rules unchanged; reject (`success=false`, keep `currentTerm`) when `prevLogIndex > lastIndex()` or `termAt(prevLogIndex) != prevLogTerm`; on accept, persist new entries, truncate any conflicting tail, set `leaderId`, and reply `success=true`. Append-entries flow persists `currentTerm` before the reply (Phase 2 rule).
- `advanceCommit()`: commit the highest `index` such that `termAt(index) == currentTerm` (never commit entries from older terms in this milestone) and the entry is on a strict majority of `matchIndex`es.
- `receiveVoteRequest(candidateId, term, lastLogIndex, lastLogTerm)`: grant only if the candidate's log is up to date (`lastLogTerm > log.lastTerm()`, or equal term and `lastLogIndex >= log.lastIndex()`), on top of the existing rules; the existing 2-arg method delegates to it with `lastLogIndex = lastLogTerm = 0` (empty-log behavior identical, `ClusterTest` untouched).
- `sendHeartbeats()` becomes `sendAppendEntries` for every follower with empty `entries` when nothing is pending.
- New accessors: `getCommitIndex()`, `getLastLogIndex()`, `getLastLogTerm()` (needed by `HttpRaftTransport`/tests; Phase 5+).

`RaftHandler` (the only transport change):

- request-vote: call the 3-arg `receiveVoteRequest(req.candidateId(), req.term(), req.lastLogIndex(), req.lastLogTerm())`.
- append-entries: call the new `receiveAppendEntries(...)` with every DTO field, drop the `receiveHeartbeat` shim, and reply with the consensus-provided `success` + `term`.

`StorageLayout.getRaftLogPath()` → `raft-log.bin` alongside `raft-metadata.bin`. `ClusterNode` storage-directory overload constructs the real `RaftLog(wal)` (WAL owned by `ClusterNode`, same as today) and passes it to the 8-arg constructor; the no-storage path keeps `inMemory()`.

Pre-flight (must land first, its own commit): fix `WriteAheadLog.append()` by deleting the dead `BinaryWriter` block (the leftover `CREATE, WRITE, TRUNCATE_EXISTING` opening); add a regression test that two appends survive a reopen.

---

## 7. Migration Plan

1. Fix `WriteAheadLog.append()`; add multi-entry persistence regression test (commit `m1`).
2. Add `RaftLog` + `LogEntry` + unit tests, including framing round-trip and truncation (commit `m2`).
3. Extend `RaftConsensus` (8-arg ctor, replication state, commit, vote restriction, accessors) keeping the 7-arg and 2-arg vote paths behavior-identical; new `RaftConsensusReplicationTest` (fake transport): single-follower commit, majority commit, rejection on mismatch, no-vote for stale candidate (commit `m3`).
4. Rewire `RaftHandler` to pass full DTO fields and honor `success`; `HttpRaftTransport` unchanged; extend HTTP end-to-end tests for entry replication (commit `m4`).
5. `StorageLayout.getRaftLogPath()` + `ClusterNode` wiring for durable log; extend `ClusterNodeDurableRaftTest` for log replay (commit `m5`).
6. Update `ARCHITECTURE.md` Ch14 §7/§8, run the full suite, produce the impact summary, commit (commit `m6`), push to both repos.

---

## 8. Acceptance Criteria

- Given a leader and one follower (fake transport): after `appendEntry(payload)`, the follower's `getLastLogIndex()` equals the leader's, and the frame round-trips payload + term.
- A majority of followers acknowledging index `i` advances `commitIndex` to `i`, only when `log.termAt(i) == currentTerm`.
- A follower with a mismatched `prevLogTerm` at `prevLogIndex` returns `success = false`; the leader backs off `nextIndex` and eventually converges.
- A candidate whose log is behind the current leader's is denied the vote; an equal-or-newer candidate is granted it.
- With real storage: kill and restart a node (durable log) — entries replayed, indexes/terms intact; the metadata file still round-trips through Phase 2 behavior.
- Protocol stays v1; `HttpRaftTransport` source unchanged; `ClusterTest` (2-arg vote) compiles and passes.
- Full `.\gradlew.bat test`: 385 prior tests still green + new tests all green; `.\gradlew.bat build -x test` succeeds.

---

## 9. Rollback

- Pre-flight `m1` is a pure bug fix; reverting restores the truncated-append behavior without breaking the Phase 2 contract.
- `m2`–`m5` are additive; revert in reverse order (wiring → handler → consensus → RaftLog → WAL fix). No wire-format change, so old and new nodes interoperate at the heartbeat level during a mixed rollout; after rollback the two-arg paths are still used by `ClusterTest` and the fake transport.
- `raft-log.bin` is safely deletable (rebuilt from `currentTerm`/`votedFor` + log replay); deleting it only loses uncommitted entries, which is Raft-legal.

---

## 10. Estimated Impact

- **Files changed:** 7 production (5 new: `LogEntry`, `RaftLog`; 3 edited: `RaftConsensus`, `RaftHandler`, `WriteAheadLog`; `StorageLayout`, `ClusterNode`) and 4–6 test files.
- **Lines:** ≈ 350–450 added (RaftLog ~120, consensus ~180, handler ~25, storage/cluster ~30, pre-flight fix ~5) and ≈ 10 deleted (dead `BinaryWriter` block). Read-only: `HttpRaftTransport`, DTOs.
- **LoC delta:** +350 / −10; net impact on the 12,500-line codebase ≈ +3%.
- **Testing cost:** +25–35 tests, +10–20 min suite time, total ≈ 410–420 tests.
- **Dependencies:** no new third-party; reuses `WriteAheadLog` per mission constraint.
- **Behavior deltas:** `sendHeartbeats` now sends real prev-index/term metadata; votes compare logs; `success` is meaningful. No visible end-user behavior change yet — commit is only consumable by accessors until Phase 5+ wires shard ownership to `commitIndex`.
- **Docs:** `ARCHITECTURE.md` Ch14 §7/§8 updated to mark replication + commit implemented, apply/snapshot still pending.
