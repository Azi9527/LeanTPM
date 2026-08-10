# WORKGROUP bootstrap contract

LeanTPM supports two explicit service-account modes for the independent
OpsControl and ReleaseAgent services:

- `GMSA` (default): four distinct domain gMSA identities.
- `WORKGROUP_VIRTUAL`: the fixed standalone-host contract below.

`WORKGROUP_VIRTUAL` never accepts caller-selected local users or aliases:

| Role | Exact account |
|---|---|
| OpsControl | `NT SERVICE\LeanTPM.OpsControl` |
| ReleaseAgent | `NT SERVICE\LeanTPM.ReleaseAgent` |
| Backend | `NT AUTHORITY\NetworkService` |
| Proxy/Caddy | `LocalSystem` |

The mode must be pinned as `serviceAccountMode` in the host-owned
`release-trust.json`. A missing property retains the legacy `GMSA` behavior;
there is no automatic fallback to WORKGROUP mode.

On a WORKGROUP host, readiness and installation must be called with
`-ServiceAccountMode WORKGROUP_VIRTUAL` and the four exact identities above.
The read-only readiness path verifies that the host is not domain joined,
derives the two deterministic `S-1-5-80-*` virtual-service SIDs, proves the
fixed SCM IDs are absent, and then invokes only installer `PlanOnly`.

The installer uses the derived service SIDs for pre-registration ACLs while
keeping the exact `NT SERVICE\...` identities in WinSW/SCM. Actual installation
still requires verified HostBootstrap and release trust, pinned Java/WinSW/JAR
and toolkit hashes, the global deployment lock, `-ConfirmInstallation`, manual
registration, binding verification, and delayed-auto verification. The
installer does not start either service.

This source capability is not evidence that a target server has been prepared
or deployed. Production use must still follow read-only discovery, backup,
PlanOnly review, and a separate final deployment authorization.
