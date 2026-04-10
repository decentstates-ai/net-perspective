package ipfs

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
)

// Store is the interface the peer uses for content storage and IPNS.
// The real implementation is Client; tests use MemStore.
type Store interface {
	Add(data []byte) (cid string, err error)
	Cat(cid string) ([]byte, error)
	PublishIPNS(keyName, cid string) error
	ResolveIPNS(ipnsAddr string) (string, error)
	Ping(ctx context.Context) error
}

// MemStore is an in-memory Store for tests.
type MemStore struct {
	mu   sync.RWMutex
	data map[string][]byte
	ipns map[string]string // keyName -> cid
	seq  atomic.Uint64
}

func NewMemStore() *MemStore {
	return &MemStore{
		data: make(map[string][]byte),
		ipns: make(map[string]string),
	}
}

func (m *MemStore) Add(data []byte) (string, error) {
	n := m.seq.Add(1)
	cid := fmt.Sprintf("bafytest%08d", n)
	cp := make([]byte, len(data))
	copy(cp, data)
	m.mu.Lock()
	m.data[cid] = cp
	m.mu.Unlock()
	return cid, nil
}

func (m *MemStore) Cat(cid string) ([]byte, error) {
	m.mu.RLock()
	d, ok := m.data[cid]
	m.mu.RUnlock()
	if !ok {
		return nil, fmt.Errorf("not found: %s", cid)
	}
	cp := make([]byte, len(d))
	copy(cp, d)
	return cp, nil
}

func (m *MemStore) PublishIPNS(keyName, cid string) error {
	m.mu.Lock()
	m.ipns[keyName] = cid
	m.mu.Unlock()
	return nil
}

func (m *MemStore) ResolveIPNS(ipnsAddr string) (string, error) {
	m.mu.RLock()
	cid, ok := m.ipns[ipnsAddr]
	m.mu.RUnlock()
	if !ok {
		return "", fmt.Errorf("ipns not found: %s", ipnsAddr)
	}
	return cid, nil
}

func (m *MemStore) Ping(_ context.Context) error { return nil }
