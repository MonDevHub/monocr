package config

import (
	"fmt"
	"os"
	"strconv"
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
	// a burst of 0 rejects every request forever, and /health does not touch the
	// limiter, so the service reports healthy through a total outage. A trailing
	// space in RATE_LIMIT_BURST was enough to cause it.
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

	return conf, nil
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}
