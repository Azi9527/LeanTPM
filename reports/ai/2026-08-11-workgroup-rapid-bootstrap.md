# WORKGROUP rapid bootstrap task record

## Requirement

Make the first OpsControl/ReleaseAgent bootstrap practical on the existing
standalone production host. The operator-facing steady-state release flow must
remain: upload one deployment bundle, review its identity and SHA-256, confirm
once, then let ReleaseAgent deploy or roll back.

The current business services and database are outside the implementation
scope. Production mutation remains prohibited until a server-side `PlanOnly`
report has been reviewed and the user gives a separate final confirmation.

## Acceptance criteria

1. Given the fixed WORKGROUP host and the current running Backend/Caddy,
   when rapid bootstrap discovery runs, then it reports only read-only host
   evidence and an explicit list of planned writes.
2. Given missing HostBootstrap and Ops service roots, when rapid bootstrap is
   run with `-PlanOnly`, then it creates no directories, files, certificates,
   services, ACLs, processes or network changes.
3. Given a plan whose host, artifact or path evidence has changed, when an
   executable bootstrap is attempted later, then it fails before the first
   mutation.
4. Given the exact standalone host and a separately confirmed plan, when the
   bootstrap executes, then only fixed LeanTPM paths and the two fixed Ops
   service IDs may be created; Backend, Caddy and MySQL are not reconfigured.
5. Given a signed deployment bundle after bootstrap, when the operator reviews
   the digest and confirms in the web console, then the existing
   `DEPLOY_SIGNED_RELEASE` path requires one web confirmation.
6. Given the strict enterprise bootstrap path, when rapid mode is unused,
   then its current double-CMS and external-authority behavior is unchanged.

## Scope and non-goals

- In scope: a one-time fixed-path WORKGROUP adoption/PlanOnly path, generated
  configuration, deterministic artifact pins, rollback journal and tests.
- Not in scope: database migration, production deployment, changing Caddy or
  Backend service bindings, opening ports, PushPlus delivery, or removing the
  existing strict bootstrap path.
- Certificate and trust mechanics may be automated inside the bootstrap kit;
  the operator must not perform a manual two-person ceremony for this host.

## Risk and impact

- Risk: **L4**, because this defines a production service/bootstrap boundary.
- GitNexus query did not surface the PowerShell orchestration flow. File impact
  for `Get-LeanTpmOpsServicesInstallationReadiness.ps1` and
  `Install-LeanTpmOpsServices.ps1` is LOW with zero indexed callers; PowerShell
  dynamic invocation remains outside that graph and is covered by release
  platform tests and source-level call inspection.
- `ReleaseWorkflowService` is not changed: its existing signed-bundle path
  already uses a confirmation threshold of one.
- Rollback: rapid mode is additive and opt-in. Removing the new bootstrap kit
  leaves the strict path unchanged. No production rollback is relevant until
  a separately confirmed execution occurs.

## Roles and ownership

| Role | Responsibility | Ownership | Read-only |
|---|---|---|---|
| Product/primary | Scope, acceptance, integration | task record and final integration | No |
| Infrastructure implementation | Rapid bootstrap scripts and schemas | new WORKGROUP bootstrap files only | No |
| Test | Failure-first contract and PowerShell behavior tests | release-platform tests | Yes after test definition |
| Review | Trust boundary, side effects, rollback and diff review | all task files | Yes |

The primary agent performs the roles sequentially; test and review phases do
not modify implementation files.

## Test matrix

| Scenario | Expected result |
|---|---|
| Missing rapid bootstrap implementation | New contract test fails for the missing entry point |
| PlanOnly against a fixture host | `PLAN` report and byte-for-byte unchanged fixture tree |
| Wrong computer/domain/account/path | Fail closed before a planned write |
| Existing unexpected target | Fail closed; no overwrite |
| Repeated PlanOnly | Same bound actions; no filesystem changes |
| Strict bootstrap regression | Existing HostBootstrap tests continue to pass |
| Ops release confirmation | Existing signed deployment test remains one confirmation |

## Authorized implementation result

The user explicitly authorized the `WORKGROUP_RAPID` route and fixed service
permissions. The implementation is complete in source but has not been run on
the Aliyun host.

- The ReleaseAgent keeps the strict executor as its default. It selects the
  rapid executor only when the already verified, dual-CMS-signed plan contains
  the exact value `deploymentMode=WORKGROUP_RAPID`.
- The pinned toolkit must contain the rapid executor and both release data
  contracts. Unknown deployment modes and unpinned executors fail closed.
- The rapid executor accepts only the fixed production roots, services,
  accounts, database identity, V50 schema and loopback listeners. It performs
  a fixed backup, immutable staging, Backend/Caddy activation checks and local
  rollback. Plans require a nonce and a UTC expiry no more than 24 hours away.
- The bootstrap grants ReleaseAgent Modify only on fixed release, backup,
  staging, pointer, lock and audit locations; read/traverse on the fixed
  application/config/secret parents; read on the existing DPAPI database
  secret; and Modify on the fixed Backend starter and Caddyfile.
- Backend and Caddy service DACLs grant only query/config-status, enumerate
  dependents, start, stop, interrogate and read-control. Pause/continue,
  delete, change-config and user-defined controls are excluded. Original
  service and path ACLs are journaled and restored on bootstrap failure.

## Verification result

- Failure-first tests were observed for the missing plan expiry contract and
  for the over-broad service-control ACE before implementation.
- `node --test scripts/tests/release-platform.test.mjs`: **79/79 PASS**.
- PowerShell 5.1 canonical AST parse: **73/73 PASS**.
- OpsControl Maven package using Java 21.0.1 and Maven 3.9.11:
  **70 tests PASS; BUILD SUCCESS**.
- `git diff --check`: **PASS** (line-ending conversion warnings only).
- Changed/untracked-source scan: **13 files**, no disallowed build/runtime
  artifact paths and no private-key, AWS, GitHub or PushPlus literal finding.
- GitNexus was refreshed to 8,242 symbols and 20,527 relationships.
  `detect-changes --scope compare --base-ref main` reports LOW risk and no
  affected indexed process. PowerShell orchestration is not represented by
  the symbol graph, so the release-platform behavior tests remain the primary
  evidence for this change.
- No certificate store, service, database, network, cloud host or production
  file was mutated during implementation and verification.

## App current alias access incident

Requirement: bootstrap `PlanOnly` must identify the running release without
requiring the interactive administrator to read through `App\current` when the
fixed Backend starter already names the immutable release JAR.

- Given a host without `current-release.json`, a protected `App\current` alias,
  and a readable fixed Backend starter that names an existing release JAR,
  when live bootstrap discovery runs, then it resolves and hashes the
  release-specific JAR from the starter before considering the alias.
- Given a starter that does not name an approved immutable release path, when
  the alias is unavailable, then discovery fails closed without changing ACLs.
- Scope: release identity discovery ordering only. No service, ACL, database,
  package or host-policy contract changes.
- Risk: L4 because this is the production bootstrap gate. GitNexus does not
  index the PowerShell `Get-LiveObservation` function and reports UNKNOWN with
  zero known callers; validation therefore uses a failure-first source-order
  contract plus the full release-platform regression suite.
