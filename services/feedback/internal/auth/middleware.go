package auth

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"os"

	"github.com/gin-gonic/gin"
)

func APIKeyMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		apiKey := c.GetHeader("X-API-Key")
		expectedKey := os.Getenv("API_KEY")

		if expectedKey == "" {
			slog.Error("API Key configuration error: API_KEY environment variable is not set")
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "API_KEY not configured on server"})
			return
		}

		// Constant time, so response timing does not reveal how much of a guess
		// matched. ConstantTimeCompare returns early only on a length mismatch,
		// which leaks the key's length and nothing else.
		if subtle.ConstantTimeCompare([]byte(apiKey), []byte(expectedKey)) != 1 {
			slog.Warn("Unauthorized access attempt", "client_ip", c.ClientIP(), "user_agent", c.Request.UserAgent())
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Unauthorized: Invalid API Key"})
			return
		}

		c.Next()
	}
}
