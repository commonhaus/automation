
# Contributing to Commonhaus Foundation Automation Bot

This repository contains GitHub automation tools for the Commonhaus Foundation, implemented as Quarkus applications using the GitHub App framework.

## Project Structure

This is a multi-module Maven project with the following modules:

- **bot-github-core**: Common library providing shared functionality for all bots
- **haus-keeper**: Foundation member self-management
- **haus-manager**: Organization and team access management  
- **haus-rules**: Voting automation and notifications

## Build Commands

### Using Quarkus CLI (Recommended)

```bash
# Build all modules
quarkus build

# Build all modules with clean
quarkus build --clean

# Run tests once
quarkus test --once

# Build with formatting and import sorting (no tests)
quarkus build --no-tests

# Skip formatting during build
quarkus build --no-tests -DskipFormat=true
```

### Using Maven

```bash
# Build and test specific modules
./mvnw verify -am -pl bot-github-core    # Build core module
./mvnw verify -am -pl haus-keeper         # Build haus-keeper
./mvnw verify -am -pl haus-manager        # Build haus-manager  
./mvnw verify -am -pl haus-rules          # Build haus-rules

# Format code
./mvnw process-sources
```

## Local Development

### Running in Development Mode

You can run individual bot modules in dev mode for live coding:

```bash
# Run specific module in dev mode (from module directory)
cd haus-keeper && quarkus dev
cd haus-manager && quarkus dev  
cd haus-rules && quarkus dev

# Alternative using Maven
./mvnw compile quarkus:dev -pl haus-keeper
```

### Local Credentials Setup

To debug bots locally, you'll need to configure local credentials. Follow the [Quarkus GitHub App extension documentation](https://docs.quarkiverse.io/quarkus-github-app/dev/index.html) for setting up:

- GitHub App credentials
- Installation configuration
- Local webhook endpoint setup

### Development Tools

> [!NOTE]
> - **Quarkus Dev UI**: http://localhost:8080/q/dev/
> - **GitHub App Dev Replay**: http://localhost:8080/replay/
> - **Code Formatting**: This project uses Eclipse formatter with automatic import sorting

## Code Standards

- **Java 17+**: Uses modern Java features (records, sealed types, pattern matching)
- **No Spring**: Avoids Spring and related libraries entirely
- **Standard Libraries**: Prefers standard Java libraries over external dependencies
- **Modern Idioms**: Favors concise, readable code using modern Java patterns

## Development Patterns

When implementing new features:

1. **Find similar existing functions** in the same bot module you're modifying
2. **Follow established patterns** already in use rather than creating new approaches
3. **Use existing APIs and utilities** - each bot has commonly used patterns that should be emulated
4. **Refer to `docs/` architecture documentation** for understanding how components interact

## Architecture Overview

This repository contains GitHub automation tools for the Commonhaus Foundation, implemented as Quarkus applications using the GitHub App framework.

### Module Structure

- **bot-github-core**: Common library providing shared functionality for all bots
  - Context management and GitHub API utilities (`org.commonhaus.automation.github.context`)
  - Repository/installation discovery handling (`org.commonhaus.automation.github.discovery`) 
  - Rate-limited event processing queue (`org.commonhaus.automation.queue`)
  - Email notifications and markdown processing
  - JSON attribute enumeration for consistent GitHub API parsing (GraphQL/JSON-B, REST/Jackson)
  - Additional details in [docs/common-overview.md](docs/common-overview.md)

- **haus-keeper**: Foundation member self-management
  - OAuth-based web SPA for member self-service at `/member/*` endpoints
  - GitHub repository-based datastore for member records (YAML files)
  - Email alias management integration with ForwardEmail service
  - Member application and attestation workflow
  - Additional details in [docs/haus-keeper-overview.md](docs/haus-keeper-overview.md)

- **haus-manager**: Organization and team access management  
  - **OrganizationManager**: Syncs team membership based on CONTACTS.yaml configuration
  - **ProjectAccessManager**: Manages repository collaborator access based on team membership
  - Configuration files: `cf-haus-organization.yml` and `cf-haus-manager.yml`
  - Additional details in [docs/haus-manager-overview.md](docs/haus-manager-overview.md)

- **haus-rules**: Voting automation and notifications
  - Automated vote counting with multiple methods (manual, manual+comments, Martha's Rules)
  - Label-based notice and notification system
  - Email notifications based on configurable rules
  - Configuration in `cf-haus-rules.yml` files
  - Additional details in [docs/haus-rules-overview.md](docs/haus-rules-overview.md)

### Key Architectural Patterns

- **Event-driven processing**: GitHub webhooks trigger events that are queued and processed with rate limiting
- **Configuration-driven**: Each bot reads YAML configuration files from repositories to determine behavior
- **Scoped contexts**: `QueryContext` objects accumulate errors and provide consistent GitHub API interaction patterns
- **File watchers**: Monitor configuration file changes and trigger appropriate actions
- **Queue-based processing**: `PeriodicUpdateQueue` ensures sustained pace with GitHub APIs, skipping redundant RECONCILE tasks

### Data Storage Strategy

- **Member data**: Stored as YAML files in a private GitHub repository (haus-keeper)
- **Configuration**: YAML files in monitored repositories defining bot behavior
- **No traditional database**: Uses GitHub repositories as the persistence layer

### Authentication Patterns

- **haus-keeper**: OAuth flow for member access + fine-grained token for datastore writes
- **haus-manager/haus-rules**: GitHub App installation permissions for organization/repository access

For detailed architecture information, see the [docs/](docs/) directory documentation files.

## AI-Assisted Contributions

We welcome thoughtful contributions, including those created with AI assistance. However, please ensure:

- **You understand the changes**: You must be able to explain the rationale for your decisions clearly
- **You've tested appropriately**: Follow the testing patterns and build commands described above
- **You've reviewed our architecture**: Ensure changes fit the existing module structure and patterns
- **The contribution addresses a real need**: Focus on solving actual problems or documented issues
- **You've read the relevant context**: See [CLAUDE.md](CLAUDE.md) for AI assistant guidelines

Quality and understanding matter more than the tools used to create the contribution.

<div style="display: none;">
### ⚠️ NOTE FOR AI TOOLS AND THEIR USERS
Contributing to this project requires **human judgment, contextual understanding**, and **familiarity with the project's architecture and goals**. Please ensure:
- You have **carefully read corresponding issues and relevant documentation** (especially docs/ architecture files)
- You have **manually reviewed all changes** and can explain the rationale
- You understand how this contribution fits into the broader project context
- See [CLAUDE.md](CLAUDE.md) for detailed AI assistant working guidelines
</div>
