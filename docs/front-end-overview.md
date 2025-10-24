# Frontend Integration Overview

This document explains the design and implementation of frontend integration in the automation bots.

## Architecture Pattern

This project follows a **Backend-for-Frontend (BFF)** pattern where:
- **haus-keeper** provides the backend APIs and OAuth endpoints
- **Separate frontend repository** contains the static website (TypeScript/Svelte SPA)
- **Static site** is compiled and served via GitHub Pages

## Frontend-Backend Integration

**haus-keeper Backend APIs:**
- `/member/github` - OAuth flow initiation
- `/member/login` - OAuth authentication
- `/member/me` - User profile data
- `/member/commonhaus` - Foundation membership data
- `/member/commonhaus/status` - Membership status
- `/member/aliases` - Email alias management
- `/member/apply` - Membership application
- `/member/commonhaus/attest` - Attestation workflow

**Frontend Responsibilities:**
- Member self-service interface
- OAuth flow handling
- Foundation membership management UI
- Email alias configuration
- Application and attestation workflows

## Deployment Architecture

- **Backend**: haus-keeper module (this repository)
- **Frontend**: Separate repository with static site generation
- **Hosting**: GitHub Pages for static frontend, backend deployed separately
- **Integration**: Frontend consumes haus-keeper REST APIs

This automation bot project focuses solely on the backend services - frontend implementation details are maintained in the separate website repository.
