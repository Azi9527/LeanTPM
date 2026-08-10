# Compact Baoshan Mining brand mark

## Requirement and acceptance criteria

- Replace compact/sidebar branding with the customer-provided red/green Baoshan Mining mark.
- Use the same image for the browser favicon.
- Keep the existing brand text and keep non-compact logos configurable through `branding.logoUrl`.
- Given a compact `BrandLogo`, when rendered, then its source is the isolated 480x480 mark asset.
- Given `frontend/index.html`, when loaded, then its favicon points to that same mark asset.
- Given a non-compact `BrandLogo`, when branding is configured, then it still uses `branding.logoUrl`.

## Impact and risk

- GitNexus query was run. `BrandLogo.vue` and `frontend/index.html` are not indexed as resolvable symbols, so context/impact returned UNKNOWN rather than a false-safe result.
- Read-only consumer review found compact usage in `AppLayout`, `MobileLayout`, and `PublicEquipmentView`; `LoginView` uses non-compact branding.
- Risk: L2 shared visual component. No API, persistence, permission, infrastructure, deployment, or database mutation.
- Scoped change: only compact image selection and favicon; non-compact behavior is unchanged.
- Governance roles were executed sequentially: requirement/impact, failing contract test, implementation, validation, browser/asset review.

## Test evidence

- Red phase: compact-source contract failed while favicon contract passed, proving the sidebar still cropped the long logo.
- Green phase: `node --test tests/compact-brand-mark-contract.test.mjs` passed 2/2.
- `npm.cmd run typecheck` passed.
- `npm.cmd run build` passed; existing Rollup dependency-comment and chunk-size warnings remain.
- The mark was deterministically cropped from the repository's official 1759x567 long-form PNG at `(0,40,480,480)`. Result: 480x480, 22,856 bytes, SHA-256 `2A34784CD6ADF54C78AB7DFD7A07DE5431332F712D55A1CF0BDB865C4431D442`.
- Local image inspection confirmed the red/green mark is complete and not clipped.
- The already-running `127.0.0.1:4173` preview serves a different/older build and therefore does not hot-reload this worktree. No deployment or service replacement was authorized or performed.

## Authorization boundary

- Source, test, report, and local build-output changes only.
- No commit, push, deployment, cloud mutation, database write, or production service restart.
