# https://hub.docker.com/_/golang
# The official image sets GOTOOLCHAIN=local, so the toolchain line in go.mod does
# not apply here: the binary is compiled by whatever Go this tag carries. Keep the
# patch equal to that toolchain line and bump the two together.
FROM golang:1.26.11-alpine AS builder

# Create and change to the app directory.
WORKDIR /app

# Retrieve application dependencies using go mod.
COPY go.mod go.sum ./
# Allow failure for now since go.sum might not be generated yet
RUN go mod download || true

# Copy local code to the container image.
COPY . .

# Build the binary.
RUN CGO_ENABLED=0 GOOS=linux go build -v -ldflags="-s -w" -o server ./cmd/api

# Use a distroless base image for a minimal, secure production image.
# https://github.com/GoogleContainerTools/distroless
FROM gcr.io/distroless/static-debian12:latest

# Copy the binary from the builder stage.
COPY --from=builder /app/server /server

# Run the web service on container startup.
USER nonroot:nonroot
ENTRYPOINT ["/server"]

