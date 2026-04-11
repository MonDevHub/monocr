# Security Policy

## Responsible Disclosure
If you find a security vulnerability, please report it via a private GitHub Vulnerability Report or by contacting the maintainers directly.

## Secure Configuration
- **Secrets Management**: Environment variables, including API keys and credentials, must be stored in the local `.env` file. These are never committed to version control.
- **Service Security**: The Feedback service is protected by API key authentication. Internal documentation (Swagger) is also restricted to authenticated requests.
- **Data Protection**: Uploads are stored in Cloudflare R2 with access controls. Objects are organized using unique identifiers and date-based partitioning to prevent enumeration.

## Input Validation
Each service implements standard security checks:
- **Content Verification**: Files are verified using MIME-type sniffing (magic numbers) to ensure they match the expected format.
- **Payload Limits**: Request sizes are limited to prevent denial-of-service attacks.
- **Sanitization**: External inputs are sanitized before being used in storage operations.
