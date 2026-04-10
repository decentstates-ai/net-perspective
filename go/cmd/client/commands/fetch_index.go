package commands

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/decentstates/net-perspective/pkg/doc"
	"github.com/decentstates/net-perspective/pkg/peer"
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
			peerBase := peerAddr
			if peerBase == "" && len(cfg.PeerHomes) > 0 {
				peerBase = cfg.PeerHomes[0]
			}
			if peerBase == "" {
				return fmt.Errorf("no peer address; use --peer or configure peers")
			}
			if !strings.HasPrefix(peerBase, "http") {
				peerBase = "http://" + peerBase
			}

			// Resolve IPNS → user-info envelope → verify → extract user-id.
			uiURL := peerBase + "/user/" + ipnsAddr
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

			// Look up the index CID via the peer's status endpoint.
			hexUserID := hex.EncodeToString(ui.UserID)
			stResp, err := http.Get(peerBase + "/status/users/" + hexUserID)
			if err != nil {
				return fmt.Errorf("fetch status: %w", err)
			}
			defer stResp.Body.Close()
			if stResp.StatusCode != http.StatusOK {
				return fmt.Errorf("fetch status: %s", stResp.Status)
			}
			var st peer.UserStatus
			if err := json.NewDecoder(stResp.Body).Decode(&st); err != nil {
				return fmt.Errorf("decode status: %w", err)
			}
			if st.IndexCID == "" {
				fmt.Println("no context-relations-deps-index published yet")
				return nil
			}

			// Fetch the index by CID.
			idxResp, err := http.Get(peerBase + "/cid/" + st.IndexCID)
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
