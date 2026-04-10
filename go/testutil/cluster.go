package testutil

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/ipfs"
	"github.com/decentstates/net-perspective/pkg/peer"
)

// Cluster is a set of peer servers sharing one IPFS node and a peer registry.
type Cluster struct {
	Peers    []*PeerFixture
	Registry *peer.PeerRegistry
	IPFS     *ipfs.Client
}

// PeerFixture wraps a peer.Server with its httptest.Server.
type PeerFixture struct {
	Server  *peer.Server
	HTTP    *httptest.Server
	BaseURL string
}

// UserFixture is a user homed at a specific peer.
type UserFixture struct {
	KP   *doc.KeyPair
	Home *PeerFixture
}

// NewCluster creates n peer servers all sharing the given IPFS client.
func NewCluster(t testing.TB, ipfsClient *ipfs.Client, n int) *Cluster {
	t.Helper()
	reg := peer.NewPeerRegistry()
	c := &Cluster{Registry: reg, IPFS: ipfsClient}

	for i := 0; i < n; i++ {
		kp, err := doc.GenerateKeyPair()
		if err != nil {
			t.Fatalf("peer %d keygen: %v", i, err)
		}
		srv := peer.NewServerWithStore(ipfsClient, ":0", kp)
		srv.Registry = reg

		mux := http.NewServeMux()
		srv.Routes(mux)
		hs := httptest.NewServer(mux)
		t.Cleanup(hs.Close)

		pf := &PeerFixture{Server: srv, HTTP: hs, BaseURL: hs.URL}
		c.Peers = append(c.Peers, pf)
	}
	return c
}

// AddUser creates a new user homed at peers[peerIdx], registers the key with
// IPFS, and records the mapping in the cluster registry.
func (c *Cluster) AddUser(t testing.TB, peerIdx int) *UserFixture {
	t.Helper()
	if peerIdx >= len(c.Peers) {
		t.Fatalf("peer index %d out of range", peerIdx)
	}
	pf := c.Peers[peerIdx]

	kp, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatalf("user keygen: %v", err)
	}

	// Create an IPNS key in the shared daemon for this user.
	keyName := fmt.Sprintf("user-%x", kp.UserID[:8])
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ipnsAddr, err := c.IPFS.KeyGen(ctx, keyName)
	if err != nil {
		t.Fatalf("KeyGen %s: %v", keyName, err)
	}

	u := &peer.HomedUser{
		KeyPair:     kp,
		IPNSKeyName: keyName,
		IPNSAddress: ipnsAddr,
	}
	pf.Server.AddHomedUser(u)
	c.Registry.Register(kp.UserID, pf.BaseURL)

	t.Logf("user %x homed at peer %s (ipns %s)", kp.UserID[:8], pf.BaseURL, ipnsAddr)
	return &UserFixture{KP: kp, Home: pf}
}

// Submit posts signed direct-relations + user-info to the user's home peer.
func (uf *UserFixture) Submit(t testing.TB, dr doc.DirectRelations) {
	t.Helper()
	dr.UserID = uf.KP.UserID
	if dr.TimestampNs == 0 {
		dr.TimestampNs = time.Now().UnixNano()
	}

	drEnv, err := doc.Wrap(dr, uf.KP)
	if err != nil {
		t.Fatalf("wrap dr: %v", err)
	}
	ui := doc.UserInfo{
		Version:       1,
		TimestampNs:   dr.TimestampNs,
		UserID:        uf.KP.UserID,
		UserPublicKey: uf.KP.EncodedPublicKey,
	}
	uiEnv, err := doc.Wrap(ui, uf.KP)
	if err != nil {
		t.Fatalf("wrap ui: %v", err)
	}

	body, _ := json.Marshal(peer.SubmitRequest{
		UserInfoEnvelope:        *uiEnv,
		DirectRelationsEnvelope: *drEnv,
	})
	resp, err := http.Post(uf.Home.BaseURL+"/submit", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("submit: status %s: %s", resp.Status, bytes.TrimSpace(b))
	}
	var result struct {
		CID string `json:"cid"`
	}
	json.NewDecoder(resp.Body).Decode(&result)
	t.Logf("user %x submitted DR → CID %s (%d contexts)", uf.KP.UserID[:8], result.CID, len(dr.Contexts))
}

// RunAllBatches runs one batch update round across every peer in the cluster.
func (c *Cluster) RunAllBatches(t testing.TB) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for i, pf := range c.Peers {
		if err := pf.Server.RunBatch(ctx); err != nil {
			t.Fatalf("peer %d batch: %v", i, err)
		}
	}
}

// RunBatchRounds runs n full rounds. Because deps are computed inductively,
// each round propagates deps one hop further. Use rounds = max_transitive_depth.
func (c *Cluster) RunBatchRounds(t testing.TB, rounds int) {
	t.Helper()
	for i := 0; i < rounds; i++ {
		t.Logf("--- batch round %d/%d ---", i+1, rounds)
		c.RunAllBatches(t)
		for _, pf := range c.Peers {
			resp, err := http.Get(pf.BaseURL + "/status/users")
			if err != nil || resp.StatusCode != http.StatusOK {
				continue
			}
			var users []peer.UserStatus
			json.NewDecoder(resp.Body).Decode(&users)
			resp.Body.Close()
			for _, u := range users {
				t.Logf("  peer %s  user %s  index=%s  contexts=%d",
					pf.BaseURL, u.UserIDHex[:16], truncate(u.IndexCID, 20), u.ContextCount)
			}
		}
	}
}

