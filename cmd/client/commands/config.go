// Package commands implements the np-client subcommands.
package commands

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Config holds client-side persistent configuration.
type Config struct {
	Dir       string   // config directory
	PeerHomes []string // peer server addresses
}

// DefaultConfig returns a Config with the default directory (~/.config/np-client).
func DefaultConfig() *Config {
	home, _ := os.UserHomeDir()
	return &Config{
		Dir: filepath.Join(home, ".config", "np-client"),
	}
}

type persistedConfig struct {
	PeerHomes []string `json:"peer-homes"`
}

func (c *Config) configFile() string { return filepath.Join(c.Dir, "config.json") }
func (c *Config) keyFile() string    { return filepath.Join(c.Dir, "private.key") }
func (c *Config) drFile() string     { return filepath.Join(c.Dir, "direct-relations.json") }

// Save writes the current config to disk.
func (c *Config) Save() error {
	if err := os.MkdirAll(c.Dir, 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(persistedConfig{PeerHomes: c.PeerHomes}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.configFile(), data, 0600)
}

// Load reads persisted config from disk, merging into c.
func (c *Config) Load() error {
	data, err := os.ReadFile(c.configFile())
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var p persistedConfig
	if err := json.Unmarshal(data, &p); err != nil {
		return fmt.Errorf("config.json: %w", err)
	}
	// Command-line --peers takes priority over saved value.
	if len(c.PeerHomes) == 0 {
		c.PeerHomes = p.PeerHomes
	}
	return nil
}
