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

## Common config structures

- `source`: `repository` and `filePath` describing a file that should be read (for whatever reason).

    If a repository is not specified for a `source` entry, the bot home repository is used (`${automation.hausManager.home.organization}/${automation.hausManager.home.repository}`)

## Organization Management

- Configuraton file: `.github/cf-haus-organization.yml`
- *Single configuration* read from `${automation.hausManager.home.organization}/${automation.hausManager.home.repository}`
- Source: `haus-manager/src/main/java/org/commonhaus/automation/hm/config/OrganizationConfig.java`

```yaml
teamMembership:
  # If present and true, do not make any changes. Result sent to dry-run email address.
  dryRun: true

  # Source mapping teams to users
  source:
    repository: public-org/source
    filePath: CONTACTS.yaml

  # Define defaults for all teams:
  # field defines key containing GitHub login
  # preserve_users defines users whose presence/absence should be ignored (bots)
  defaults:
    field: login
    preserve_users:
      - test-bot

  # Map members of teams (as read from source) to GitHub organization teams
  sync:
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

# Define repository-specific target email addresses
# Error addresses will be combined with bot default
emailNotifications:
  errors:
    - test@test.org
  dryRun:
    - test@test.org
  audit:
    - records@test.org
```

`sync` assumes the following source file structure:

```yml
team-name-key:
    - field: user-login
    ... (other stuff, ignored)
```

For each team (`team-name-key` from source), it will ensure each user (login specified by `login` or the field defined in `defaults.field`)
is a member of the specified teams.

## Project Management

- Configuraton file: `.github/cf-haus-manager.yml`
- *Multiple configurations* read from projects in `${automation.hausManager.home.organization}`
- Source: `haus-manager/src/main/java/org/commonhaus/automation/hm/config/ProjectConfig.java`

```yaml
# If not present, or present and true, enable this function
enabled: true

# If present and true, do not make any changes. Result sent to dry-run email address.
dryRun: true

# Add users from the specified source team as collaborators
# to the repository containing this file.
# Specifially, this team can be in a different organization
teamAccess:
  source: other-org/teamA

  # ignore the presence/absence of these logins
  ignoreUsers:
    - botLogin

# Define repository-specific target email addresses
# Error addresses will be combined with bot default
emailNotifications:
  errors:
    - test@test.org
  dryRun:
    - test@test.org
  audit:
    - records@test.org
```
