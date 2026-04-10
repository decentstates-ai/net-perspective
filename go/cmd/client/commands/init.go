package commands

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/spf13/cobra"
)

// InitCmd creates the `init` subcommand.
func InitCmd(cfg *Config) *cobra.Command {
	return &cobra.Command{
		Use:   "init",
		Short: "Generate key pair and create an empty direct-relations document",
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := os.MkdirAll(cfg.Dir, 0700); err != nil {
				return err
			}

			// Load any existing config (peer list etc).
			_ = cfg.Load()

			// Don't overwrite an existing key.
			if _, err := os.Stat(cfg.keyFile()); err == nil {
				return fmt.Errorf("already initialised: %s exists", cfg.keyFile())
			}

			kp, err := doc.GenerateKeyPair()
			if err != nil {
				return err
			}
			if err := os.WriteFile(cfg.keyFile(), kp.Private.Seed(), 0600); err != nil {
				return err
			}

			// Write empty direct-relations.
			dr := doc.DirectRelations{
				Version:     1,
				TimestampNs: time.Now().UnixNano(),
				UserID:      kp.UserID,
				Contexts:    []doc.DirectRelationsContext{},
			}
			drBytes, err := json.MarshalIndent(dr, "", "  ")
			if err != nil {
				return err
			}
			if err := os.WriteFile(cfg.drFile(), drBytes, 0600); err != nil {
				return err
			}

			if err := cfg.Save(); err != nil {
				return err
			}

			fmt.Printf("initialised in %s\n", cfg.Dir)
			fmt.Printf("user-id: %x\n", kp.UserID)
			return nil
		},
	}
}
