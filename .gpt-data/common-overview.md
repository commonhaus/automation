This section provides additional information that provides context and explains design and implementation choices.

Base file system directory for source: `bot-github-core/src/main/java`

There are six packages in the common bot module:

1. `org.commonhaus.automation.config` is the base bot config (shared by all bots) and common config types (POJO)
2. `org.commonhaus.automation.github` contains a few subpackages to manage interactions with GitHub: 
    - `context`: utilities for parsing and retaining context across queries during a session
    - `discovery`: installation/repository discovery with priority-based event processing (CONNECTED → CORE_DISCOVERY → 
  WATCHER_DISCOVERY → APP_DISCOVERY → APP_EVENT)
    - `scopes`: utility to map GitHub organizations to the correct app installation to ensure correct permissions are used when making queries
    - `stats`: project health metrics collection including repository statistics, star history, and release tracking. See [Health metrics collection](#health-metrics-collection)
    - `watchers`: File and Team monitors that convert GitHub events (completely async and whenever) into notifications for bot-specific listeners (who then add appropriate CHANGE or RECONCILE events to the queue).
3. `org.commonhaus.automation.mail` provides common code for working with the Quarkus mail extension to send mail notifications using SMTP
4. `org.commonhaus.automation.markdown` provides a common markdown converter
5. `org.commonhaus.automation.opencollective` provides a service to interact with OpenCollective GraphQL APIs
6. `org.commonhaus.automation.queue` provides a queue to manage the flow of work, see [Workflow and task management](#workflow-and-task-management)

**JsonAttribute Design Pattern**: The project uses two different JSON libraries with completely different object models:
- **Java GitHub SDK**: Jackson for REST API responses
- **Quarkus GitHub App**: JSON-P (Jakarta) for GraphQL responses

Rather than creating heavy POJOs for each library, `org.commonhaus.automation.github.JsonAttribute` provides a unified parsing approach that:

**Design Goals**:
- **Avoid POJO overhead**: POJOs for two different object models proved not worth the complexity
- **Readable parsing**: Methods read like sentences, especially for GraphQL response parsing
- **Forgiving POJOs**: Where POJOs exist, they tolerate null/missing values and only read what's needed
- **Query alignment**: Parsing maps directly onto GraphQL queries (defined nearby in code)

**JsonAttribute Benefits**:
- **(a) Avoid typos**: Common field names are enumeration values (`login`, `name`, `id`)
- **(b) Consistent parsing**: Same interface for attributes across different payload types
- **(c) Sentence-like interface**: 
    ```java
    JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(pageLabels)
    this.category = JsonAttribute.category.discussionCategoryFrom(object);
    ```

This approach provides straightforward parsing that easily maps onto GraphQL queries while avoiding the overhead of maintaining dual POJO hierarchies.

`org.commonhaus.automation.github.ContextService` (and `org.commonhaus.automation.github.BaseContextService`) provide common functions shared by all bots, like logging and sending email with common bot configuration.

**QueryContext Architecture** - The **most important** component that handles all GitHub API invocations with consistent error handling:

**Class Hierarchy**:
- `GraphQLQueryContext`: Base class for error accumulation and GraphQL operations
- `GitHubQueryContext`: Adds GitHub REST API support and installation-specific context
- `ScopedQueryContext`: Repository/organization-scoped operations with installation mapping

**Core Responsibilities**:
- **All GitHub API calls**: Every GitHub operation (REST/GraphQL) flows through QueryContext methods
- **Error accumulation**: Captures errors as close to occurrence as possible, allowing processing to continue
- **DryRun integration**: `isDryRun()` flag passed to all operations, preventing mutations during testing
- **Built-in retries**: Automatic retry for authentication errors (401/403) up to 2 attempts

**Error Handling Features**:
- **Granular error inspection**: Methods like `hasNotFound()`, `hasErrors()` for specific error type checking
- **Context-specific recovery**: Error recovery varies by situation; most cases log and stop, but some have clear fallbacks (e.g., bot comment not found by ID → search all comments to fix the link)
- **Error bundling**: `bundleExceptions()` packages all accumulated errors for final handling
- **Continuation capability**: Processing can continue after errors, with caller deciding appropriate response strategy

**Installation Scoping (`ScopedInstallationMap`)**:
- Maps GitHub organizations to specific app installation IDs
- **Multi-organization support**: Ensures each operation uses correct installation context
- **Permission isolation**: Prevents cross-organizational data access or permission leakage
- **Dynamic installation discovery**: Automatically tracks and maps available installations

**Usage Pattern**: Short-lived context objects created per operation that accumulate errors, handle authentication, manage installation scoping, and provide comprehensive error reporting to callers for intelligent retry/fail decisions.

## Workflow and task management

`org.commonhaus.automation.queue.PeriodicUpdateQueue` functions as a rate-limiting queue that processes GitHub events and CDI tasks with controlled pacing:

**Task Processing Rules**:
- **CHANGE tasks**: Always processed (represent specific events that must be handled)
- **RECONCILE tasks**: Can be collapsed by `taskGroup` to avoid redundant expensive operations
  - If multiple RECONCILE tasks with the same `taskGroup` are queued, only the latest is processed
  - This allows changes to "quiesce" before triggering expensive summary/reactive processing
- **BACKGROUND tasks**: Always processed but with lower priority to prevent starvation of main queue
    - Executed one at a time only when no CHANGE/RECONCILE tasks are pending
    - Can spawn CHANGE/RECONCILE tasks, creating interleaved execution where background work triggers priority work

**Task Group Assignment** (programmatically defined by each bot):
- **HausRules**: Uses issue/discussion ID (e.g., vote counting for a specific discussion)
- **HausManager/HausKeeper**: Varies by operation but ensures appropriate uniqueness for collapsing
- Groups are assigned to ensure expensive operations can be collapsed as late as possible

**Purpose**: Rate limiting ensures sustained GitHub API usage without overwhelming the service, while task collapsing prevents redundant work when multiple rapid changes occur.

**Retry Mechanism**: Failed tasks can be automatically retried using `scheduleReconciliationRetry()` with exponential
backoff (5s → 30s → 2min → 10min → 30min). Retry tasks are processed separately via a scheduled job every 30 seconds,
allowing recovery from transient network or authentication failures beyond the GitHub SDK's built-in retries.

### Event Flow Through the System

```mermaid
flowchart TD
    A[GitHub Repository] -->|webhook| B[Quarkus GitHub App]
    B -->|CDI Event| C{Watchers}
    C -->|config changes| D[FileWatcher]
    C -->|team/collaborator changes| E[MembershipWatcher]
    D -->|notification| F[Bot Listeners]
    E -->|notification| F
    F -->|create task| G[PeriodicUpdateQueue]
    G -->|CHANGE: always process| H[Bot Action]
    G -->|RECONCILE: collapse by group| H
    H -->|all operations| I[QueryContext]
    I -->|rate limited| J[GitHub API]
    I -->|error handling| K[Email/Logging]
    J -->|success/failure| L[Result Processing]
    L -->|may re-queue| G
```

**Key Flow Characteristics**:
- **Watchers** filter CDI events to bot-relevant notifications (FileWatcher for config changes, MembershipWatcher for team changes)
- **Queue** provides rate limiting and task collapsing (CHANGE tasks always process, RECONCILE tasks collapse by task group)
- **QueryContext** ensures all GitHub operations have consistent error handling, authentication retry, and dryRun support
- **Event-driven architecture** decouples GitHub's timing from bot processing pace while maintaining reliable multi-organization operation

## Health Metrics Collection

The `org.commonhaus.automation.github.stats` package provides repository health monitoring:

**ProjectHealthCollector**: Collects weekly statistics including:
- Issue/PR creation and closure rates
- Star history tracking
- Release frequency analysis
- Repository activity metrics

**ProjectHealthReport**: Structured health data for individual repositories, supporting both:
- **Historical analysis**: `createHistory()` generates complete repository timeline (expensive, manual-only operation)
- **Current metrics**: `collect()` gathers statistics for specific time periods with configurable data inclusion

**Usage Pattern**: Health collection is typically triggered as BACKGROUND tasks to avoid impacting real-time event
processing, with results used for dashboards, trend analysis, and project health monitoring across the organization.
