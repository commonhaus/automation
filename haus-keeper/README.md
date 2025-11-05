# Haus Keeper: Team and membership automation

## Project Email Aliases

**For Project Maintainers:** Haus Keeper can manage email aliases for your project team members.

### Configuration

Create a `project-mail-aliases.yml` file in your project repository:

```yaml
# Single domain (backward compatible)
domain: project.example.com
userMapping:
  - login: username
    aliases:
      - maintainer@project.example.com
      - lead@project.example.com

# OR: Multiple domains (new format)
domains:
  - project.example.com
  - project.example.org
userMapping:
  - login: username
    aliases:
      - maintainer@project.example.com
      - lead@project.example.org
```

### How it works

- Haus Keeper syncs aliases every 3 days (scheduled at 4:47 AM)
- Changes are automatically detected when you update the configuration file
- Email aliases are validated to ensure they match allowed domains
- User login changes are automatically handled and propagated to affected projects

### Email Notifications

You can (and should!) configure who receives error notifications:

```yaml
domains:
  - project.example.com
userMapping:
  - login: username
    aliases:
      - maintainer@project.example.com
emailNotifications:
  to:
    - project-admin@example.com
```

## Membership management (backend for Membership UI)

See:

- [KnownUserInterceptor](./src/main/java/org/commonhaus/automation/admin/api/KnownUserInterceptor.java)
- [MemberAliasesResource](./src/main/java/org/commonhaus/automation/admin/api/MemberAliasesResource.java)
    - `/member/aliases`
- [MemberApplicationResource](./src/main/java/org/commonhaus/automation/admin/api/MemberApplicationResource.java)
    - `/member/apply`
- [MemberAttestationResource](./src/main/java/org/commonhaus/automation/admin/api/MemberAttestationResource.java)
    - `/member/commonhaus/attest`
- [MemberResource](./src/main/java/org/commonhaus/automation/admin/api/MemberResource.java)
    - `/member/github`
    - `/member/login`
    - `/member/me`
    - `/member/commonhaus`
    - `/member/commonhaus/status`
- [MemberSession](./src/main/java/org/commonhaus/automation/admin/api/MemberSession.java)

```yaml
userManagement:
  defaultAliasDomain: example.com

  attestations:
    repository: commonhaus/foundation
    filePath: ATTESTATIONS.yaml

  groupRole:
    teams:
      commonhaus-test/cf-council: cfc
      commonhaus-test/cf-voting: egc
      commonhaus-test/team-quorum-default: member
    outsideCollaborators:
      commonhaus-test/sponsors-test: sponsor

  roleStatus:
    cfc: COMMITTEE
    egc: COMMITTEE
    member: ACTIVE
    sponsor: SPONSOR
```
