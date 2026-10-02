# MonOCR Feedback Service

A Go API that receives feedback and contributions from the Android and iOS apps and stores them in
Cloudflare R2, so storage credentials stay off client devices. Project overview in the
[root README](../../README.md).

The contract callers receive is the swaggo-generated Swagger 2.0 document in `docs/docs.go`,
served at `/v1/swagger/`. [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml) is not generated
from it and may disagree; [ADR-0003](../../docs/architecture/adr/0003-openapi-and-authenticated-docs.md)
records the gap.

## Endpoints

- `GET /health`: outside the rate limiter, so a probe still answers 200 while a client is being
  throttled.
- `POST /v1/feedback` and `POST /v1/contribution`: multipart uploads, authenticated with the
  `X-API-Key` header.
- `GET /v1/swagger/*any`: Swagger UI, behind the same key.

## Behaviour

- **Limits.** Request bodies are capped at 20 MiB (`MaxUploadSize`, `20 * 1024 * 1024`), and
  uploads are rate-limited per client IP.
- **Validation.** File types are checked by magic number, not by the declared MIME type.
- **Runtime.** Distroless image, non-root user, graceful shutdown, explicit server timeouts, and
  structured JSON logs carrying a per-request `X-Request-ID`.

## Configuration

Required; the service refuses to start without them:

| Variable               | Description                   |
| :--------------------- | :---------------------------- |
| `API_KEY`              | Secret for `X-API-Key` header |
| `R2_ACCOUNT_ID`        | Cloudflare R2 Account ID      |
| `R2_ACCESS_KEY_ID`     | R2 Access Key                 |
| `R2_SECRET_ACCESS_KEY` | R2 Secret                     |
| `R2_BUCKET_NAME`       | Target bucket name            |

Optional. An invalid rate limit or proxy list refuses to start the service rather than falling
back to a value that cannot serve traffic:

| Variable              | Description                                                             |
| :-------------------- | :---------------------------------------------------------------------- |
| `PORT`                | Service port (default: 8080)                                            |
| `GIN_MODE`            | `debug` or `release` (default: release)                                 |
| `RATE_LIMIT_REQUESTS` | Sustained requests per second per client IP (default: 5.0)              |
| `RATE_LIMIT_BURST`    | Burst allowance per client IP (default: 10)                             |
| `TRUSTED_PROXIES`     | Proxy IPs or CIDRs, comma separated. Unset trusts every hop; see below. |

`env.example` lists them all.

Rate limiting is keyed on the client IP as gin resolves it. With `TRUSTED_PROXIES` unset, gin
trusts every hop and takes the client-supplied end of `X-Forwarded-For`: that is per-user rather
than one bucket for the whole user base, but a caller that rotates the header gets a fresh quota,
so treat it as a fairness control, not an anti-abuse one. Set `TRUSTED_PROXIES` to the range your
front end connects from to make the key unforgeable. Memory is bounded either way: idle buckets
are evicted and the total is hard capped.

## Develop and test

Requires Go 1.26 or later (`go.mod`).

```bash
cd services/feedback
go run ./cmd/api
go test ./...
go vet ./...
```

CI also runs `gofmt`, `go build` and `govulncheck`, and fails unless at least 60 tests passed.

## Deploy

```bash
docker build -t ocr-feedback-service .
```

## Layout

- `cmd/api/`: entry point and routes.
- `internal/auth/`: API key middleware.
- `internal/config/`: configuration loading and validation.
- `internal/middleware/`: body limit, rate limiting, recovery and tracing.
- `internal/r2/`: Cloudflare R2 client.
- `internal/upload/`: upload handling and validation.
