# Haus Manager: Team and membership automation

## Application properties

`bot-github-core/src/main/java/org/commonhaus/automation/config/BotConfig.java`

```properties
automation.reply-to=no-reply@example.com
automation.error-email-address=send-errors-to@example.com

automation.dry-run=true

# Examine all registered installations on startup
automation.discovery-enabled=true

# Constraints on queue used to execute against GH API
automation.queue.initial-delay=10s
automation.queue.period=2s
```

`haus-manager/src/main/java/org/commonhaus/automation/hm/config/ManagerBotConfig.java`

```properties
# Home organization and repository.
# Config (used below) will only be discovered in this org and repo.
automation.hausManager.home.organization=commonhaus-test
automation.hausManager.home.repository=automation-test

# Quartz cron expressions for re-scan of organization and team members
automation.hausManager.cron.organization=0 47 2 */3 * ?
automation.hausManager.cron.projects=0 47 */3 * * ?
```

## Organization Management

Organization-level configuration management.

- *Single configuration* read from `${automation.hausManager.home.organization}/${automation.hausManager.home.repository}`
- File path: `.github/cf-haus-organization.yml`
- Source: `haus-manager/src/main/java/org/commonhaus/automation/hm/config/OrganizationConfig.java`

[`teamMembership`](#team-membership) is a common/shared structure.

```yaml
teamMembership:
  - # Source mapping teams to users
    source:
      repository: public-org/source
      filePath: CONTACTS.yaml

    # If present and true, do not make any changes. Result sent to dry-run email address.
    dryRun: true

    # Define defaults for all teams:
    # field defines key containing GitHub login
    # ignoreUsers: users not in the source list that should be ignored
    # preserveUsers: users not in the source list that should be added if missing
    defaults:
      field: login
      preserveUsers:
        - maintainer
      ignoreUsers:
        - test-bot

    # Map members of teams (as read from source) to GitHub organization teams
    # field, ignoreUsers, perserveUsers can be defined as siblings of teams
    pushMembers:
      cf-council:
        teams:
          - test-org/cf-council
          - test-org/admin
      egc:
        teams:
          - test-org/team-quorum

## List of known projects (single source of truth, verifies project configuration)
projects:
  source:
    filePath: PROJECTS.yaml

emailNotifications:
  errors:
    - test@test.org
  dryRun:
    - test@test.org
  audit:
    - records@test.org
```

## Project Management

Project-level management for project-specific teams and resources.

- *Multiple configurations* read from projects in `${automation.hausManager.home.organization}`
- File path: `.github/cf-haus-manager.yml`
- Source: `haus-manager/src/main/java/org/commonhaus/automation/hm/config/ProjectConfig.java`

```yaml
# If not present, or present and true, enable this function
enabled: true

# If present and true, do not make any changes. Result sent to dry-run email address.
dryRun: true

# Add users from the specified source team as collaborators
# to the repository containing this file.
# This team will likely be in a different organization
collaboratorSync:
  sourceTeam: other-org/teamA

  # Hard-code some logins that should be added as collaborators
  includeUsers:
    - userX

  # ignore the presence/absence of these logins
  ignoreUsers:
    - botLogin

teamMembership:
  - ... # same as org-level config, see below

emailNotifications:
  errors:
    - test@test.org
  dryRun:
    - test@test.org
  audit:
    - records@test.org
```

## Common config structures

### Source

`source` describes a file that should be read (for whatever reason).

```yaml
source:
  repository: full/name
  filePath: path/to/file
```

If a repository is not specified for a `source` entry, the bot home repository is used (`${automation.hausManager.home.organization}/${automation.hausManager.home.repository}`)

### Email notifications

`emailNotifications` describe addresses that various notifications should be sent to.

```yaml
emailNotifications:
errors:
    - test@test.org
dryRun:
    - test@test.org
audit:
    - records@test.org
```

Error addresses will be combined with bot configured default

### Team membership

```yaml
teamMembership:
  - # Source mapping teams to users
    source:
      repository: public-org/source
      filePath: CONTACTS.yaml

    # If present and true, do not make any changes. Result sent to dry-run email address.
    dryRun: true

    # optional path to array of groups
    mapPointer: /path/to/groupList"

    # Define defaults for all teams:
    # field defines key containing GitHub login
    # ignoreUsers: users not in the source list that should be ignored
    # preserveUsers: users not in the source list that should be added if missing
    defaults:
      field: login
      preserveUsers:
        - maintainer
      ignoreUsers:
        - test-bot

    # Map members of teams (as read from source) to GitHub organization teams
    # field, ignoreUsers, perserveUsers can be defined as siblings of teams
    pushMembers:
      cf-council:
        teams:
          - test-org/cf-council
          - test-org/admin
      egc:
        teams:
          - test-org/team-quorum
```
