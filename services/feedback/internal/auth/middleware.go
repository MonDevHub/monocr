package auth

import (
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

		if apiKey != expectedKey {
			slog.Warn("Unauthorized access attempt", "client_ip", c.ClientIP(), "user_agent", c.Request.UserAgent())
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Unauthorized: Invalid API Key"})
			return
		}

		c.Next()
	}
}
