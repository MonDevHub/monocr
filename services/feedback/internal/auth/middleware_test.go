package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func serve(t *testing.T, header string, set bool) int {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/", APIKeyMiddleware(), func(c *gin.Context) { c.Status(http.StatusOK) })
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	if set {
		req.Header.Set("X-API-Key", header)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w.Code
}

func TestAPIKeyMiddleware(t *testing.T) {
	t.Setenv("API_KEY", "expected-key")
	cases := []struct {
		name   string
		header string
		set    bool
		want   int
	}{
		{"matching key", "expected-key", true, http.StatusOK},
		{"wrong key", "unexpected-k", true, http.StatusUnauthorized},
		{"prefix of the key", "expected", true, http.StatusUnauthorized},
		{"key plus suffix", "expected-key2", true, http.StatusUnauthorized},
		{"empty header", "", true, http.StatusUnauthorized},
		{"no header", "", false, http.StatusUnauthorized},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := serve(t, tc.header, tc.set); got != tc.want {
				t.Fatalf("status %d, want %d", got, tc.want)
			}
		})
	}
}

func TestAPIKeyMiddlewareRefusesWhenUnconfigured(t *testing.T) {
	t.Setenv("API_KEY", "")
	if got := serve(t, "", true); got != http.StatusInternalServerError {
		t.Fatalf("status %d, want %d", got, http.StatusInternalServerError)
	}
}
