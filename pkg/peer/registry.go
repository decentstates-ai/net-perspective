package peer

import (
	"encoding/base64"
	"sync"
)

// PeerRegistry maps user-ids to the HTTP base URL of the peer that homes them.
// Used during batch updates to resolve remote users' deps via cross-peer HTTP.
//
// In production this would be replaced by IPNS resolution + peered-users lookup;
// the registry exists for the test cluster.
type PeerRegistry struct {
	mu    sync.RWMutex
	homes map[string]string // base64(userID) → peer HTTP base URL (e.g. "http://127.0.0.1:8081")
}

// NewPeerRegistry creates an empty registry.
func NewPeerRegistry() *PeerRegistry {
	return &PeerRegistry{homes: make(map[string]string)}
}

// Register records that userID is homed at peerBaseURL.
func (r *PeerRegistry) Register(userID []byte, peerBaseURL string) {
	key := base64.RawURLEncoding.EncodeToString(userID)
	r.mu.Lock()
	r.homes[key] = peerBaseURL
	r.mu.Unlock()
}

// Lookup returns the peer HTTP base URL for userID, or "" if not found.
func (r *PeerRegistry) Lookup(userID []byte) (string, bool) {
	key := base64.RawURLEncoding.EncodeToString(userID)
	r.mu.RLock()
	u, ok := r.homes[key]
	r.mu.RUnlock()
	return u, ok
}
