package peer_test

// TestNetworkSim is a continuous-simulation test:
//
//   - 4 peers, 20 users distributed round-robin across them.
//   - Each user publishes URI content and follows random other users in 2–6
//     randomly assigned topic contexts.
//   - After every batch round the network state is printed: per-user hop depth,
//     reachable DR count, context coverage bar chart, global CID set size.
//   - Between rounds ~25% of users are "mutated" — they add new follows or new
//     contexts, simulating a live network that evolves over time.
//
// Run with -v to see per-round tables.
// Control the number of rounds with SIM_ROUNDS=N (default 10; 3 with -short).

import (
	"fmt"
	"math/rand"
	"os"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/testutil"
)

var simTopics = [][]string{
	{"food"}, {"news"}, {"music"}, {"sports"}, {"tech"},
	{"science"}, {"art"}, {"travel"}, {"health"}, {"finance"},
}

func TestNetworkSim(t *testing.T) {
	const (
		numPeers = 4
		numUsers = 20
	)
	numRounds := 10
	if testing.Short() {
		numRounds = 3
	}
	if v := os.Getenv("SIM_ROUNDS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			numRounds = n
		}
	}

	seed := time.Now().UnixNano()
	rng := rand.New(rand.NewSource(seed))
	t.Logf("seed=%d   re-run: SIM_ROUNDS=%d go test ./pkg/peer/... -v -run TestNetworkSim", seed, numRounds)

	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, numPeers)

	users := make([]*testutil.UserFixture, numUsers)
	for i := range users {
		users[i] = c.AddUser(t, i%numPeers)
	}

	// Initial submissions: each user picks 2–6 contexts, posts URIs + random follows.
	drs := make([]doc.DirectRelations, numUsers)
	for i := range users {
		drs[i] = simBuildDR(rng, i, users, nil)
		users[i].Submit(t, drs[i])
	}
	t.Logf("topology: %d users across %d peers, %d topic contexts", numUsers, numPeers, len(simTopics))
	simPrintTopology(t, users, drs)

	for round := 1; round <= numRounds; round++ {
		header := fmt.Sprintf("ROUND %d/%d", round, numRounds)
		bar := strings.Repeat("━", 64)
		t.Logf("\n%s\n%s\n%s", bar, header, bar)
		c.RunAllBatches(t)
		simPrintNetworkStats(t, c, users)

		if round < numRounds {
			simMutate(t, rng, users, drs)
		}
	}
}

// simBuildDR builds a fresh direct-relations document for user selfIdx.
// existing is the current DR (may be nil for first build).
func simBuildDR(rng *rand.Rand, selfIdx int, users []*testutil.UserFixture, existing *doc.DirectRelations) doc.DirectRelations {
	numCtx := 2 + rng.Intn(5) // 2–6
	if numCtx > len(simTopics) {
		numCtx = len(simTopics)
	}

	var contexts []doc.DirectRelationsContext
	for _, ci := range rng.Perm(len(simTopics))[:numCtx] {
		path := simTopics[ci]
		rels := []doc.DirectRelation{
			{Type: "uri", URI: fmt.Sprintf("https://u%d.example.com/%s", selfIdx, path[0])},
		}
		numFollows := 2 + rng.Intn(4) // 2–5 follows per context
		for _, ti := range rng.Perm(len(users)) {
			if ti == selfIdx {
				continue
			}
			rels = append(rels, doc.DirectRelation{
				Type:            "user",
				RelUserID:       users[ti].KP.UserID,
				TransitiveDepth: 2 + rng.Intn(4), // 2–5
			})
			numFollows--
			if numFollows == 0 {
				break
			}
		}
		contexts = append(contexts, doc.DirectRelationsContext{
			ContextPath: path,
			Relations:   rels,
		})
	}
	return doc.DirectRelations{
		Version:     1,
		TimestampNs: time.Now().UnixNano(),
		Contexts:    contexts,
	}
}

