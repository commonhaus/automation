This section provides additional information about testing infrastructure and patterns used across all modules.

# Testing Overview

## Test Infrastructure Architecture

All modules share a common testing foundation provided by the `bot-github-core` module, which packages its test infrastructure into a test JAR for reuse across all bot modules.

### Key Testing Components

**ContextHelper**: The central mocking utility that provides extensive GitHub API mocks to ensure test data lookups connect properly. This helper provides virtually every incidental attribute that could be looked up during GitHub operations, allowing tests to behave properly while avoiding actual GitHub API calls.

**GitHub API Mocking**: The Quarkus GitHub App framework provides solid mocking infrastructure for testing inbound webhook events. However, webhook tests often require extensive mocked references, which ContextHelper handles comprehensively.

## Shared Test Services

The `bot-github-core` module tests the behavior of core services that other modules depend on:

- **PeriodicUpdateQueue**: Rate-limiting queue behavior and task processing
- **File and Membership Watchers**: Configuration change detection and event handling  
- **RepositoryDiscovery**: Installation and repository discovery logic

## Testing Strategies by Module

### Module-Specific Test Patterns

**bot-github-core**: Tests foundational services directly and provides test infrastructure for other modules.

**haus-keeper**: Tests OAuth flows, member management APIs, and GitHub repository-based data storage. Uses common test infrastructure to mock GitHub operations.

**haus-manager**: Tests team synchronization and access management. Relies on mocked GitHub team and collaborator APIs.

**haus-rules**: Tests voting automation and notification rules. Uses mocked GitHub discussion and issue APIs.

### Avoiding GitHub API Calls

Tests can avoid GitHub API calls through several approaches:

1. **Use ContextHelper mocks**: Comprehensive mocking of GitHub API responses
2. **Fire CDI events directly**: Bypass watchers and queue processing
3. **Mock core services**: Assume foundational services function correctly
4. **Test in isolation**: Focus on business logic rather than integration points

## Test Execution Notes

- Tests should use the shared test infrastructure from bot-github-core
- Webhook event testing relies heavily on ContextHelper for consistent mocking
- Core service behavior can be assumed to function correctly in module-specific tests
- Direct CDI event firing can bypass complex integration chains when testing specific logic