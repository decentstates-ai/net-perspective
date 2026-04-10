package commands

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/spf13/cobra"
)

// FetchIndexCmd creates the `fetch-index` subcommand.
func FetchIndexCmd(cfg *Config) *cobra.Command {
	var peerAddr string
	cmd := &cobra.Command{
		Use:   "fetch-index <ipns-address>",
		Short: "Fetch and display the context-relations-deps-index for a user",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := cfg.Load(); err != nil {
				return err
			}

			ipnsAddr := args[0]
			peer := peerAddr
			if peer == "" && len(cfg.PeerHomes) > 0 {
				peer = cfg.PeerHomes[0]
			}
			if peer == "" {
				return fmt.Errorf("no peer address; use --peer or configure peers")
			}
			if !strings.HasPrefix(peer, "http") {
				peer = "http://" + peer
			}

			// Fetch user-info via the peer's IPNS resolve endpoint.
			uiURL := peer + "/user/" + ipnsAddr
			resp, err := http.Get(uiURL)
			if err != nil {
				return fmt.Errorf("fetch user-info: %w", err)
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return fmt.Errorf("fetch user-info: status %s", resp.Status)
			}

			uiBody, err := io.ReadAll(resp.Body)
			if err != nil {
				return err
			}

			var env doc.Envelope
			if err := doc.Unmarshal(uiBody, &env); err != nil {
				return fmt.Errorf("decode envelope: %w", err)
			}
			var ui doc.UserInfo
			if err := doc.Unwrap(&env, &ui); err != nil {
				return fmt.Errorf("verify user-info: %w", err)
			}

			if ui.ContextRelsDepsIndexIPNSAddress == "" {
				fmt.Println("no context-relations-deps-index published yet")
				return nil
			}

			// Fetch the index by CID.
			indexURL := peer + "/cid/" + ui.ContextRelsDepsIndexIPNSAddress
			idxResp, err := http.Get(indexURL)
			if err != nil {
				return fmt.Errorf("fetch index: %w", err)
			}
			defer idxResp.Body.Close()
			idxBody, err := io.ReadAll(idxResp.Body)
			if err != nil {
				return err
			}

			var index doc.ContextRelationsDepsIndex
			if err := doc.Unmarshal(idxBody, &index); err != nil {
				return fmt.Errorf("decode index: %w", err)
			}

			out, err := json.MarshalIndent(index, "", "  ")
			if err != nil {
				return err
			}
			fmt.Println(string(out))
			return nil
		},
	}
	cmd.Flags().StringVar(&peerAddr, "peer", "", "peer address to query (overrides config)")
	return cmd
}
