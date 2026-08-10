# LeanTPM 1.0.2 source freeze

- Scope: freeze Backend, PC Web, OpsControl and ReleaseAgent source at product version 1.0.2 while keeping the currently published APP contract at 1.0.1 / versionCode 101.
- Release boundary: the 1.0.2 PC/API deployment payload must contain no APK. A signed APP will be versioned and released separately.
- Given a payload with backend, web, database and operations artifacts but no `app/LeanTPM.apk`, when the explicit no-APP manifest workflow is selected, then generation and validation pass and the manifest records `components.app.includedInRelease=false`.
- Given that no-APP marker, when any APP artifact appears in the payload, then validation fails closed.
- Compatibility: legacy manifests without `includedInRelease` continue to mean APP included; the existing signed-APP path remains unchanged.
- Risk: L4 release-contract change. GitNexus does not index the PowerShell/JSON targets and returned UNKNOWN, so scope is constrained by behavior tests, release/offline regression, PS5.1 AST validation, secret scan and `git diff --check`.
- Roles: implementation owns VERSION/release scripts/schema; test phase owns behavior and regression evidence (read-only after implementation); review phase independently checks backward compatibility, fail-closed artifact rules and deployment/rollback consumers.
- Non-goals: no database migration, no production write, no service/Caddy change, no APK build/signing, no deployment approval.
- Rollback: revert the dedicated version-freeze commit; old manifests continue to validate under the default APP-included path.

## Validation evidence

- Canonical version contract: PASS (`productVersion=1.0.2`, `appVersionName=1.0.1`, `appVersionCode=101`, database V50, 50 migrations).
- Backend: Maven 3.9.11 source test BUILD SUCCESS; 179 tests passed, 0 failures/errors, 60 explicitly isolated MySQL integration tests skipped. The available JDK was 21.0.7, so this is source validation rather than the formal pinned Java 21.0.1 release build.
- Backend source package: isolated Maven 3.9.11 build produced `leantpm-backend-1.0.2.jar` successfully with tests skipped only because the full test run had already passed.
- Frontend: typecheck PASS; Node contract tests 10/10 PASS; production Vite build PASS.
- LeanTPM-APP: project check PASS; contract tests 26/26 PASS. No APK was built or included.
- OpsControl/ReleaseAgent: Maven test BUILD SUCCESS; 70/70 tests PASS.
- OpsControl source package: isolated Maven 3.9.11 build produced `leantpm-ops-control-plane-1.0.2.jar` successfully with tests skipped only because the full test run had already passed.
- Release/offline platform: 74/74 tests PASS, including deterministic bundle, no-APP manifest, PlanOnly, external ingress, service isolation and release-agent scenarios.
- The no-APP validator rejects non-boolean `includedInRelease` values before coercion; numeric `0` is covered by a failing-then-passing regression test.
- PowerShell 5.1 parser: 68 files, 0 syntax errors.
- Modified JSON parse: 8/8 PASS.
- Secret scan: 0 strong secret candidates and 0 literal credential candidates in the freeze diff.
- Artifact exclusion: 0 forbidden changed paths; generated `frontend/dist`, Maven `target` and `.tmp` evidence remain ignored.
- GitNexus `detect_changes`: 18 changed files, 0 indexed symbols/processes, LOW risk.
- `git diff --check`: PASS (line-ending conversion warnings only; no whitespace errors).

## Formal release blocker

The source freeze is not a formal release candidate yet. The pinned toolchain requires Java 21.0.1, while this workstation currently exposes Java 21.0.7. No signed manifest, detached CMS pair, deployment bundle, host discovery, production database read-only evidence, backup receipt or Deployment PlanOnly has been produced in this step.
