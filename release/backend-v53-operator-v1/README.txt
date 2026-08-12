LeanTPM 1.0.4-20260812.2 Backend-only V53 operator v1

Scope:
- Backend only; Web/Caddy and APP are not switched or packaged.
- Database migrates from V52 to V53 through the isolated Flyway entry point.
- Runtime Flyway remains disabled.

Required order:
1. Upload the release ZIP and operator ZIP to D:\LeanTPM\temp.
2. Verify the exact byte counts and SHA256 values supplied by the coordinator.
3. Extract the operator ZIP into a new directory.
4. Run Test-BackendV53ReleaseOperator.ps1 in Windows PowerShell 5.1.
5. Run the executor as elevated Administrator with -PlanOnly and return the full Plan JSON and PLAN_SHA256.
6. Execute only after the coordinator confirms that exact PLAN_SHA256.

Safety:
- The Plan binds the current Backend JAR, starter, MySQL server UUID, V52 schema, TLS anchors, service identities, release ZIP, executor and target paths.
- Confirmed Plan SHA256 is checked before Backend stop or any file/database write.
- Backend is stopped before the fresh V52 backup and remains stopped through migration and starter switch.
- The verified release contains one Backend JAR, one V53 SQL file and one checksum catalog; no Web/APP payload exists.
- Caddy is not stopped or reconfigured.
- A failure after the verified backup invokes V52 database restore before the old Backend can start.
- Rollback class is RECOVERY_REQUIRED, not application-only.
