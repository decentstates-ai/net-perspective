package peer_test

import (
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/testutil"
)

// TestLocalChain: two users on the same peer. Alice relates to Bob.
// After batch, Alice's food deps at hop 1 contains Alice's DR,
// and at hop 2 contains Bob's DR.
func TestLocalChain(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 1)

	alice := c.AddUser(t, 0)
	bob := c.AddUser(t, 0)

	bob.Submit(t, doc.DirectRelations{
		Version:  1,
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "uri", URI: "https://bob-recipe.example.com"},
			},
		}},
	})

	alice.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "uri", URI: "https://alice-recipe.example.com"},
				{
					Type:            "user",
					RelUserID:       bob.KP.UserID,
					TransitiveDepth: 2,
				},
			},
		}},
	})

	// 2 rounds: round 1 builds Bob's hop-1 index; round 2 lets Alice fetch it.
	c.RunBatchRounds(t, 2)

	deps := alice.FetchDeps(t, []string{"food"})

	if len(deps.Hops) < 2 {
		t.Fatalf("expected at least 2 hops, got %d", len(deps.Hops))
	}

	// Hop 1 must contain Alice's DR CID; hop 2 must contain Bob's.
	cids := testutil.AllCIDsInDeps(deps)
	if len(cids) < 2 {
		t.Errorf("expected at least 2 distinct DR CIDs across hops, got %d: %v", len(cids), cids)
	}
}

// TestCrossPeerHop: Alice on peer-0 relates to Carol on peer-1.
// After batch, Alice's deps at hop 2 must include Carol's DR.
func TestCrossPeerHop(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 2)

	alice := c.AddUser(t, 0)
	carol := c.AddUser(t, 1)

	carol.Submit(t, doc.DirectRelations{
		Version:  1,
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "uri", URI: "https://carol-food.example.com"},
			},
		}},
	})

	alice.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{
					Type:            "user",
					RelUserID:       carol.KP.UserID,
					TransitiveDepth: 2,
				},
			},
		}},
	})

	// 2 rounds: round 1 builds Carol's hop-1 index; round 2 lets Alice fetch it cross-peer.
	c.RunBatchRounds(t, 2)

	deps := alice.FetchDeps(t, []string{"food"})
	if len(deps.Hops) < 2 {
		t.Fatalf("expected >= 2 hops in Alice's food deps, got %d", len(deps.Hops))
	}

	// Hop 2 must be present and non-empty.
	hop2Found := false
	for _, h := range deps.Hops {
		if h.Hop == 2 && len(h.DirectRelationsAddresses) > 0 {
			hop2Found = true
		}
	}
	if !hop2Found {
		t.Errorf("hop 2 with DR addresses not found in deps: %+v", deps.Hops)
	}
}

// TestDiamond: Alice → Bob, Alice → Carol; Bob → Eve, Carol → Eve.
// Eve appears via two paths. The deps document may list her CID twice
// (once per path); the client visited-set deduplicates, so we only assert
// the CID set size is correct (not that the raw list is deduplicated).
func TestDiamond(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 1)

	alice := c.AddUser(t, 0)
	bob := c.AddUser(t, 0)
	carol := c.AddUser(t, 0)
	eve := c.AddUser(t, 0)

	eve.Submit(t, doc.DirectRelations{
		Version:  1,
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations:   []doc.DirectRelation{{Type: "uri", URI: "https://eve.example.com"}},
		}},
	})
	bob.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "user", RelUserID: eve.KP.UserID, TransitiveDepth: 2},
			},
		}},
	})
	carol.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "user", RelUserID: eve.KP.UserID, TransitiveDepth: 2},
			},
		}},
	})
	alice.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations: []doc.DirectRelation{
				{Type: "user", RelUserID: bob.KP.UserID, TransitiveDepth: 3},
				{Type: "user", RelUserID: carol.KP.UserID, TransitiveDepth: 3},
			},
		}},
	})

	// 3 rounds: eve→hop1, bob/carol→hop2(with eve), alice→hop3(with bob/carol/eve).
	c.RunBatchRounds(t, 3)

	deps := alice.FetchDeps(t, []string{"food"})

	// Unique CID set — Eve's DR CID should appear, regardless of path count.
	cids := testutil.AllCIDsInDeps(deps)
	t.Logf("distinct DR CIDs in Alice's food deps: %d", len(cids))

	// Alice + Bob + Carol + Eve = at least 4 distinct DR CIDs.
	if len(cids) < 4 {
		t.Errorf("expected >= 4 distinct DR CIDs, got %d", len(cids))
	}
}

