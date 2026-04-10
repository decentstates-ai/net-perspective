# Clojure Implementation Plan

A second implementation of the net-perspective peer and user-client in
Clojure, targeting full wire-compatibility with the Go implementation.

---

## Goals

- Single binary (`net-perspective`) with subcommands for both peer and CLI roles:
  ```
  net-perspective peer          # peer server + scheduler
  net-perspective init          # generate key pair
  net-perspective submit        # sign and submit direct-relations
  net-perspective fetch-index   # fetch and display index for a user
  ```
- Wire-compatible with the Go peer: same document field names, same
  multiformat encoding, same ML-DSA-44 signatures, same JCS canonicalization.
- IPFS integration via direct HTTP calls to the kubo RPC API (no Go bindings).

---

## Project layout

```
clojure/
├── deps.edn              # deps + aliases (test, build, uberjar)
├── build.clj             # tools.build uberjar script
├── src/
│   └── net_perspective/
│       ├── main.clj      # entrypoint: subcommand dispatch
│       ├── schema.clj    # Malli schemas, JCS encode/decode, wrap/unwrap envelopes
│       ├── crypto.clj    # ML-DSA-44, key generation, multiformats
│       ├── ipfs/
│       │   └── client.clj    # kubo HTTP API: add, cat, publish, resolve, key-gen
│       └── peer/
│           ├── state.clj     # atom-based server state
│           ├── registry.clj  # peer registry (user-id → peer URL)
│           ├── handler.clj   # Ring HTTP handlers + routes
│           ├── batch.clj     # inductive dep computation
│           └── scheduler.clj # 10-minute batch scheduler
└── test/
    └── net_perspective/
        ├── schema_test.clj
        ├── crypto_test.clj
        └── peer/
            ├── handler_test.clj
            └── cluster_test.clj
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `org.clojure/clojure` | 1.12.0 | Language runtime |
| `org.bouncycastle/bcprov-jdk18on` | 1.79 | Bouncy Castle core provider |
| `org.bouncycastle/bcpqc-jdk18on` | 1.79 | ML-DSA-44 (post-quantum) |
| `io.github.erdtman/java-json-canonicalization` | 1.1 | RFC 8785 JCS |
| `metosin/malli` | 0.16.4 | Schema definitions, validation, coercion |
| `cheshire/cheshire` | 5.13.0 | JSON encode/decode |
| `ring/ring-core` | 1.12.2 | HTTP server abstraction |
| `ring/ring-jetty-adapter` | 1.12.2 | Embedded Jetty |
| `metosin/reitit` | 0.7.2 | HTTP routing (with malli request/response coercion) |
| `clj-http/clj-http` | 3.13.0 | HTTP client (multipart support for IPFS /add) |
| `org.clojure/tools.cli` | 1.1.230 | CLI argument parsing |

---

## Key implementation details

### Malli schemas (`schema.clj`)

All document shapes are defined in a single `net-perspective.schema` namespace
using Malli. This acts as the authoritative description of the wire format and
is used for:

- **Validation** of incoming HTTP request bodies and IPFS-fetched documents
- **Coercion** of JSON-parsed maps (string keys → keyword keys, base64 strings → byte arrays)
- **Generation** of test data via `malli.generator`
- **Reitit integration** — Reitit uses Malli schemas for request/response coercion
  out of the box when `reitit.coercion.malli` is enabled

Rough schema sketch:

```clojure
(def DirectRelation
  [:map
   [:direct-relations-rel/type [:enum "user" "uri"]]
   [:direct-relations-rel-uri/uri {:optional true} :string]
   [:direct-relations-rel-user/user-id {:optional true} bytes?]
   [:direct-relations-rel-user/transitive-depth {:optional true}
    [:int {:min 1 :max 10}]]
   ;; ... etc
   ])

