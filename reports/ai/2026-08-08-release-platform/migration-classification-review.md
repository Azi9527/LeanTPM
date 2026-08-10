# V1–V48 migration classification review

Status: **STATIC CLASSIFICATION APPROVED / RUNTIME VALIDATION PENDING**
Review time: 2026-08-08T07:30:00Z
Reviewer identity recorded in machine asset: `codex-static-review`

This review classifies the immutable SQL files for release policy. It is not a production deployment approval and does not replace the required signed plan, isolated MySQL runs, old-schema upgrades, application compatibility tests, backup/restore proof, or human release decision. Any migration marked `CONTRACT` or `backwardCompatible=false` forces `RECOVERY_REQUIRED`; the platform must never auto-start an older application after it begins.

Classification rules:

- **EXPAND**: additive table/column/index or a loosening change; old readers/writers remain structurally valid.
- **MIGRATE**: data/seed/backfill work without removing the old structural contract.
- **CONTRACT**: deletes/retirements, semantic narrowing, identity/status replacement, or other old-application incompatibility.
- `requiresDowntime=true` is conservative for historical CONTRACT migrations. Fresh installation is already an offline ceremony.

| Version | Phase | Backward compatible | Downtime | Static evidence |
|---:|---|:---:|:---:|---|
| V1 | EXPAND | yes | no | foundation tables and seed rows |
| V2 | EXPAND | yes | no | new parameter and numbering tables |
| V3 | MIGRATE | yes | no | permission seed data only |
| V4 | MIGRATE | yes | no | additive scope tables plus data backfill |
| V5 | EXPAND | yes | no | new audit and attachment-relation tables |
| V6 | EXPAND | yes | no | additive organization/location model |
| V7 | EXPAND | yes | no | new equipment category model |
| V8 | EXPAND | yes | no | new equipment ledger model |
| V9 | EXPAND | yes | no | new inspection aggregate |
| V10 | EXPAND | yes | no | new maintenance aggregate |
| V11 | EXPAND | yes | no | new OEE aggregate |
| V12 | EXPAND | yes | no | new visualization aggregate |
| V13 | MIGRATE | yes | no | mobile menu and parameter seed data |
| V14 | EXPAND | yes | no | additive multi-assignee table |
| V15 | CONTRACT | no | yes | deletes role relations, retires a role, and rewrites seeded identities |
| V16 | MIGRATE | yes | no | team seed and user-organization backfill |
| V17 | MIGRATE | yes | no | customer remediation permission seed |
| V18 | EXPAND | yes | no | new inspection import batch table |
| V19 | EXPAND | yes | no | new import batch and additive role templates |
| V20 | EXPAND | yes | no | new notification engine tables |
| V21 | EXPAND | yes | no | new fault/repair tables and nullable links |
| V22 | EXPAND | yes | no | new mobile evidence table and version policy |
| V23 | MIGRATE | yes | no | widens parameter storage and updates branding data |
| V24 | MIGRATE | yes | no | branding theme data update |
| V25 | EXPAND | yes | no | new calendar tables and additive defaulted columns |
| V26 | EXPAND | yes | no | additive idempotency columns and unique key |
| V27 | EXPAND | yes | no | additive nullable submitter and backfill |
| V28 | EXPAND | yes | no | additive defaulted abnormal-policy fields |
| V29 | EXPAND | yes | no | additive photo-policy fields and backfill |
| V30 | EXPAND | yes | no | new export job tables |
| V31 | CONTRACT | no | yes | deletes legacy role assignments and rebuilds permission contracts |
| V32 | MIGRATE | yes | no | dashboard refresh parameter update |
| V33 | CONTRACT | no | yes | converts pending-review tasks and removes the old review state semantics |
| V34 | MIGRATE | yes | no | scheme-equipment relationship backfill |
| V35 | EXPAND | yes | no | new team-membership table and terminology update |
| V36 | CONTRACT | no | yes | deletes manager role assignment and rewrites team ownership |
| V37 | MIGRATE | yes | no | Android release policy and permission seed |
| V38 | CONTRACT | no | yes | renames seeded login identities |
| V39 | CONTRACT | no | yes | retires legacy equipment status values and rewrites current status data |
| V40 | EXPAND | yes | no | new default-assignee table |
| V41 | EXPAND | yes | no | additive watermark policy field and parameter |
| V42 | CONTRACT | no | yes | logically deletes locations and narrows location-type semantics |
| V43 | CONTRACT | no | yes | retires seeded users and their active relationships |
| V44 | EXPAND | yes | no | additive organization ownership and upload permission |
| V45 | MIGRATE | yes | no | customer branding value update |
| V46 | EXPAND | yes | no | additive submission photo policy |
| V47 | EXPAND | yes | no | loosens task-item nullability and extends attachment type semantics |
| V48 | MIGRATE | yes | no | menu permission seed data |
| V49 | EXPAND | yes | no | additively adds a user auth epoch plus durable auth-session, login-lock and idempotency tables |
| V50 | CONTRACT | no | no | removes the obsolete login challenge toggle and raises the mobile minimum contract to versionCode 101 |

## Residual validation requirements

- Run fresh V1→V50 and repeated no-op migration against the pinned non-production MySQL UUID over VERIFY_IDENTITY TLS.
- Run V32/V37/V44/V48/V49→V50 snapshots, checksum tamper, interrupted migration and disk/lock failure injection.
- Prove N/N-1 application compatibility for every migration still marked backward compatible.
- Review the original SQL bytes and this classification again before signing a production manifest; changing either byte set invalidates the catalog hash.
