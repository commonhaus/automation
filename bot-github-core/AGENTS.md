# bot-github-core

Shared foundation library. All other modules depend on this one.

## Context

- [docs/common-overview.md](../docs/common-overview.md) - QueryContext hierarchy, queue mechanics, watchers, JsonAttribute pattern, health metrics
- [docs/testing-overview.md](../docs/testing-overview.md) - ContextHelper and test infrastructure packaged here as a test JAR

## Key constraints

- **QueryContext is the only path to GitHub APIs.** `GraphQLQueryContext` → `GitHubQueryContext` → `ScopedQueryContext`. Do not bypass this hierarchy.
- **JsonAttribute over POJOs.** When parsing GitHub REST or GraphQL responses, extend `JsonAttribute` rather than creating new POJO classes. Two JSON libraries are in use (Jackson for REST, JSON-P for GraphQL) — `JsonAttribute` unifies them.
- **Queue task types matter.** CHANGE tasks always run. RECONCILE tasks collapse by `taskGroup` — use a stable, operation-specific group name so expensive reconciliations quiesce before firing. BACKGROUND tasks run only when the main queue is idle.
- **Test JAR**: This module packages its test infrastructure (`ContextHelper`, mocks) as a test JAR. Changes to test helpers here affect all modules — verify nothing breaks in dependent modules before committing.