// Status fetches the current UserStatus for this user from its home peer.
func (uf *UserFixture) Status(t testing.TB) peer.UserStatus {
	t.Helper()
	resp, err := http.Get(uf.Home.BaseURL + "/status/users/" + hexID(uf.KP.UserID))
	if err != nil {
		t.Fatalf("fetch status: %v", err)
	}
	defer resp.Body.Close()
	var st peer.UserStatus
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	return st
}

// FetchIndex fetches and decodes a user's current context-relations-deps-index.
func (uf *UserFixture) FetchIndex(t testing.TB) doc.ContextRelationsDepsIndex {
	t.Helper()
	statusURL := uf.Home.BaseURL + "/status/users/" + hexID(uf.KP.UserID)
	resp, err := http.Get(statusURL)
	if err != nil {
		t.Fatalf("fetch status: %v", err)
	}
	defer resp.Body.Close()

	var st peer.UserStatus
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if st.IndexCID == "" {
		t.Fatal("no index CID after batch run")
	}

	cidResp, err := http.Get(uf.Home.BaseURL + "/cid/" + st.IndexCID)
	if err != nil {
		t.Fatalf("fetch index: %v", err)
	}
	defer cidResp.Body.Close()

	var index doc.ContextRelationsDepsIndex
	if err := json.NewDecoder(cidResp.Body).Decode(&index); err != nil {
		t.Fatalf("decode index: %v", err)
	}
	return index
}

// FetchDeps fetches the context-relations-deps for a specific context path.
func (uf *UserFixture) FetchDeps(t testing.TB, contextPath []string) doc.ContextRelationsDeps {
	t.Helper()
	index := uf.FetchIndex(t)
	for _, entry := range index.Contexts {
		if pathsEqual(entry.ContextPath, contextPath) {
			depsCID := string(entry.ContextRelsDepsAddress)
			resp, err := http.Get(uf.Home.BaseURL + "/cid/" + depsCID)
			if err != nil {
				t.Fatalf("fetch deps: %v", err)
			}
			defer resp.Body.Close()
			var deps doc.ContextRelationsDeps
			if err := json.NewDecoder(resp.Body).Decode(&deps); err != nil {
				t.Fatalf("decode deps: %v", err)
			}
			return deps
		}
	}
	t.Fatalf("no deps found for context path %v in index", contextPath)
	return doc.ContextRelationsDeps{}
}

// TryFetchIndex fetches a user's current index without failing the test.
// Returns the zero value and false if the user has no index yet or on any error.
func (uf *UserFixture) TryFetchIndex(t testing.TB) (doc.ContextRelationsDepsIndex, bool) {
	t.Helper()
	resp, err := http.Get(uf.Home.BaseURL + "/status/users/" + hexID(uf.KP.UserID))
	if err != nil {
		return doc.ContextRelationsDepsIndex{}, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return doc.ContextRelationsDepsIndex{}, false
	}
	var st peer.UserStatus
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil || st.IndexCID == "" {
		return doc.ContextRelationsDepsIndex{}, false
	}

	cidResp, err := http.Get(uf.Home.BaseURL + "/cid/" + st.IndexCID)
	if err != nil {
		return doc.ContextRelationsDepsIndex{}, false
	}
	defer cidResp.Body.Close()
	if cidResp.StatusCode != http.StatusOK {
		return doc.ContextRelationsDepsIndex{}, false
	}
	var index doc.ContextRelationsDepsIndex
	if err := json.NewDecoder(cidResp.Body).Decode(&index); err != nil {
		return doc.ContextRelationsDepsIndex{}, false
	}
	return index, true
}

// TryFetchDepsByCID fetches a deps document by CID without failing the test.
func (uf *UserFixture) TryFetchDepsByCID(t testing.TB, depsCID string) (doc.ContextRelationsDeps, bool) {
	t.Helper()
	resp, err := http.Get(uf.Home.BaseURL + "/cid/" + depsCID)
	if err != nil {
		return doc.ContextRelationsDeps{}, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return doc.ContextRelationsDeps{}, false
	}
	var deps doc.ContextRelationsDeps
	if err := json.NewDecoder(resp.Body).Decode(&deps); err != nil {
		return doc.ContextRelationsDeps{}, false
	}
	return deps, true
}

// AllCIDsInDeps returns the flat set of all direct-relations CIDs across all hops.
func AllCIDsInDeps(deps doc.ContextRelationsDeps) map[string]struct{} {
	out := make(map[string]struct{})
	for _, h := range deps.Hops {
		for _, addr := range h.DirectRelationsAddresses {
			out[string(addr)] = struct{}{}
		}
	}
	return out
}

func pathsEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func hexID(id []byte) string {
	return fmt.Sprintf("%x", id)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
