package middleware

import (
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"os"
	"runtime/debug"
	"strings"

	"github.com/gin-gonic/gin"
)

// RecoveryMiddleware provides a custom gin.Recovery implementation that logs
// panics using structured slog JSON instead of raw console output.
func RecoveryMiddleware(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				// Check for a broken connection, as it's not really a panic we can do much about
				var brokenPipe bool
				if ne, ok := err.(*net.OpError); ok {
					if se, ok := ne.Err.(*os.SyscallError); ok {
						if strings.Contains(strings.ToLower(se.Error()), "broken pipe") || strings.Contains(strings.ToLower(se.Error()), "connection reset by peer") {
							brokenPipe = true
						}
					}
				}

				httpRequest, _ := httputil.DumpRequest(c.Request, false)
				if brokenPipe {
					logger.Error("Path is broken",
						"error", err,
						"request", string(httpRequest),
						"request_id", c.GetString("requestID"))
					c.Error(err.(error))
					c.Abort()
					return
				}

				logger.Error("Recovery from panic",
					"error", err,
					"stack", string(debug.Stack()),
					"request", string(httpRequest),
					"request_id", c.GetString("requestID"))

				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"error":      "An internal server error occurred",
					"request_id": c.GetString("requestID"),
				})
			}
		}()
		c.Next()
	}
}
