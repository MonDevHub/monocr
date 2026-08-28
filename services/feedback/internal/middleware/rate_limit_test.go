package middleware

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

// Tests for the limiter, which had none, and which was therefore free to be
// global while being named per-IP.
//
// The measurements quoted here were all taken against the pre-fix code in this
// package on 2026-08-28 and are what each test is pinning.

// gfe stands in for the Google front end: the peer address every Cloud Run
// request arrives from, and the value the limiter used to key on.
const gfe = "169.254.1.1:35000"

// limitedRouter wires the limiter the way newRouter does, so a test drives the
// real middleware rather than GetLimiter directly.
func limitedRouter(r rate.Limit, b int) (*gin.Engine, *IPRateLimiter) {
	gin.SetMode(gin.TestMode)
	limiter := NewIPRateLimiter(r, b)
	router := gin.New()
	router.Use(RequestIDMiddleware())
	router.Use(RateLimitMiddleware(limiter))
	router.GET("/x", func(c *gin.Context) { c.String(http.StatusOK, "ok") })
	return router, limiter
}

func getAs(router *gin.Engine, forwardedFor string) int {
	req := httptest.NewRequest(http.MethodGet, "/x", nil)
	req.RemoteAddr = gfe
	if forwardedFor != "" {
		req.Header.Set("X-Forwarded-For", forwardedFor)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec.Code
}

// The defect that made "per-IP" a misnomer in production.
//
// Keyed on c.Request.RemoteAddr, four different users behind one front end got
// 200, 429, 429, 429 with burst=1 — one user's single request locked out the
// other three, and on Cloud Run that is the entire user base sharing the default
// 5 req/s.
func TestRateLimitIsPerClientNotGlobal(t *testing.T) {
	// A rate low enough that nothing refills mid-test, so every allow is a
	// distinct bucket rather than a token that trickled back.
	router, _ := limitedRouter(rate.Limit(0.001), 1)

	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"} {
		if code := getAs(router, ip); code != http.StatusOK {
			t.Fatalf("client %s got %d; one client's request must not spend another's quota", ip, code)
		}
	}
}

// The other half of the same property: a per-IP limiter that never says no is
// not a limiter. This is what fails if someone "fixes" the test above by keying
// on something unique per request.
func TestRateLimitStillStopsOneClient(t *testing.T) {
	router, _ := limitedRouter(rate.Limit(0.001), 1)

	if code := getAs(router, "5.5.5.5"); code != http.StatusOK {
		t.Fatalf("first request from a fresh client got %d, want 200", code)
	}
	if code := getAs(router, "5.5.5.5"); code != http.StatusTooManyRequests {
		t.Fatalf("second request from the same client got %d, want 429", code)
	}
}

// The lost update in GetLimiter.
//
// The pre-fix code re-created the limiter unconditionally under the write lock,
// so concurrent first-hits for one IP each installed a fresh full bucket.
// Directly measured at 32 concurrent callers for a single IP it allowed 2 of 32
// instead of 1, but only in 5 of 500 rounds — far too rare for a single-IP test
// to rely on. So this races many IPs at once and asserts the exact total: a
// correct implementation always allows exactly one per IP, and the buggy one has
// to win none of a few thousand independent races to survive.
func TestConcurrentFirstHitsShareOneBucketPerIP(t *testing.T) {
	const (
		ips              = 2000
		callersPerIP     = 8
		wantAllowedTotal = ips
	)

	limiter := NewIPRateLimiter(rate.Limit(0.001), 1)

	var (
		mu      sync.Mutex
		allowed int
		wg      sync.WaitGroup
	)
	start := make(chan struct{})

	for n := 0; n < ips; n++ {
		ip := fmt.Sprintf("10.%d.%d.%d", n/65536, (n/256)%256, n%256)
		for c := 0; c < callersPerIP; c++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				if limiter.GetLimiter(ip).Allow() {
					mu.Lock()
					allowed++
					mu.Unlock()
				}
			}()
		}
	}
	close(start)
	wg.Wait()

	if allowed != wantAllowedTotal {
		t.Fatalf("allowed %d of %d concurrent calls across %d IPs, want exactly %d; "+
			"a burst of 1 was handed out more than once per IP, so the ceiling is bypassable by racing it",
			allowed, ips*callersPerIP, ips, wantAllowedTotal)
	}
}

