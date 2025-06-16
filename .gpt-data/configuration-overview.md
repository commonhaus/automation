This section provides additional information about configuration patterns and file management across all bot modules.

# Configuration Management Overview

## Configuration File Naming Convention

All bot configuration files follow the pattern: `cf-haus-{module}.yml`

- **cf-haus-organization.yml** → OrganizationManager (haus-manager)
- **cf-haus-manager.yml** → ProjectAccessManager (haus-manager)  
- **cf-haus-rules.yml** → Notice and Voting automation (haus-rules)

## Configuration-Driven Architecture Pattern

Each bot module reads YAML configuration files from repositories to determine behavior:

1. **File Discovery**: Bots monitor repositories for configuration files
2. **FileWatcher Integration**: Configuration changes trigger automatic updates
3. **Runtime Behavior**: Bot actions are entirely driven by configuration content
4. **Scoped Operation**: Each bot operates only on repositories containing its config files

## Configuration File Locations

**haus-manager configurations:**
- `cf-haus-organization.yml`: Located in primary organization's main repository
- `cf-haus-manager.yml`: Located in individual project repositories

**haus-rules configurations:**
- `cf-haus-rules.yml`: Located in any repository requiring vote/notice automation

## Configuration Structure Patterns

### OrganizationManager Configuration
```yaml
teamMembership:
  dryRun: true/false
  source:
    repository: org/repo-name
    filePath: CONTACTS.yaml
  defaults:
    field: login
    preserve_users:
      - bot-login
  sync:
    group-name:
      teams:
        - org/team-name
```

### ProjectAccessManager Configuration  
```yaml
teamAccess:
  source: sourceOrg/teamName
  ignoreUsers:
    - bot-login
```

### Rules Configuration
```yaml
notice:
  discussion:
    rules: [...]
    actions: [...]
voting:
  managers: ["@org/team"]
  error_email_address: [...]
  status:
    badge: "URI template"
    page: "URI template"
```

## FileWatcher Integration

**How Configuration Changes Are Detected:**
1. GitHub webhooks trigger file change events
2. FileWatcher identifies configuration file modifications
3. Bot-specific listeners receive change notifications
4. Appropriate CHANGE or RECONCILE events added to PeriodicUpdateQueue
5. Configuration is reloaded and new behavior takes effect

**Supported Change Types:**
- File creation (new configuration)
- File modification (behavior updates)
- File deletion (configuration removal)

## Configuration Validation

Each module defines configuration POJOs that map to YAML structure:
- `org.commonhaus.automation.hm.config.OrganizationConfig`
- `org.commonhaus.automation.hm.config.ProjectConfig`
- Similar patterns for haus-rules configurations

**Error Handling:**
- Invalid YAML structure logged but doesn't crash bot
- Missing required fields result in configuration being ignored
- Configuration errors sent to configured error email addresses