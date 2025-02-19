# Haus Manager: Team and membership automation

## Group/Team Management

Configuration in `.github/cf-haus-manager.yml`

```yaml
dryRun: true # optional.
groupSync:
  # List of teams to sync across organizations haus-manager has access to
  - source: orgA/teamA
    # ignore the presence/absence of these logins
    # when syncing across repositories.
    # use case: management bot accounts that may be present in some organizations but not others
    ignoreUsers:
      - botLogin
    # add to the following teams. This may create an invitation.
    # note: invitation to a team adds the user to the organization
    teams:
      - orgB/teamB
    # add as external collaborators to the following repositories
    # (they will not be added to the organization)
    repositories:
      - orgC/privateRepo

emailNotifications:
  errors:
    - hausManagerError@example.com
  dryRun:
    - dryRunResults@example.com
```
