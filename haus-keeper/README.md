# Haus Keeper: Team and membership automation

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
    repo: commonhaus/foundation
    path: ATTESTATIONS.yaml

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
