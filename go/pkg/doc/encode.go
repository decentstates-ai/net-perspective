package doc

import (
	"encoding/json"
	"fmt"

	"github.com/gowebpki/jcs"
)

// Marshal encodes v as RFC 8785 canonical JSON.
func Marshal(v any) ([]byte, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	canonical, err := jcs.Transform(raw)
	if err != nil {
		return nil, fmt.Errorf("jcs transform: %w", err)
	}
	return canonical, nil
}

// Unmarshal decodes RFC 8785 JSON into v. Accepts any valid JSON input.
func Unmarshal(data []byte, v any) error {
	return json.Unmarshal(data, v)
}
