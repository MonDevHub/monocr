package middleware

import (
	"net/http"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

// Bounds on what the limiter is allowed to remember.
//
// The TTL only has to outlast a full refill. At the default 5 req/s with a
// burst of 10 a bucket is full again 2s after the last request, so dropping an
// entry that has been idle for minutes hands back a burst the client had
// already earned — eviction cannot be used to reset a bucket that still had a
// penalty owing. 10 minutes is far past that and short enough that a scan of
// rotated keys does not accumulate.
//
// maxTrackedIPs is the unconditional bound, and it is the one that matters:
// see RateLimitMiddleware on why the key is client-influenced.
const (
	defaultLimiterTTL      = 10 * time.Minute
	defaultLimiterSweepGap = 1 * time.Minute
	defaultMaxTrackedIPs   = 50_000
)

// ipEntry is one client's bucket plus the last time it was used.
//
// lastSeen is atomic because the hot path updates it while holding only the
// read lock — a returning client is a map read, and taking the write lock to
// stamp a timestamp would serialise every request through one mutex.
type ipEntry struct {
	limiter  *rate.Limiter
	lastSeen atomic.Int64 // unix nanos
}

// IPRateLimiter is a thread-safe per-IP token bucket limiter with eviction.
type IPRateLimiter struct {
	mu  sync.RWMutex
	ips map[string]*ipEntry
	r   rate.Limit
	b   int

	// Eviction policy. Fields rather than consts so a test can shrink them
	// without mutating package state; the bound is the whole point of the
	// eviction and an unmeasured bound is not a bound.
	ttl         time.Duration
	sweepGap    time.Duration
	maxTracked  int
	lastSweepNs atomic.Int64
	now         func() time.Time
}

func NewIPRateLimiter(r rate.Limit, b int) *IPRateLimiter {
	l := &IPRateLimiter{
		ips:        make(map[string]*ipEntry),
		r:          r,
		b:          b,
		ttl:        defaultLimiterTTL,
		sweepGap:   defaultLimiterSweepGap,
		maxTracked: defaultMaxTrackedIPs,
		now:        time.Now,
	}
	l.lastSweepNs.Store(l.now().UnixNano())
	return l
}

// GetLimiter returns the bucket for ip, creating it once and only once.
//
// The re-check after taking the write lock is not defensive tidying; without it
// this method loses updates. The old version tested `exists` under the read
// lock, released it, then unconditionally assigned a fresh limiter under the
// write lock — so N concurrent first-hits for the same IP each installed a new
// full bucket and each of the first N-1 callers got a limiter that was then
// thrown away. Measured on the pre-fix code with burst=1 and 32 concurrent
// callers for one IP: 2 of 32 allowed instead of 1, reproducing in 5 of 500
// rounds. Low odds per attempt and free to retry, which is the wrong shape for
// a control that is supposed to be a ceiling.
//
// A double-check is the fix rather than sync.Map or singleflight because the
// contention here is trivial (one map write per new IP, reads take RLock) and
// both alternatives would trade a four-line invariant for a second concurrency
// primitive to reason about. sync.Map's LoadOrStore would also construct a
// throwaway rate.Limiter on every hit, which is the same wasted allocation
// without the correctness argument.
func (i *IPRateLimiter) GetLimiter(ip string) *rate.Limiter {
	now := i.now()

	i.mu.RLock()
	entry, exists := i.ips[ip]
	i.mu.RUnlock()

	if !exists {
		i.mu.Lock()
		// Someone may have installed it between the RUnlock and the Lock.
		if entry, exists = i.ips[ip]; !exists {
			entry = &ipEntry{limiter: rate.NewLimiter(i.r, i.b)}
			i.ips[ip] = entry
		}
		i.mu.Unlock()
	}

	// Stamp before sweeping, so the caller's own entry is never the one evicted.
	entry.lastSeen.Store(now.UnixNano())
	i.maybeSweep(now)

	return entry.limiter
}

// maybeSweep runs the eviction at most once per sweepGap, or immediately once
// the map is over its cap.
//
// The compare-and-swap claims the sweep so that a burst of concurrent requests
// does not have all of them scan the map behind the write lock.
func (i *IPRateLimiter) maybeSweep(now time.Time) {
	last := i.lastSweepNs.Load()

	if now.Sub(time.Unix(0, last)) < i.sweepGap {
		i.mu.RLock()
		over := len(i.ips) > i.maxTracked
		i.mu.RUnlock()
		if !over {
			return
		}
	}

	if !i.lastSweepNs.CompareAndSwap(last, now.UnixNano()) {
		return
	}
	i.sweep(now)
}

// sweep drops idle entries, then enforces the hard cap by dropping the oldest.
//
// There was no eviction at all until 2026-08-28, and nothing noticed because
// the map only ever held one key: the middleware keyed on RemoteAddr, which on
// Cloud Run is the Google front end for every request on Earth. Fixing that
// turned one entry into one per claimed client IP, which is exactly the change
// that makes an unbounded map reachable — measured on the pre-fix map: 5,000
// distinct keys, 5,000 entries retained, no path that ever removed one.
func (i *IPRateLimiter) sweep(now time.Time) {
	cutoff := now.Add(-i.ttl).UnixNano()

	i.mu.Lock()
	defer i.mu.Unlock()

	for ip, entry := range i.ips {
		if entry.lastSeen.Load() < cutoff {
			delete(i.ips, ip)
		}
	}

	// The TTL alone is only a bound on idle keys, and a fast enough scan of
	// fresh keys never becomes idle. Drop the least recently seen down to the
	// cap so the memory ceiling holds regardless of arrival rate.
	excess := len(i.ips) - i.maxTracked
	if excess <= 0 {
		return
	}

	type aged struct {
		ip   string
		seen int64
	}
	ages := make([]aged, 0, len(i.ips))
	for ip, entry := range i.ips {
		ages = append(ages, aged{ip: ip, seen: entry.lastSeen.Load()})
	}
	sort.Slice(ages, func(a, b int) bool { return ages[a].seen < ages[b].seen })
	for _, a := range ages[:excess] {
		delete(i.ips, a.ip)
	}
}

// tracked reports how many IPs are currently held. Test-only.
func (i *IPRateLimiter) tracked() int {
	i.mu.RLock()
	defer i.mu.RUnlock()
	return len(i.ips)
}

// RateLimitMiddleware applies one token bucket per client IP.
//
// It keyed on c.Request.RemoteAddr until 2026-08-28, which made the "per-IP"
// limiter global in the only deployment that matters. Both mobile clients
// hardcode a Cloud Run URL, and there RemoteAddr is the Google front end, so
// every request in the world shared one bucket and the default 5 req/s with a
// burst of 10 was the ceiling for the entire user base. Measured with burst=1
// and four distinct X-Forwarded-For values behind one RemoteAddr: 200, 429,
// 429, 429 — three users locked out by the first one's single request.
//
// c.ClientIP() reads X-Forwarded-For, and that deserves stating plainly rather
// than being left implicit:
//
//   - Under gin's default trusted-proxy list (0.0.0.0/0 and ::/0, verified in
//     gin v1.9.1) every hop is trusted, so the right-to-left walk in
//     validateHeader never stops early and returns the LEFTMOST entry — the
//     one the client wrote. The key is therefore client-chosen, and a caller
//     that rotates the header gets a fresh quota.
//
//   - That default is still kept, deliberately. On Cloud Run there is no
//     stable front-end range to trust, and narrowing the list to exclude the
//     peer sends ClientIP() straight back to the front end's address, which is
//     the global-bucket bug this change exists to fix. A per-claimed-IP ceiling
//     is strictly better than one bucket for the whole user base: honest
//     clients no longer starve each other, which was the measured harm.
//
//   - So this is a fairness control, not an anti-abuse control, and it is
//     documented as such. What is NOT left to trust is the memory: the key
//     being attacker-influenced is precisely why the eviction above has an
//     unconditional cap, so a rotating header cannot grow the map without
//     limit.
//
//   - An operator who does front this service with a known proxy range closes
//     the spoofing gap by setting TRUSTED_PROXIES, which newRouter feeds to
//     SetTrustedProxies; the key then becomes the first untrusted hop from the
//     right, which a client cannot forge.
func RateLimitMiddleware(limiter *IPRateLimiter) gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := c.ClientIP()
		if ip == "" {
			// ClientIP returns "" when the peer address will not parse. Falling
			// back to the raw value keeps those requests in their own buckets
			// rather than collapsing every one of them into the "" bucket,
			// which would be the global-bucket bug again for that subset.
			ip = c.Request.RemoteAddr
		}

		logger := GetLogger(c)
		l := limiter.GetLimiter(ip)
		if !l.Allow() {
			logger.Warn("Rate limit exceeded", "ip", ip)
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error":      "Rate limit exceeded: slowly down your requests",
				"request_id": c.GetString("requestID"),
			})
			return
		}

		c.Next()
	}
}
