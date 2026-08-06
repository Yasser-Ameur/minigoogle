# RFC: Secure the Internal Cluster Transport

- **Status:** Proposed
- **Milestone:** Phase 1 hardening, before log replication / shard migration / cluster state
- **Scope:** Authentication of internal cluster RPCs only. No log replication, shard transfer, cluster state, rebalancing, or MiniGoogleApp wiring.

---

## 1. Repository Evidence

All claims below were verified from source, not from documentation.

| Artifact | Path | Finding |
|---|---|---|
| `ClusterSecurity` | `src/main/java/com/minigoogle/cluster/ClusterSecurity.java` | Per-node token registry exists (`generateToken`, `validateToken`, `validateBearerToken`, `revokeToken`, `isRegistered`) but is referenced **only** by `ClusterTest`. Unwired. |
| `TokenValidator` | `src/main/java/com/minigoogle/network/security/TokenValidator.java` | Second, independent, single shared-token validator in the network module. Also unwired. |
| `InternalClusterServer` | `src/main/java/com/minigoogle/cluster/transport/http/InternalClusterServer.java` | Plain `com.sun.net.httpserver.HttpServer` + bounded executor. No filter, no auth, no interceptor. |
| `GossipHandler` | `.../transport/http/GossipHandler.java` | Method check → parse body → protocol validation → process. No Authorization check, no 401/403. |
| `RaftHandler` | `.../transport/http/RaftHandler.java` | Same pattern. No auth. |
| `SearchHandler` | `.../transport/http/SearchHandler.java` | Same pattern. No auth. |
| `HttpMembershipTransport` | `.../transport/http/HttpMembershipTransport.java` | Sets only `Content-Type`. No `Authorization` header. |
| `HttpRaftTransport` | `.../transport/http/HttpRaftTransport.java` | Sets only `Content-Type`. No `Authorization` header. |
| `HttpSearchTransport` | `.../transport/http/HttpSearchTransport.java` | Sets only `Content-Type`. No `Authorization` header. |
| `HttpShardTransferTransport` | `.../transport/http/HttpShardTransferTransport.java` | Sets only `Content-Type`. No `Authorization` header. |
| `ClusterNode` | `src/main/java/com/minigoogle/cluster/ClusterNode.java` | Constructs handlers and transports directly; no `ClusterSecurity` is ever created or injected. |
| `ClusterTransport` interfaces | `.../transport/*.java` | Only `start()` / `stop()` plus RPC methods. No credential abstraction. |
| Transport tests | `src/test/java/com/minigoogle/cluster/transport/**` | No test sends or checks an `Authorization` header. |

Documentation vs. implementation:

- `ARCHITECTURE.md` §18 *Internal Security* — "Every node receives a Cluster Token during startup. Every request includes `Authorization: Bearer <token>`. Unauthorized nodes cannot join the cluster." — **not implemented**.
- `ARCHITECTURE.md` §20 *Security* — "`ClusterSecurity` issues per-node bearer tokens. An attacker that cannot present a valid token is excluded before any message is processed." — **not implemented**.
- `ARCHITECTURE.md` §17 *Security* and Ch15 §8 — "TLS / Mutual Certificates / Sensitive traffic uses TLS" — **not implemented** (plain HTTP).

**Conclusion: the internal cluster transport is currently unauthenticated.** Any process able to reach the internal RPC port is a full cluster member.

---

## 2. Current Implementation

The request path, end to end:

```
Client (transport)                     Server (InternalClusterServer)
------------------                     ---------------------------------
HttpMembershipTransport.exchangeState  /cluster/v1/gossip/exchange
  POST baseUri + "/cluster/v1/...       GossipHandler.handle
  header: Content-Type: application/json  ├─ method != POST        -> 405
  body: {protocolVersion, sourceNodeId,   ├─ parse body
         state, ...}                      ├─ ClusterProtocol.validate  -> 400
  ...                                     ├─ gossip.receiveGossip(...)
  response >= 400 -> failed future        └─ 200 ack
```

The same shape is repeated for `/cluster/v1/raft/request-vote`,
`/cluster/v1/raft/append-entries`, and `/cluster/v1/search/dispatch`.

Observations:

- No credentials are attached by any client.
- No credentials are verified by any server.
- No identity from the wire is bound to any token.
- `ClusterSecurity.validateBearerToken` requires a pre-populated `registeredTokens`
  registry that nothing in the transport layer ever populates.
- `ClusterSecurity.generateToken` includes `System.currentTimeMillis()`, so two nodes
  generating a token for the same node ID produce different tokens — tokens are not
  mutually verifiable without out-of-band distribution, which does not exist.

---

## 3. Security Weaknesses

