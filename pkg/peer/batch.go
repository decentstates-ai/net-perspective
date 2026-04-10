package peer

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
)

// RunBatch computes context-relations-deps for all homed users, stores them to
// IPFS, and atomically publishes each user's updated user-info via IPNS.
//
// The final IPNS publish of each user-info is the atomic commit: a cancelled
// context leaves the previous consistent state intact.
func (s *Server) RunBatch(ctx context.Context) error {
	users := s.AllUsers()
	if len(users) == 0 {
		return nil
	}

	for _, u := range users {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := s.processUser(ctx, u); err != nil {
			// Log and continue; one user's failure shouldn't block others.
			log.Printf("batch: user %x: %v", u.KeyPair.UserID[:8], err)
		}
	}
	return nil
}

func (s *Server) processUser(ctx context.Context, u *HomedUser) error {
	dr, _ := u.DirectRelations()
	if dr == nil {
		return nil // nothing submitted yet
	}

	now := time.Now().UnixNano()

	// Build context-relations-deps per context and collect into an index.
	index := doc.ContextRelationsDepsIndex{
		Version:     1,
		TimestampNs: now,
		UserID:      u.KeyPair.UserID,
	}

	for _, ctx_ := range dr.Contexts {
		if err := ctx.Err(); err != nil {
			return err
		}

		deps, err := s.computeDeps(ctx, u, dr, ctx_.ContextPath)
		if err != nil {
			return fmt.Errorf("compute deps for %v: %w", ctx_.ContextPath, err)
		}

		depsBytes, err := doc.Marshal(deps)
		if err != nil {
			return err
		}
		depsCID, err := s.IPFS.Add(depsBytes)
		if err != nil {
			return fmt.Errorf("store deps: %w", err)
		}

		totalSize := int64(0)
		for _, h := range deps.Hops {
			totalSize += h.Size
		}
		maxHops := 0
		if len(deps.Hops) > 0 {
			maxHops = deps.Hops[len(deps.Hops)-1].Hop
		}

		index.Contexts = append(index.Contexts, doc.ContextRelationsDepsIndexContext{
			ContextPath:            ctx_.ContextPath,
			ContextRelsDepsAddress: []byte(depsCID),
			Hops:                   maxHops,
			Size:                   totalSize,
		})
	}

	// Store index.
	indexBytes, err := doc.Marshal(index)
	if err != nil {
		return err
	}
	indexCID, err := s.IPFS.Add(indexBytes)
	if err != nil {
		return fmt.Errorf("store index: %w", err)
	}
	u.SetIndexCID(indexCID)

	// Atomic commit: update user-info with the new index address and publish to IPNS.
	ui := &doc.UserInfo{
		Version:                         1,
		TimestampNs:                     now,
		UserID:                          u.KeyPair.UserID,
		UserPublicKey:                   u.KeyPair.EncodedPublicKey,
		ContextRelsDepsIndexIPNSAddress: indexCID,
	}
	// Preserve the direct-relations IPNS address if already set.
	if prev := u.latestUserInfo; prev != nil {
		ui.DirectRelationsIPNSAddress = prev.DirectRelationsIPNSAddress
		ui.TrustedPeers = prev.TrustedPeers
		ui.PeeredUsers = prev.PeeredUsers
	}

	env, err := doc.Wrap(ui, u.KeyPair)
	if err != nil {
		return fmt.Errorf("wrap user-info: %w", err)
	}
	uiBytes, err := doc.Marshal(env)
	if err != nil {
		return err
	}
	uiCID, err := s.IPFS.Add(uiBytes)
	if err != nil {
		return fmt.Errorf("store user-info: %w", err)
	}
	return s.IPFS.PublishIPNS(u.IPNSKeyName, uiCID)
}

// computeDeps builds a ContextRelationsDeps for one user+context by scanning
// the direct-relations transitively up to the per-relation transitive-depth cap.
//
// Fetching remote peers' deps and seeding is Phase 3+ work; for now it builds
// hop-1 from the user's own direct-relations.
func (s *Server) computeDeps(
	ctx context.Context,
	u *HomedUser,
	dr *doc.DirectRelations,
	contextPath []string,
) (*doc.ContextRelationsDeps, error) {
	_, drCID := u.DirectRelations()

	hop := doc.ContextRelationsDepsHop{
		Hop:                          1,
		DirectRelationsAddresses:     [][]byte{[]byte(drCID)},
		DirectRelationsArchiveAddress: []byte(drCID), // archive = same doc for hop-1
	}

	// Compute hop size from the stored direct-relations bytes.
	if drCID != "" {
		data, err := s.IPFS.Cat(drCID)
		if err == nil {
			hop.Size = int64(len(data))
		}
	}

	// Fetch and incorporate deps from related users (hop 2+).
	additionalHops, sourceAddrs, err := s.fetchTransitiveDeps(ctx, dr, contextPath, 1)
	if err != nil {
		// Non-fatal: log and continue with what we have.
		log.Printf("fetchTransitiveDeps: %v", err)
	}

	hops := append([]doc.ContextRelationsDepsHop{hop}, additionalHops...)

	return &doc.ContextRelationsDeps{
		Version:         1,
		TimestampNs:     time.Now().UnixNano(),
		UserID:          u.KeyPair.UserID,
		ContextPath:     contextPath,
		Hops:            hops,
		SourceAddresses: sourceAddrs,
	}, nil
}

