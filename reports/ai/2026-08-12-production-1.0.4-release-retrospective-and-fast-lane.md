# LeanTPM 1.0.4 production release retrospective and fast lane

## Final production state

- Release: `1.0.4-20260812.1`
- Scope: Backend and Web only; APP/APK excluded
- Database: remained at V52; no Flyway migration was executed
- Final health: Backend, Caddy, and MySQL running; readiness `UP`; info version `1.0.4`; schema `52`
- Source commit: `38b30e7363b07a15d1de8aabb0c99047c187ecc4`
- Successful operator: direct application-only operator v3

## Binding decision for the next release

- The next production release must use the scripted fast lane. Do not return to a sequence of ad-hoc discovery and repair commands coordinated through chat.
- Classify the release before building it:
  - `APPLICATION_ONLY` when Backend/Web changes but the database schema remains unchanged.
  - `DATABASE_MIGRATION` only when a new Flyway migration is included.
- The 1.0.3 V50-to-V52 migrator and the one-time 1.0.4 operator are audit baselines, not version templates. Never copy them and globally replace version or schema strings.
- Version, schema, source commit, release scope, artifact hashes, and APP inclusion must come from one immutable manifest. Generated starter and Caddy configuration must be parsed and verified after generation.
- APP/APK remains a separately packaged client artifact unless a future release explicitly changes that scope.
- Before the next production release, implement and rehearse the version-independent fast-lane toolkit described below. If it is not ready, stop before production rather than silently falling back to the old manual sequence.

## Functional content

1. Inspection schemes now reference inspection item IDs instead of maintaining a second set of item rules.
2. Executable, unsubmitted tasks read the latest inspection item definition. Editing an item bumps affected editable task versions.
3. Submission refreshes task item snapshots from the source item inside the transaction; after successful submission the result is frozen for audit.
4. Per-item photo requirements use one effective rule: `photoRequiredFlag=true` or `photoMinCount>0` means photos are required.
5. Inspection item maintenance keeps the photo switch and minimum count consistent: enabling required photos sets a minimum of at least one; setting the minimum to zero disables required photos.
6. Result photos are classified and counted per task item, without confusing whole-task photos with item evidence.
7. The APP source labels photo-required items with the minimum count and preserves photo evidence correctly. APP/APK was not part of the cloud deployment and must be packaged separately.
8. Offline inspection submission binds idempotency keys to payloads, uses revision-aware draft updates, stops blind retry for uncertain idempotency states, and preserves drafts for manual confirmation.
9. Equipment inspection availability messaging now explains that an applicable scheme must be published, effective today, and assigned to the scanned equipment.

## Release failures and permanent corrections

| Failure | Root cause | Permanent correction | Regression gate |
|---|---|---|---|
| 1.0.3 JAR started with old version/schema metadata | Earlier script replaced only the JAR path | Candidate starter independently replaces and verifies exactly one release version, schema version, Flyway flag, and JAR path | Positive and negative starter contract tests |
| Backend SCM binding falsely rejected | Win32 service path may quote the executable | Accept only the exact executable path, quoted or unquoted; reject all extra arguments | Quoted/unquoted and extra-argument tests |
| 1.0.4 JAR became unreadable | Recursive `icacls /inheritance:r ... /T` stripped effective file permissions | Apply inheritable ACL entries once at the release root; verify every artifact afterwards | Test rejects recursive target ACL; real child ACL inheritance probe passed |
| Rollback/public validation failed on Chinese JSON | Windows PowerShell 5.1 console decoding corrupted UTF-8 before `ConvertFrom-Json` | Validate the ASCII response contract without decoding localized text through the console code page | Test rejects `ConvertFrom-Json` in public branding probe |
| Failed target blocked a clean retry | A partial target and the first backup/evidence roots already existed | Quarantine instead of delete; require a new absent target and fresh backup/evidence roots | Fresh-root contract and target-absent preflight |
| Packaging could skip safety tests | The operator test was run manually | Operator builder now invokes the PowerShell 5.1 safety regression before creating any ZIP | Build-order assertion |

## Proposed fast release lane

### Two separate lanes

1. `APPLICATION_ONLY`: the normal path for Backend/Web changes when schema stays unchanged. It must never invoke Flyway or database restore.
2. `DATABASE_MIGRATION`: a separate, infrequent path when schema changes. It keeps the stricter backup/restore rehearsal and explicit recovery state.

The two lanes must not share a mutable script with conditionals that can accidentally cross from one recovery policy into the other.

### Operator experience

Local build:

```powershell
.\scripts\New-LeanTpmFastRelease.ps1 -Version 1.0.5 -Mode APPLICATION_ONLY
```

Expected output: one immutable ZIP containing Backend, Web, manifest, operator, checksums, and a short release summary.

Server deployment:

```powershell
.\Deploy-LeanTpmFastRelease.ps1 -Package D:\LeanTPM\temp\LeanTPM-1.0.5.zip
```

The stable server operator should:

1. Read current and target versions from signed/hash-bound manifests rather than source-code string replacement.
2. Discover and validate the current release pointer, service identities, exact process/JAR binding, schema, and available space.
3. Print a concise plan and request one local confirmation such as `DEPLOY 1A2B3C4D`.
4. Create and verify the backup automatically.
5. Extract to a fresh partial directory, verify all hashes, apply root ACLs once, and atomically rename it to the final release.
6. Generate the starter and Caddy configuration from templates, then parse and re-read the resulting contracts.
7. Start Backend, verify readiness/info/schema/exact JAR, then start Caddy and verify HTTP/branding.
8. On any application-only failure, automatically restore the previous Backend/Web while keeping the database unchanged.
9. Write one compact result JSON and update `Runtime\pointers\current-release.json` only after every postflight check passes.

### One-time server preparation

- Install one stable, version-independent deployment toolkit under `D:\LeanTPM\tools\release`.
- Create and validate `Runtime\pointers\current-release.json` from the proven 1.0.4 state.
- Create a least-privilege backup/metadata MySQL account or a machine-bound protected credential so routine application-only releases do not require the root password.
- Keep Backend on loopback, keep MySQL protected by the existing firewall rule, and retain the previous two verified application releases.
- Run a full application-only rehearsal on an isolated copy before enabling the fast lane in production.

### Required gates

- Clean source commit and reproducible Backend/Web build.
- Package manifest binds source commit, artifact bytes/hashes, schema from/to, APP inclusion, and migration inclusion.
- PowerShell 5.1 regression passes before packaging.
- Plan-only path performs zero mutations.
- Wrong version/schema/JAR/service path/ACL/manifest/hash fails before stopping services.
- Backend failure keeps Caddy closed until rollback Backend is healthy.
- Successful deployment proves readiness `UP`, exact target version, exact schema, exact JAR process binding, HTTP 200, and branding `OK`.

## Time target

- Local verified build: 10–15 minutes unattended.
- Upload: one ZIP.
- Production operator interaction: 3–5 minutes.
- Expected service interruption: 30–90 seconds.
- Routine end-to-end release: under 20 minutes, without coordinating individual discovery scripts through chat.

## Rollout plan

1. Preserve the successful v3 operator and evidence as the 1.0.4 audit baseline.
2. Extract its proven checks into a version-independent application-only toolkit.
3. Add manifest-driven templates and the current-release pointer.
4. Add isolated success, Backend-failure, Caddy-failure, ACL, encoding, and rollback tests.
5. Rehearse once against an isolated clone of the production layout.
6. Install the stable toolkit during a separate authorized maintenance action.
7. Use the fast lane for the next application-only release; retain the migration lane only for schema releases.
