# haus-keeper

Member self-service: OAuth authentication, membership applications, email aliases, attestations. Data is stored as YAML files in a GitHub repository (not a database).

## Context

- [docs/haus-keeper-overview.md](../docs/haus-keeper-overview.md) - Full component breakdown (UserManager, AdminDataCache, REST endpoints, application workflow, ForwardEmailService, CommonhausDatastore)
- [docs/configuration-overview.md](../docs/configuration-overview.md) - Config file naming (`cf-haus-keeper.yml`) and FileWatcher pattern
- [docs/front-end-overview.md](../docs/front-end-overview.md) - BFF architecture: this module is backend-only; the frontend lives in a separate repository

## Key constraints

- **GitHub as datastore.** Member records are YAML files in a GitHub repo, accessed via `CommonhausDatastore` / `DatastoreQueryContext`. Do not introduce a database or local file persistence.
- **OAuth via Quarkus OIDC.** Authentication flows through `KnownUserInterceptor` and the OIDC extension. Do not add custom auth middleware.
- **Cache tiers have intentional TTLs.** `AdminDataCache` has five named tiers with specific expiry (15 min to 6 hours). Don't flatten or bypass the cache without understanding which tier applies.
- **No frontend code here.** REST API endpoints only. UI is in the separate website repository.
- **ForwardEmail API.** Email alias management calls the Forward Email external service. Keep external calls inside `ForwardEmailService`; don't scatter HTTP calls into business logic.
