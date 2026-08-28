package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	swaggerFiles "github.com/swaggo/files"
	ginSwagger "github.com/swaggo/gin-swagger"
	"golang.org/x/time/rate"

	_ "ocr-feedback-service/docs"
	"ocr-feedback-service/internal/auth"
	"ocr-feedback-service/internal/config"
	"ocr-feedback-service/internal/middleware"
	"ocr-feedback-service/internal/r2"
	"ocr-feedback-service/internal/upload"
)

// @title MonOCR Feedback API
// @version 1.0
// @description Production-grade image feedback and contribution service for MonOCR.
// @contact.name MonDevHub Support
// @license.name MIT
// @license.url https://opensource.org/licenses/MIT

// @host localhost:8080
// @BasePath /

// @securityDefinitions.apikey ApiKeyAuth
// @in header
// @name X-API-Key

const maxUploadSize = 20 * 1024 * 1024 // 20MB Hard Limit

// newRouter builds the middleware chain and the routes.
//
// Extracted from main() so the chain is testable at all. The two things this
// function has to get right are properties of the wiring rather than of any
// handler — /health outside the rate limiter, and the body limit in front of the
// upload routes — and while this lived inline in main() there was no way to
// assert either one. That is not hypothetical: the comment in config.Load()
// justifying the rate-limit validation asserted "/health does not touch the
// limiter", it had been false since the day it was written, and no test could
// have caught it.
func newRouter(conf *config.Config, uploader upload.Uploader, logger *slog.Logger) (*gin.Engine, error) {
	router := gin.New()

	// Client-IP resolution. Empty keeps gin's default, which trusts every hop
	// and therefore takes the client-supplied end of X-Forwarded-For; see
	// middleware.RateLimitMiddleware for why that is the right default on Cloud
	// Run and exactly what it does and does not buy.
	if len(conf.TrustedProxies) > 0 {
		if err := router.SetTrustedProxies(conf.TrustedProxies); err != nil {
			return nil, fmt.Errorf("TRUSTED_PROXIES is not a list of IPs or CIDRs: %w", err)
		}
	}

	// Staff Grade: No CORS middleware needed as this service is
	// restricted to Native Mobile (Kotlin/Swift) clients only.
	// Web version uses R2 Pre-signed URLs directly.
	router.Use(middleware.RecoveryMiddleware(logger))
	router.Use(middleware.RequestIDMiddleware())

	// /health is registered on the bare engine, before the limiter exists, and
	// the limited routes get their own group.
	//
	// It used to sit behind a router.Use(RateLimitMiddleware) placed above it,
	// which had two costs. The claim in config.Load() that a probe survives a
	// zero-burst misconfiguration was simply false — measured with burst=1, five
	// probes gave 200 then four 429s. And user traffic and health probes shared
	// one bucket, so ordinary load could starve the probe and take a healthy
	// revision out of service.
	//
	// A group rather than registration order is deliberate: gin captures the
	// handler chain when a route is registered, so putting /health above the
	// router.Use would also work and would silently break the first time someone
	// moved a line. This way the exclusion is structural.
	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "ok",
			"timestamp": time.Now().UTC(),
			"version":   "v1.0.2-staff-hardened",
		})
	})

	limiter := middleware.NewIPRateLimiter(rate.Limit(conf.RateLimitRequests), conf.RateLimitBurst)

	limited := router.Group("/")
	limited.Use(middleware.RateLimitMiddleware(limiter))
	limited.Use(middleware.BodyLimitMiddleware(conf.MaxUploadSize))

	uploadHandler := upload.NewHandler(uploader, conf.MaxUploadSize)

	v1 := limited.Group("/v1")
	v1.Use(auth.APIKeyMiddleware())
	{
		v1.POST("/feedback", uploadHandler.UploadFeedback)
		v1.POST("/contribution", uploadHandler.UploadContribution)
		v1.GET("/swagger/*any", ginSwagger.WrapHandler(swaggerFiles.Handler))
	}

	return router, nil
}

func main() {
	// 1. Initialise Structured Logging
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	// 2. Load Configuration (Staff Grade: Fails fast if env is missing)
	// Try loading .env from current dir, then from monorepo root if it exists
	_ = godotenv.Load()
	_ = godotenv.Load("../../.env")

	conf, err := config.Load()
	if err != nil {
		slog.Error("Configuration failure", "error", err)
		os.Exit(1)
	}

	// 3. Initialise R2 Client
	r2Client, err := r2.NewClient()
	if err != nil {
		slog.Error("Critical: Failed to initialise R2 client", "error", err)
		os.Exit(1)
	}

	// 4. Initialise Router & Hardened Middlewares
	if conf.GinMode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	router, err := newRouter(conf, r2Client, logger)
	if err != nil {
		slog.Error("Router configuration failure", "error", err)
		os.Exit(1)
	}

	// 7. Hardened HTTP Server
	srv := &http.Server{
		Addr:           ":" + conf.Port,
		Handler:        router,
		ReadTimeout:    15 * time.Second,
		WriteTimeout:   2 * time.Minute,
		IdleTimeout:    120 * time.Second,
		MaxHeaderBytes: 1 << 20, // 1MB
	}

	// 8. Graceful Shutdown
	done := make(chan struct{})
	go func() {
		sigint := make(chan os.Signal, 1)
		signal.Notify(sigint, os.Interrupt, syscall.SIGTERM)
		s := <-sigint
		slog.Info("Shutting down server...", "signal", s.String())

		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		if err := srv.Shutdown(ctx); err != nil {
			slog.Error("Server forced to shutdown", "error", err)
		}
		close(done)
	}()

	slog.Info("Server starting", "port", conf.Port, "mode", conf.GinMode)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("Server failed to start", "error", err)
		os.Exit(1)
	}

	<-done
	slog.Info("Server exited gracefully")
}
