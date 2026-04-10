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

run_clojure_script() {
  local script="$1"
  shift
  (cd "$CLJ_DIR" && clojure -M -i "$script" "$@")
}

echo "=== Cross-compatibility test ==="
echo ""

# ---------------------------------------------------------------------------
echo "--- Direction 1: Go produces, Clojure verifies ---"
FIXTURE_GO="$TMPDIR/go-fixture.json"
(cd "$REPO/go" && go run "$GO_XCOMPAT" produce) > "$FIXTURE_GO"
echo "Go produced fixture ($(wc -c < "$FIXTURE_GO") bytes)"

run_clojure_script "$REPO/scripts/clj_verify_envelope.clj" < "$FIXTURE_GO"

# ---------------------------------------------------------------------------
echo ""
echo "--- Direction 2: Clojure produces, Go verifies ---"
FIXTURE_CLJ="$TMPDIR/clj-fixture.json"

run_clojure_script "$REPO/scripts/clj_produce_envelope.clj" > "$FIXTURE_CLJ"
echo "Clojure produced fixture ($(wc -c < "$FIXTURE_CLJ") bytes)"

(cd "$REPO/go" && go run "$GO_XCOMPAT" verify) < "$FIXTURE_CLJ"

echo ""
echo "=== All cross-compat checks passed ==="
