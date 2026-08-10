# D drive canonical workspace evidence

- Recorded at: 2026-08-11T01:22:04+08:00
- Original temporary worktree:
  `C:\Users\Mayn\.codex\worktrees\fca1\LeanTPM`
- Canonical workspace: `D:\codex\LeanTPM`
- Pre-integration D safety branch:
  `safety/d-drive-pre-integration-20260811`
- Pre-integration D safety commit:
  `a4f760580cb9c0130376b0ee3d670901e30e2dcb`
- Integration branch: `release/1.0.2-integration`
- Integrated main commit before this evidence commit:
  `a771b0c6b721a564b25d2673bbe59f3155826fe3`

## Canonical workspace checks

- `D:\codex\LeanTPM` is the common Git repository root and is checked out on
  `main`.
- `main` was advanced with `git merge --ff-only
  release/1.0.2-integration`; no reset, stash, force operation, or file-copy
  overwrite was used.
- The C integration worktree and the D main worktree both resolved to
  `a771b0c6b721a564b25d2673bbe59f3155826fe3` immediately after the
  fast-forward.
- D `git status --short` was empty before and after the validation commands.
- The current local `main` contains the fetched `origin/main` history; remote
  main had not advanced independently.

## D main validation

| Gate | Result |
| --- | --- |
| Backend isolated Maven test | PASS: 179 tests, 0 failures, 0 errors, 60 MySQL integration tests skipped. Source was copied from D main into an ignored D `.tmp` build sandbox because the pre-existing ignored `backend/target` was not authorized for deletion. No database was contacted. |
| Frontend typecheck | PASS. |
| Frontend contract tests | PASS: 10/10. |
| Frontend production build | PASS into an ignored D `.tmp` output directory. |
| LeanTPM-APP project check | PASS: 15 pages, 45 source files. |
| LeanTPM-APP tests | PASS: 26/26. |
| OpsControl isolated Maven test | PASS: 70 tests, 0 failures, 0 errors, 0 skipped. Source was copied from D main into an ignored D `.tmp` build sandbox. |
| Release/offline Node gates | PASS: 80/80. |
| PowerShell 5.1 AST | PASS: 69 tracked scripts, 0 parse errors. |
| `git diff --check` | PASS. |
| D working-tree cleanliness | PASS: 0 status lines. |

## Core source inventory confirmed on D

- Backend database-backed auth session, login-attempt, and idempotency sources
  plus V49/V50 migration history.
- Frontend organization, import, QR-label, branding, and error-observability
  changes.
- LeanTPM-APP production-cloud default and HTTP-compatible source.
- Release schemas, signed deployment scripts, ReleaseAgent toolkit lock, and
  offline release tests.
- Independent `ops-control-plane` release, monitoring, bounded remediation,
  and multi-recipient PushPlus source and tests.
- Windows HostBootstrap, external Caddy, service binding, backup, recovery,
  PlanOnly, and Ops service installation gates.

## Remaining release blocker

Source synchronization is valid, but formal 1.0.2 freeze is not yet authorized
by this evidence because local Java is 21.0.7 while the fixed production build
toolchain requires Java 21.0.1. Maven 3.9.11 was used. A formal 1.0.2 package
must be rebuilt and revalidated from this D main workspace with Java 21.0.1.

No cloud server, production MySQL database, Windows service, Caddy service,
production configuration, or PushPlus recipient was contacted or modified.
