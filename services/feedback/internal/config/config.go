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

	// Optional Rate Limits
	rateReq, _ := strconv.ParseFloat(getEnv("RATE_LIMIT_REQUESTS", "5.0"), 64)
	rateBurst, _ := strconv.Atoi(getEnv("RATE_LIMIT_BURST", "10"))
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
