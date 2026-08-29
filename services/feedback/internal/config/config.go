package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	Port              string
	GinMode           string
	ApiKey            string
	R2AccountID       string
	R2AccessKeyID     string
	R2SecretAccessKey string
	R2BucketName      string
	MaxUploadSize     int64
	RateLimitRequests float64
	RateLimitBurst    int
	TrustedProxies    []string
}

func Load() (*Config, error) {
	conf := &Config{
		Port:              getEnv("PORT", "8080"),
		GinMode:           getEnv("GIN_MODE", "release"),
		ApiKey:            os.Getenv("API_KEY"),
		R2AccountID:       os.Getenv("R2_ACCOUNT_ID"),
		R2AccessKeyID:     os.Getenv("R2_ACCESS_KEY_ID"),
		R2SecretAccessKey: os.Getenv("R2_SECRET_ACCESS_KEY"),
		R2BucketName:      os.Getenv("R2_BUCKET_NAME"),
		MaxUploadSize:     20 * 1024 * 1024, // 20MB
	}

	// Validate required fields
	if conf.ApiKey == "" || conf.R2AccountID == "" || conf.R2AccessKeyID == "" || conf.R2SecretAccessKey == "" || conf.R2BucketName == "" {
		return nil, fmt.Errorf("missing critical environment variables: check API_KEY, R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME")
	}

	// Optional rate limits.
	//
	// These discarded their parse errors until 2026-08-19, and the zero value
	// Go returns on failure is not a harmless default here: rate.NewLimiter with
	// a burst of 0 rejects every request forever. A trailing space in
	// RATE_LIMIT_BURST was enough to cause it.
	//
	// This comment used to add "and /health does not touch the limiter, so the
	// service reports healthy through a total outage". That was false from the
	// day it was written: newRouter attached the limiter above the /health
	// registration, so the probe shared the bucket. Measured with burst=1 — five
	// probes gave 200, 429, 429, 429, 429, meaning the outage would have taken
	// the probe down with it and the revision would at least have been marked
	// unhealthy. /health is outside the limiter as of 2026-08-28, so the claim is
	// now true, but the validation below is what the argument rests on and it
	// does not need the probe to be exempt.
	//
	// Refuse to start instead. A service that will serve nothing should say so
	// at boot, where the operator is still watching, not at the first request.
	rateReq, err := strconv.ParseFloat(getEnv("RATE_LIMIT_REQUESTS", "5.0"), 64)
	if err != nil {
		return nil, fmt.Errorf("RATE_LIMIT_REQUESTS must be a number: %w", err)
	}
	if rateReq <= 0 {
		return nil, fmt.Errorf("RATE_LIMIT_REQUESTS must be greater than 0, got %v", rateReq)
	}

	rateBurst, err := strconv.Atoi(getEnv("RATE_LIMIT_BURST", "10"))
	if err != nil {
		return nil, fmt.Errorf("RATE_LIMIT_BURST must be an integer: %w", err)
	}
	if rateBurst <= 0 {
		return nil, fmt.Errorf("RATE_LIMIT_BURST must be greater than 0, got %d", rateBurst)
	}

	conf.RateLimitRequests = rateReq
	conf.RateLimitBurst = rateBurst

	// Trusted proxy IPs or CIDRs for client-IP resolution, comma separated.
	//
	// Unset leaves gin's default in place, which trusts every hop and so keys
	// the rate limiter on the client-supplied end of X-Forwarded-For. That is
	// the deliberate default on Cloud Run and the reasoning is written out in
	// middleware.RateLimitMiddleware. Set this when the service sits behind a
	// proxy range you know, which is what makes the client IP unforgeable.
	//
	// Not validated here on purpose: SetTrustedProxies already parses the list,
	// newRouter surfaces its error, and main exits on it — so a typo still fails
	// at boot, without this package growing a second copy of gin's parser.
	for _, proxy := range strings.Split(os.Getenv("TRUSTED_PROXIES"), ",") {
		if proxy = strings.TrimSpace(proxy); proxy != "" {
			conf.TrustedProxies = append(conf.TrustedProxies, proxy)
		}
	}

	return conf, nil
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}
