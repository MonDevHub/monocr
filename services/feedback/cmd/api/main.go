package main

import (
	"context"
	"errors"
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
	
	// 4. Initialise Router & Hardened Middlewares
	router := gin.New()

	// Staff Grade: No CORS middleware needed as this service is 
	// restricted to Native Mobile (Kotlin/Swift) clients only.
	// Web version uses R2 Pre-signed URLs directly.
	router.Use(middleware.RecoveryMiddleware(logger))

	// Staff Grade: In-memory IP Rate Limiting
	limiter := middleware.NewIPRateLimiter(rate.Limit(conf.RateLimitRequests), conf.RateLimitBurst)
	
	// Middleware Chain (Order Matters)
	router.Use(middleware.RequestIDMiddleware())
	router.Use(middleware.RateLimitMiddleware(limiter))
	
	// Global Payload Limit
	router.Use(func(c *gin.Context) {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, conf.MaxUploadSize)
		c.Next()
	})

	// 5. Handlers
	uploadHandler := upload.NewHandler(r2Client, conf.MaxUploadSize)

	// 6. Routes
	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "ok",
			"timestamp": time.Now().UTC(),
			"version":   "v1.0.2-staff-hardened",
		})
	})

	v1 := router.Group("/v1")
	v1.Use(auth.APIKeyMiddleware())
	{
		v1.POST("/feedback", uploadHandler.UploadFeedback)
		v1.POST("/contribution", uploadHandler.UploadContribution)
		v1.GET("/swagger/*any", ginSwagger.WrapHandler(swaggerFiles.Handler))
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
