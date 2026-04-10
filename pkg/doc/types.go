package doc

import "encoding/json"

// Envelope wraps any signed document.
type Envelope struct {
	Content       json.RawMessage `json:"envelope/content"`
	UserID        []byte          `json:"envelope/user-id"`
	UserPublicKey []byte          `json:"envelope/user-public-key"`
	Signature     []byte          `json:"envelope/signature"`
}

// UserInfo is the user's self-published document, placed inside an Envelope.
type UserInfo struct {
	Version                         int          `json:"user-info/version"`
	TimestampNs                     int64        `json:"user-info/timestamp-ns"`
	UserID                          []byte       `json:"user-info/user-id"`
	UserPublicKey                   []byte       `json:"user-info/user-public-key"`
	DirectRelationsIPNSAddress      string       `json:"user-info/direct-relations-ipns-address"`
	ContextRelsDepsIndexIPNSAddress string       `json:"user-info/context-relations-deps-index-ipns-address"`
	TrustedPeers                    [][]byte     `json:"user-info/trusted-peers"`
	PeeredUsers                     []PeeredUser `json:"user-info/peered-users"`
}

// PeeredUser is an entry in UserInfo.PeeredUsers.
type PeeredUser struct {
	UserID                      []byte `json:"user-info-peered-user/user-id"`
	ContextRelsDepsIndexAddress string `json:"user-info-peered-user/context-relations-deps-index-address"`
}

// DirectRelations is the user's signed list of relations, placed inside an Envelope.
type DirectRelations struct {
	Version     int                      `json:"direct-relations/direct-relations-version"`
	TimestampNs int64                    `json:"direct-relations/timestamp-ns"`
	UserID      []byte                   `json:"direct-relations/user-id"`

	ContactEmail          string `json:"direct-relations/contact-email,omitempty"`
	ContactSignalUsername string `json:"direct-relations/contact-signal-username,omitempty"`
	ContactNumber         string `json:"direct-relations/contact-number,omitempty"`

	Contexts []DirectRelationsContext `json:"direct-relations/contexts"`
}

// DirectRelationsContext groups relations under a context path.
type DirectRelationsContext struct {
	ContextPath []string         `json:"direct-relations-context/context-path"`
	Relations   []DirectRelation `json:"direct-relations-context/relations"`
}

// DirectRelation is a single user or URI relation.
type DirectRelation struct {
	Type string `json:"direct-relations-rel/type"` // "user" or "uri"

	// URI relation fields.
	URI     string `json:"direct-relations-rel-uri/uri,omitempty"`
	Name    string `json:"direct-relations-rel-uri/name,omitempty"`
	Comment string `json:"direct-relations-rel-uri/comment,omitempty"`

	// User relation fields.
	RelUserID       []byte   `json:"direct-relations-rel-user/user-id,omitempty"`
	RelContextPath  []string `json:"direct-relations-rel-user/context-path,omitempty"`
	TransitiveDepth int      `json:"direct-relations-rel-user/transitive-depth,omitempty"`
	SubjectGlob     int      `json:"direct-relations-rel-user/subject-glob,omitempty"`
	ObjectGlob      int      `json:"direct-relations-rel-user/object-glob,omitempty"`
}

// ContextRelationsDeps is a peer-produced dependency set for one user+context.
type ContextRelationsDeps struct {
	Version     int                       `json:"context-relations-deps/version"`
	TimestampNs int64                     `json:"context-relations-deps/timestamp-ns"`
	UserID      []byte                    `json:"context-relations-deps/user-id"`
	ContextPath []string                  `json:"context-relations-deps/context-path"`
	Hops        []ContextRelationsDepsHop `json:"context-relations-deps/hops"`
	// Content addresses of the context-relations-deps documents used as input.
	SourceAddresses [][]byte `json:"context-relations-deps/source-context-relations-deps-content-addresses"`
}

// ContextRelationsDepsHop holds data for one transitive hop.
type ContextRelationsDepsHop struct {
	Hop int `json:"context-relations-deps-hop/hop"` // 1–10

	// Content addresses of individual direct-relations documents at this hop.
	DirectRelationsAddresses [][]byte `json:"context-relations-deps-hop/direct-relations-addresses"`

	// Content address of a compressed archive of all direct-relations at this hop.
	DirectRelationsArchiveAddress []byte `json:"context-relations-deps-hop/direct-relations-archive-address"`

	// Total bytes of all content in this hop.
	Size int64 `json:"context-relations-deps-hop/size"`
}

// ContextRelationsDepsIndex is a peer-produced index of deps per context for a user.
type ContextRelationsDepsIndex struct {
	Version     int                                `json:"context-relations-deps-index/version"`
	TimestampNs int64                              `json:"context-relations-deps-index/timestamp-ns"`
	UserID      []byte                             `json:"context-relations-deps-index/user-id"`
	Contexts    []ContextRelationsDepsIndexContext `json:"context-relations-deps-index/contexts"`
}

// ContextRelationsDepsIndexContext is one context entry in the index.
type ContextRelationsDepsIndexContext struct {
	ContextPath             []string `json:"context-relations-deps-index-context/context-path"`
	ContextRelsDepsAddress  []byte   `json:"context-relations-deps-index-context/context-relations-deps-content-address"`
	Hops                    int      `json:"context-relations-deps-index-context/hops"`
	ArchiveAddresses        [][]byte `json:"context-relations-deps-index-context-hop/direct-relations-collection-context-address"`
	Size                    int64    `json:"context-relations-deps-index-context/size"`
}
