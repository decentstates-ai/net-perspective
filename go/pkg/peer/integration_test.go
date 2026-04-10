package peer_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/ipfs"
	"github.com/decentstates/net-perspective/pkg/peer"
)

// TestSubmitBatchFetch exercises the full path:
// submit direct-relations → run batch → fetch deps index.
func TestSubmitBatchFetch(t *testing.T) {
	selfKP, _ := doc.GenerateKeyPair()
	userKP, _ := doc.GenerateKeyPair()
	store := ipfs.NewMemStore()
	srv := peer.NewServerWithStore(store, ":0", selfKP)

	u := &peer.HomedUser{
		KeyPair:     userKP,
		IPNSKeyName: "user-test",
		IPNSAddress: "k51test",
	}
	srv.AddHomedUser(u)

	// 1. Submit direct-relations.
	ts := time.Now().UnixNano()
	dr := doc.DirectRelations{
		Version:     1,
		TimestampNs: ts,
		UserID:      userKP.UserID,
		Contexts: []doc.DirectRelationsContext{
			{ContextPath: []string{"food"}, Relations: []doc.DirectRelation{
				{Type: "uri", URI: "https://example.com/recipe", Name: "pasta"},
			}},
		},
	}
	drEnv, _ := doc.Wrap(dr, userKP)
	ui := doc.UserInfo{Version: 1, TimestampNs: ts, UserID: userKP.UserID, UserPublicKey: userKP.EncodedPublicKey}
	uiEnv, _ := doc.Wrap(ui, userKP)

	body, _ := json.Marshal(peer.SubmitRequest{UserInfoEnvelope: *uiEnv, DirectRelationsEnvelope: *drEnv})
	mux := http.NewServeMux()
	srv.Routes(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/submit", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("submit: %d %s", rec.Code, rec.Body)
	}

	// 2. Run batch update.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.RunBatch(ctx); err != nil {
		t.Fatalf("batch: %v", err)
	}

	// 3. The index CID should now be set.
	indexCID := u.IndexCID()
	if indexCID == "" {
		t.Fatal("index CID not set after batch run")
	}

	// 4. Fetch and decode the index.
	indexBytes, err := store.Cat(indexCID)
	if err != nil {
		t.Fatalf("fetch index: %v", err)
	}
	var index doc.ContextRelationsDepsIndex
	if err := doc.Unmarshal(indexBytes, &index); err != nil {
		t.Fatalf("decode index: %v", err)
	}

	if len(index.Contexts) != 1 {
		t.Fatalf("expected 1 context in index, got %d", len(index.Contexts))
	}
	if len(index.Contexts[0].ContextPath) == 0 || index.Contexts[0].ContextPath[0] != "food" {
		t.Errorf("unexpected context path: %v", index.Contexts[0].ContextPath)
	}

	// 5. Follow the CID to the deps document.
	depsCID := string(index.Contexts[0].ContextRelsDepsAddress)
	depsBytes, err := store.Cat(depsCID)
	if err != nil {
		t.Fatalf("fetch deps: %v", err)
	}
	var deps doc.ContextRelationsDeps
	if err := doc.Unmarshal(depsBytes, &deps); err != nil {
		t.Fatalf("decode deps: %v", err)
	}
	if len(deps.Hops) == 0 {
		t.Fatal("expected at least one hop in deps")
	}
}