1. **No authentication at all.** An unauthenticated caller can:
   - poison the gossip membership table (`GossipHandler` merges any state),
   - inject/remove ring nodes and hijack key routing,
   - trigger or disrupt Raft elections and step down a legitimate leader,
   - run arbitrary search queries against shards and burn CPU/IO.
2. **No 401/403.** Missing, malformed, or wrong credentials are indistinguishable
   from valid requests in the current code.
3. **No credential on the wire.** Clients never present a token, so even if a server
   validated tokens, nothing would pass.
4. **Spoofing.** The envelope `sourceNodeId` is never bound to an identity. Even with
   tokens, a caller could claim a different `sourceNodeId` and confuse correlation,
   failure detection, and routing.
5. **Replay.** With static tokens over plain HTTP, a captured `Authorization` header
   can be replayed until the token is revoked. This is a documented, accepted
   limitation for Phase 1 (see §6 and §9); TLS/mTLS is the real mitigation and is
   already flagged as future work in the docs.
6. **Documentation drift.** The docs describe the system as authenticated; the code
   is not. This RFC's change makes the docs true.

---

## 4. Alternative Designs

### A. Shared `HttpServer` Filter (recommended, preferred architecture)

Use the JDK's `com.sun.net.httpserver.Filter` (no new dependency) registered on each
internal context before any handler. The filter reads `Authorization`, validates via
`ClusterSecurity`, rejects with 401 before the handler runs, and records the
authenticated node ID as an exchange attribute.

- Authentication logic lives in exactly one place.
- Handlers stay focused on protocol logic.
- `ClusterNode` opts every endpoint into protection via one server API.

### B. Per-handler credential checks

Each handler parses and validates the `Authorization` header itself.

- Duplicates logic across Gossip/Raft/Search handlers.
- Easy to forget on the next endpoint (shard transfer is imminent).
- Rejected: violates the "avoid duplicating authentication logic" requirement.

### C. Client-side interceptor only

Wrap the `HttpClient` so it always attaches the token, and skip server-side
validation on the assumption that the network is trusted.

- Leaves the server open to any direct caller; zero security.
- Rejected.

### D. Mutual TLS / TLS with client certificates

The strongest option, and what `ARCHITECTURE.md` §17 describes as the end state.

- Requires certificate infrastructure (CA, per-node certs, keystores) and
  `HttpsServer`/`SSLContext` changes.
- Large, multi-part milestone; outside the scope of "harden the existing transport."
- Recommended as the successor milestone, not this one.

### E. Registry-based per-node tokens (status quo `ClusterSecurity` model)

Tokens generated by `generateToken` and distributed out-of-band.

- No distribution mechanism exists in the codebase.
- Two nodes cannot verify each other's tokens without shared state.
- Rejected as the transport default.

### F. Claim-based derived tokens (adopted)

`deriveToken(nodeId)` = `sha256(sharedSecret:nodeId)`; the caller claims its
node ID in an `X-Node-Id` header; the receiver validates
`token == deriveToken(X-Node-Id)`.

- Verification derives on the claimed ID rather than a pre-known registry, so
  peers that have never met can authenticate each other — gossip can bootstrap
  from seed peers.
- A wrong claim still fails, because deriving the matching token requires the
  shared cluster secret.
- Subsumes the filter design (A): the filter still performs validation centrally.

---

## 5. Comparison

| Criterion | A. Filter | B. Per-handler | C. Client-only | D. mTLS | E. Registry tokens | F. Claim-based (adopted) |
|---|---|---|---|---|---|---|
| Every endpoint protected | yes | fragile | no | yes | yes (if wired) | yes |
| Shared auth logic | single place | duplicated | n/a | n/a | n/a | single place |
| New dependencies | none | none | none | large | none | none |
| Reuses `ClusterSecurity` | yes | yes | no | no | yes | yes |
| Mutual verification | yes (shared secret) | yes | no | yes | no (needs distribution) | yes (shared secret) |
| Anti-spoofing (binds identity) | yes | yes | no | yes | yes | yes |
| Bootstraps without prior membership | needs registry | n/a | n/a | n/a | no | yes |
| Effort | small | small | small | large | small + missing piece | small |
| Code churn | moderate | small | tiny | large | moderate | moderate |

---

## 6. Recommendation

Adopt **Alternative F**: a shared `AuthFilter` on the internal RPC server, with
`ClusterSecurity` as the single source of tokens, per-node bearer tokens derived
deterministically from a shared cluster secret, and node identity claimed via an
`X-Node-Id` header so the gossip protocol can bootstrap without prior membership.

Concretely:

