This section provides additional information that provides context and explains design and implementation choices.

Base file system directory for source: `haus-manager/src/main/java`

# Haus Manager: Organization/Team (ACL) management

The Haus Manager synchronizes team and collaborator membership across organizations and repositories based on known configurations.

The Bot's configuration (ManagerBotConfig) defines a single primary organization and a single/primary repository to check for organization configuration.

- OrganizationManager (single: only in the primary org / main repository) 

    ```yaml
    teamMembership:
      dryRun: true
      source:
        repository: commonhaus/foundation
        filePath: CONTACTS.yaml
      defaults:
        field: login
        preserve_users:
          - commonhaus-bot
      sync:
        cf-council:
          teams:
            - commonhaus-test/cf-council
            - commonhaus-test/admin
    ```

    - `cf-haus-organization.yml` == `org.commonhaus.automation.hm.config.OrganizationConfig`
    - Reads from a configured `source` (usually CONTACTS.yaml) associates GitHub logins with a particular group. 
    - `sync` maps these groups to one or more teams (which may or may not be in the same organization)
    - The OrganizationManager ensures that the membership of these target GitHub teams matches the published/configured logins from the source file.

- ProjectAccessManager (multiple: any repository in the primary organization):

    ```yaml
    teamAccess:
      source: orgA/teamA
      # ignore the presence/absence of these logins
      ignoreUsers:
        - botLogin
    ```

    - `cf-haus-manager.yml` == `org.commonhaus.automation.hm.config.ProjectConfig`
    - `teamAccess` defines a source GitHub team (usually in a different organization)
    - The ProjectAccessManager ensures that members of that source team are collaborators on the repository that contains the config file.

Both managers use the FileWatcher to be notified when the config files change. Both rely on the MembershipWatcher to be notified when team membership or outside collaborators on a repository change.
