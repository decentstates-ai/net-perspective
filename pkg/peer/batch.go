package peer

import (
	"context"
	"fmt"
	"log"
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
func (s *Server) fetchUserContextDeps(
	ctx context.Context,
	rel doc.DirectRelation,
	contextPath []string,
) (*doc.ContextRelationsDeps, string, error) {
	// Look up the related user's peer via their peered-users list.
	// For now, check if they are homed locally.
	relUser := s.GetUser(rel.RelUserID)
	if relUser == nil {
		// Remote fetch would go here; deferred to later implementation.
		return &doc.ContextRelationsDeps{}, "", nil
	}

	indexCID := relUser.IndexCID()
	if indexCID == "" {
		return &doc.ContextRelationsDeps{}, "", nil
	}

	indexBytes, err := s.IPFS.Cat(indexCID)
	if err != nil {
		return nil, "", err
	}

	var index doc.ContextRelationsDepsIndex
	if err := doc.Unmarshal(indexBytes, &index); err != nil {
		return nil, "", err
	}

	targetPath := contextPath
	if len(rel.RelContextPath) > 0 {
		targetPath = rel.RelContextPath
	}

	for _, entry := range index.Contexts {
		if pathMatches(entry.ContextPath, targetPath) {
			depsCIDStr := string(entry.ContextRelsDepsAddress)
			depsBytes, err := s.IPFS.Cat(depsCIDStr)
			if err != nil {
				return nil, depsCIDStr, err
			}
			var deps doc.ContextRelationsDeps
			if err := doc.Unmarshal(depsBytes, &deps); err != nil {
				return nil, depsCIDStr, err
			}
			return &deps, depsCIDStr, nil
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

