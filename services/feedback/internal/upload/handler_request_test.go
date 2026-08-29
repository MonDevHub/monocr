package upload

import (
	"bytes"
	"context"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// The first test in this service that drives an actual HTTP request through the
// upload handler.
//
// Before 2026-08-28 there was none, and the consequence was measured rather than
// guessed: with the API-key check, the MIME whitelist and the rate limiter all
// replaced by `if false`, `gofmt`, `go vet`, `go build`, `go test` and
// `govulncheck` all stayed green and a Windows executable uploaded with no key.
// The `go` CI job could not fail on any of it.
//
// This covers the object key, which is the part a client can steer.

// fakeUploader records what the handler asked storage to do.
type fakeUploader struct {
	keys        []string
	contentType string
	metadata    map[string]string
	body        []byte
	err         error
}

func (f *fakeUploader) UploadFile(
	_ context.Context, key string, body io.Reader, contentType string, metadata map[string]string,
) error {
	f.keys = append(f.keys, key)
	f.contentType = contentType
	f.metadata = metadata
	if body != nil {
		f.body, _ = io.ReadAll(body)
	}
	return f.err
}

// A PNG header, so the MIME sniffer admits the part.
var pngBytes = append(
	[]byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
	bytes.Repeat([]byte{0x00}, 64)...,
)

func postUpload(t *testing.T, h *Handler, route string, recordID, originalName string) *httptest.ResponseRecorder {
	t.Helper()

	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, err := w.CreateFormFile("file", "upload.png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(pngBytes); err != nil {
		t.Fatal(err)
	}
	if err := w.WriteField("record_id", recordID); err != nil {
		t.Fatal(err)
	}
	if err := w.WriteField("original_name", originalName); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/v1/feedback", h.UploadFeedback)
	router.POST("/v1/contribution", h.UploadContribution)

	req := httptest.NewRequest(http.MethodPost, route, &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

func TestUploadKeyStaysInsideItsPrefix(t *testing.T) {
	cases := []struct {
		name       string
		route      string
		recordID   string
		original   string
		wantPrefix string
	}{
		{"a real upload", "/v1/feedback", "550e8400-e29b-41d4-a716-446655440000", "page.png", "feedback/"},
		{"traversal in record_id", "/v1/feedback", "../../../../etc/passwd", "x.png", "feedback/"},
		{"crossing into contribution", "/v1/feedback", "contribution/2026-08/OVERWRITE", "x.png", "feedback/"},
		{"traversal in the filename", "/v1/contribution", "550e8400-e29b-41d4-a716-446655440000", "../../evil.png", "contribution/"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fake := &fakeUploader{}
			h := NewHandler(fake, 20<<20)

			rec := postUpload(t, h, c.route, c.recordID, c.original)

			if rec.Code != http.StatusOK {
				t.Fatalf("want 200, got %d: %s", rec.Code, rec.Body.String())
			}
			if len(fake.keys) != 1 {
				t.Fatalf("want exactly one upload, got %d", len(fake.keys))
			}
			key := fake.keys[0]

			if !strings.HasPrefix(key, c.wantPrefix) {
				t.Fatalf("key %q escaped the prefix %q", key, c.wantPrefix)
			}
			// The invariant. Two separators are the prefix's own; a third means a
			// client chose part of the path.
			if n := strings.Count(key, "/"); n != 2 {
				t.Fatalf("key %q has %d separators, want 2", key, n)
			}
			if strings.Contains(key, "..") {
				t.Fatalf("key %q still contains a traversal", key)
			}
		})
	}
}

// The two routes must not be able to write into each other's namespace, which is
// what makes the prefix meaningful for anything reading the corpus.
func TestTheTwoRoutesCannotReachEachOthersPrefix(t *testing.T) {
	feedback := &fakeUploader{}
	postUpload(t, NewHandler(feedback, 20<<20), "/v1/feedback", "contribution/x", "a.png")

	contribution := &fakeUploader{}
	postUpload(t, NewHandler(contribution, 20<<20), "/v1/contribution", "feedback/x", "a.png")

	if len(feedback.keys) != 1 || len(contribution.keys) != 1 {
		t.Fatal("both routes should have uploaded exactly once")
	}
	if !strings.HasPrefix(feedback.keys[0], "feedback/") {
		t.Fatalf("feedback route wrote %q", feedback.keys[0])
	}
	if !strings.HasPrefix(contribution.keys[0], "contribution/") {
		t.Fatalf("contribution route wrote %q", contribution.keys[0])
	}
}
