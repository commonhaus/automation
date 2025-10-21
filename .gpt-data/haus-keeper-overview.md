This section provides additional information that provides context and explains design and implementation choices.

Base file system directory for source: `haus-keeper/src/main/java`

# Haus Keeper: Member Self-Service & Project Management

The Haus Keeper provides comprehensive member self-service capabilities including membership applications, email alias management, attestations, and project-specific email forwarding. Built on Quarkus with OAuth authentication and GitHub-based data storage.

## Core Management Components

### UserManager

**Configuration Discovery**: Manages HausKeeper configuration discovery and file watching.

- Watches for `cf-haus-keeper.yml` configuration files in the datastore repository
- Uses FileWatcher to monitor configuration changes
- Coordinates with ActiveHausKeeperConfig for dynamic configuration updates
- **Discovery Priority**: `RdePriority.APP_DISCOVERY`

### ProjectAliasManager

**Email Alias Management**: Handles project-specific email alias configuration and synchronization.

```yaml
# project-mail-aliases.yml in individual project repositories
domains:
  - "project.example.com"
userMapping:
  - login: "username"
    aliases:
      - "maintainer@project.example.com"
      - "lead@project.example.com"
```

- **Scheduled Sync**: Every 3 days at 4:47 AM (`0 47 4 */3 * ?`)
- **User Login Changes**: Listens for `LoginChangeEvent` and notifies affected projects
- **Task Groups**: `📫-aliases-{repoFullName}` for per-repository workflow management
- **Domain Validation**: Ensures project domains match central configuration

### AdminDataCache

**Multi-Level Caching**: Provides sophisticated caching for different data types.

- **APPLICATION_STATE**: User application workflow state (3 hours)
- **MEMBER_SESSION**: Active GitHub sessions (15 minutes)  
- **COMMONHAUS_DATA**: User record state for deferred persistence (3 hours)
- **KNOWN_USER**: User authorization cache (6 hours)
- **ALIASES**: Forward email alias cache (6 hours)

## API Layer & Authentication

**Authentication**: OAuth flow managed by Quarkus OIDC with GitHub integration.

**Authorization**: KnownUserInterceptor validates user permissions and caches authorization state.

### REST API Endpoints

**MemberResource**: Core member management
- `/member/github` - OAuth flow initiation
- `/member/login` - OAuth callback
- `/member/me` - User profile information
- `/member/commonhaus` - Commonhaus-specific data
- `/member/commonhaus/status` - Member status information

**MemberApplicationResource**: Membership application workflow
- `GET/POST /member/apply` - Application submission and retrieval

**MemberAliasesResource**: Email alias management
- `GET/POST /member/aliases` - Email alias configuration

**MemberAttestationResource**: Member attestation process
- `/member/commonhaus/attest` - Attestation submission

**CouncilResource**: Administrative functions
- Council-specific administrative endpoints

## Application Workflow

### MemberApplicationProcess

**Complete Membership Lifecycle**: Orchestrates the entire membership application process.

**Application States**:
- `application/new` - Initial application submitted
- `application/accepted` - Application approved
- `application/declined` - Application rejected

**Workflow**:
1. **Submission**: User submits application via REST API
2. **Issue Creation**: Creates GitHub issue in datastore repository
3. **Review Process**: Uses GitHub labels and voting system
4. **Decision Processing**: Automated label-based decision handling
5. **Team Assignment**: Automatic team membership for accepted applications
6. **Email Notifications**: Template-based notifications to applicants

**Feedback System**: Supports reviewer feedback with `::response::` comment syntax

## Email Integration

### ForwardEmailService
**Email Forwarding Management**: Integrates with Forward Email API for alias management.

- **REST Client Integration**: Connects to Forward Email service
- **Alias Lifecycle**: Create, update, fetch, and manage email aliases
- **Password Generation**: Automated password generation for email accounts
- **Validation**: Domain and recipient validation
- **Caching**: Cached API calls to reduce external service load

**Configuration Integration**:
- Domain management from central configuration
- User permission validation
- Sanitization of input addresses

## Data Management

### CommonhausDatastore

**GitHub-Based Storage**: Uses GitHub repository as persistent storage for member data.

**Data Structure**: YAML files stored per user with comprehensive member information:

```yaml
---
login: "username"
id: 12345
data:
  status: "ACTIVE"  # ACTIVE, COMMITTEE, SPONSOR, PENDING, DECLINED
  goodUntil:
    attestation:
      council:
        withStatus: "COMMITTEE"
        date: "2025-06-08"
        version: "cf-2024-06-07"
  services:
    forwardEmail:
      defaultAlias: true # Only present if user may have user@commonhaus.dev address
      altAlias: # Alternate commonhaus.dev address (rare), or alternate project address (user@project.org)
      - "alternate@example.com"
history:
- "2024-06-11T21:06:03Z Membership application accepted"
isMember: true
application: # Only present when a membership application is pending
  nodeId: "issue-node-id"
  url: "https://github.com/org/repo/issues/123"
```

**Access Control**: Uses fine-grained GitHub access token for write operations to datastore repository.

**Components**:
- **CommonhausDatastore**: Core data access and persistence layer
- **DatastoreQueryContext**: GitHub API operations scoped to datastore repository  
- **UpdateEvent**: Event-driven updates with replay capability for eventual consistency

### Configuration Management

**ActiveHausKeeperConfig**: Dynamic configuration management with file watching.

- **Configuration Discovery**: Monitors `cf-haus-keeper.yml` in datastore repository
- **Callback System**: Notifies dependent components of configuration changes
- **Ready State**: Tracks configuration readiness for dependent operations

## Configuration Architecture

**HausKeeperConfig** (`cf-haus-keeper.yml` in datastore repository):

```yaml
userManagement:
  defaultAliasDomain: example.com
  emailDisabled: false

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

projectAliases:
  enabled: true
  projectList:
    repository: commonhaus/foundation
    filePath: PROJECTS.yaml
  # Project name pattern matching for repository discovery
  projectPattern: "^project-(.+)$"
```

**Central Project Configuration** (PROJECTS.yaml):
```yaml
project-name:
  displayName: "Project Name"
  description: "Project description"
```

## Integration Patterns

**Repository Discovery**: Uses priority-based discovery system with FileWatcher integration

**Event-Driven Updates**: LoginChangeEvent notifications across project configurations

**Queue Integration**: Uses PeriodicUpdateQueue for scheduled operations and task management

**Error Handling**: Comprehensive error handling with email notifications and GitHub issue creation

**Security**: Fine-grained GitHub access tokens, OAuth authentication, and permission validation

## Scheduled Operations

- **ProjectAliasManager**: Every 3 days at 4:47 AM (`0 47 4 */3 * ?`)
- **Email alias synchronization**: Reactive to configuration changes
- **Cache expiration**: Automated cache invalidation based on data types
