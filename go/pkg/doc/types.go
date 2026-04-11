package doc

import "encoding/json"

// Envelope wraps any signed document.
type Envelope struct {
	Content       json.RawMessage `json:"env/content"`
	UserID        []byte          `json:"env/user-id"`
	UserPublicKey []byte          `json:"env/user-public-key"`
	Signature     []byte          `json:"env/signature"`
}

// UserInfo is the user's self-published document, placed inside an Envelope.
type UserInfo struct {
	Version       int          `json:"user/version"`
	TimestampNs   int64        `json:"user/timestamp-ns"`
	UserID        []byte       `json:"user/user-id"`
	UserPublicKey []byte       `json:"user/user-public-key"`
	DRAddress     string       `json:"user/dr-address"`
	TrustedPeers  [][]byte     `json:"user/trusted-peers"`
	PeeredUsers   []PeeredUser `json:"user/peered-users"`
}

// PeeredUser is an entry in UserInfo.PeeredUsers.
type PeeredUser struct {
	UserID         []byte `json:"user-peered/user-id"`
	CRDIdxAddress  string `json:"user-peered/crd-idx-address"`
}

// DirectRelations is the user's signed list of relations, placed inside an Envelope.
type DirectRelations struct {
	Version     int                      `json:"dr/version"`
	TimestampNs int64                    `json:"dr/timestamp-ns"`
	UserID      []byte                   `json:"dr/user-id"`

	ContactEmail          string `json:"dr/contact-email,omitempty"`
	ContactSignalUsername string `json:"dr/contact-signal-username,omitempty"`
	ContactNumber         string `json:"dr/contact-number,omitempty"`

	Contexts []DirectRelationsContext `json:"dr/contexts"`
}

// DirectRelationsContext groups relations under a context path.
type DirectRelationsContext struct {
	ContextPath []string         `json:"dr-ctx/path"`
	Relations   []DirectRelation `json:"dr-ctx/relations"`
}

// DirectRelation is a single user or URI relation.
type DirectRelation struct {
	Type string `json:"dr-rel/type"` // "user" or "uri"

	// URI relation fields.
	URI     string `json:"dr-rel-uri/uri,omitempty"`
	Name    string `json:"dr-rel-uri/name,omitempty"`
	Comment string `json:"dr-rel-uri/comment,omitempty"`

	// User relation fields.
	RelUserID       []byte   `json:"dr-rel-user/user-id,omitempty"`
	RelContextPath  []string `json:"dr-rel-user/context-path,omitempty"`
	TransitiveDepth int      `json:"dr-rel-user/transitive-depth,omitempty"`
	SubjectGlob     int      `json:"dr-rel-user/subject-glob,omitempty"`
	ObjectGlob      int      `json:"dr-rel-user/object-glob,omitempty"`
}

// ContextRelationsDeps is a peer-produced dependency set for one user+context.
type ContextRelationsDeps struct {
	Version         int                       `json:"crd/version"`
	TimestampNs     int64                     `json:"crd/timestamp-ns"`
	UserID          []byte                    `json:"crd/user-id"`
	ContextPath     []string                  `json:"crd/context-path"`
	Hops            []ContextRelationsDepsHop `json:"crd/hops"`
	SourceAddresses [][]byte                  `json:"crd/source-addresses"`
}

// ContextRelationsDepsHop holds data for one transitive hop.
type ContextRelationsDepsHop struct {
	Hop int `json:"crd-hop/hop"` // 1–10

	// Content addresses of individual direct-relations documents at this hop.
	DirectRelationsAddresses [][]byte `json:"crd-hop/dr-addresses"`

	// Content address of a compressed archive of all direct-relations at this hop.
	DirectRelationsArchiveAddress []byte `json:"crd-hop/archive-address"`

	// Total bytes of all content in this hop.
	Size int64 `json:"crd-hop/size"`
}

// ContextRelationsDepsIndex is a peer-produced index of deps per context for a user.
type ContextRelationsDepsIndex struct {
	Version     int                                `json:"crd-idx/version"`
	TimestampNs int64                              `json:"crd-idx/timestamp-ns"`
	UserID      []byte                             `json:"crd-idx/user-id"`
	Contexts    []ContextRelationsDepsIndexContext `json:"crd-idx/contexts"`
}

// ContextRelationsDepsIndexContext is one context entry in the index.
type ContextRelationsDepsIndexContext struct {
	ContextPath            []string `json:"crd-idx-ctx/path"`
	ContextRelsDepsAddress []byte   `json:"crd-idx-ctx/crd-address"`
	Hops                   int      `json:"crd-idx-ctx/hops"`
	ArchiveAddresses       [][]byte `json:"crd-idx-ctx-hop/archive-addresses"`
	Size                   int64    `json:"crd-idx-ctx/size"`
}