// fetchTransitiveDeps fetches context-relations-deps from directly related users
// and returns additional hops and source CIDs.
func (s *Server) fetchTransitiveDeps(
	ctx context.Context,
	dr *doc.DirectRelations,
	contextPath []string,
	currentHop int,
) ([]doc.ContextRelationsDepsHop, [][]byte, error) {
	var hops []doc.ContextRelationsDepsHop
	var sourceAddrs [][]byte

	for _, c := range dr.Contexts {
		if !pathMatches(c.ContextPath, contextPath) {
			continue
		}
		for _, rel := range c.Relations {
			if rel.Type != "user" {
				continue
			}
			maxDepth := rel.TransitiveDepth
			if maxDepth == 0 {
				maxDepth = 2
			}
			if maxDepth > 10 {
				maxDepth = 10
			}
			if currentHop >= maxDepth {
				continue
			}

			// Resolve the related user's index.
			deps, depsCID, err := s.fetchUserContextDeps(ctx, rel, contextPath)
			if err != nil {
				log.Printf("fetchUserContextDeps %x: %v", rel.RelUserID[:min(8, len(rel.RelUserID))], err)
				continue
			}
			if depsCID != "" {
				sourceAddrs = append(sourceAddrs, []byte(depsCID))
			}

			// Merge their hops, incrementing hop numbers.
			for _, h := range deps.Hops {
				adjusted := h
				adjusted.Hop = currentHop + h.Hop
				if adjusted.Hop > 10 {
					break
				}
				hops = append(hops, adjusted)
			}
		}
	}
	return hops, sourceAddrs, nil
}

// fetchUserContextDeps resolves a related user's context-relations-deps-index
// and extracts the entry for the given context path.
//
// It first checks if the user is homed locally. If not, and a PeerRegistry is
// configured, it queries the remote peer over HTTP.
func (s *Server) fetchUserContextDeps(
	ctx context.Context,
	rel doc.DirectRelation,
	contextPath []string,
) (*doc.ContextRelationsDeps, string, error) {
	targetPath := contextPath
	if len(rel.RelContextPath) > 0 {
		targetPath = rel.RelContextPath
	}

	// Try local first.
	if relUser := s.GetUser(rel.RelUserID); relUser != nil {
		return s.fetchLocalUserContextDeps(relUser, targetPath)
	}

	// Try remote via peer registry.
	if s.Registry != nil {
		if peerURL, ok := s.Registry.Lookup(rel.RelUserID); ok {
			return s.fetchRemoteUserContextDeps(ctx, peerURL, rel.RelUserID, targetPath)
		}
	}

	return &doc.ContextRelationsDeps{}, "", nil
}

func (s *Server) fetchLocalUserContextDeps(
	relUser *HomedUser,
	targetPath []string,
) (*doc.ContextRelationsDeps, string, error) {
	indexCID := relUser.IndexCID()
	if indexCID == "" {
		return &doc.ContextRelationsDeps{}, "", nil
	}
	indexBytes, err := s.IPFS.Cat(indexCID)
	if err != nil {
		return nil, "", err
	}
	return extractDepsFromIndex(indexBytes, targetPath, s.IPFS.Cat)
}

// fetchRemoteUserContextDeps fetches a user's index from a remote peer via HTTP,
// then fetches the specific deps document for the target context path.
func (s *Server) fetchRemoteUserContextDeps(
	ctx context.Context,
	peerBaseURL string,
	userID []byte,
	targetPath []string,
) (*doc.ContextRelationsDeps, string, error) {
	// GET /status/users/{hexUserID} to find the index CID.
	statusURL := peerBaseURL + "/status/users/" + hex.EncodeToString(userID)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, statusURL, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("fetch remote status: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return &doc.ContextRelationsDeps{}, "", nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("remote status: %s", resp.Status)
	}

	var st UserStatus
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil {
		return nil, "", fmt.Errorf("decode remote status: %w", err)
	}
	if st.IndexCID == "" {
		return &doc.ContextRelationsDeps{}, "", nil
	}

	// Fetch the index document via /cid/.
	catRemote := func(cid string) ([]byte, error) {
		url := peerBaseURL + "/cid/" + cid
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("GET %s: %s", url, resp.Status)
		}
		return io.ReadAll(resp.Body)
	}

	indexBytes, err := catRemote(st.IndexCID)
	if err != nil {
		return nil, "", fmt.Errorf("fetch remote index: %w", err)
	}
	return extractDepsFromIndex(indexBytes, targetPath, catRemote)
}

// extractDepsFromIndex decodes an index document, finds the entry matching
// targetPath, and fetches its deps document using the provided fetch function.
func extractDepsFromIndex(
	indexBytes []byte,
	targetPath []string,
	fetch func(cid string) ([]byte, error),
) (*doc.ContextRelationsDeps, string, error) {
	var index doc.ContextRelationsDepsIndex
	if err := doc.Unmarshal(indexBytes, &index); err != nil {
		return nil, "", err
	}
	for _, entry := range index.Contexts {
		if pathMatches(entry.ContextPath, targetPath) {
			depsCID := string(entry.ContextRelsDepsAddress)
			depsBytes, err := fetch(depsCID)
			if err != nil {
				return nil, depsCID, err
			}
			var deps doc.ContextRelationsDeps
			if err := doc.Unmarshal(depsBytes, &deps); err != nil {
				return nil, depsCID, err
			}
			return &deps, depsCID, nil
		}
	}
	return &doc.ContextRelationsDeps{}, "", nil
}

// pathMatches returns true if a equals b (exact match for now; glob handled by client).
func pathMatches(a, b []string) bool {
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

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

