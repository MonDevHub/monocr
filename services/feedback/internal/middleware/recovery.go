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
// sensitiveHeaders are removed before a request is written to a log.
//
// `httputil.DumpRequest(req, false)` omits the BODY, not the headers, which is
// the trap: every panic log carried `X-API-Key` in full. Panic logs go to stdout
// and on to Cloud Logging, so anyone holding a log-viewer role could read the
// production key out of them. The service authenticates with one shared static
// key and has no revocation path, so that key is the whole credential.
var sensitiveHeaders = []string{
	"X-API-Key",
	"Authorization",
	"Cookie",
	"Proxy-Authorization",
}

// redactSensitiveHeaders returns a shallow copy of r whose sensitive headers are
// replaced with a placeholder, leaving the original request untouched.
//
// A copy, because the request is still in flight: mutating its headers here
// would change what the rest of the chain sees.
func redactSensitiveHeaders(r *http.Request) *http.Request {
	if r == nil {
		return nil
	}
	clone := *r
	clone.Header = make(http.Header, len(r.Header))
	for name, values := range r.Header {
		clone.Header[name] = values
	}
	for _, name := range sensitiveHeaders {
		if len(clone.Header.Values(name)) > 0 {
			clone.Header.Set(name, "[REDACTED]")
		}
	}
	return &clone
}

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

				httpRequest, _ := httputil.DumpRequest(redactSensitiveHeaders(c.Request), false)
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
