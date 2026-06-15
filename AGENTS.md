# AI Agent Guidelines

**For build commands and development setup, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## Architecture

- [docs/common-overview.md](docs/common-overview.md) - Core framework (QueryContext, queue, watchers, JsonAttribute pattern)
- [docs/pom-xml-overview.md](docs/pom-xml-overview.md) - Module structure and dependencies

## Module layout

```text
bot-github-core/   shared library (QueryContext, queue, watchers, test infrastructure)
haus-keeper/       member self-service, OAuth, email aliases, GitHub-backed data storage
haus-manager/      org/team/collaborator/sponsor/domain management
haus-rules/        vote counting and notice automation
```

Each module has its own `AGENTS.md` with module-specific context. Read it before making changes within that module.

## Cross-cutting concerns

- [docs/configuration-overview.md](docs/configuration-overview.md) - Config file naming conventions and FileWatcher pattern
- [docs/testing-overview.md](docs/testing-overview.md) - Shared test infrastructure (ContextHelper, mocking patterns)
- [docs/api-reference/](docs/api-reference/) - API documentation

## Key constraints

- **No Spring**: Quarkus only — no Spring annotations or dependencies.
- **JSON libraries**: Two are in use. Jackson for GitHub REST (Java GitHub SDK); JSON-P (Jakarta) for GraphQL (Quarkus GitHub App). Use `JsonAttribute` rather than creating new POJOs for parsing either. See `common-overview.md`.
- **All GitHub API calls go through QueryContext** — never call GitHub APIs directly. This ensures error accumulation, dryRun support, and auth retry behavior.
- **Queue discipline**: Route work through `PeriodicUpdateQueue`. Use CHANGE for specific events, RECONCILE (with a stable `taskGroup`) for expensive idempotent operations that can be collapsed.
