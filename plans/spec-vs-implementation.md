# Spec vs Implementation

_Comparing `spec.md` against `go/` as of the `new-start` branch._

---

## Summary

The Go implementation covers the core protocol faithfully: all five document
types are present with correct JSON field names, the peer server handles
submission, batch computation, and cross-peer dep fetching, and the scheduler
runs on the 10-minute cycle with cancel-replace semantics. Several spec
features are parsed/typed but not yet wired into logic (glob, contact fields,
archive addresses). A handful of structural deviations are worth resolving
before the protocol is stable.

---

## Document types

All five document types are implemented in `go/pkg/doc/types.go` with JSON
field names that match the spec exactly.

| Document | Spec fields | Implementation | Notes |
|---|---|---|---|
| `envelope` | 4 fields | ✓ all present | — |
| `user-info` | 9 fields | ✓ all present | `context-relations-deps-index-ipns-address` deviation — see below |
| `direct-relations` | 11 fields incl. contact | ✓ all present | contact fields, name/comment never read |
| `context-relations-deps` | hops + source addresses | ✓ all present | archive address is a placeholder |
| `context-relations-deps-index` | per-context entry | ✓ all present | — |

---

## Field-level deviations

### ~~`context-relations-deps-index-ipns-address`~~ — removed

This field was left in the spec by mistake and has been removed from both the
spec and the `UserInfo` type. The index CID is returned by the peer's
`/status/users/{id}` endpoint; there is no need to carry it in the published
user-info document.

### ~~`direct-relations-ipns-address`~~ → `direct-relations-content-address`

Renamed in spec and implementation. `processUser` now populates
`DirectRelationsContentAddress` with the current DR CID during each batch run,
so the published user-info carries an accurate pointer to the latest DR.

---

## Features typed but not wired

These fields exist in `types.go` and are round-tripped in documents, but no
server-side logic reads them.

| Field | Where | Gap |
|---|---|---|
| `subject-glob`, `object-glob` | `DirectRelation` | `fetchTransitiveDeps` uses exact `pathMatches`; glob expansion not implemented |
| `direct-relations-rel-user/context-path` | `DirectRelation.RelContextPath` | Passed to `fetchUserContextDeps` as `targetPath` — partially wired but glob-aware routing is not |
| `contact-email`, `contact-signal-username`, `contact-number` | `DirectRelations` | Never read or validated |
| `direct-relations-rel-uri/name`, `comment` | `DirectRelation` | Stored, never surfaced |
| `trusted-peers`, `peered-users` | `UserInfo` | Preserved across batch updates but not used for access control or routing |

---

## Functional gaps

### Glob context mapping

Spec defines `subject-glob` (expand Alice's view to Bob's subcontexts) and
`object-glob` (collapse Bob's subcontexts into Alice's context). Neither is
applied during batch computation. Only exact-path matching is used.

This is the most significant missing behaviour for non-trivial context graphs.

### Archive addresses are placeholders

`context-relations-deps-hop/direct-relations-archive-address` is specified as
a compressed archive of all DRs at a hop. In `computeDeps` it is set to the
same CID as the single DR document:

```go
DirectRelationsArchiveAddress: []byte(drCID), // archive = same doc for hop-1
```

For merged hops (hop 2+) the archive address is copied as-is from the upstream
deps hop without building an actual archive. No compression or bundling is done.

### Data seeding / caching

The spec states: _"you seed data you cache, popular data will have more
seeders."_ The implementation fetches and stores data but does not actively
seed it (no IPFS pin or announce calls). On kubo this is partially automatic
(added blocks are retained), but the explicit seeding behaviour described in
the spec is not implemented.

### Batch is sequential, not DAG-based

The spec says: _"Generate a DAG of operations utilising a DAG library."_
`go.mod` imports `github.com/dominikbraun/graph` but it is never used. The
actual batch in `RunBatch` is a sequential loop over users. There is no
parallelism or explicit dependency ordering.

For correctness the sequential approach works (inductive propagation over
rounds), but the DAG design would allow parallel execution per user and explicit
dependency tracking between operations.

### Client-side collect

Spec defines a `collect context` operation — client-side BFS over the
`context-relations-deps` graph to produce the final rendered list of URIs and
users. The algorithm is specified in detail (to-process/processed visited sets).

The CLI (`np-client`) has `init`, `submit`, and `fetch-index` but no `collect`
command. The peer-side batch computes deps correctly; the client-facing traversal
is not yet exposed.

### User/peer discovery

The spec relies on DHT (via IPFS) for locating users' peers. The implementation
uses an in-process `PeerRegistry` (a simple `map[userID]→peerURL`) populated by
direct registration calls in tests. There is no DHT lookup, no bootstrap, and no
mechanism for one peer to discover another peer's users outside of pre-registration.

This is appropriate for the current testing phase but needs to be replaced with
real DHT/IPNS resolution for a deployed network.

### Size limits and weight budgets

The spec describes users watching their own weight, peers enforcing budgets,
and per-relation size visibility. None of this is implemented. The `Size` field
is computed per hop (bytes of DR data) and stored in the index, which is the
right foundation, but no limits are enforced and no warnings are surfaced.

### User registration API

The spec says peers have "homed-users provided on the command-line." The `peer`
binary implements this via `AddHomedUser` called from startup, but there is no
HTTP endpoint for a user to register with or leave a peer at runtime.

---

## Endpoints not in the spec

The implementation adds several HTTP endpoints not described in the spec that
are useful for observability and testing:

| Endpoint | Purpose |
|---|---|
| `GET /status/users` | List all homed users with their index CIDs and context counts |
| `GET /status/users/{userID}` | Status for one user (hex-encoded userID) |
| `GET /cid/{cid}` | Proxy-fetch any CID from IPFS |

The spec only describes `/submit`, `/user/{userID}` (IPNS resolve), and the
implicit IPFS content fetch. The status and CID endpoints should either be
formally added to the spec or gated behind an admin interface.

---

## Implementation extras

| Item | Notes |
|---|---|
| `MemStore` | In-memory IPFS substitute for unit tests; not in spec |
| `PeerRegistry` | In-process peer discovery; stand-in for DHT |
| `RunBatchRounds` (testutil) | Multi-round helper for integration tests |
| `TestNetworkSim` | Continuous simulation test with 4 peers, 20 users, 10 contexts, random mutations |

---

## Priority order for closing gaps

1. **Glob context mapping** — core feature, affects what contexts users can
   meaningfully express.
2. **Client-side collect command** — needed to make the system usable end-to-end.
3. **User/peer discovery** — required for a real multi-operator network.
4. **Archive addresses** — can be phased in; low value until datasets are large.
5. **DAG-based batch** — parallelism improvement; correctness is fine without it.
6. **Size limits / weight budgets** — operator and UX concern, not blocking.
7. **Data seeding** — kubo's implicit retention partially covers this.

_The IPNS field naming issues have been resolved: `context-relations-deps-index-ipns-address`
removed, `direct-relations-ipns-address` renamed to `direct-relations-content-address`._