(def DirectRelations
  [:map
   [:direct-relations/direct-relations-version :int]
   [:direct-relations/timestamp-ns :int]
   [:direct-relations/user-id bytes?]
   [:direct-relations/contexts [:vector DirectRelationsContext]]])

(def Envelope
  [:map
   [:envelope/content :any]
   [:envelope/user-id bytes?]
   [:envelope/user-public-key bytes?]
   [:envelope/signature bytes?]])
```

All five spec document types are covered: `Envelope`, `UserInfo`,
`DirectRelations`, `ContextRelationsDeps`, `ContextRelationsDepsIndex`.

Reference: `local-docs/malli.md`

### ML-DSA-44 via Bouncy Castle

Use the low-level Bouncy Castle PQC API directly (more control than JCA):

```clojure
;; Namespaces to interop with:
;; org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters       (.ml_dsa_44)
;; org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
;; org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
;; org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters (.getSeed)
;; org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters  (.getEncoded)
;; org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
;; org.bouncycastle.crypto.SecureRandom (or java.security.SecureRandom)
```

Key sizes:
- Private key stored as 32-byte seed (same as Go)
- Public key: 1312 bytes raw
- Signature: 2420 bytes

The `MLDSAPrivateKeyParameters.getSeed()` returns the 32-byte seed, which is
the persisted form. Reconstruction: generate params from seed via
`MLDSAKeyPairGenerator` deterministically.

### Multiformat encoding (wire-compatible with Go)

```
encoded-public-key = varint(0x1203) ++ raw-public-key-bytes

varint(0x1203 = 4611):
  byte 1: (4611 & 0x7F) | 0x80 = 0x83
  byte 2:  4611 >> 7 = 36 = 0x24
  → [0x83, 0x24]

user-id = multihash(SHA2-256, encoded-public-key)
        = [0x12, 0x20] ++ sha256(encoded-public-key)
```

All user-ids and encoded-public-keys are stored as byte arrays and
serialized to JSON as base64 (standard JSON encoding of `[]byte` in Go
uses base64; Clojure must match — use `java.util.Base64/getEncoder`
without padding, same as Go's `json.Marshal` of `[]byte`).

### JCS canonicalization

```clojure
;; org.erdtman.jcs.JsonCanonicalizer
(defn canonicalize [json-string]
  (.getEncodedUTF8 (JsonCanonicalizer. json-string)))
```

`Marshal` = `(-> data cheshire/generate-string canonicalize)`
`Unmarshal` = `(cheshire/parse-string json-string true)` (keyword keys)

### IPFS HTTP API

All kubo RPC endpoints are `POST`. Base URL configurable (default
`http://localhost:5001`).

| Operation | Endpoint | Notes |
|---|---|---|
| Add content | `POST /api/v0/add` | multipart/form-data, parse `Hash` from JSON response |
| Fetch by CID | `POST /api/v0/cat?arg={cid}` | returns raw bytes |
| Publish IPNS | `POST /api/v0/name/publish?key={name}&arg=/ipfs/{cid}&allow-offline=true` | — |
| Resolve IPNS | `POST /api/v0/name/resolve?arg={addr}` | strip `/ipfs/` prefix from `Path` |
| Generate key | `POST /api/v0/key/gen?arg={name}&type=ed25519` | returns `Id` = IPNS address |
| List keys | `POST /api/v0/key/list` | returns `Keys` array of `{Name, Id}` |

### Peer state

```clojure
;; Single atom per server instance
(def ^:private state
  (atom {:users    {}   ;; base64(user-id) → user-map
         :registry {}})) ;; base64(user-id) → peer-url

;; user-map shape:
{:key-pair        {...}
 :ipns-key-name   "user-abc123"
 :ipns-address    "k51q..."
 :latest-dr       nil       ;; decoded DirectRelations map
 :latest-dr-cid   ""
 :index-cid       ""}
```

Mutations via `swap!` with pure functions — no locks needed for
reads/writes to individual user maps (swap! is atomic on the whole atom).

