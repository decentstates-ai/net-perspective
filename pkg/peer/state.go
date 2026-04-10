// Package peer implements the peer server logic.
package peer

import (
	"encoding/base64"
	"sync"

	"github.com/decentstates/net-perspective/pkg/doc"
	ipfsstore "github.com/decentstates/net-perspective/pkg/ipfs"
)

// HomedUser holds the peer-side state for one user it homes.
type HomedUser struct {
	KeyPair *doc.KeyPair // peer's IPNS key for this user (keyed by user-id)

	// IPNS key name in the kubo keystore for this user's IPNS record.
	IPNSKeyName string
	IPNSAddress string

	mu              sync.RWMutex
	latestUserInfo  *doc.UserInfo
	latestDirectRel *doc.DirectRelations
	latestDRCID     string // CID of the stored direct-relations envelope

	// Per-context deps index CID, updated after each batch run.
	indexCID string
}

// DirectRelationsTimestamp returns the timestamp of the stored direct-relations, or 0.
func (u *HomedUser) DirectRelationsTimestamp() int64 {
	u.mu.RLock()
	defer u.mu.RUnlock()
	if u.latestDirectRel == nil {
		return 0
	}
	return u.latestDirectRel.TimestampNs
}

// Store persists a new user-info and direct-relations for this user.
func (u *HomedUser) Store(ui *doc.UserInfo, dr *doc.DirectRelations, drCID string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.latestUserInfo = ui
	u.latestDirectRel = dr
	u.latestDRCID = drCID
}

// DirectRelations returns the current direct-relations and its CID, or nil.
func (u *HomedUser) DirectRelations() (*doc.DirectRelations, string) {
	u.mu.RLock()
	defer u.mu.RUnlock()
	return u.latestDirectRel, u.latestDRCID
}

// SetIndexCID records the latest context-relations-deps-index CID.
func (u *HomedUser) SetIndexCID(cid string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.indexCID = cid
}

// IndexCID returns the latest context-relations-deps-index CID.
func (u *HomedUser) IndexCID() string {
	u.mu.RLock()
	defer u.mu.RUnlock()
	return u.indexCID
}

// Server holds peer server state.
type Server struct {
	IPFS     ipfsstore.Store
	SelfKP   *doc.KeyPair  // peer's own identity
	Addr     string        // HTTP listen address
	Registry *PeerRegistry // optional; enables cross-peer dep fetching

	mu    sync.RWMutex
	users map[string]*HomedUser // keyed by base64(user-id)
}

// NewServer creates a Server backed by a real IPFS daemon at ipfsAddr.
func NewServer(ipfsAddr, listenAddr string, selfKP *doc.KeyPair) *Server {
	return &Server{
		IPFS:   ipfsstore.New(ipfsAddr),
		SelfKP: selfKP,
		Addr:   listenAddr,
		users:  make(map[string]*HomedUser),
	}
}

// NewServerWithStore creates a Server using the provided Store (e.g. for tests).
func NewServerWithStore(store ipfsstore.Store, listenAddr string, selfKP *doc.KeyPair) *Server {
	return &Server{
		IPFS:   store,
		SelfKP: selfKP,
		Addr:   listenAddr,
		users:  make(map[string]*HomedUser),
	}
}

// AddHomedUser registers a user the peer will home.
func (s *Server) AddHomedUser(u *HomedUser) {
	key := base64.RawURLEncoding.EncodeToString(u.KeyPair.UserID)
	s.mu.Lock()
	s.users[key] = u
	s.mu.Unlock()
}

// GetUser returns the HomedUser for the given user-id bytes, or nil.
func (s *Server) GetUser(userID []byte) *HomedUser {
	key := base64.RawURLEncoding.EncodeToString(userID)
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.users[key]
}

// AllUsers returns a snapshot of all homed users.
func (s *Server) AllUsers() []*HomedUser {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]*HomedUser, 0, len(s.users))
	for _, u := range s.users {
		out = append(out, u)
	}
	return out
}

