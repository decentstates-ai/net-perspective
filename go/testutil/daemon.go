// Package testutil provides test helpers for net-perspective.
package testutil

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/decentstates/net-perspective/pkg/ipfs"
)

// StartIPFS starts a private kubo daemon in a temporary directory and returns
// a client connected to it. The daemon is stopped and the directory cleaned up
// when the test ends.
//
// The test is skipped if the `ipfs` binary is not found in PATH.
func StartIPFS(t testing.TB) *ipfs.Client {
	t.Helper()

	bin, err := exec.LookPath("ipfs")
	if err != nil {
		t.Skip("ipfs binary not found in PATH; run tests inside the nix devShell")
	}

	repoDir := t.TempDir()
	env := append(os.Environ(), "IPFS_PATH="+repoDir)

	run := func(args ...string) {
		t.Helper()
		cmd := exec.Command(bin, args...)
		cmd.Env = env
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("ipfs %v: %v\n%s", args, err, out)
		}
	}

	// Initialise a minimal repo with the test profile (no bootstrap peers).
	run("init", "--profile=test", "--empty-repo")

	apiPort := freePort(t)
	swarmPort := freePort(t)
	gatewayPort := freePort(t)

	// Configure addresses.
	run("config", "Addresses.API",
		fmt.Sprintf("/ip4/127.0.0.1/tcp/%d", apiPort))
	run("config", "Addresses.Gateway",
		fmt.Sprintf("/ip4/127.0.0.1/tcp/%d", gatewayPort))

	swarmAddrs, _ := json.Marshal([]string{
		fmt.Sprintf("/ip4/127.0.0.1/tcp/%d", swarmPort),
	})
	run("config", "--json", "Addresses.Swarm", string(swarmAddrs))

	// Start the daemon in offline mode (no swarm connections, no DHT).
	daemon := exec.Command(bin, "daemon", "--offline")
	daemon.Env = env
	// Direct output to test log for debugging failures.
	daemon.Stdout = &testLogger{t: t, prefix: "ipfs: "}
	daemon.Stderr = &testLogger{t: t, prefix: "ipfs: "}
	if err := daemon.Start(); err != nil {
		t.Fatalf("start ipfs daemon: %v", err)
	}

	t.Cleanup(func() {
		daemon.Process.Signal(os.Interrupt)
		daemon.Wait()
	})

	// Wait until the API is responsive.
	apiAddr := fmt.Sprintf("127.0.0.1:%d", apiPort)
	client := ipfs.New(apiAddr)
	deadline := time.Now().Add(30 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		err := client.Ping(ctx)
		cancel()
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("ipfs daemon did not start within 30s: %v", err)
		}
		time.Sleep(200 * time.Millisecond)
	}

	t.Logf("ipfs daemon ready at %s (repo %s)", apiAddr, filepath.Base(repoDir))
	return client
}

func freePort(t testing.TB) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("find free port: %v", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port
}

// testLogger forwards daemon output to t.Log line by line.
type testLogger struct {
	t      testing.TB
	prefix string
	buf    []byte
}

func (tl *testLogger) Write(p []byte) (int, error) {
	tl.buf = append(tl.buf, p...)
	for {
		idx := -1
		for i, b := range tl.buf {
			if b == '\n' {
				idx = i
				break
			}
		}
		if idx < 0 {
			break
		}
		tl.t.Log(tl.prefix + string(tl.buf[:idx]))
		tl.buf = tl.buf[idx+1:]
	}
	return len(p), nil
}
