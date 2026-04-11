# Environment Setup Guide

This guide ensures you have a consistent development environment for the MonOCR monorepo.

## Prerequisites

- **Node.js**: v24.0.0+ (LTS recommended)
- **pnpm**: v10.0.0+ (Workspace support required)
- **Go**: v1.23.0+ (For feedback service development)
- **Android Studio**: Latest Jellyfish+ (For Android development)
- **Xcode**: v15.0+ (For iOS development)

## Installation

1. Cloned the repository.
2. Run the shared installation command from the root:
   ```bash
   pnpm install
   ```

## Environment Configuration

1. Copy the template from the root:
   ```bash
   cp .env.example .env
   ```
2. Fill in the required keys for R2, Google Sheets, and API Authentication.

> [!IMPORTANT]
> The hangar will automatically validate your `.env` during `pnpm dev` or `pnpm build`. Missing keys will result in a graceful exit with a descriptive error.

## Common Commands

| Command | Action |
| :--- | :--- |
| `pnpm dev:all` | Starts Web, Android, and Feedback services in parallel. |
| `pnpm build` | Builds all production-ready artifacts across all engines. |
| `pnpm translate` | Synchronizes the latest locals from Google Sheets. |
| `pnpm lint:all` | Executes a unified lint pass across all languages. |
