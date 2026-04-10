// Command client is the net-perspective user-client CLI.
package main

import (
	"os"

	"github.com/decentstates/net-perspective/cmd/client/commands"
	"github.com/spf13/cobra"
)

func main() {
	root := &cobra.Command{
		Use:   "np-client",
		Short: "net-perspective user client",
	}

	cfg := commands.DefaultConfig()
	root.PersistentFlags().StringVar(&cfg.Dir, "dir", cfg.Dir, "config directory")
	root.PersistentFlags().StringSliceVar(&cfg.PeerHomes, "peers", cfg.PeerHomes, "peer home addresses (comma-separated)")

	root.AddCommand(
		commands.InitCmd(cfg),
		commands.SubmitCmd(cfg),
		commands.FetchIndexCmd(cfg),
	)

	if err := root.Execute(); err != nil {
		os.Exit(1)
	}
}
