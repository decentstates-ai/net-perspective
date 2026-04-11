package doc

import (
	"crypto/rand"
	"fmt"

	"github.com/cloudflare/circl/sign/mldsa/mldsa44"
	"github.com/multiformats/go-multihash"
	varint "github.com/multiformats/go-varint"
)

// MLDsa44PubCodec is the multicodec code for ML-DSA-44 public keys.
// Draft allocation — update when officially registered in the multicodec table.
const MLDsa44PubCodec uint64 = 0x1203

// KeyPair holds an ML-DSA-44 key pair plus the precomputed user-id and encoded public key.
type KeyPair struct {
	Public  *mldsa44.PublicKey
	Private *mldsa44.PrivateKey

	// EncodedPublicKey is the multicodec-encoded public key (used as user-public-key in docs).
	EncodedPublicKey []byte

	// UserID is the multihash of EncodedPublicKey (SHA2-256).
	UserID []byte
}

// GenerateKeyPair generates a new ML-DSA-44 key pair.
func GenerateKeyPair() (*KeyPair, error) {
	pub, priv, err := mldsa44.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return keyPairFrom(pub, priv)
}

// KeyPairFromSeed reconstructs a KeyPair from a 32-byte seed.
// This is the canonical on-disk format, compatible with the Clojure implementation.
func KeyPairFromSeed(seed []byte) (*KeyPair, error) {
	if len(seed) != mldsa44.SeedSize {
		return nil, fmt.Errorf("invalid seed size: got %d, want %d", len(seed), mldsa44.SeedSize)
	}
	var buf [mldsa44.SeedSize]byte
	copy(buf[:], seed)
	pub, sk := mldsa44.NewKeyFromSeed(&buf)
	return keyPairFrom(pub, sk)
}

// KeyPairFromBytes reconstructs a KeyPair from raw private key bytes or a seed.
// Accepts both the legacy full-key format (mldsa44.PrivateKeySize bytes) and
// the current seed format (mldsa44.SeedSize bytes) so old key files still load.
func KeyPairFromBytes(data []byte) (*KeyPair, error) {
	if len(data) == mldsa44.SeedSize {
		return KeyPairFromSeed(data)
	}
	if len(data) == mldsa44.PrivateKeySize {
		var buf [mldsa44.PrivateKeySize]byte
		copy(buf[:], data)
		var sk mldsa44.PrivateKey
		sk.Unpack(&buf)
		pub := sk.Public().(*mldsa44.PublicKey)
		return keyPairFrom(pub, &sk)
	}
	return nil, fmt.Errorf("invalid key size: got %d bytes (want %d seed or %d full key)",
		len(data), mldsa44.SeedSize, mldsa44.PrivateKeySize)
}

func keyPairFrom(pub *mldsa44.PublicKey, priv *mldsa44.PrivateKey) (*KeyPair, error) {
	encoded := encodePublicKey(pub)
	uid, err := computeUserID(encoded)
	if err != nil {
		return nil, err
	}
	return &KeyPair{
		Public:           pub,
		Private:          priv,
		EncodedPublicKey: encoded,
		UserID:           uid,
	}, nil
}

// encodePublicKey returns varint(MLDsa44PubCodec) + raw public key bytes.
func encodePublicKey(pub *mldsa44.PublicKey) []byte {
	codec := varint.ToUvarint(MLDsa44PubCodec)
	return append(codec, pub.Bytes()...)
}

// DecodePublicKey reconstructs a PublicKey from multicodec-encoded bytes.
func DecodePublicKey(encoded []byte) (*mldsa44.PublicKey, error) {
	code, n, err := varint.FromUvarint(encoded)
	if err != nil {
		return nil, fmt.Errorf("reading codec varint: %w", err)
	}
	if code != MLDsa44PubCodec {
		return nil, fmt.Errorf("unexpected codec 0x%x, want 0x%x", code, MLDsa44PubCodec)
	}
	raw := encoded[n:]
	if len(raw) != mldsa44.PublicKeySize {
		return nil, fmt.Errorf("invalid public key size: got %d, want %d", len(raw), mldsa44.PublicKeySize)
	}
	var buf [mldsa44.PublicKeySize]byte
	copy(buf[:], raw)
	var pk mldsa44.PublicKey
	pk.Unpack(&buf)
	return &pk, nil
}

// computeUserID returns multihash(SHA2-256, encoded).
func computeUserID(encoded []byte) ([]byte, error) {
	mh, err := multihash.Sum(encoded, multihash.SHA2_256, -1)
	if err != nil {
		return nil, err
	}
	return []byte(mh), nil
}

// Sign signs msg with the private key. Uses deterministic (non-randomized) signing.
func Sign(sk *mldsa44.PrivateKey, msg []byte) ([]byte, error) {
	sig := make([]byte, mldsa44.SignatureSize)
	if err := mldsa44.SignTo(sk, msg, nil, false, sig); err != nil {
		return nil, err
	}
	return sig, nil
}

// Verify checks whether sig is a valid ML-DSA-44 signature over msg by pub.
func Verify(pub *mldsa44.PublicKey, msg, sig []byte) bool {
	return mldsa44.Verify(pub, msg, nil, sig)
}

