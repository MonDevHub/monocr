package config

import (
	"strings"
	"testing"
)

// The first tests in this service. `go test ./...` was green here from the day
// the CI job was written, over zero test files, and ci.yml:157-160 said so
// plainly: "It is not evidence the service works."
//
// These cover Load() because it is the one place where a one-character mistake
// in the deployment environment used to produce a total, silent outage:
// ParseFloat and Atoi errors were discarded, Go returns zero on failure, and
// rate.NewLimiter with a burst of 0 rejects every request. /health never touches
// the limiter, so it stayed green throughout. Measured: burst 10 admits 5 of 5
// requests, burst 0 admits 0 of 5.

// required is the minimum environment Load() needs before it will look at
// anything optional. Kept in one place so a new required variable breaks every
// test at once rather than one confusingly.
func required(t *testing.T) {
	t.Helper()
	for k, v := range map[string]string{
		"API_KEY":              "k",
		"R2_ACCOUNT_ID":        "a",
		"R2_ACCESS_KEY_ID":     "b",
		"R2_SECRET_ACCESS_KEY": "c",
		"R2_BUCKET_NAME":       "d",
	} {
		t.Setenv(k, v)
	}
}

func TestLoadDefaultsAreUsable(t *testing.T) {
	required(t)

	conf, err := Load()
	if err != nil {
		t.Fatalf("Load() with no optional vars set: %v", err)
	}
	// The defaults must be values the limiter can actually serve traffic with.
	// Asserting they are non-zero is the whole point; a zero burst is the outage.
	if conf.RateLimitBurst <= 0 {
		t.Errorf("default RateLimitBurst = %d, want > 0", conf.RateLimitBurst)
	}
	if conf.RateLimitRequests <= 0 {
		t.Errorf("default RateLimitRequests = %v, want > 0", conf.RateLimitRequests)
	}
}

func TestLoadRefusesUnservableRateLimits(t *testing.T) {
	// Each of these used to yield zero and boot a service that rejected
	// everything. The trailing-space case is the realistic one: it survives a
	// copy-paste into a dashboard and is invisible in most UIs.
	cases := []struct {
		name  string
		key   string
		value string
	}{
		{"burst with a trailing space", "RATE_LIMIT_BURST", "10 "},
		{"burst quoted", "RATE_LIMIT_BURST", `"10"`},
		{"burst not a number", "RATE_LIMIT_BURST", "ten"},
		{"burst zero", "RATE_LIMIT_BURST", "0"},
		{"burst negative", "RATE_LIMIT_BURST", "-1"},
		{"requests with a trailing space", "RATE_LIMIT_REQUESTS", "5.0 "},
		{"requests not a number", "RATE_LIMIT_REQUESTS", "five"},
		{"requests zero", "RATE_LIMIT_REQUESTS", "0"},
		{"requests negative", "RATE_LIMIT_REQUESTS", "-2.5"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			required(t)
			t.Setenv(c.key, c.value)

			conf, err := Load()
			if err == nil {
				t.Fatalf("Load() accepted %s=%q and returned burst=%d requests=%v; "+
					"a service that cannot serve a request must not start",
					c.key, c.value, conf.RateLimitBurst, conf.RateLimitRequests)
			}
			// The operator reads this line at 3am. It has to name the variable.
			if !strings.Contains(err.Error(), c.key) {
				t.Errorf("error %q does not name %s", err, c.key)
			}
		})
	}
}

func TestLoadRefusesMissingCredentials(t *testing.T) {
	// Already correct before this change; pinned so it stays that way.
	for _, missing := range []string{"API_KEY", "R2_ACCOUNT_ID", "R2_BUCKET_NAME"} {
		t.Run("without "+missing, func(t *testing.T) {
			required(t)
			t.Setenv(missing, "")

			if _, err := Load(); err == nil {
				t.Fatalf("Load() started with %s empty", missing)
			}
		})
	}
}
