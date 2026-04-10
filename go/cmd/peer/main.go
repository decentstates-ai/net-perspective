// Command peer runs a net-perspective peer server.
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/peer"
)

func main() {
	ipfsAddr := flag.String("ipfs", "localhost:5001", "IPFS API address")
	listenAddr := flag.String("listen", ":8080", "HTTP listen address")
	keyFile := flag.String("key", "peer.key", "Path to peer private key file (created if absent)")
	flag.Parse()

	kp, err := loadOrCreateKey(*keyFile)
	if err != nil {
		log.Fatalf("key: %v", err)
	}
	log.Printf("peer identity: %x", kp.UserID)

	srv := peer.NewServer(*ipfsAddr, *listenAddr, kp)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go srv.RunScheduler(ctx)

	mux := http.NewServeMux()
	srv.Routes(mux)

	httpSrv := &http.Server{Addr: *listenAddr, Handler: mux}
	go func() {
		<-ctx.Done()
		httpSrv.Shutdown(context.Background())
	}()

	log.Printf("listening on %s", *listenAddr)
	if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func loadOrCreateKey(path string) (*doc.KeyPair, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		kp, err := doc.GenerateKeyPair()
		if err != nil {
			return nil, err
		}
		if err := os.WriteFile(path, kp.Private.Seed(), 0600); err != nil {
			return nil, err
		}
		log.Printf("generated new key pair, saved to %s", path)
		return kp, nil
	}
	if err != nil {
		return nil, err
	}
	return doc.KeyPairFromBytes(data)
}
