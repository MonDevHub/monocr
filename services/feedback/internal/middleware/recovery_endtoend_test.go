package middleware

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// The redaction, exercised through the middleware rather than in isolation.
//
// Calling `redactSensitiveHeaders` directly was not enough and a mutation proved
// it: putting `c.Request` back into `DumpRequest` left every test green, because
// none of them went through the middleware. The credential leak is at the call
// site, so the test has to be too.
func TestRecoveryMiddlewareDoesNotLogTheApiKey(t *testing.T) {
	const key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

	var logged bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logged, nil))

	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(RecoveryMiddleware(logger))
	router.POST("/v1/feedback", func(c *gin.Context) {
		panic("something went wrong deep in the upload path")
	})

	req := httptest.NewRequest(http.MethodPost, "/v1/feedback", nil)
	req.Header.Set("X-API-Key", key)
	req.Header.Set("X-Request-ID", "trace-me")
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("panic should surface as 500, got %d", recorder.Code)
	}
	out := logged.String()
	if out == "" {
		t.Fatal("the panic was not logged at all, so this test proves nothing")
	}
	if strings.Contains(out, key) {
		t.Fatalf("the API key reached the log:\n%s", out)
	}
	if !strings.Contains(out, "trace-me") {
		t.Fatalf("the request id was lost, so the log is less useful than before:\n%s", out)
	}
	// And the response body must not carry it either.
	if strings.Contains(recorder.Body.String(), key) {
		t.Fatalf("the API key reached the response body: %s", recorder.Body.String())
	}
}
