package peer

import (
	"context"
	"log"
	"time"
)

const batchInterval = 10 * time.Minute

// RunScheduler runs the batch update every 10 minutes.
// It cancels and replaces any in-progress run before starting the next.
// Call with a parent context; returns when the parent is cancelled.
func (s *Server) RunScheduler(ctx context.Context) {
	var (
		runCancel context.CancelFunc
		done      = make(chan struct{}, 1)
	)
	runBatch := func() {
		if runCancel != nil {
			runCancel() // cancel prior run
			<-done      // wait for it to exit
		}
		var runCtx context.Context
		runCtx, runCancel = context.WithCancel(ctx)
		go func() {
			defer func() { done <- struct{}{} }()
			if err := s.RunBatch(runCtx); err != nil && runCtx.Err() == nil {
				log.Printf("batch run error: %v", err)
			}
		}()
	}

	// Run immediately on startup, then on each tick.
	runBatch()
	ticker := time.NewTicker(batchInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			if runCancel != nil {
				runCancel()
				<-done
			}
			return
		case <-ticker.C:
			runBatch()
		}
	}
}
