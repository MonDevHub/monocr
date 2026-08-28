package middleware

import (
	"net/http"
	"net/http/httputil"
	"strings"
	"testing"
)

// The panic log used to carry the production API key.
//
// `httputil.DumpRequest(req, false)` omits the BODY, not the headers, and the
// service authenticates with one shared static key that has no revocation path.
// Panic logs go to stdout and on to Cloud Logging, so a log-viewer role was a
// credential.
func TestPanicLogDoesNotCarryTheApiKey(t *testing.T) {
	const key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	req, err := http.NewRequest(http.MethodPost, "https://example.test/v1/feedback", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-API-Key", key)
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("X-Request-ID", "keep-me")

	dumped, err := httputil.DumpRequest(redactSensitiveHeaders(req), false)
	if err != nil {
		t.Fatal(err)
	}
	got := string(dumped)

	if strings.Contains(got, key) {
		t.Fatalf("the dump still contains the credential:\n%s", got)
	}
	if !strings.Contains(got, "[REDACTED]") {
		t.Fatalf("nothing was redacted:\n%s", got)
	}
	// Redaction must not cost the fields that make a panic log useful.
	if !strings.Contains(got, "keep-me") {
		t.Fatalf("an unrelated header was dropped:\n%s", got)
	}
}

// The request is still in flight when this runs, so redaction must not mutate it.
func TestRedactionLeavesTheRequestUntouched(t *testing.T) {
	const key = "secret-key-value"
	req, err := http.NewRequest(http.MethodGet, "https://example.test/", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-API-Key", key)

	_ = redactSensitiveHeaders(req)

	if got := req.Header.Get("X-API-Key"); got != key {
		t.Fatalf("the original header was modified: %q", got)
	}
}

func TestRedactionHandlesANilRequest(t *testing.T) {
	if redactSensitiveHeaders(nil) != nil {
		t.Fatal("want nil for a nil request")
	}
}
