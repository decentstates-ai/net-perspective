#!/usr/bin/env bash
# Wire-compatibility test between the Go and Clojure implementations.
#
# Run from within `nix develop` at the repo root:
#   bash scripts/cross-compat.sh
#
# Requires: go, clojure on PATH (provided by nix develop).

set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

GO_XCOMPAT="$REPO/go/cmd/cross-compat/main.go"
CLJ_DIR="$REPO/clojure"

run_clj() {
  (cd "$CLJ_DIR" && clojure -M -i "$1")
}

echo "=== Cross-compatibility test ==="
echo ""

# ---------------------------------------------------------------------------
echo "--- Envelope: Go produces, Clojure verifies ---"
FIXTURE_GO="$TMPDIR/go-fixture.json"
(cd "$REPO/go" && go run "$GO_XCOMPAT" produce) > "$FIXTURE_GO"
echo "Go produced fixture ($(wc -c < "$FIXTURE_GO") bytes)"
run_clj "$REPO/scripts/clj_verify_envelope.clj" < "$FIXTURE_GO"

echo ""
echo "--- Envelope: Clojure produces, Go verifies ---"
FIXTURE_CLJ="$TMPDIR/clj-fixture.json"
run_clj "$REPO/scripts/clj_produce_envelope.clj" > "$FIXTURE_CLJ"
echo "Clojure produced fixture ($(wc -c < "$FIXTURE_CLJ") bytes)"
(cd "$REPO/go" && go run "$GO_XCOMPAT" verify) < "$FIXTURE_CLJ"

echo ""
echo "--- Seed: Go seed → Clojure reconstruct → compare public key ---"
SEED_FILE="$TMPDIR/seed.b64"
# Generate a Go key pair, extract the seed via a tiny Go snippet, verify Clojure derives the same pubkey.
(cd "$REPO/go" && go run "$GO_XCOMPAT" produce) > "$FIXTURE_GO"
# Extract encoded_public_key and seed separately from Go
python3 -c "
import json, base64, sys
fix = json.load(open('$FIXTURE_GO'))
print(fix['encoded_public_key'])   # line 1: expected encoded pubkey
" > "$TMPDIR/expected_pub.txt"

# Now: produce a fresh pair in Clojure, extract seed, round-trip through Go
FIXTURE_CLJ2="$TMPDIR/clj-fixture2.json"
run_clj "$REPO/scripts/clj_produce_envelope.clj" > "$FIXTURE_CLJ2"
python3 -c "
import json, base64, sys
fix = json.load(open('$FIXTURE_CLJ2'))
env = fix['envelope']
# encoded_public_key is in the fixture
print(fix['encoded_public_key'])  # expected
" > "$TMPDIR/clj_expected_pub.txt"

# Extract seed from Clojure-produced fixture and round-trip through Go
run_clj "$REPO/scripts/clj_produce_seed.clj" > "$SEED_FILE"
SEED=$(cat "$SEED_FILE")
GO_PUB=$(echo "$SEED" | (cd "$REPO/go" && go run "$GO_XCOMPAT" seed-roundtrip))
CLJ_PUB=$(echo "$SEED" | run_clj "$REPO/scripts/clj_seed_roundtrip.clj" | tr -d '\n')

if [ "$GO_PUB" = "$CLJ_PUB" ]; then
  echo "ok: Go and Clojure derive identical public key from same 32-byte seed"
  echo "    (pubkey b64 prefix: ${GO_PUB:0:40}...)"
else
  echo "FAIL: public key mismatch from same seed"
  echo "  Go:     $GO_PUB"
  echo "  Clojure: $CLJ_PUB"
  exit 1
fi

echo ""
echo "=== All cross-compat checks passed ==="
