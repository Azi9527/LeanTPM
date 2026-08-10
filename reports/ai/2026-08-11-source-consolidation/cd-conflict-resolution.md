# C/D pre-integration conflict resolution

- Review date: 2026-08-11 (Asia/Shanghai)
- C integration branch: `release/1.0.2-integration`
- C integration head before merge: `fff5a35`
- D safety branch: `safety/d-drive-pre-integration-20260811`
- D safety snapshot: `a4f760580cb9c0130376b0ee3d670901e30e2dcb`
- Merge mode: `--no-ff --no-commit`

## Inventory result

The raw C/D workspace comparison reported 122 byte-different shared paths. Git
canonical blob comparison reduced this to 25 substantive differences: 97 paths
were line-ending or other canonicalization-only differences and did not require
content selection.

All 25 substantive paths were reviewed. Eight merged without an unresolved
index entry. Seventeen produced an explicit conflict and were compared using
the stage-2 (C integration) and stage-3 (D safety snapshot) blobs before being
resolved individually.

## Non-conflicting substantive paths

| Path | Resolution |
| --- | --- |
| `.gitignore` | Combined. Kept C exclusions and added the D Ops target and local preservation snapshot exclusions. |
| `LeanTPM-APP/manifest.json` | Took the D product display name `大宝山矿业LeanTPM`; version remains unchanged pending the separate version freeze. |
| `backend/src/main/java/com/leantpm/system/mapper/SystemMapper.java` | Automatic merge was content-equivalent to the integration result; no D-only behavior was lost. |
| `backend/src/main/java/com/leantpm/system/service/SystemService.java` | Automatic merge was content-equivalent to the integration result; no D-only behavior was lost. |
| `backend/src/main/resources/mapper/system/SystemMapper.xml` | Automatic merge was content-equivalent to the integration result; no D-only mapping was lost. |
| `backend/src/test/java/com/leantpm/system/SystemUserImportMySqlIntegrationTest.java` | Automatic merge retained the integration test coverage. |
| `backend/src/test/java/com/leantpm/system/service/SystemServicePersonnelRelationshipTest.java` | Automatic merge retained the integration test coverage. |
| `frontend/src/utils/http.ts` | Automatic merge retained the newer safe error-message helpers used by the login page. |

## Individually resolved conflicts

| Path | Decision and rationale |
| --- | --- |
| `AGENTS.md` | Kept C: only the generated GitNexus counts differed; C has the freshly rebuilt `8236 / 20521 / 300` index. |
| `CLAUDE.md` | Kept C for the same freshly rebuilt GitNexus counts. |
| `deploy/windows/Start-LeanTpmReleaseAgentService.ps1` | Kept C: both sides have the same 130 substantive lines; only normalization/terminal blank-line bytes differed. |
| `frontend/src/views/auth/LoginView.vue` | Kept C: `loginErrorMessage` replaces D's generic `errorMessage(..., '登录失败')` and preserves network, 5xx, and safe business-error observability. |
| `ops-control-plane/README.md` | Kept C: it is a strict superset documenting monitoring, PushPlus, and bounded remediation. |
| `ops-control-plane/config/application-production.example.yml` | Kept C: it retains D fields and adds monitoring, multi-recipient PushPlus, and fail-safe disabled remediation settings. |
| `ops-control-plane/pom.xml` | Kept C: it retains D dependencies and adds the dependencies required by monitoring/remediation. |
| `ops-control-plane/src/main/java/com/leantpm/opscontrol/OpsControlPlaneApplication.java` | Kept C: it retains the application entry point and enables the scheduled monitoring/remediation jobs. |
| `ops-control-plane/src/main/java/com/leantpm/opscontrol/config/OpsControlPlaneConfiguration.java` | Kept C: D's single properties class is included in C's multi-class `EnableConfigurationProperties` annotation together with monitoring, remediation, and PushPlus properties. |
| `ops-control-plane/src/main/java/com/leantpm/opscontrol/security/OpsSecurityConfiguration.java` | Kept C: it retains D rules and includes the fixed monitoring/remediation API contract. |
| `ops-control-plane/src/main/resources/application.yml` | Kept C: D settings are preserved and new integration settings remain disabled/restricted by default. |
| `ops-control-plane/src/main/resources/static/app.js` | Kept C: D-only lines were the older release-only refresh and token handling; C uses authenticated identity validation, `Promise.allSettled`, and the monitoring/remediation dashboard. |
| `ops-control-plane/src/main/resources/static/index.html` | Kept C: D-only lines were old step labels and an unversioned script reference; C retains release controls and adds host/resource/service/log views. |
| `ops-control-plane/src/main/resources/static/styles.css` | Kept C: it is a strict superset with resource charts and service/log status layouts. |
| `ops-control-plane/src/test/java/com/leantpm/opscontrol/OpsControlPlaneApplicationContextTest.java` | Kept C: all D assertions remain and monitoring, remediation, and PushPlus configuration tests are added. |
| `release/release-agent-toolkit-lock.json` | Provisionally kept C because it matches the selected C deployment sources. The lock is independently regenerated from the merged tree and byte-compared before the merge commit. |
| `scripts/tests/release-platform.test.mjs` | Kept C: D's only unique line is the old one-line form of the same secret assertion; C retains the assertion and adds the newer deployment and Ops gates. |

## Safety result

- Unresolved index entries after the individual decisions: 0
- Conflict markers found in the worktree: 0
- No production system, database, service, Caddy configuration, PushPlus
  endpoint, or cloud host was contacted or modified during this merge.
- The merge remains uncommitted until the regenerated toolkit lock, secret and
  artifact scan, GitNexus change detection, and the full merged-tree test gates
  pass.
