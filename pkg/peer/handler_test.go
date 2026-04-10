package peer_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/ipfs"
	"github.com/decentstates/net-perspective/pkg/peer"
)

func testServer(t *testing.T) (*peer.Server, *doc.KeyPair, *peer.HomedUser) {
	t.Helper()
	selfKP, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	userKP, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	srv := peer.NewServerWithStore(ipfs.NewMemStore(), ":0", selfKP)

	u := &peer.HomedUser{
		KeyPair:     userKP,
		IPNSKeyName: "user-test",
		IPNSAddress: "k51test",
	}
	srv.AddHomedUser(u)
	return srv, userKP, u
}

func submitDR(t *testing.T, srv *peer.Server, userKP *doc.KeyPair, ts int64) int {
	t.Helper()
	dr := doc.DirectRelations{
		Version:     1,
		TimestampNs: ts,
		UserID:      userKP.UserID,
		Contexts: []doc.DirectRelationsContext{
			{ContextPath: []string{"food"}, Relations: []doc.DirectRelation{
				{Type: "uri", URI: "https://example.com"},
			}},
		},
	}
	drEnv, err := doc.Wrap(dr, userKP)
	if err != nil {
		t.Fatal(err)
	}
	ui := doc.UserInfo{
		Version:       1,
		TimestampNs:   ts,
		UserID:        userKP.UserID,
		UserPublicKey: userKP.EncodedPublicKey,
	}
	uiEnv, err := doc.Wrap(ui, userKP)
	if err != nil {
		t.Fatal(err)
	}

	body, _ := json.Marshal(peer.SubmitRequest{
		UserInfoEnvelope:        *uiEnv,
		DirectRelationsEnvelope: *drEnv,
	})

	mux := http.NewServeMux()
	srv.Routes(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/submit", bytes.NewReader(body)))
	return rec.Code
}

func TestSubmitAccepted(t *testing.T) {
	srv, userKP, _ := testServer(t)
	if code := submitDR(t, srv, userKP, time.Now().UnixNano()); code != http.StatusOK {
		t.Fatalf("submit: expected 200, got %d", code)
	}
}

func TestSubmitStaleRejected(t *testing.T) {
	srv, userKP, _ := testServer(t)
	if code := submitDR(t, srv, userKP, 100); code != http.StatusOK {
		t.Fatalf("first submit: %d", code)
	}
	if code := submitDR(t, srv, userKP, 50); code != http.StatusConflict {
		t.Fatalf("stale submit: expected 409, got %d", code)
	}
}

func TestSubmitUnknownUserRejected(t *testing.T) {
	selfKP, _ := doc.GenerateKeyPair()
	srv := peer.NewServerWithStore(ipfs.NewMemStore(), ":0", selfKP)
	unknownKP, _ := doc.GenerateKeyPair()
	if code := submitDR(t, srv, unknownKP, 1); code != http.StatusForbidden {
		t.Fatalf("unknown user: expected 403, got %d", code)
	}
}

func TestSubmitWrongSignatureRejected(t *testing.T) {
	srv, userKP, _ := testServer(t)

	dr := doc.DirectRelations{Version: 1, TimestampNs: 1, UserID: userKP.UserID}
	drEnv, _ := doc.Wrap(dr, userKP)
	ui := doc.UserInfo{Version: 1, TimestampNs: 1, UserID: userKP.UserID, UserPublicKey: userKP.EncodedPublicKey}
	uiEnv, _ := doc.Wrap(ui, userKP)

	// Tamper with the direct-relations content after signing.
	drEnv.Content = []byte(`{"direct-relations/direct-relations-version":99}`)

	body, _ := json.Marshal(peer.SubmitRequest{UserInfoEnvelope: *uiEnv, DirectRelationsEnvelope: *drEnv})
	mux := http.NewServeMux()
	srv.Routes(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/submit", bytes.NewReader(body)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("tampered: expected 400, got %d", rec.Code)
	}
}
