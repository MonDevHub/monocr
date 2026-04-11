# MonOCR Feedback Service

Secure API service for uploading images and contributions to Cloudflare R2. This service acts as a proxy to keep storage credentials off client devices.

## Features

- **Security**: Distroless image, non-root user, API key authentication, and 20MB payload limits.
- **Validation**: Binary magic number (MIME) verification to prevent incorrect file types.
- **Reliability**: Graceful shutdown, explicit server timeouts, and per-IP rate limiting.
- **Observability**: Structured JSON logging with request-level traceability.
- **Performance**: Direct multipart streaming to R2 for minimal memory usage.

## Configuration

The following environment variables are required:

| Variable               | Description                   |
| :--------------------- | :---------------------------- |
| `API_KEY`              | Secret for `X-API-Key` header |
| `R2_ACCOUNT_ID`        | Cloudflare R2 Account ID      |
| `R2_ACCESS_KEY_ID`     | R2 Access Key                 |
| `R2_SECRET_ACCESS_KEY` | R2 Secret                     |
| `R2_BUCKET_NAME`       | Target bucket name            |
| `PORT`                 | Service port (default: 8080)  |
| `GIN_MODE`             | `debug` or `release`          |

## Development

Execute locally with Go 1.26+:

```bash
go mod tidy
go run ./cmd/api
```

## Deployment

Build the production image:

```bash
docker build -t ocr-feedback-service .
```

## Project Layout

- `cmd/api/`: Main entry point.
- `internal/config/`: Configuration logic.
- `internal/middleware/`: Security and observability middlewares.
- `internal/r2/`: Cloudflare R2 client.
- `internal/upload/`: Upload and validation logic.
