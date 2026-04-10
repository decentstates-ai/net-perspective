// cross-compat is a helper for cross-language wire-compatibility testing.
//
// Modes:
//
//	produce      – generate a key pair, sign a DR envelope, print JSON to stdout
//	verify       – read JSON from stdin, verify signature, exit 0 on success
//	seed-roundtrip – read a base64 seed from stdin, reconstruct key pair, print encoded public key
//
// Fixture JSON format for produce/verify:
//
//	{
//	  "encoded_public_key": "<base64>",
//	  "envelope": { ... }        // serialised net-perspective envelope
//	}
package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
)

type fixture struct {
	EncodedPublicKey string          `json:"encoded_public_key"`
	Envelope         json.RawMessage `json:"envelope"`
}

func produce() {
	kp, err := doc.GenerateKeyPair()
	if err != nil {
		fmt.Fprintln(os.Stderr, "GenerateKeyPair:", err)
		os.Exit(1)
	}

	dr := doc.DirectRelations{
		Version:     1,
		TimestampNs: time.Now().UnixNano(),
		UserID:      kp.UserID,
		Contexts: []doc.DirectRelationsContext{
			{
				ContextPath: []string{"cross-compat"},
				Relations: []doc.DirectRelation{
					{Type: "uri", URI: "https://cross-compat.example.com"},
				},
			},
		},
	}

	env, err := doc.Wrap(dr, kp)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Wrap:", err)
		os.Exit(1)
	}

	envJSON, err := json.Marshal(env)
	if err != nil {
		fmt.Fprintln(os.Stderr, "marshal envelope:", err)
		os.Exit(1)
	}

	out := fixture{
		EncodedPublicKey: base64.StdEncoding.EncodeToString(kp.EncodedPublicKey),
		Envelope:         envJSON,
	}
	if err := json.NewEncoder(os.Stdout).Encode(out); err != nil {
		fmt.Fprintln(os.Stderr, "encode output:", err)
		os.Exit(1)
	}
}

func verify() {
	var fix fixture
	if err := json.NewDecoder(os.Stdin).Decode(&fix); err != nil {
		fmt.Fprintln(os.Stderr, "decode input:", err)
		os.Exit(1)
	}

	var env doc.Envelope
	if err := json.Unmarshal(fix.Envelope, &env); err != nil {
		fmt.Fprintln(os.Stderr, "decode envelope:", err)
		os.Exit(1)
	}

	var dr doc.DirectRelations
	if err := doc.Unwrap(&env, &dr); err != nil {
		fmt.Fprintln(os.Stderr, "Unwrap failed:", err)
		os.Exit(1)
	}

	fmt.Printf("ok: DR version=%d contexts=%d\n", dr.Version, len(dr.Contexts))
}

// seedRoundTrip reads a base64 seed from stdin, reconstructs the key pair,
// and prints the base64-encoded public key — used to verify seed compatibility.
func seedRoundTrip() {
	var b64seed string
	if _, err := fmt.Scan(&b64seed); err != nil {
		fmt.Fprintln(os.Stderr, "read seed:", err)
		os.Exit(1)
	}
	seed, err := base64.StdEncoding.DecodeString(b64seed)
	if err != nil {
		fmt.Fprintln(os.Stderr, "decode seed:", err)
		os.Exit(1)
	}
	kp, err := doc.KeyPairFromSeed(seed)
	if err != nil {
		fmt.Fprintln(os.Stderr, "KeyPairFromSeed:", err)
		os.Exit(1)
	}
	fmt.Println(base64.StdEncoding.EncodeToString(kp.EncodedPublicKey))
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: cross-compat <produce|verify|seed-roundtrip>")
		os.Exit(1)
	}
	switch os.Args[1] {
	case "produce":
		produce()
	case "verify":
		verify()
	case "seed-roundtrip":
		seedRoundTrip()
	default:
		fmt.Fprintln(os.Stderr, "unknown mode:", os.Args[1])
		os.Exit(1)
	}
}
