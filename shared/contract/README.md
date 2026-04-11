# MonOCR API Contract

This document defines the shared communication protocols between the **Independent Engines** (Web, Android, iOS) and the **Feedback Service**.

## Base URL
- Production: `https://api.monocr.example.com`
- Authentication: `X-API-Key` header required.

---

### [POST] /v1/feedback
Used to submit user reports and corrections.

**Payload (Multipart Form):**
- `file`: (Binary) The image or PDF being reported.
- `record_id`: (String) UUID of the recognition attempt.
- `original_name`: (String) Filename on the client.

**Response (200 OK):**
```json
{
  "message": "Successfully archived to R2",
  "key": "feedback/2026-04/...",
  "request_id": "req-..."
}
```

---

### [POST] /v1/contribution
Used to submit high-quality samples for future dataset training.

**Payload (Multipart Form):**
- `file`: (Binary) The contribution sample.
- `original_name`: (String) Filename on the client.

**Response (200 OK):**
```json
{
  "message": "Successfully archived to R2",
  "key": "contribution/2026-04/...",
  "request_id": "req-..."
}
```

---

## Error Handling
All endpoints return a `request_id` for traceability.
- `400`: Validation error (e.g., unsupported MIME type).
- `413`: Payload too large (Max 20MB for mobile).
- `500`: Storage or internal failure.
