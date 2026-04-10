package ipfs_test

import (
	"context"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/ipfs"
)

// requireDaemon skips the test if no IPFS daemon is reachable.
func requireDaemon(t *testing.T, c *ipfs.Client) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := c.Ping(ctx); err != nil {
		t.Skipf("no IPFS daemon at default address: %v", err)
	}
}

func TestStoreAndRetrieve(t *testing.T) {
	c := ipfs.New("localhost:5001")
	requireDaemon(t, c)

	kp, err := doc.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	ui := doc.UserInfo{
		Version:     1,
		TimestampNs: time.Now().UnixNano(),
		UserID:      kp.UserID,
		UserPublicKey: kp.EncodedPublicKey,
	}
	env, err := doc.Wrap(ui, kp)
	if err != nil {
		t.Fatal(err)
	}

	data, err := doc.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}

	cid, err := c.Add(data)
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	t.Logf("stored as CID %s", cid)

	retrieved, err := c.Cat(cid)
	if err != nil {
		t.Fatalf("Cat: %v", err)
	}

	var env2 doc.Envelope
	if err := doc.Unmarshal(retrieved, &env2); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	var ui2 doc.UserInfo
	if err := doc.Unwrap(&env2, &ui2); err != nil {
		t.Fatalf("unwrap: %v", err)
	}

	if string(ui2.UserID) != string(ui.UserID) {
		t.Errorf("user-id mismatch after store/retrieve")
	}
}