// simMutate picks ~25% of users at random and applies one of three mutations:
// add a new follow in an existing context, add a new context, or increase a depth.
func simMutate(t *testing.T, rng *rand.Rand, users []*testutil.UserFixture, drs []doc.DirectRelations) {
	n := 1 + len(users)/4
	targets := rng.Perm(len(users))[:n]
	mutDesc := make([]string, 0, n)

	for _, i := range targets {
		dr := &drs[i]
		action := rng.Intn(3)

		switch action {
		case 0: // add a new follow in a random existing context
			if len(dr.Contexts) == 0 {
				continue
			}
			ci := rng.Intn(len(dr.Contexts))
			ti := rng.Intn(len(users))
			for ti == i {
				ti = rng.Intn(len(users))
			}
			dr.Contexts[ci].Relations = append(dr.Contexts[ci].Relations, doc.DirectRelation{
				Type:            "user",
				RelUserID:       users[ti].KP.UserID,
				TransitiveDepth: 2 + rng.Intn(4),
			})
			mutDesc = append(mutDesc, fmt.Sprintf("u%d +follow→u%d in %s",
				i, ti, strings.Join(dr.Contexts[ci].ContextPath, "/")))

		case 1: // add a new context if there's room
			if len(dr.Contexts) >= len(simTopics) {
				continue
			}
			existing := make(map[string]bool)
			for _, c := range dr.Contexts {
				existing[strings.Join(c.ContextPath, "/")] = true
			}
			var candidates [][]string
			for _, p := range simTopics {
				if !existing[strings.Join(p, "/")] {
					candidates = append(candidates, p)
				}
			}
			if len(candidates) == 0 {
				continue
			}
			path := candidates[rng.Intn(len(candidates))]
			ti := rng.Intn(len(users))
			for ti == i {
				ti = rng.Intn(len(users))
			}
			dr.Contexts = append(dr.Contexts, doc.DirectRelationsContext{
				ContextPath: path,
				Relations: []doc.DirectRelation{
					{Type: "uri", URI: fmt.Sprintf("https://u%d.example.com/%s", i, path[0])},
					{Type: "user", RelUserID: users[ti].KP.UserID, TransitiveDepth: 2 + rng.Intn(4)},
				},
			})
			mutDesc = append(mutDesc, fmt.Sprintf("u%d +ctx:%s", i, strings.Join(path, "/")))

		case 2: // bump a transitive depth on a random follow
			if len(dr.Contexts) == 0 {
				continue
			}
			ci := rng.Intn(len(dr.Contexts))
			ctx := &dr.Contexts[ci]
			userRels := []int{}
			for ri, r := range ctx.Relations {
				if r.Type == "user" {
					userRels = append(userRels, ri)
				}
			}
			if len(userRels) == 0 {
				continue
			}
			ri := userRels[rng.Intn(len(userRels))]
			old := ctx.Relations[ri].TransitiveDepth
			ctx.Relations[ri].TransitiveDepth = min(old+1, 10)
			mutDesc = append(mutDesc, fmt.Sprintf("u%d depth %d→%d in %s",
				i, old, ctx.Relations[ri].TransitiveDepth, strings.Join(ctx.ContextPath, "/")))
		}

		dr.TimestampNs = time.Now().UnixNano()
		users[i].Submit(t, *dr)
	}

	if len(mutDesc) > 0 {
		t.Logf("mutations: %s", strings.Join(mutDesc, " | "))
	}
}