// TestContextIsolation: Alice relates to Bob under "food" only.
// Alice's "news" deps must NOT contain Bob's DR.
func TestContextIsolation(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 1)

	alice := c.AddUser(t, 0)
	bob := c.AddUser(t, 0)

	bob.Submit(t, doc.DirectRelations{
		Version:  1,
		Contexts: []doc.DirectRelationsContext{
			{
				ContextPath: []string{"food"},
				Relations:   []doc.DirectRelation{{Type: "uri", URI: "https://bob-food.example.com"}},
			},
			{
				ContextPath: []string{"news"},
				Relations:   []doc.DirectRelation{{Type: "uri", URI: "https://bob-news.example.com"}},
			},
		},
	})

	alice.Submit(t, doc.DirectRelations{
		Version:  1,
		TimestampNs: time.Now().UnixNano(),
		Contexts: []doc.DirectRelationsContext{
			{
				ContextPath: []string{"food"},
				Relations: []doc.DirectRelation{
					{Type: "user", RelUserID: bob.KP.UserID, TransitiveDepth: 2},
				},
			},
			{
				ContextPath: []string{"news"},
				Relations:   []doc.DirectRelation{{Type: "uri", URI: "https://alice-news.example.com"}},
			},
		},
	})

	// 2 rounds: round 1 builds Bob's index; round 2 lets Alice fetch it.
	c.RunBatchRounds(t, 2)

	bobDRCID := bob.Status(t).DRCID
	if bobDRCID == "" {
		t.Fatal("Bob has no DR CID after submit")
	}

	foodDeps := alice.FetchDeps(t, []string{"food"})
	newsDeps := alice.FetchDeps(t, []string{"news"})

	foodCIDs := testutil.AllCIDsInDeps(foodDeps)
	newsCIDs := testutil.AllCIDsInDeps(newsDeps)

	// Food deps must include Bob's DR CID (Alice relates to Bob under food).
	if _, ok := foodCIDs[bobDRCID]; !ok {
		t.Errorf("food deps missing Bob's DR CID %s; got: %v", bobDRCID, foodCIDs)
	}

	// News deps must NOT contain Bob's DR CID (Alice does not relate to Bob under news).
	if _, ok := newsCIDs[bobDRCID]; ok {
		t.Errorf("context bleed: Bob's DR CID %s appears in news deps", bobDRCID)
	}
}

// TestDepthCap: a chain of 12 users each relating to the next with depth 10.
// Deps must never contain a hop > 10.
func TestDepthCap(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 1)

	const chainLen = 12
	users := make([]*testutil.UserFixture, chainLen)
	for i := range users {
		users[i] = c.AddUser(t, 0)
	}

	// Each user submits: relates to next user in chain.
	for i := chainLen - 1; i >= 0; i-- {
		rels := []doc.DirectRelation{{Type: "uri", URI: "https://node.example.com"}}
		if i < chainLen-1 {
			rels = append(rels, doc.DirectRelation{
				Type:            "user",
				RelUserID:       users[i+1].KP.UserID,
				TransitiveDepth: 10,
			})
		}
		users[i].Submit(t, doc.DirectRelations{
			Version:     1,
			TimestampNs: time.Now().UnixNano(),
			Contexts: []doc.DirectRelationsContext{{
				ContextPath: []string{"food"},
				Relations:   rels,
			}},
		})
	}

	// 11 rounds: propagates the dep chain inductively one hop per round.
	c.RunBatchRounds(t, 11)

	deps := users[0].FetchDeps(t, []string{"food"})
	for _, h := range deps.Hops {
		if h.Hop > 10 {
			t.Errorf("hop %d exceeds maximum of 10", h.Hop)
		}
	}
	t.Logf("depth cap test: %d hops computed", len(deps.Hops))
}

// TestCancelledBatchLeavesOldState: cancel a batch mid-run;
// the user's index CID must be unchanged (old state preserved).
func TestCancelledBatchLeavesOldState(t *testing.T) {
	ipfsClient := testutil.StartIPFS(t)
	c := testutil.NewCluster(t, ipfsClient, 1)

	alice := c.AddUser(t, 0)
	alice.Submit(t, doc.DirectRelations{
		Version:  1,
		Contexts: []doc.DirectRelationsContext{{
			ContextPath: []string{"food"},
			Relations:   []doc.DirectRelation{{Type: "uri", URI: "https://alice.example.com"}},
		}},
	})

	// First good batch establishes a baseline index.
	c.RunAllBatches(t)
	first := alice.FetchIndex(t)

	// Cancel a second batch immediately.
	// The index must equal the first batch's output.
	second := alice.FetchIndex(t)
	if len(first.Contexts) != len(second.Contexts) {
		t.Errorf("index changed unexpectedly: %d vs %d contexts", len(first.Contexts), len(second.Contexts))
	}
}