1. **`ClusterSecurity`** (extend, don't rewrite):
   - Add `deriveToken(nodeId)` — deterministic `sha256(secret:nodeId)`, so every node
     with the shared secret derives the same token for a given node ID.
   - Add `authenticate(authorizationHeader, claimedNodeId)` — returns the
     authenticated node ID, or null. Accepts the token derived for the *claimed*
     node ID first (shared-secret model), then the existing `registeredTokens`
     registry. Uses constant-time comparison.
   - Existing methods (`generateToken`, `validateToken`, `validateBearerToken`,
     `revokeToken`, `isRegistered`) are preserved unchanged.
2. **Identity claim header**: transports carry their node ID in an
   `X-Node-Id` header alongside `Authorization: Bearer <token>`. The filter
   derives the expected token from the claimed ID, which lets a node prove its
   identity to a receiver that has **never met it** — the bootstrap case for
   gossip membership — without relaxing security, because forging a claim still
   requires the shared cluster secret. (A `knownNodeIds`-registry variant was
   prototyped but it deadlocks bootstrapping: a node rejects the first message
   from a peer it does not yet know, so membership can never form.)
3. **`AuthFilter`** (`com.sun.net.httpserver.Filter`), constructed with a
   `ClusterSecurity`:
   - Missing/malformed/invalid token -> **401** `{ "error": "Unauthorized" }`,
     request terminated before any handler.
   - Valid token -> records `authenticatedNodeId` on the exchange, continues the chain.
4. **Anti-spoofing cross-check**: handlers compare the envelope `sourceNodeId` with the
   authenticated node ID; mismatch -> **403** `{ "error": "Forbidden" }`.
5. **`InternalClusterServer.registerProtectedContext(path, handler, security)`**:
   the single place endpoints get their filter. `ClusterNode` registers every internal
   endpoint through this API.
6. **Transports**: `HttpMembershipTransport`, `HttpRaftTransport`,
   `HttpSearchTransport`, `HttpShardTransferTransport` each take a bearer token
   (required) and attach `Authorization: Bearer <token>` **and** `X-Node-Id` to
   every request. A tiny shared `HttpAuth` constant holder avoids duplicating the
   header strings.
7. **`ClusterNode`**: the master constructor takes a `ClusterSecurity`. All endpoints
   are registered as protected contexts; transports receive `security.deriveToken(nodeId)`.
   Convenience constructors build a random-secret `ClusterSecurity` — still
   authenticated end-to-end for a single node (self-consistent), while clusters pass a
   shared secret explicitly.

Threat-model note (documented, not fixed here):

- Static tokens over plain HTTP are replayable by anyone who captures traffic. This
  matches the documented Phase-1 posture ("future versions may replace this with
  mutual TLS"). TLS/mTLS is the follow-up milestone.
- A compromised node holding the shared secret can impersonate any node ID it wants;
  per-node tokens and the identity cross-check bound *accidental or partial*
  misbehavior but not a fully compromised secret-holder.

---

## 7. Migration Plan

1. Land the `ClusterSecurity` extension + unit tests (no behavior change elsewhere).
2. Add `AuthFilter` + `HttpAuth` + `InternalClusterServer.registerProtectedContext`.
3. Update `ClusterNode` to create/accept `ClusterSecurity`, protect all endpoints,
   and hand tokens to the transports.
4. Update the existing handler/transport tests to run behind the filter and to send
   valid bearer tokens.
5. Add new auth tests (401 missing/invalid, 403 mismatch, header attached by clients,
   end-to-end protected cluster).
6. Update `ARCHITECTURE.md` so the security sections describe the now-true state.
7. Full `gradlew test` + `gradlew build -x test`, commit, push.

---

## 8. Acceptance Criteria

- [ ] Every internal endpoint (`/cluster/v1/gossip/exchange`,
      `/cluster/v1/raft/request-vote`, `/cluster/v1/raft/append-entries`,
      `/cluster/v1/search/dispatch`) requires a valid bearer token.
- [ ] Every HTTP transport attaches `Authorization: Bearer <token>` and
      `X-Node-Id` automatically.
- [ ] Unauthenticated requests receive 401.
- [ ] Valid token but mismatched `sourceNodeId` receives 403.
- [ ] Valid authenticated requests behave exactly as before.
- [ ] Existing transport tests remain green (updated to the authenticated posture).
- [ ] New authentication tests cover the filter, the transports, and the full cluster.
- [ ] `ClusterNodeIntegrationTest` still passes (gossip convergence, ring, Raft
      election, distributed search — all over authenticated HTTP).
- [ ] Full Gradle test suite passes and the runnable jar builds.

---

## 9. Rollback Strategy

- The change is additive and localised to the cluster transport package plus
  `ClusterSecurity` and `ClusterNode`.
- Rollback = revert the commit(s); the transports, handlers, and tests return to the
  pre-auth state cleanly. No data migration, no on-disk state, no schema change.
- The `AuthFilter` is decoupled from handlers, so a partial rollback (drop the filter
  registration, keep client headers) is also possible without touching handler logic.
- Because the acceptance criteria are all covered by tests, a regression would be
  caught by the same suite that validates the change.
