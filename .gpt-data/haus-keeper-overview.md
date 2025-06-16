This section provides additional information that provides context and explains design and implementation choices.

Haus Keeper is an existing module that has been refactored to use updated common services in bot-module-core.

Base file system directory for source: `haus-keeper/src/main/java`

# Haus Keeper: Foundation member self-management

Authentication is an OAuth flow managed by Quarkus OIDC.
The KnownUserInterceptor checks/caches knowledge about the user based to determine if the user is authorized (and to work with user-specific resources thereafter.)  

- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/KnownUserInterceptor.java
- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/MemberSession.java

Users work with a web-based SPA to manage their memberhip using a variety of endpoints

- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/MemberResource.java
    - `/member/github` -- OAuth flow
    - `/member/login`  -- OAuth flow
    - `/member/me`
    - `/member/commonhaus`
    - `/member/commonhaus/status`
- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/MemberAliasesResource.java
    - `/member/aliases`
- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/MemberApplicationResource.java
    - `/member/apply`
- haus-keeper/src/main/java/org/commonhaus/automation/hk/api/MemberAttestationResource.java
    - `/member/commonhaus/attest`

Some github operations specifically related to maintaining membership are performed on behalf of the user.

- haus-keeper/src/main/java/org/commonhaus/automation/hk/github/UserQueryContext.java

## Datastore

Data is also stored in github (rather than a database) as simple text (YAML) files.

Sample contents:

```yaml
---
login: "ebullient"
id: 808713
data:
  status: "COMMITTEE"
  goodUntil:
    attestation:
      council:
        withStatus: "COMMITTEE"
        date: "2025-06-08"
        version: "cf-2024-06-07"
  services:
    forwardEmail:
      configured: true
history:
- "2024-06-11T21:06:03Z Membership application accepted"
isMember: true
```

The datastore repository is accessed using a specific fine-grained access token, as it requires permission to write content, which this bot does not otherwise need.

- haus-keeper/src/main/java/org/commonhaus/automation/hk/github/CommonhausDatastore.java
- haus-keeper/src/main/java/org/commonhaus/automation/hk/github/DatastoreQueryContext.java

## Configuration

Config looks like this: 

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
