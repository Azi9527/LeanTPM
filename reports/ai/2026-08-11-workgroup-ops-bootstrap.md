# LeanTPM WORKGROUP Ops bootstrap task record

## Requirement

Enable the existing OpsControl and ReleaseAgent bootstrap path on the confirmed
standalone Alibaba Cloud Windows host without weakening the production service
identity boundary.

Confirmed host facts (read-only user evidence):

- `PartOfDomain = False`, `Domain = WORKGROUP`.
- Backend runs as `NT AUTHORITY\NetworkService`.
- Caddy runs as `LocalSystem`.

The new path must be explicit and fail closed. It must not auto-detect a relaxed
account model and must not accept arbitrary local users.

## Acceptance criteria

### WORKGROUP positive path

Given `ServiceAccountMode=WORKGROUP_VIRTUAL`, a non-domain Windows host, and the
four exact identities below:

- OpsControl: `NT SERVICE\LeanTPM.OpsControl`
- ReleaseAgent: `NT SERVICE\LeanTPM.ReleaseAgent`
- Backend: `NT AUTHORITY\NetworkService`
- Proxy: `LocalSystem`

When the read-only readiness entry and installer `PlanOnly` run with otherwise
valid pinned inputs, then they return a non-executable plan that binds the exact
mode and accounts without creating files, changing ACLs, or registering services.

### Existing gMSA path

Given `ServiceAccountMode=GMSA` (the default), when four distinct domain gMSA
accounts are supplied, then the existing validation and plan remain accepted.

### Negative paths

- WORKGROUP mode rejects a domain-joined host.
- WORKGROUP mode rejects any account alias or arbitrary local/domain account.
- Duplicate service identities are rejected.
- GMSA mode continues to reject non-gMSA identities.
- The installed binding verifier rejects a policy whose mode/account contract
  does not match its signed/host-owned trust and current SCM state.
- Production execution remains gated by HostBootstrap, pinned inputs,
  `ConfirmInstallation`, the deployment lock, ACL setup, and binding checks.

## Scope

- `deploy/windows/Get-LeanTpmOpsServicesInstallationReadiness.ps1`
- `deploy/windows/Install-LeanTpmOpsServices.ps1`
- `deploy/windows/Test-LeanTpmOpsServicesBinding.ps1`
- `deploy/windows/Test-LeanTpmHostBootstrap.ps1`
- `deploy/windows/release-trust.production.example.json`
- `ops-control-plane/README.md`
- focused release-platform behavior/contract tests

## Non-goals

- No production deployment, service registration, ACL mutation, service restart,
  database access, Caddy change, or PushPlus delivery.
- No change to Backend or Caddy service accounts.
- No general support for arbitrary local users, LocalService, or shared service
  identities.
- No bypass of HostBootstrap or production root policy.

## Impact and risk

- Workflow risk: **L4** because these scripts control production SCM identities,
  protected ACL principals, and ReleaseAgent execution authority.
- GitNexus file impact: LOW/0 indexed callers for the four PowerShell entry files;
  the index does not model their script-to-script invocation graph, so this is
  not treated as evidence of low operational risk.
- Manual blast radius: Ops installation readiness, PlanOnly output, binding policy
  schema/hash, host bootstrap identity validation, service ACL principals, and
  post-registration SCM verification.

## Role and ownership plan

| Phase | Role | Ownership | Mutation policy |
|---|---|---|---|
| 1 | Product/architecture | acceptance contract and account model | read-only |
| 2 | Test | focused behavior and negative cases | tests only |
| 3 | Implementation | four PowerShell scripts and docs/example | scoped edits only |
| 4 | Security review | identity, ACL, PlanOnly and downgrade checks | read-only |
| 5 | Validation | Node tests, PS 5.1 AST, release gates, diff checks | build/test outputs only |

The phases are executed sequentially because the active environment does not
authorize delegated subagents for this task.

## Rollback

Source rollback is the removal of the explicit `WORKGROUP_VIRTUAL` mode while
leaving the default GMSA path unchanged. No runtime rollback is required because
this task does not mutate the server.

## Implementation result

- Added explicit `GMSA` / `WORKGROUP_VIRTUAL` mode selection; GMSA remains the
  default and a missing trust property resolves only to GMSA.
- WORKGROUP mode accepts only the four fixed identities in this record and
  rejects domain-joined hosts in the production HostBootstrap/readiness path.
- OpsControl and ReleaseAgent virtual service SIDs are derived read-only with
  `sc.exe showsid`; pre-registration ACLs use the numeric `*S-1-5-80-*`
  principals while WinSW/SCM retain the fixed `NT SERVICE\...` account names.
- Ops binding policy schema is version 2 and binds `serviceAccountMode` into its
  canonical SHA-256 core.
- Added a placeholder-only WORKGROUP release-trust example and operator notes.

## Validation evidence

- Test-first red phase: three focused tests failed because the mode/contract did
  not exist; the virtual SID assertion also failed before implementation.
- Focused behavior/contract tests: 3/3 PASS.
- Full `scripts/tests/release-platform.test.mjs`: 74/74 PASS in 89.6 seconds.
- Canonical PowerShell parser: 69/69 `.ps1` files PASS.
- JSON parse: both production release-trust examples PASS.
- `git diff --check`: PASS (line-ending normalization warnings only).
- Secret scan: no secret pattern was added by the diff. Two whole-file matches
  are pre-existing test-only `dpapi://...` secret references, not values and not
  changed by this task.
- Untracked artifact exclusion: no target/dist/node_modules/log/PID/backup/temp,
  database dump, private key, certificate-private-key, or DPAPI binary was found.
- GitNexus `detect_changes`: LOW, 2 indexed test symbols, 0 affected processes;
  PowerShell script relationships remain outside the indexed symbol graph, so
  the manual L4 classification is retained.

## Production status

No server file, Windows service, ACL, database, Caddy configuration, PushPlus
receiver, or production release pointer was changed. A production package and
bootstrap plan have not been generated from this uncommitted source state.
