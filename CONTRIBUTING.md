
# Contributing to Commonhaus Foundation Automation Bot

This repository contains GitHub automation tools for the Commonhaus Foundation, implemented as Quarkus applications using the GitHub App framework.

## Project Structure

- **bot-github-core**: Common library with shared GitHub API utilities ([docs/common-overview.md](docs/common-overview.md))
- **haus-keeper**: Member self-management with OAuth interface ([docs/haus-keeper-overview.md](docs/haus-keeper-overview.md))
- **haus-manager**: Organization/team access management ([docs/haus-manager-overview.md](docs/haus-manager-overview.md))
- **haus-rules**: Voting automation and notifications ([docs/haus-rules-overview.md](docs/haus-rules-overview.md))

## Code Standards

- **Java 21+**: Use modern Java features (records, sealed types, pattern matching)
- **Standard Libraries**: Prefer standard Java libraries over external dependencies
- **No Spring**: Avoid Spring and related libraries entirely

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

## Development Patterns

When implementing new features:

1. **Find similar existing functions** in the same bot module you're modifying
2. **Follow established patterns** already in use rather than creating new approaches
3. **Use existing APIs and utilities** - each bot has commonly used patterns that should be emulated
4. **Refer to `docs/` architecture documentation** for understanding how components interact

## Architecture Overview

### Key Architectural Patterns

- **Event-driven processing**: GitHub webhooks trigger events that are queued and processed with rate limiting
- **Configuration-driven**: Each bot reads YAML configuration files from repositories to determine behavior
- **Scoped contexts**: `QueryContext` objects accumulate errors and provide consistent GitHub API interaction patterns
- **File watchers**: Monitor configuration file changes and trigger appropriate actions
- **Queue-based processing**: `PeriodicUpdateQueue` ensures sustained pace with GitHub APIs, skipping redundant RECONCILE tasks

**Data Storage**: GitHub repositories as persistence (YAML files for member data and configuration)

**Authentication**: OAuth + fine-grained tokens (haus-keeper), GitHub App permissions (haus-manager/haus-rules)

See [docs/](docs/) for detailed architecture and [AGENTS.md](AGENTS.md) for AI collaboration guidelines.

## Contributing

Contributions should address real needs, follow existing patterns, and include appropriate tests. Contributors must understand and be able to explain their changes.
