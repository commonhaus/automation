# Haus Manager: Organization and Project Management Automation

*Comprehensive management of GitHub organizations, teams, collaborators, sponsors, and project health monitoring*

> **GitHub App**: [haus-manager-bot](https://github.com/apps/haus-manager-bot)  
> **Source Code**: [commonhaus/automation/haus-manager](https://github.com/commonhaus/automation/tree/main/haus-manager)

## Overview

Haus Manager provides automated management for GitHub organizations and projects, including:

- **Team Membership**: Sync teams from source-controlled configuration files
- **Repository Access**: Automated collaborator management across organizations  
- **Sponsor Management**: Automatic repository access for GitHub Sponsors and OpenCollective supporters
- **Project Health**: Weekly metrics collection and reporting
- **Conflict Resolution**: Prevents team ownership conflicts between organization and project configurations

## Architecture

Haus Manager uses a single-instance architecture with specialized components:

- **OrganizationManager**: Manages organization-level team membership from the primary repository
- **ProjectManager**: Handles project-specific configurations across multiple repositories  
- **SponsorManager**: Automates sponsor access based on GitHub Sponsors and OpenCollective
- **ProjectHealthManager**: Collects weekly health metrics for configured repositories
- **TeamConflictResolver**: Prevents conflicts between organization and project-level team management

### Operational Schedules

- **SponsorManager**: Every 3 days at 1:47 AM (`0 47 1 */3 * ?`)
- **OrganizationManager**: Every 3 days at 2:47 AM (`0 47 2 */3 * ?`)  
- **ProjectManager**: Every 3 days at 4:47 AM (`0 47 4 */3 * ?`)
- **ProjectHealthManager**: Weekly on Sunday at 6:00 AM (`0 0 6 ? * SUN`)

## Quick Start

### 1. Install the GitHub App

1. [Install Haus Manager Bot](https://github.com/apps/haus-manager-bot)
2. Configure for your GitHub organization(s)
3. Select repositories for access management

### 2. Basic Repository Access Configuration

Configuration files are only read from repositories in the Commonhaus organization.
These config files can be used to manage settings across GitHub organizations and repositories related to that project (which also have haus-manager installed).

Create `.github/cf-haus-manager.yml` in your Commonhaus project repository:

```yaml
enabled: true

# Add users from source team as collaborators
collaboratorSync:
  sourceTeam: other-org/teamA
  role: triage  # Options: read, triage, write, maintain, admin

emailNotifications:
  errors: [admin@example.org]
  dryRun: [admin@example.org]
  audit: [records@example.org]
```

This automatically grants repository access to members of `other-org/teamA`.

## Advanced Features

### Team Management

Synchronize team membership in your project's GitHub organization based on source-controlled configuration files:

```yaml
teamMembership:
  - source:
      repository: your-org/source-repo
      filePath: CONTACTS.yaml
    defaults:
      field: login
      preserveUsers: [commonhaus-bot]
      ignoreUsers: [temp-user]
    pushMembers:
      core-team:
        teams: [your-org/maintainers, your-org/admin]
```

### Project Health Monitoring

Collect weekly health metrics for your repositories:

```yaml
projectHealth:
  organizationRepositories:
    "your-project-org":
      include: [main-repo, website-repo]
      exclude: [archived-repo, template-repo]
```

### Sponsor Management

Organization-level sponsor configuration automatically grants repository access:

```yaml
sponsors:
  sponsorable: "organization-or-user"
  targetRepository: "org/repo"
  role: "triage"
  ignoreUsers: ["non-sponsor-user"]
```

### Team Conflict Resolution

Haus Manager prevents conflicts between organization and project-level team management:

- **Organization Priority**: Organization-level configs always take precedence
- **Project Isolation**: Single project configs work when no organization conflict exists  
- **Conflict Detection**: Multiple projects claiming the same team are automatically blocked
- **Email Alerts**: Administrators receive notifications about conflicts

## GitHub App Permissions

**Summary:**
- **Read** access to code, discussions, issues, metadata, organization administration, and pull requests
- **Read** and **write** access to administration and members

**Organization Permissions:**
- **Administration**: *Read* - Organization settings access
- **Members**: *Read/Write* - Management of members and teams

**Repository Permissions:**
- **Administration**: *Read/Write* - Repository settings, teams, and collaborators
- **Contents**: *Read* - Read configuration files and commit statistics  
- **Discussions, Issues, Pull Requests**: *Read* - Gather statistics for health reports

For the primary/home organization, Haus Manager uses an additional **fine-grained access token** for:
- **Contents**: *Write* access for committing health reports and metrics
- **Repository Dispatch**: Trigger workflows and automation events

## Troubleshooting

### Common Issues

- **Configuration Not Applied**: Verify `.github/cf-haus-manager.yml` exists with valid YAML syntax
- **Team Sync Issues**: Ensure Haus Manager has access to both source and target organizations
- **Permission Errors**: Check that administrators have approved the requested permission level
- **Team Conflicts**: Review email notifications for conflict resolution guidance
- **Health Monitoring Not Working**: Verify repositories are listed correctly with read access

### Debugging Steps

1. **Enable Dry Run**: Add `dryRun: true` to preview changes
2. **Check Email Notifications**: Review error emails for specific failure details
3. **Verify File Paths**: Ensure source file paths are correct and accessible
4. **Test Permissions**: Confirm Haus Manager can access all specified repositories
5. **Review Team Names**: Double-check team names match exactly (case-sensitive)

Configuration or access errors will be emailed to the `errors` address specified in `emailNotifications`.

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
automation.hausManager.cron.projects=0 47 4 */3 * ?
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
