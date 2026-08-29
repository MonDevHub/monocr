package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ocr-feedback-service/internal/config"
)

// The first tests over the wiring in this package.
//
// It had none, and that is exactly where the false claim lived: config.Load()
// justified its rate-limit validation with "/health does not touch the limiter,
// so the service reports healthy through a total outage", and the limiter was
// attached above the /health registration. The statement was untestable, so it
// went unchallenged from the day it was written. Measured on the pre-fix router
// with burst=1, five probes gave 200, 429, 429, 429, 429.

// nullUploader satisfies upload.Uploader without touching Cloudflare. The
// handler-level behaviour is covered in internal/upload; here only the chain
// matters, so this records nothing.
type nullUploader struct{}

func (nullUploader) UploadFile(context.Context, string, io.Reader, string, map[string]string) error {
	return nil
}

const testAPIKey = "router-test-key"

func testRouter(t *testing.T, conf *config.Config) http.Handler {
	t.Helper()
	t.Setenv("API_KEY", testAPIKey)

	router, err := newRouter(conf, nullUploader{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("newRouter: %v", err)
	}
	return router
}

// conf returns a config with the limiter squeezed to a burst of one, so a single
// request exhausts it and the next observable status is unambiguous.
func squeezedConf() *config.Config {
	return &config.Config{
		Port:              "8080",
		GinMode:           "test",
		MaxUploadSize:     20 << 20,
		RateLimitRequests: 0.001, // low enough that nothing refills mid-test
		RateLimitBurst:    1,
	}
}

func send(router http.Handler, method, path, forwardedFor string, body io.Reader) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, body)
	req.RemoteAddr = "169.254.1.1:35000" // the Google front end
	if forwardedFor != "" {
		req.Header.Set("X-Forwarded-For", forwardedFor)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

// The claim in config.Load(), turned into an assertion.
//
// Two things are being pinned. The probe must answer 200 while the limiter is
// exhausted for that same client — otherwise a zero-burst misconfiguration is
// invisible to the platform. And it must consume nothing, otherwise ordinary
// load shares a bucket with the probe and can take a healthy revision out of
// service on its own.
func TestHealthIsOutsideTheRateLimiter(t *testing.T) {
	router := testRouter(t, squeezedConf())
	const client = "203.0.113.7"

	// Exhaust this client's single token on a limited route. The status does not
	// matter (no API key), only that the token is spent.
	send(router, http.MethodPost, "/v1/feedback", client, nil)

	// Prove the limiter is genuinely exhausted, or the rest proves nothing.
	if code := send(router, http.MethodPost, "/v1/feedback", client, nil).Code; code != http.StatusTooManyRequests {
		t.Fatalf("second limited request answered %d, want 429; the limiter is not active "+
			"and this test cannot show /health is exempt from it", code)
	}

	for probe := 1; probe <= 5; probe++ {
		rec := send(router, http.MethodGet, "/health", client, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("health probe %d answered %d, want 200; the probe shares the limiter's "+
				"bucket, so the service cannot report healthy through a rate-limit outage", probe, rec.Code)
		}
	}

	// And the probes must not have spent anything themselves: a fresh client is
	// still entitled to its token after five probes from the same address.
	if code := send(router, http.MethodPost, "/v1/feedback", "203.0.113.8", nil).Code; code == http.StatusTooManyRequests {
		t.Error("health probes consumed another client's quota")
	}
}

// The contract's 413, end to end through the real chain, because the answer is
// produced jointly by BodyLimitMiddleware and the handler's error branch and
// neither one alone can be asserted for it.
func TestOversizeUploadIs413ThroughTheRealChain(t *testing.T) {
	conf := squeezedConf()
	conf.MaxUploadSize = 1 << 20 // 1MB
	conf.RateLimitBurst = 10
	conf.RateLimitRequests = 100
	router := testRouter(t, conf)

	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, err := w.CreateFormFile("file", "big.png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(bytes.Repeat([]byte{0x41}, 3<<20)); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/feedback", &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("X-API-Key", testAPIKey)
	req.RemoteAddr = "169.254.1.1:35000"
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize upload answered %d, want 413 as promised in "+
			"shared/contract/README.md:51: %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "1 MB") {
		t.Errorf("413 body does not name the limit: %s", rec.Body.String())
	}
}

// TRUSTED_PROXIES is the knob that makes the limiter's key unforgeable, and it
// is only worth documenting if it is wired.
//
// With the default (empty) list gin trusts every hop and takes the
// client-written end of X-Forwarded-For, so two claimed IPs get two buckets.
// Narrow the list so the peer is not trusted and the header is ignored
// entirely, so the two requests share one bucket. Both directions are asserted
// because either one alone is satisfied by a limiter that ignores the config.
func TestTrustedProxiesDecidesWhetherForwardedForIsBelieved(t *testing.T) {
	t.Run("default trusts the header, so claimed IPs get their own buckets", func(t *testing.T) {
		router := testRouter(t, squeezedConf())

		if code := send(router, http.MethodPost, "/v1/feedback", "198.51.100.1", nil).Code; code == http.StatusTooManyRequests {
			t.Fatalf("first client was rate limited immediately (%d)", code)
		}
		if code := send(router, http.MethodPost, "/v1/feedback", "198.51.100.2", nil).Code; code == http.StatusTooManyRequests {
			t.Error("a second, different claimed IP was limited by the first one's request")
		}
	})

	t.Run("a narrowed list ignores the header, so the peer is the key", func(t *testing.T) {
		conf := squeezedConf()
		// A range that does not contain the test peer, 169.254.1.1.
		conf.TrustedProxies = []string{"203.0.113.0/24"}
		router := testRouter(t, conf)

		if code := send(router, http.MethodPost, "/v1/feedback", "198.51.100.1", nil).Code; code == http.StatusTooManyRequests {
			t.Fatalf("first request was rate limited immediately (%d)", code)
		}
		if code := send(router, http.MethodPost, "/v1/feedback", "198.51.100.2", nil).Code; code != http.StatusTooManyRequests {
			t.Errorf("a spoofed X-Forwarded-For got a fresh bucket (%d) despite the peer "+
				"being untrusted; TRUSTED_PROXIES is not reaching SetTrustedProxies", code)
		}
	})
}

// A typo in TRUSTED_PROXIES must stop the service at boot, where the operator is
// still watching — the same argument config.Load() makes for the rate limits.
func TestNewRouterRefusesAnUnparseableTrustedProxyList(t *testing.T) {
	conf := squeezedConf()
	conf.TrustedProxies = []string{"not-an-ip"}
	t.Setenv("API_KEY", testAPIKey)

	if _, err := newRouter(conf, nullUploader{}, slog.New(slog.NewTextHandler(io.Discard, nil))); err == nil {
		t.Fatal("newRouter accepted a trusted-proxy list it cannot parse")
	}
}
