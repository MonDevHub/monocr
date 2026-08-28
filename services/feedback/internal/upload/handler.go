package upload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gabriel-vasile/mimetype"
	"github.com/gin-gonic/gin"
	"ocr-feedback-service/internal/middleware"
)

// Uploader is the one thing this handler needs from object storage.
//
// An interface rather than `*r2.Client` so the request path can be tested at all.
// Until 2026-08-28 it could not be: the handler took a concrete client that talks
// to Cloudflare, so there was no way to drive a request through it, and the whole
// `go` CI job stayed green with the auth check, the MIME whitelist and the rate
// limiter deleted. `*r2.Client` satisfies this as written.
type Uploader interface {
	UploadFile(ctx context.Context, key string, body io.Reader, contentType string, metadata map[string]string) error
}

type Handler struct {
	R2Client      Uploader
	MaxUploadSize int64
}

func NewHandler(r2Client Uploader, maxUploadSize int64) *Handler {
	return &Handler{
		R2Client:      r2Client,
		MaxUploadSize: maxUploadSize,
	}
}

// UploadFeedback godoc
// @Summary Upload User Feedback
// @Description Securely upload image feedback or corrections to Cloudflare R2
// @Tags feedback
// @Accept multipart/form-data
// @Produce json
// @Param file formData file true "Image or PDF file"
// @Param record_id formData string false "Recognition record ID"
// @Param original_name formData string false "Original filename"
// @Security ApiKeyAuth
// @Success 200 {object} map[string]interface{}
// @Failure 400 {object} map[string]interface{}
// @Failure 401 {object} map[string]interface{}
// @Router /v1/feedback [post]
// buildObjectKey assembles the R2 key for one upload.
//
// A pure function of its inputs so the sanitisation and the assembly are ONE
// tested unit. They were two: the sanitiser was correct in isolation and the
// handler interpolated `record_id` past it, and a test of the sanitiser alone
// could not see that. Mutating the call site to skip sanitising survived the
// first version of these tests, which is why this exists.
//
// `now` is a parameter rather than a `time.Now()` call inside, so a test can pin
// the month partition without touching the clock.
func buildObjectKey(folder string, now time.Time, recordID, originalName string) string {
	dateStr := now.UTC().Format("2006-01")

	safeName := sanitizeKeySegment(originalName)
	if safeName == "" {
		safeName = "unnamed_file"
	}

	// record_id goes through the SAME sanitiser as the filename.
	//
	// It did not, and it was interpolated straight into the key. A client sending
	// `record_id=../../../../etc/passwd` produced
	// `feedback/2026-08/../../../../etc/passwd-x.txt`, and one sending
	// `record_id=contribution/2026-08/OVERWRITE` wrote into the CONTRIBUTION
	// namespace from the feedback endpoint. `PutObject` overwrites silently, so
	// that is an arbitrary-prefix write and a corpus-integrity problem, not just
	// an untidy key.
	//
	// Sanitising rather than rejecting is deliberate and costs nothing: both
	// clients send a UUID, and every character of a UUID is already in the
	// allowlist, so a legitimate id passes through byte for byte. Rejection would
	// have been a new way for a client to start failing.
	safeRecordID := sanitizeKeySegment(recordID)
	if safeRecordID == "" {
		safeRecordID = now.UTC().Format("150405")
	}

	return fmt.Sprintf("%s/%s/%s-%s", folder, dateStr, safeRecordID, safeName)
}

// maxKeySegment bounds one path segment of an object key.
//
// Without it a client could send a 5,000-character record_id and get a key of
// that length. 128 is far above a UUID's 36 and far below anything awkward.
const maxKeySegment = 128

// sanitizeKeySegment reduces a client-supplied string to one safe object-key
// segment: alphanumerics, dot, underscore and hyphen survive, everything else
// becomes an underscore, and leading or trailing punctuation is trimmed.
//
// The point is that `/` and `.` cannot combine into a traversal or a prefix
// change. Applied to both the filename and the record id; see the call site for
// what happened when it was applied to only one of them.
func sanitizeKeySegment(raw string) string {
	if len(raw) > maxKeySegment {
		raw = raw[:maxKeySegment]
	}
	mapped := strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-' {
			return r
		}
		return '_'
	}, raw)
	return strings.Trim(mapped, "._-")
}

func (h *Handler) UploadFeedback(c *gin.Context) {
	h.handleUpload(c, "feedback")
}

// UploadContribution godoc
// @Summary Upload Dataset Contribution
// @Description Securely upload high-quality Mon script samples for future training
// @Tags feedback
// @Accept multipart/form-data
// @Produce json
// @Param file formData file true "Contribution sample file"
// @Param original_name formData string false "Original filename"
// @Security ApiKeyAuth
// @Success 200 {object} map[string]interface{}
// @Failure 400 {object} map[string]interface{}
// @Failure 401 {object} map[string]interface{}
// @Router /v1/contribution [post]
func (h *Handler) UploadContribution(c *gin.Context) {
	h.handleUpload(c, "contribution")
}