### Scheduler

Use a simple loop on a dedicated thread:

```clojure
(defn run-scheduler [ipfs-client state-atom registry stop-ch]
  (future
    (loop []
      (try (run-batch! ipfs-client state-atom registry)
           (catch Exception e (log/error e "batch error")))
      (when-not (realized? stop-ch)
        (Thread/sleep (* 10 60 1000))
        (recur)))))
```

Cancel-replace semantics: keep a reference to the running batch future;
on new tick, `future-cancel` the old one before starting a new batch.

### JSON byte array encoding

Go's `encoding/json` encodes `[]byte` as base64 (standard, padded).
Clojure must match: use `java.util.Base64/getEncoder` (standard, padded)
for byte arrays going into JSON, and the decoder for reading. Cheshire
handles this via a custom encoder for `(Class/forName "[B")`.

---

## Phases

### Phase 1 — Project skeleton + schema/crypto layer

- `clojure/deps.edn`, `clojure/build.clj`
- `schema.clj`: Malli schemas for all 5 document types; JCS marshal/unmarshal;
  wrap/unwrap envelope logic; validation and coercion helpers
- `crypto.clj`: ML-DSA-44 key-gen, encode-public-key, compute-user-id, sign,
  verify, key-pair-from-seed
- Tests: crypto roundtrip, envelope roundtrip, tamper detection, schema validation

### Phase 2 — IPFS HTTP client

- `ipfs/client.clj`: add, cat, publish-ipns, resolve-ipns, key-gen (idempotent)
- Shared test helper to start a real kubo daemon (mirrors Go's `testutil/daemon.go`)

### Phase 3 — Peer server

- `peer/state.clj`: atom helpers (add-user, get-user, all-users, set-index-cid)
- `peer/registry.clj`: register, lookup
- `peer/handler.clj`: all 5 routes (submit, user, cid, status/users, status/users/id)
- Unit tests with in-memory fake IPFS (map-based, mirrors Go's MemStore)

### Phase 4 — Batch computation

- `peer/batch.clj`: run-batch!, process-user!, compute-deps, fetch-transitive-deps,
  fetch-user-context-deps (local + remote)
- `peer/scheduler.clj`: run-scheduler, stop-scheduler
- Integration tests: local chain, cross-peer hop, depth cap

### Phase 5 — CLI entrypoint

- `main.clj`: subcommand dispatch (peer / init / submit / fetch-index)
- `peer` subcommand: start server + scheduler, graceful shutdown on SIGTERM
- `init`: generate key pair, write seed to file, write empty DR template
- `submit`: read DR file, sign, POST to peers
- `fetch-index`: IPNS resolve → user-info → `/status` → index CID → print

### Phase 6 — Uberjar + flake integration

- `build.clj`: `clojure -T:build uber` produces a self-contained jar
- `flake.nix`: add `packages.net-perspective-clj` via `pkgs.clojure` + `fetchMavenArtifact`
  or simply expose the jar in devShell

---

## Build and run

```bash
# development REPL
cd clojure && clojure -M:repl

# run tests
clojure -M:test

# build uberjar
clojure -T:build uber

# run
java -jar target/net-perspective.jar peer --ipfs localhost:5001 --listen :8080
java -jar target/net-perspective.jar init
java -jar target/net-perspective.jar submit --peers localhost:8080
java -jar target/net-perspective.jar fetch-index k51q... --peer localhost:8080
```

---

## Wire compatibility checklist

Before any cross-implementation test:

- [ ] byte-array JSON encoding matches (Go: standard base64 with padding)
- [ ] varint codec prefix for public key (bytes `[0x83, 0x24]`)
- [ ] multihash prefix for user-id (bytes `[0x12, 0x20]`)
- [ ] JCS output identical for the same logical document
- [ ] ML-DSA-44 signatures cross-verify (Go signs → Clojure verifies and vice versa)
- [ ] All JSON field names verbatim from spec
