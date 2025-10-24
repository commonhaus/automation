# Maven Module Structure

This repository contains a multi-module Maven project using Quarkus framework for GitHub automation.

## Module Dependencies

```
bot-github-core (shared library)
├── haus-keeper (extends core)
├── haus-manager (extends core)
└── haus-rules (extends core)
```

## Module Descriptions

**bot-github-core**: Foundation library providing shared functionality
- GitHub API utilities and context management
- Rate-limited event processing queue
- File and membership watchers
- Email notifications and markdown processing
- Test infrastructure (packaged as test JAR for other modules)

**haus-keeper**: Member self-management service
- OAuth-based member authentication
- GitHub repository-based data storage
- Email alias management integration
- Dependency: bot-github-core

**haus-manager**: Organization and team access management
- Team membership synchronization
- Repository collaborator management
- Dependency: bot-github-core

**haus-rules**: Voting automation and notifications
- Automated vote counting with multiple methods
- Label-based notification system
- Configuration-driven behavior
- Dependency: bot-github-core

## Build Characteristics

- **Framework**: Quarkus (no Spring dependencies)
- **Build Tool**: Maven with Quarkus plugin
- **Shared Testing**: bot-github-core provides test infrastructure via test JAR
- **Module Isolation**: Each bot module can be built and deployed independently
- **Common Patterns**: All modules follow the same configuration and event processing patterns