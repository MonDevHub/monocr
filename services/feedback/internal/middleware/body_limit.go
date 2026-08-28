package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// BodyLimitMiddleware caps how much of a request body the service will read.
//
// This was an anonymous closure inline in main(), which meant the payload
// ceiling — one of the few controls this service has — sat in the one file with
// no test coverage at all. It is a named middleware so the wiring can be
// asserted: the property that matters is not "MaxBytesReader was called" but
// "an oversize upload answers 413", and that spans this middleware and the
// upload handler's error branch. See upload.handleUpload for what the generic
// error used to be reported as.
func BodyLimitMiddleware(maxBytes int64) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBytes)
		c.Next()
	}
}
