package upload

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"ocr-feedback-service/internal/middleware"
)

// The two ways a request could get the wrong answer out of handleUpload: an
// oversize body reported as a missing file part, and an empty file reported as
// success. Both drive a real multipart request through the handler, reusing the
// fakeUploader from handler_request_test.go so nothing touches Cloudflare.

// postFile posts one part with the exact bytes given, through a router with the
// body limit wired the way newRouter wires it.
func postFile(t *testing.T, h *Handler, bodyLimit int64, filename string, content []byte) *httptest.ResponseRecorder {
	t.Helper()

	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, err := w.CreateFormFile("file", filename)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(content); err != nil {
		t.Fatal(err)
	}
	if err := w.WriteField("record_id", "550e8400-e29b-41d4-a716-446655440000"); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(middleware.RequestIDMiddleware())
	router.Use(middleware.BodyLimitMiddleware(bodyLimit))
	router.POST("/v1/feedback", h.UploadFeedback)

	req := httptest.NewRequest(http.MethodPost, "/v1/feedback", &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

// Oversize must be 413, and the message must name the limit.
//
// Measured on the pre-fix handler with a 1KB limit and an 8KB body: 400
// {"error":"No file part in the request (expected 'file')"} — while
// shared/contract/README.md:51 promises 413. Neither mobile client branches on
// status code, so that answer was retried up to 5 times with the same oversize
// body instead of being reported to the user as a file that is too big.
func TestOversizeUploadIsRejectedWith413(t *testing.T) {
	const limit = 1 << 20 // 1MB, so the message has a whole number to name

	fake := &fakeUploader{}
	h := NewHandler(fake, limit)

	oversize := append(append([]byte{}, pngBytes...), bytes.Repeat([]byte{0x41}, 3<<20)...)
	rec := postFile(t, h, limit, "big.png", oversize)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize upload answered %d, want 413: %s", rec.Code, rec.Body.String())
	}
	// A 413 that does not say what the limit is leaves the client guessing how
	// much less to send, which is the only useful thing it can do with this.
	if !strings.Contains(rec.Body.String(), "1 MB") {
		t.Errorf("413 body does not name the limit: %s", rec.Body.String())
	}
	if len(fake.keys) != 0 {
		t.Errorf("an oversize upload reached storage as %v", fake.keys)
	}
}

// A within-limit upload must still succeed, so the test above cannot be
// satisfied by answering 413 to everything.
func TestWithinLimitUploadStillSucceeds(t *testing.T) {
	fake := &fakeUploader{}
	h := NewHandler(fake, 1<<20)

	rec := postFile(t, h, 1<<20, "small.png", pngBytes)

	if rec.Code != http.StatusOK {
		t.Fatalf("a small upload answered %d, want 200: %s", rec.Code, rec.Body.String())
	}
	if len(fake.keys) != 1 {
		t.Fatalf("want exactly one upload, got %d", len(fake.keys))
	}
}

// Zero bytes must be rejected, and must not reach storage.
//
// The MIME whitelist cannot catch this: mimetype.Detect on an empty buffer
// returns the library's fallback root, text/plain, and text/plain is on the
// whitelist. Measured on the pre-fix handler: a 0-byte part named empty.png
// answered 200 and wrote feedback/2026-08/<record-id>-empty.png with
// detected-mime text/plain. The cost is a corpus row that nothing can use and
// that no reader can tell from a real sample without fetching it.
func TestEmptyUploadIsRejectedAndNeverStored(t *testing.T) {
	for _, name := range []string{"empty.png", "empty.txt", "empty.pdf"} {
		t.Run(name, func(t *testing.T) {
			fake := &fakeUploader{}
			h := NewHandler(fake, 20<<20)

			rec := postFile(t, h, 20<<20, name, nil)

			if rec.Code != http.StatusBadRequest {
				t.Errorf("empty upload answered %d, want 400: %s", rec.Code, rec.Body.String())
			}
			// The assertion that actually protects the corpus.
			if len(fake.keys) != 0 {
				t.Fatalf("an empty file was streamed to storage as %v", fake.keys)
			}
		})
	}
}
