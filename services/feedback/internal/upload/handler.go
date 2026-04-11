package upload

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gabriel-vasile/mimetype"
	"github.com/gin-gonic/gin"
	"ocr-feedback-service/internal/middleware"
	"ocr-feedback-service/internal/r2"
)

type Handler struct {
	R2Client      *r2.Client
	MaxUploadSize int64
}

func NewHandler(r2Client *r2.Client, maxUploadSize int64) *Handler {
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
		logger.Warn("Upload failed: no file part", "error", err)
		h.jsonError(c, http.StatusBadRequest, "No file part in the request (expected 'file')")
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
	dateStr := time.Now().UTC().Format("2006-01")
	safeName := strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-' {
			return r
		}
		return '_'
	}, originalName)
	safeName = strings.Trim(safeName, "._-")
	if safeName == "" {
		safeName = "unnamed_file"
	}

	var key string
	if recordID != "" {
		key = fmt.Sprintf("%s/%s/%s-%s", folder, dateStr, recordID, safeName)
	} else {
		timestamp := time.Now().UTC().Format("150405")
		key = fmt.Sprintf("%s/%s/%s-%s", folder, dateStr, timestamp, safeName)
	}

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
