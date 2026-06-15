# haus-manager

Organization, team, collaborator, sponsor, and domain management. Driven entirely by YAML configuration files found in repositories — no imperative admin interface.

## Context

- [docs/haus-manager-overview.md](../docs/haus-manager-overview.md) - Full component breakdown (OrganizationManager, ProjectManager, SponsorManager, ProjectHealthManager, TeamConflictResolver, DomainMonitor + Namecheap API notes)
- [docs/configuration-overview.md](../docs/configuration-overview.md) - Config file naming (`cf-haus-organization.yml`, `cf-haus-manager.yml`) and FileWatcher pattern
- [docs/api-reference/](../docs/api-reference/) - Namecheap API reference (consult before touching `DomainMonitor` or Namecheap integration)

## Key constraints

- **Organization takes priority over projects.** `TeamConflictResolver` enforces: ORGANIZATION > single PROJECT > PROJECT_CONFLICT (blocked). Don't short-circuit conflict resolution.
- **Bootstrap vs. runtime behavior differs.** During initial discovery, reconciliation is deferred until all repositories are found. Runtime config changes trigger immediate reconciliation. Maintain this distinction.
- **Namecheap API is unusual.** All operations use GET (including mutations). Responses are XML. Global auth params are injected by `NamecheapClientFilter`. Use the existing `@BeanParam` request classes and `NamecheapResponseParser` — don't write raw API calls.
- **DomainMonitor has two-level dryRun.** Both org-level `domainMonitoring.dryRun` and domain-level `domainManagement.dryRun` must be checked. Effective dryRun is `org.isMonitoringDryRun() OR domain.isDryRun()`.
- **Health collection is BACKGROUND priority.** `ProjectHealthManager` tasks must not starve the main queue. Keep health work in BACKGROUND task type.
- **Task group naming is load-bearing.** Each manager uses specific group names for queue collapsing (`"🏡-org"`, `"cfg#" + repoFullName`, `"💸-sponsor"`, `"health#" + repoFullName`). Changing these breaks collapse behavior.
