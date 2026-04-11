package doc

import (
	"encoding/json"
	"fmt"
)

// Wrap encodes content as canonical JSON, signs it, and returns an Envelope.
func Wrap(content any, kp *KeyPair) (*Envelope, error) {
	contentBytes, err := Marshal(content)
	if err != nil {
		return nil, fmt.Errorf("marshal content: %w", err)
	}
	sig, err := Sign(kp.Private, contentBytes)
	if err != nil {
		return nil, fmt.Errorf("sign: %w", err)
	}
	return &Envelope{
		Content:       json.RawMessage(contentBytes),
		UserID:        kp.UserID,
		UserPublicKey: kp.EncodedPublicKey,
		Signature:     sig,
	}, nil
}

// Unwrap verifies the envelope signature and decodes the content into v.
func Unwrap(env *Envelope, v any) error {
	pub, err := DecodePublicKey(env.UserPublicKey)
	if err != nil {
		return fmt.Errorf("decode public key: %w", err)
	}

	// Verify the declared user-id matches the public key.
	encoded := encodePublicKey(pub)
	expectedUID, err := computeUserID(encoded)
	if err != nil {
		return fmt.Errorf("compute user-id: %w", err)
	}
	if string(expectedUID) != string(env.UserID) {
		return fmt.Errorf("user-id mismatch")
	}

	contentBytes, err := Marshal(json.RawMessage(env.Content))
	if err != nil {
		return fmt.Errorf("canonicalize content: %w", err)
	}
	if !Verify(pub, contentBytes, env.Signature) {
		return fmt.Errorf("invalid signature")
	}
	return Unmarshal(env.Content, v)
}
