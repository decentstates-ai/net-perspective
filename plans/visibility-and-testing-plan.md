# Visibility and Testing Plan

A plan for running a realistic multi-peer, multi-user network and being able to
see what is happening inside it.

---

## What the current code is missing

Before any realistic testing is possible, several things need to be added:

### 1. Cross-peer dep fetching (critical gap)

`fetchUserContextDeps` in batch.go returns empty when a related user is not
homed on the same peer. This is the core of the distributed protocol and it
doesn't work yet. To fix it, a peer must:

1. Resolve the related user's IPNS address to get their user-info.
2. Read `user-info/peered-users` to find the peer(s) that home that user and
   their index addresses.
3. Fetch the relevant `context-relations-deps` from those peers via CID.

This requires peers to either know each other's HTTP addresses, or discover
them through the user-info's `peered-users` map. The simplest approach for now:
include the peering peer's HTTP address inside the `PeeredUser` entry (as an
additional field, or as the index address prefix). This is a spec-level decision
to make before implementing.

**Alternative for testing only**: a peer registry that maps user-id → peer HTTP
address, shared by all peers in the test cluster. This lets us test the inductive
computation without solving peer discovery yet.

### 2. Peer status/inspection API

No way to see what a peer is doing from the outside. Needed:

- `GET /status` — summary: uptime, homed user count, last batch run time and
  duration, error count.
- `GET /status/users` — per-user state: user-id (hex), context count, current
  index CID, direct-relations CID, last submitted timestamp.
- `GET /status/users/{userID}/index` — full decoded index document for a user.

These are read-only and should expose the internal `Server` state directly.

### 3. Structured logging

`log.Printf` is unusable in a multi-peer environment. Replace with `slog`
(stdlib since Go 1.21, and we have 1.25):

- Each log line includes: peer-id (first 8 hex chars of the peer's user-id),
  user-id when relevant, context-path, operation, duration, error.
- JSON output mode for log aggregation.
- Log each batch run's start, end, duration, and per-user outcomes.
- Log each submit: user-id, timestamp, accept/reject reason.

### 4. Peer registry (for the test environment)

A lightweight in-memory (or file-backed) registry: `user-id → peer HTTP address`.
Peers register their homed users into it at startup; the batch update consults
it to find where to fetch remote users' deps. This is not the production
discovery mechanism (that's IPNS + peered-users) but it's sufficient for a
controlled test cluster.

---

## Test topology

### Peers

3 peer servers: **peer-a**, **peer-b**, **peer-c**.
Each runs its own kubo IPFS node (or MemStore for in-process tests).

### Users (20 total)

Distributed across peers:

| User | Homed at | Notes |
|------|----------|-------|
| alice | peer-a | hub: many relations |
| bob | peer-a | leaf |
| carol | peer-b | hub |
| dave | peer-b | leaf |
| eve | peer-b | leaf |
| frank | peer-c | cross-peer target |
| ... 14 more | mixed | various |

### Graph topology

Designed to exercise:

- **Local transitive chain**: alice → bob → (bob has no relations) — depth 1 only
- **Cross-peer hop**: alice → carol (on peer-b), carol → frank (on peer-c) — tests
  cross-peer fetch at hop 2 and 3
- **Diamond**: alice → bob, alice → carol; bob → eve; carol → eve — eve should
  appear in alice's deps once, not twice
- **Deep chain**: user-1 → user-2 → … → user-5 at depth 5, to validate hop limit
- **Context isolation**: alice relates to carol under `food` but not `news` —
  batch should not bleed contexts
- **Glob**: one user with subject-glob=1 to validate subcategory expansion

### Contexts used

- `food`
- `food/local`
- `news`
- `news/tech`
- `code`

---

## Two-layer test strategy

### Layer 1: In-process multi-peer test (fast, automated)

Use a shared `MemStore` and multiple `Server` instances wired to a simple
in-process peer registry. No network, no IPFS daemon. Good for CI.

Structure:
```
testutil/cluster.go  — spin up N Server instances sharing a MemStore and registry
testutil/scenario.go — generate users with a given relation graph
```

Scenarios as Go table tests:
- `TestLocalChain`: two users on same peer, hop 1 and 2
- `TestCrossPeerHop`: user on peer-a relates to user on peer-b, verify hop 2
- `TestDiamond`: verify deduplication of deps
- `TestDepthCap`: verify hop 11 is never included
- `TestContextIsolation`: verify dep leakage between contexts doesn't happen
- `TestCancelledBatchLeavesOldState`: cancel batch mid-run, old index is intact
- `TestStaleSubmissionRejected`: already covered, keep

### Layer 2: Docker-compose integration (realistic, run manually or in CI)

Each peer runs as a container:
```
peer-a:
  - np-peer binary
  - kubo sidecar on port 5001 (internal)
peer-b: same
peer-c: same
```

Plus:
- A `seed` container that runs the user-client CLI against each peer to submit
  the initial direct-relations, then exits.
- A `checker` container that polls each peer's `/status` every 30s, logs the
  results, and asserts invariants after each batch cycle.

docker-compose also exposes each peer's `/status` on different host ports so you
can curl them during a run.

`checker` assertions (after at least one batch run):
- Each peer's homed users have a non-empty index.
- Cross-peer transitive deps exist at the correct hops.
- No context leakage.
- Index sizes are non-zero and plausible.
- Batch run duration < 30s for 20 users.

---

## Observability during a run

With the status API and structured logging in place:

**Terminal view** (two windows):
```
# Watch peer-a status
watch -n 5 'curl -s localhost:8080/status/users | jq .'

# Follow logs (all peers via docker-compose)
docker compose logs -f | jq -r '[.peer, .user, .op, .duration_ms] | @tsv'
```

**Between batch runs**: compare index CIDs across two runs to confirm
re-computation produces stable results for unchanged direct-relations.

**After adding a relation**: submit a new direct-relations, wait for the next
batch run, verify the new dep appears in the index at the right hop.

---

## Implementation order

1. **Add `GET /status` and `GET /status/users`** — immediate visibility, no
   other changes needed.

2. **Replace `log.Printf` with `slog`** — commit separately, small diff.

3. **Implement peer registry** — in-process struct + optional HTTP endpoint for
   the docker-compose case; peers register on startup.

4. **Implement cross-peer dep fetching** — the real `fetchUserContextDeps`
   that queries the registry and fetches via CID from the remote peer's HTTP API.

5. **Write Layer 1 in-process cluster tests** — `testutil/cluster.go` and the
   scenario table tests.

6. **Write docker-compose setup** — `docker/` directory, seed and checker scripts.

---

## Open questions before implementation

- **How does a peer expose its HTTP address for remote fetching?** Options:
  a. Add a `peer-http-address` field to `PeeredUser` in user-info (spec change).
  b. Use the peer registry only for the test environment; production uses IPNS.
  c. Out-of-band configuration (each peer knows the others' addresses from config).
  Option (c) is simplest for now and avoids a spec change.

- **Should the peer registry be a separate HTTP service or embedded?** For the
  test environment, embedding it in one of the peers (or making it a tiny
  separate binary) is fine.

- **Deduplication of deps across hops**: resolved. The peer emits CIDs into hop
  structures without deduplication — that is not its job. Content addressing
  ensures the same document always has the same CID. The client's collect
  algorithm uses a `to-process` / `processed` visited-set (BFS over the CID
  graph); a CID reached via multiple paths (diamond) is simply skipped on the
  second encounter. No batch-side change needed.
