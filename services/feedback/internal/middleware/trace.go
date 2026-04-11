package middleware

import (
	"log/slog"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func RequestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Get Request ID from header or generate a new one
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.New().String()
		}

		// Set in header and context
		c.Header("X-Request-ID", requestID)
		c.Set("requestID", requestID)

		// Create a logger scoped to this request
		logger := slog.With("request_id", requestID)
		c.Set("logger", logger)

		c.Next()
	}
}

// GetLogger retrieves the request-scoped logger from the gin context
func GetLogger(c *gin.Context) *slog.Logger {
	val, exists := c.Get("logger")
	if !exists {
		return slog.Default()
	}
	return val.(*slog.Logger)
}