// Two calls for one IP must hand back the same bucket object, which is the
// invariant the double-check exists to hold.
func TestGetLimiterReturnsTheSameBucketForOneIP(t *testing.T) {
	limiter := NewIPRateLimiter(rate.Limit(5), 10)

	first := limiter.GetLimiter("9.9.9.9")
	second := limiter.GetLimiter("9.9.9.9")
	if first != second {
		t.Fatal("the same IP got two different buckets, so its usage is not being accumulated")
	}
	if limiter.GetLimiter("8.8.8.8") == first {
		t.Fatal("two different IPs share one bucket")
	}
	if n := limiter.tracked(); n != 2 {
		t.Fatalf("tracking %d IPs after two distinct clients, want 2", n)
	}
}

// Eviction, which did not exist.
//
// Nothing noticed because the map only ever held one key while the limiter was
// global; measured on the pre-fix map, 5,000 distinct keys left 5,000 entries
// and no code path ever removed one. Making the key per-client is what turns
// that into unbounded growth.
func TestIdleBucketsAreEvictedAndActiveOnesAreNot(t *testing.T) {
	clock := time.Now()
	limiter := NewIPRateLimiter(rate.Limit(5), 10)
	limiter.now = func() time.Time { return clock }
	limiter.ttl = time.Minute
	limiter.sweepGap = time.Second

	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3"} {
		limiter.GetLimiter(ip)
	}
	if n := limiter.tracked(); n != 3 {
		t.Fatalf("tracking %d IPs after three clients, want 3", n)
	}

	// 1.1.1.1 stays active; the other two go quiet.
	clock = clock.Add(30 * time.Second)
	limiter.GetLimiter("1.1.1.1")

	// Past the TTL for the two quiet ones, but not for 1.1.1.1.
	clock = clock.Add(40 * time.Second)
	limiter.GetLimiter("4.4.4.4")

	if n := limiter.tracked(); n != 2 {
		t.Fatalf("tracking %d IPs, want 2 (the active client and the new one); "+
			"idle buckets are not being reclaimed, so the map grows without bound", n)
	}
	if _, ok := limiter.ips["1.1.1.1"]; !ok {
		t.Error("the active client was evicted, which would hand it a fresh burst it had not earned")
	}
	for _, gone := range []string{"2.2.2.2", "3.3.3.3"} {
		if _, ok := limiter.ips[gone]; ok {
			t.Errorf("%s was idle past the TTL and is still held", gone)
		}
	}
}

// The TTL alone only bounds idle keys; a fast enough scan of fresh ones never
// becomes idle. This is the bound that holds regardless of arrival rate, and it
// is the reason a client-influenced key is acceptable at all.
func TestTrackedBucketsAreHardCapped(t *testing.T) {
	const maxKept = 100

	clock := time.Now()
	limiter := NewIPRateLimiter(rate.Limit(5), 10)
	limiter.now = func() time.Time { return clock }
	limiter.ttl = time.Hour      // nothing expires
	limiter.sweepGap = time.Hour // no time-triggered sweep either
	limiter.maxTracked = maxKept

	// 500 distinct keys arriving faster than any TTL, the shape of a client
	// rotating X-Forwarded-For.
	for n := 0; n < 500; n++ {
		clock = clock.Add(time.Millisecond)
		limiter.GetLimiter(fmt.Sprintf("172.16.%d.%d", n/256, n%256))
	}

	if n := limiter.tracked(); n > maxKept {
		t.Fatalf("tracking %d IPs with a cap of %d; the memory ceiling does not hold, "+
			"so a rotating header grows the map without limit", n, maxKept)
	}
	// And the survivors are the recent ones, not an arbitrary hundred.
	if _, ok := limiter.ips["172.16.1.243"]; !ok { // key 499, the last one added
		t.Error("the most recent client was evicted, so eviction is not least-recently-seen")
	}
}