func (h *Handler) jsonError(c *gin.Context, code int, message string) {
	c.JSON(code, gin.H{
		"error":      message,
		"request_id": c.GetString("requestID"),
	})
}

func (h *Handler) handleUpload(c *gin.Context, folder string) {
	logger := middleware.GetLogger(c)

	// 1. Capture Form Part
	file, err := c.FormFile("file")
	if err != nil {
		// An oversize body is its own answer, not a missing file part.
		//
		// middleware.BodyLimitMiddleware wraps the body in http.MaxBytesReader,
		// and when the limit trips it surfaces here as an ordinary FormFile
		// error. Until 2026-08-28 every error in this branch was reported as
		// "No file part", so a 21MB upload answered 400 while
		// shared/contract/README.md:51 promises 413. Measured with a 1KB limit
		// and an 8KB body: 400 {"error":"No file part in the request"}.
		//
		// That compounds rather than merely misinforming: neither mobile client
		// branches on status code, so 400, 401, 413 and 429 are retried
		// identically up to 5 attempts. The one failure a client could actually
		// act on — send less — was indistinguishable from the ones it cannot,
		// so it re-sent the whole oversize body four more times.
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			logger.Warn("Upload rejected: body over the limit", "limit_bytes", h.MaxUploadSize)
			h.jsonError(c, http.StatusRequestEntityTooLarge, fmt.Sprintf(
				"File exceeds the maximum upload size of %d MB", h.MaxUploadSize/(1024*1024)))
			return
		}

		logger.Warn("Upload failed: no file part", "error", err)
		h.jsonError(c, http.StatusBadRequest, "No file part in the request (expected 'file')")
		return
	}

	// An empty upload is rejected here because the MIME whitelist cannot do it.
	//
	// mimetype.Detect on zero bytes returns the library's fallback root,
	// text/plain, and text/plain is on the whitelist — so an empty part sniffed
	// clean and was streamed to R2. Measured: a 0-byte part named empty.png
	// answered 200 and wrote feedback/2026-08/<record-id>-empty.png. Each of
	// those is a row in the corpus that no training run can use and that no
	// reader can distinguish from a real sample without fetching it, so the cost
	// is paid by every later consumer rather than by the client that caused it.
	if file.Size == 0 {
		logger.Warn("Upload rejected: empty file", "filename", file.Filename)
		h.jsonError(c, http.StatusBadRequest, "Uploaded file is empty")
		return
	}

	// 2. Metadata Extraction
	recordID := c.PostForm("record_id")
	originalName := c.PostForm("original_name")
	if originalName == "" {
		originalName = file.Filename
	}

	// 3. Open File Payload
	f, err := file.Open()
	if err != nil {
		logger.Error("Upload failed: could not open file", "error", err)
		h.jsonError(c, http.StatusInternalServerError, "Failed to open uploaded file")
		return
	}
	defer f.Close()

	// 4. Staff Engineer Hardening: Deep MIME Verification (Magic Numbers)
	sniffBuffer := make([]byte, 3072)
	n, err := f.Read(sniffBuffer)
	if err != nil && err != io.EOF {
		logger.Error("Deep validation failed: read error", "error", err)
		h.jsonError(c, http.StatusInternalServerError, "Content verification failed")
		return
	}
	sniffBuffer = sniffBuffer[:n]

	// Reset seeker for subsequent upload
	if _, err := f.Seek(0, 0); err != nil {
		logger.Error("Deep validation failed: seek error", "error", err)
		h.jsonError(c, http.StatusInternalServerError, "Content re-seek failed")
		return
	}

	mtype := mimetype.Detect(sniffBuffer)
	detectedMime := mtype.String()

	// Whitelist Check
	allowedMimes := map[string]bool{
		"image/jpeg":      true,
		"image/png":       true,
		"image/webp":      true,
		"application/pdf": true,
		"text/plain":      true,
	}

	if !allowedMimes[detectedMime] && !strings.HasPrefix(detectedMime, "text/plain") {
		logger.Warn("Deep validation failed: rejected mime", "mime", detectedMime)
		h.jsonError(c, http.StatusBadRequest, fmt.Sprintf("Unsupported file content: detected %s", detectedMime))
		return
	}

	// 5. Key Structure Construction (Monthly Partitioning)
	key := buildObjectKey(folder, time.Now().UTC(), recordID, originalName)

	// 6. R2 Metadata
	metadata := map[string]string{
		"record-id":     recordID,
		"original-name": originalName,
		"detected-mime": detectedMime,
	}

	// 7. Streaming Upload
	logger.Info("Starting R2 stream", "key", key, "mime", detectedMime)
	err = h.R2Client.UploadFile(c.Request.Context(), key, f, detectedMime, metadata)
	if err != nil {
		logger.Error("R2 streaming upload failed", "key", key, "error", err)
		h.jsonError(c, http.StatusInternalServerError, "Cloud storage provider failure")
		return
	}

	logger.Info("Upload successful", "key", key, "mime", detectedMime)
	c.JSON(http.StatusOK, gin.H{
		"message":    "Successfully archived to R2",
		"key":        key,
		"request_id": c.GetString("requestID"),
	})
}
