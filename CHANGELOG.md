# Changelog

All notable LeanTPM changes are recorded here. Product versions follow SemVer; database versions remain immutable Flyway integers and are mapped by each release manifest.

## [Unreleased]

## [1.0.3] - 2026-08-11

### Added

- Inspection schemes can be viewed, copied and safely deleted with active-plan protection.
- Inspection item categories use a tenant dictionary with six Chinese defaults.
- Mobile photo-source policy can disable album selection centrally.

### Changed

- Mobile inspection tasks require an authoritative equipment scan before execution.
- Equipment context is condensed for mobile use, while expanded management information remains scrollable.
- Equipment Excel import performs whole-workbook prevalidation, reports all row and field errors together, and accepts common Chinese and numeric date formats.
- Mobile equipment access includes the parent scope for team users and descendant equipment for higher-level organizations without changing the shared PC data-permission resolver.

### Fixed

- Inspection submission identifies the exact item and attachment-rule failure and ignores photo requirements for skipped items.
- Optimistic conflicts preserve local drafts and merge current task and result versions before retry.
- Pending inspection lists include overdue assigned tasks consistently with the workbench.

### Database

- Upgrade path is V50 to V52 through checksum-locked V51 and V52 migrations.
- V51 seeds inspection item category dictionaries; V52 seeds customer-feedback configuration defaults.

### Operations

- Product and APP versionName are 1.0.3; Android versionCode is 102 and the minimum supported code remains 101.
- Production Flyway remains disabled in the business service; migrations require the approved short-lived migrator workflow.

### Rollback

- V50 to V52 is classified `RECOVERY_REQUIRED`; application rollback requires restoration of the verified V50 backup before the previous Backend starts.

## [1.0.2] - 2026-08-11

### Added

- Machine-readable unified version contract and compatibility matrix.
- Signed release-manifest contract, artifact hash validation and deterministic package design.
- Windows Server service, backup, deployment and rollback design assets.
- MySQL-persisted authentication sessions, refresh-token rotation, logout revocation, login-failure state and account security epochs.
- MySQL-persisted request idempotency with tenant/user isolation, fencing tokens and fail-closed unknown outcomes.
- A Redis-free public branding probe used by PC and Android clients for server validation.

### Removed

- The Redis runtime dependency, Redis health/readiness requirement, Redis configuration and Redis secret contract.
- The login captcha endpoint, DTO, configuration, PC/Android UI and client request fields.

### Security

- Release tooling fails closed on unsigned production manifests, path traversal, unexpected files and artifact hash drift.
- Database credentials are being removed from defaults and process command lines.
- Logout and refresh-token replay protection survive backend restarts because their state is persisted in MySQL.
- Account disablement, password changes and role changes invalidate stale JWT claims and cannot race a new session into existence.
- Idempotency keys are scoped by tenant and authenticated user; unknown outcomes are not automatically replayed.

### Database

- Current repository schema is Flyway V50. Existing migration files remain immutable.
- V49 is an additive Expand migration for authentication-session, login-security and idempotency state.
- V50 is a Contract migration that removes the obsolete captcha toggle and raises the Android minimum versionCode to 101.
- Production migration is separated from business service startup and follows Expand → Migrate → Contract.

### Operations

- Product version is frozen as 1.0.2 for Backend, PC Web, OpsControl and ReleaseAgent.
- The published APP remains 1.0.1 with Android versionCode 101 and is excluded from the PC/API deployment payload.
- Android clients below versionCode 101 are blocked because the public login contract no longer contains captcha fields or endpoints.
- This candidate is not a production release, Git tag or deployment approval.

### Rollback

- Before V49/V50 is applied, rollback is the ordinary release-pointer rollback described by the release plan.
- After persistent security state has been written or V50 has been applied, rollback to the previous Redis/captcha contract is classified `RECOVERY_REQUIRED`; the old application must not be started against the new schema.
- Recovery must complete forward or restore a verified backup into a new isolated target under the recovery-inhibit state machine.
