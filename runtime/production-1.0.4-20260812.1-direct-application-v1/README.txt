LeanTPM 1.0.4-20260812.1 direct application-only operator v1

Scope:
- Backend and Web only.
- APP/APK is not included.
- Database remains V52. No Flyway migration or database write is executed.
- OpsControl and ReleaseAgent are not used.

Required order:
1. Upload both the release ZIP and operator ZIP to D:\LeanTPM\temp.
2. Verify the exact ZIP byte counts and SHA256 values supplied by the coordinator.
3. Extract the operator ZIP into a new directory.
4. Run Windows PowerShell 5.1 as elevated Administrator.
5. Run the executor with -PlanOnly and return the complete Plan JSON and PLAN_SHA256.
6. Execute only after the coordinator confirms that exact PLAN_SHA256.

Safety:
- The source starter must contain exactly one 1.0.3 version line, one V52 schema line, and one 1.0.3 JAR path.
- The generated starter is parsed and verifies 1.0.4, V52, and the 1.0.4 JAR independently.
- After Backend/Caddy stop, a verified V52 SQL dump plus the current 1.0.3 starter/Caddy bindings are stored under a restricted backup directory.
- The immutable 1.0.3 JAR and Web index are hash-bound and left in place for application rollback.
- Any execution failure restores the 1.0.3 starter and Caddyfile and keeps the database at V52.
- Never run the older V50 recovery executor for this release.
