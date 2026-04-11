# Implementation Plan v1

## Scope

Initial implementation of the net-perspective peer and user-client as described in spec.md.
IPFS/IPNS as transport. Peer in Go, user-client in TypeScript/React.

## Phases

### Phase 1: Core data layer (Go)

Define all document types as Go structs with RFC 8785 canonical JSON encoding.
Implement signing and verification using ML-DSA-44 via the relevant Go library.
No IPFS yet — just the data model and crypto.

Documents:
- envelope
- user-info
- direct-relations
- context-relations-deps
- context-relations-deps-index

Deliverables:
- `pkg/doc` — document types, encoding, signing, verification
- `pkg/doc` — unit tests covering round-trip encode/decode and signature verification

### Phase 2: IPFS integration (Go)

Wire the document layer to IPFS (kubo client or go-ipfs-api).
IPNS key management: one IPNS key per homed user.

Operations:
- Store document → get CID
- Resolve IPNS → CID
- Publish CID → IPNS

Deliverables:
- `pkg/ipfs` — thin wrapper around IPFS/IPNS operations

### Phase 3: Peer server (Go)

HTTP server for user-client requests. State: homed users, their direct-relations and user-info.

Actions:
- `POST /submit` — accept signed user-info + direct-relations, store to IPFS, update IPNS
- `GET /user-info/:user-id` — return cached user-info (fetch from IPFS if needed)
- `GET /direct-relations/:cid` — proxy/cache direct-relations fetch

Batch update job (every 10m):
1. For each homed user, for each context:
   a. Fetch dependency context-relations-deps from related users' peers
   b. Cache and seed fetched content
   c. Compute context-relations-deps inductively (pure function given data)
   d. Store computed context-relations-deps to IPFS
2. Compute context-relations-deps-index per user
3. Store indexes to IPFS
4. Publish user-info with updated index IPNS addresses (atomic commit)

Use a DAG job library (e.g. `github.com/dominikbraun/graph` or similar) to express the dependency graph. Cancel previous run on new tick.

Deliverables:
- `cmd/peer` — peer binary
- `pkg/peer` — batch update logic, job DAG, HTTP handlers

### Phase 4: User-client CLI (Go)

Thin CLI for key management and submitting direct-relations to a peer.

Commands:
- `init` — generate key pair, create empty direct-relations
- `submit` — sign and submit direct-relations to configured peer(s)
- `fetch-index <user-id>` — fetch and display context-relations-deps-index for a user

Deliverables:
- `cmd/client` — client binary

### Phase 5: User-client UI (TypeScript/React)

Plain React, no router, no state management lib. CSS via picnic.css. State in URL.

Views:
- Top bar: share user-id link, link to own index
- User Index: list of contexts, click to expand, context menu showing subcontexts with sizes
- User Context: list of users and URIs, search filter, add to direct-relations, click user → User Index, click user-context → User Context
- Export: as map, as vouch, as link-tree

Deliverables:
- `ui/` — React app, communicates with peer HTTP API

### Phase 6: Nix packaging

- Nix flake outputs for peer binary and UI static build
- Integration tests (follow pattern from existing nix-integration-test branch)

## Key decisions recorded

- Conflict resolution: latest timestamp-ns wins
- Batch update atomicity: user-info publish is the final step; cancelled runs leave prior state intact
- transitive-depth capped at 10
- subject-glob / object-glob are depth integers (0 = no glob)
- collect (rendering a context view) is a client-side scan, no peer request
- ML-DSA-44 is the assumed signature scheme (PQ baseline)
- contact-* fields and direct-relations/user-info merge deferred
- Key revocation deferred
