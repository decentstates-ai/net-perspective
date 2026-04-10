// Package ipfs provides a thin wrapper around the IPFS/IPNS HTTP API (kubo).
package ipfs

import (
	"bytes"
	"context"
	"fmt"
	"io"

	shell "github.com/ipfs/go-ipfs-api"
)

// Client wraps the IPFS HTTP API.
type Client struct {
	sh *shell.Shell
}

// New creates a Client connected to the IPFS daemon at addr (e.g. "localhost:5001").
func New(addr string) *Client {
	return &Client{sh: shell.NewShell(addr)}
}

// Ping checks connectivity to the IPFS daemon.
func (c *Client) Ping(ctx context.Context) error {
	_, err := c.sh.ID()
	return err
}

// Add stores data and returns its CID string.
func (c *Client) Add(data []byte) (string, error) {
	cid, err := c.sh.Add(bytes.NewReader(data), shell.CidVersion(1))
	if err != nil {
		return "", fmt.Errorf("ipfs add: %w", err)
	}
	return cid, nil
}

// Cat retrieves raw bytes for a CID.
func (c *Client) Cat(cid string) ([]byte, error) {
	rc, err := c.sh.Cat(cid)
	if err != nil {
		return nil, fmt.Errorf("ipfs cat %s: %w", cid, err)
	}
	defer rc.Close()
	data, err := io.ReadAll(rc)
	if err != nil {
		return nil, fmt.Errorf("reading %s: %w", cid, err)
	}
	return data, nil
}

// PublishIPNS publishes cid under the IPNS key identified by keyName.
// The key must already exist in the IPFS keystore.
func (c *Client) PublishIPNS(keyName, cid string) error {
	_, err := c.sh.PublishWithDetails("/ipfs/"+cid, keyName, 0, 0, false)
	if err != nil {
		return fmt.Errorf("ipns publish %s -> %s: %w", keyName, cid, err)
	}
	return nil
}

// ResolveIPNS resolves an IPNS name to a CID string.
func (c *Client) ResolveIPNS(ipnsAddr string) (string, error) {
	resolved, err := c.sh.Resolve(ipnsAddr)
	if err != nil {
		return "", fmt.Errorf("ipns resolve %s: %w", ipnsAddr, err)
	}
	// resolved is "/ipfs/<cid>"; strip the prefix.
	const prefix = "/ipfs/"
	if len(resolved) <= len(prefix) {
		return "", fmt.Errorf("unexpected resolved value: %q", resolved)
	}
	return resolved[len(prefix):], nil
}

// KeyGen creates a new IPFS keystore entry and returns the IPNS address.
// If a key with keyName already exists, it returns the existing address.
func (c *Client) KeyGen(ctx context.Context, keyName string) (ipnsAddr string, err error) {
	keys, err := c.sh.KeyList(ctx)
	if err != nil {
		return "", fmt.Errorf("key list: %w", err)
	}
	for _, k := range keys {
		if k.Name == keyName {
			return k.Id, nil
		}
	}
	key, err := c.sh.KeyGen(ctx, keyName, shell.KeyGen.Type("ed25519"))
	if err != nil {
		return "", fmt.Errorf("key gen %s: %w", keyName, err)
	}
	return key.Id, nil
}
