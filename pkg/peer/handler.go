package peer

import (
	"encoding/json"
	"net/http"

	"github.com/decentstates/net-perspective/pkg/doc"
)

// SubmitRequest is the body of POST /submit.
type SubmitRequest struct {
	UserInfoEnvelope        doc.Envelope `json:"user-info-envelope"`
	DirectRelationsEnvelope doc.Envelope `json:"direct-relations-envelope"`
}

// Routes registers all HTTP handlers on mux.
func (s *Server) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /submit", s.handleSubmit)
	mux.HandleFunc("GET /user/{userID}", s.handleGetUserInfo)
	mux.HandleFunc("GET /cid/{cid}", s.handleGetByCID)
}

// handleSubmit accepts a signed user-info and direct-relations from a user-client.
//
// It validates both envelopes, checks the user is homed here, enforces
// latest-timestamp-wins conflict resolution, stores both documents to IPFS,
// and updates IPNS.
func (s *Server) handleSubmit(w http.ResponseWriter, r *http.Request) {
	var req SubmitRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Verify and decode user-info.
	var ui doc.UserInfo
	if err := doc.Unwrap(&req.UserInfoEnvelope, &ui); err != nil {
		http.Error(w, "invalid user-info envelope: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Verify and decode direct-relations.
	var dr doc.DirectRelations
	if err := doc.Unwrap(&req.DirectRelationsEnvelope, &dr); err != nil {
		http.Error(w, "invalid direct-relations envelope: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Both documents must belong to the same user.
	if string(ui.UserID) != string(dr.UserID) {
		http.Error(w, "user-id mismatch between envelopes", http.StatusBadRequest)
		return
	}

	user := s.GetUser(ui.UserID)
	if user == nil {
		http.Error(w, "user not homed here", http.StatusForbidden)
		return
	}

	// Latest-timestamp-wins: reject if we already have a newer document.
	if dr.TimestampNs <= user.DirectRelationsTimestamp() {
		http.Error(w, "stale submission", http.StatusConflict)
		return
	}

	// Store direct-relations envelope to IPFS.
	drBytes, err := doc.Marshal(&req.DirectRelationsEnvelope)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	drCID, err := s.IPFS.Add(drBytes)
	if err != nil {
		http.Error(w, "ipfs error: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Store user-info envelope to IPFS and update IPNS.
	uiBytes, err := doc.Marshal(&req.UserInfoEnvelope)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	uiCID, err := s.IPFS.Add(uiBytes)
	if err != nil {
		http.Error(w, "ipfs error: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := s.IPFS.PublishIPNS(user.IPNSKeyName, uiCID); err != nil {
		http.Error(w, "ipns error: "+err.Error(), http.StatusInternalServerError)
		return
	}

	user.Store(&ui, &dr, drCID)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"cid": drCID})
}

// handleGetUserInfo returns the cached user-info envelope for a user-id (base64url).
func (s *Server) handleGetUserInfo(w http.ResponseWriter, r *http.Request) {
	// User-id is the IPNS address or base64-encoded user-id.
	// For now, resolve via IPNS using the path parameter as the IPNS address.
	ipnsAddr := r.PathValue("userID")

	cid, err := s.IPFS.ResolveIPNS(ipnsAddr)
	if err != nil {
		http.Error(w, "resolve error: "+err.Error(), http.StatusNotFound)
		return
	}
	data, err := s.IPFS.Cat(cid)
	if err != nil {
		http.Error(w, "fetch error: "+err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
}

// handleGetByCID proxies a CID fetch from IPFS (for direct-relations and deps).
func (s *Server) handleGetByCID(w http.ResponseWriter, r *http.Request) {
	cid := r.PathValue("cid")
	data, err := s.IPFS.Cat(cid)
	if err != nil {
		http.Error(w, "fetch error: "+err.Error(), http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
}
