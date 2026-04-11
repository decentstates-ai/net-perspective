package commands

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/spf13/cobra"
)

// SubmitCmd creates the `submit` subcommand.
func SubmitCmd(cfg *Config) *cobra.Command {
	return &cobra.Command{
		Use:   "submit",
		Short: "Sign and submit direct-relations to configured peer(s)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := cfg.Load(); err != nil {
				return err
			}
			if len(cfg.PeerHomes) == 0 {
				return fmt.Errorf("no peers configured; use --peers or add to config.json")
			}

			kp, err := loadKeyPair(cfg.keyFile())
			if err != nil {
				return err
			}

			var dr doc.DirectRelations
			drBytes, err := os.ReadFile(cfg.drFile())
			if err != nil {
				return fmt.Errorf("reading %s: %w", cfg.drFile(), err)
			}
			if err := json.Unmarshal(drBytes, &dr); err != nil {
				return fmt.Errorf("parsing direct-relations: %w", err)
			}

			dr.TimestampNs = time.Now().UnixNano()
			dr.UserID = kp.UserID

			drEnv, err := doc.Wrap(dr, kp)
			if err != nil {
				return fmt.Errorf("signing direct-relations: %w", err)
			}

			ui := doc.UserInfo{
				Version:       1,
				TimestampNs:   dr.TimestampNs,
				UserID:        kp.UserID,
				UserPublicKey: kp.EncodedPublicKey,
			}
			uiEnv, err := doc.Wrap(ui, kp)
			if err != nil {
				return fmt.Errorf("signing user-info: %w", err)
			}

			body, err := json.Marshal(map[string]any{
				"user-env": uiEnv,
				"dr-env":   drEnv,
			})
			if err != nil {
				return err
			}

			var lastErr error
			for _, peer := range cfg.PeerHomes {
				url := "http://" + peer + "/submit"
				resp, err := http.Post(url, "application/json", bytes.NewReader(body))
				if err != nil {
					fmt.Fprintf(cmd.ErrOrStderr(), "peer %s: %v\n", peer, err)
					lastErr = err
					continue
				}
				resp.Body.Close()
				if resp.StatusCode == http.StatusOK {
					fmt.Printf("submitted to %s\n", peer)
				} else {
					fmt.Fprintf(cmd.ErrOrStderr(), "peer %s: status %s\n", peer, resp.Status)
					lastErr = fmt.Errorf("status %s", resp.Status)
				}
			}
			return lastErr
		},
	}
}

func loadKeyPair(path string) (*doc.KeyPair, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading key %s: %w (run `np-client init` first)", path, err)
	}
	return doc.KeyPairFromBytes(data)
}
