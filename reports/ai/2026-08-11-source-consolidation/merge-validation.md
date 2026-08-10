# Merged-tree validation

Validation date: 2026-08-11 (Asia/Shanghai)

Scope: uncommitted merge of `safety/d-drive-pre-integration-20260811`
(`a4f760580cb9c0130376b0ee3d670901e30e2dcb`) into
`release/1.0.2-integration` at `fff5a35`.

## Test and build gates

| Gate | Result |
| --- | --- |
| Backend Maven test | PASS: 179 tests, 0 failures, 0 errors, 60 MySQL integration tests skipped; no database was contacted. |
| Backend Maven package | PASS: `leantpm-backend-1.0.1.jar`, 63,720,122 bytes, SHA-256 `E23181D8B33D295163EDADDE6D6A9B67E2A41A3F478F1BBB964E8F94DE2CE532`. This is a merge-validation artifact, not the frozen 1.0.2 release artifact. |
| Frontend typecheck | PASS. |
| Frontend contract tests | PASS: 10/10. |
| Frontend production build | PASS; output isolated under ignored `.tmp/merged-frontend-dist`. |
| LeanTPM-APP project check | PASS: 15 pages, 45 source files. |
| LeanTPM-APP tests | PASS: 26/26. |
| OpsControl Maven test | PASS: 70 tests, 0 failures, 0 errors. |
| OpsControl Maven package | PASS: `leantpm-ops-control-plane-1.0.1.jar`, 31,917,604 bytes, SHA-256 `3E1AB645ED77C609A1F3597F1CC9F13C9FAC34CC477C3497062ACD90586B032A`. This is a merge-validation artifact, not the frozen 1.0.2 release artifact. |
| Release/offline Node gates | PASS: 80/80 across three test files. |
| PowerShell 5.1 AST | PASS: 69 tracked `.ps1` files, 0 parse errors. |
| ReleaseAgent toolkit lock | PASS: 68 files; two independent generations produced SHA-256 `EE68CBF5814C326B4C4D429E05CCC32CFDC2DEA04904A9D155C5B12973A59095`. |
| Conflict markers | PASS: 0. |
| `git diff --check` | PASS. |

## Secret and artifact gates

- Dedicated `gitleaks`/`trufflehog` executables were not installed, so a
  repository-local high-confidence scanner was run over every tracked text
  file plus all XML payloads in the added XLSX document.
- Private-key, AWS, Google, GitHub, OpenAI-style key, known unsafe password,
  literal PushPlus credential, and credential-literal patterns were checked.
- Nine generic credential candidates were manually classified: a Vue
  `:password` binding, offline test tokens, a test-only password, DPAPI URI
  fixtures, a deliberately rejected neutral API-key fixture, and an explicitly
  wrong E2E password. None is a production secret.
- High-confidence secret result after classification: 0.
- XLSX embedded XML high-confidence secret candidates: 0.
- Tracked forbidden build/runtime artifacts, PID/log/temp files, DPAPI secret
  blobs, keystores, and database backup dumps: 0.
- Git status contains no untracked, unstaged source or document outside the
  staged merge result.

## Code intelligence

GitNexus was rebuilt against the current source tree before merge validation:
8,236 symbols, 20,521 relationships, 300 execution flows. Pre-commit
`detect_changes(compare, base_ref=main)` reported 439 changed files, 428 changed
symbols, zero graph-resolved affected processes, and `LOW` aggregate risk. The
large changed-file count is expected because this is the full integration
branch, not only the final safety-snapshot merge commit.

## Toolchain note

- Maven: repository-local Apache Maven 3.9.11.
- Java used for validation: 21.0.7.
- The required formal release Java is 21.0.1. Therefore these gates authorize
  the source merge only; they do not authorize the 1.0.2 release freeze or
  production package. The formal release build remains blocked until Java
  21.0.1 is available and revalidated from the canonical D workspace.

No cloud host, MySQL instance, Windows service, Caddy service, production
configuration, or PushPlus recipient was contacted or modified.