// simPrintTopology logs the initial follow graph in compact form.
func simPrintTopology(t *testing.T, users []*testutil.UserFixture, drs []doc.DirectRelations) {
	var sb strings.Builder
	fmt.Fprintf(&sb, "\nInitial topology:\n")
	for i, dr := range drs {
		var ctxLines []string
		for _, c := range dr.Contexts {
			var follows []string
			for _, r := range c.Relations {
				if r.Type == "user" {
					// find user index
					for ui, u := range users {
						if string(u.KP.UserID) == string(r.RelUserID) {
							follows = append(follows, fmt.Sprintf("u%d(d%d)", ui, r.TransitiveDepth))
							break
						}
					}
				}
			}
			ctxLines = append(ctxLines, fmt.Sprintf("%s→[%s]", strings.Join(c.ContextPath, "/"), strings.Join(follows, ",")))
		}
		fmt.Fprintf(&sb, "  u%-2d  %s\n", i, strings.Join(ctxLines, "  "))
	}
	t.Log(sb.String())
}

// simPrintNetworkStats prints a per-user table and context coverage chart.
func simPrintNetworkStats(t *testing.T, c *testutil.Cluster, users []*testutil.UserFixture) {
	type row struct {
		i        int
		peerIdx  int
		idHex    string
		indexed  bool
		ctxCount int
		maxHop   int
		totalDRs int
	}

	ctxCoverage := make(map[string]int)
	globalCIDs := make(map[string]struct{})
	rows := make([]row, len(users))

	for i, u := range users {
		peerIdx := simPeerIndex(c, u)
		r := row{i: i, peerIdx: peerIdx, idHex: fmt.Sprintf("%x", u.KP.UserID[:4])}

		index, ok := u.TryFetchIndex(t)
		if !ok {
			rows[i] = r
			continue
		}
		r.indexed = true
		r.ctxCount = len(index.Contexts)

		for _, entry := range index.Contexts {
			key := strings.Join(entry.ContextPath, "/")
			deps, ok := u.TryFetchDepsByCID(t, string(entry.ContextRelsDepsAddress))
			if !ok {
				continue
			}
			ctxCoverage[key]++
			for _, h := range deps.Hops {
				for _, addr := range h.DirectRelationsAddresses {
					globalCIDs[string(addr)] = struct{}{}
				}
				if h.Hop > r.maxHop {
					r.maxHop = h.Hop
				}
				r.totalDRs += len(h.DirectRelationsAddresses)
			}
		}
		rows[i] = r
	}

	var sb strings.Builder
	fmt.Fprintf(&sb, "\n%-6s  %-4s  %-8s  %-4s  %-7s  %s\n",
		"USER", "PEER", "ID", "CTX", "MAX-HOP", "DRS-SEEN")
	fmt.Fprintf(&sb, "%s\n", strings.Repeat("─", 48))
	for _, r := range rows {
		if r.indexed {
			fmt.Fprintf(&sb, "u%-5d  p%-3d  %s  %-4d  %-7d  %d\n",
				r.i, r.peerIdx, r.idHex, r.ctxCount, r.maxHop, r.totalDRs)
		} else {
			fmt.Fprintf(&sb, "u%-5d  p%-3d  %s  (pending)\n", r.i, r.peerIdx, r.idHex)
		}
	}

	// Context coverage bar chart.
	var ctxKeys []string
	for k := range ctxCoverage {
		ctxKeys = append(ctxKeys, k)
	}
	sort.Strings(ctxKeys)
	if len(ctxKeys) > 0 {
		fmt.Fprintf(&sb, "\nContext coverage:\n")
		for _, k := range ctxKeys {
			n := ctxCoverage[k]
			bar := strings.Repeat("█", n)
			pad := strings.Repeat("░", len(users)-n)
			fmt.Fprintf(&sb, "  %-10s %s%s %2d/%d\n", k, bar, pad, n, len(users))
		}
	}

	indexedCount := 0
	for _, r := range rows {
		if r.indexed {
			indexedCount++
		}
	}
	fmt.Fprintf(&sb, "\n%d/%d users indexed | %d unique DR-CIDs reachable across network\n",
		indexedCount, len(users), len(globalCIDs))

	t.Log(sb.String())
}

func simPeerIndex(c *testutil.Cluster, u *testutil.UserFixture) int {
	for i, pf := range c.Peers {
		if pf == u.Home {
			return i
		}
	}
	return -1
}
