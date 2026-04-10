package doc_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
)

func TestKeyPairGeneration(t *testing.T) {
	kp, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	if len(kp.UserID) == 0 {
		t.Fatal("empty user-id")
	}
	if len(kp.EncodedPublicKey) == 0 {
		t.Fatal("empty encoded public key")
	}
}

func TestKeyPairRoundTrip(t *testing.T) {
	kp, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	kp2, err := doc.KeyPairFromBytes(kp.Private.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if string(kp.UserID) != string(kp2.UserID) {
		t.Fatal("user-id mismatch after round-trip")
	}
}

func TestEnvelopeRoundTrip(t *testing.T) {
	kp, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	dr := doc.DirectRelations{
		Version:     1,
		TimestampNs: time.Now().UnixNano(),
		UserID:      kp.UserID,
		Contexts: []doc.DirectRelationsContext{
			{
				ContextPath: []string{"food"},
				Relations: []doc.DirectRelation{
					{
						Type:    "uri",
						URI:     "https://example.com/recipe",
						Name:    "Great pasta recipe",
						Comment: "simple and quick",
					},
				},
			},
		},
	}

	env, err := doc.Wrap(dr, kp)
	if err != nil {
		t.Fatal(err)
	}

	var decoded doc.DirectRelations
	if err := doc.Unwrap(env, &decoded); err != nil {
		t.Fatalf("unwrap: %v", err)
	}

	if decoded.Version != dr.Version {
		t.Errorf("version: got %d, want %d", decoded.Version, dr.Version)
	}
	if len(decoded.Contexts) != 1 {
		t.Errorf("contexts: got %d, want 1", len(decoded.Contexts))
	}
}

func TestEnvelopeRejectsWrongKey(t *testing.T) {
	kp1, _ := doc.GenerateKeyPair()
	kp2, _ := doc.GenerateKeyPair()

	dr := doc.DirectRelations{Version: 1, TimestampNs: 1, UserID: kp1.UserID}
	env, err := doc.Wrap(dr, kp1)
	if err != nil {
		t.Fatal(err)
	}

	// Swap in kp2's public key — should fail user-id check.
	env.UserPublicKey = kp2.EncodedPublicKey
	env.UserID = kp2.UserID

	var decoded doc.DirectRelations
	if err := doc.Unwrap(env, &decoded); err == nil {
		t.Fatal("expected error for wrong key, got nil")
	}
}

func TestEnvelopeRejectsTamperedContent(t *testing.T) {
	kp, _ := doc.GenerateKeyPair()
	dr := doc.DirectRelations{Version: 1, TimestampNs: 1, UserID: kp.UserID}
	env, _ := doc.Wrap(dr, kp)

	// Tamper with the content.
	env.Content = json.RawMessage(`{"direct-relations/direct-relations-version":99}`)

	var decoded doc.DirectRelations
	if err := doc.Unwrap(env, &decoded); err == nil {
		t.Fatal("expected error for tampered content, got nil")
	}
}

func TestCanonicalEncoding(t *testing.T) {
	// RFC 8785 requires keys sorted, no extra whitespace.
	type simple struct {
		Z string `json:"z"`
		A string `json:"a"`
	}
	out, err := doc.Marshal(simple{Z: "last", A: "first"})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"a":"first","z":"last"}`
	if string(out) != want {
		t.Errorf("canonical JSON: got %s, want %s", out, want)
	}
}
