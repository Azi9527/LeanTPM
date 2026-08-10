# AI development rules

Before any development work, read [the full development workflow](docs/ai/development-workflow.md) and [the task template](docs/ai/task-template.md).

- Protect all user-owned and uncommitted work. Never overwrite, clean, stage, format, or stash unrelated changes.
- Never run `reset`, `clean`, force-delete, or automatic `stash` without explicit authorization.
- Before editing code, document the requirement, Given/When/Then acceptance criteria, impact analysis, and L0-L4 risk level.
- If GitNexus is available, use `query` for unfamiliar code, then `context` and `impact` before changing a symbol. Warn before HIGH or CRITICAL impact and narrow the change first.
- L2 and higher work requires multi-role governance with explicit file/module ownership. Test and review roles are read-only by default.
- Write a failing test first, or record a concrete reason why a test cannot precede implementation.
- Run conditional validation whenever the change touches UI, API, persistence, infrastructure, or an external integration.
- Before any authorized commit, run relevant tests, the cleanup gate, GitNexus `detect-changes` when available, impact recheck, and `git diff --check`.
- Never push, merge a primary branch, create a PR, deploy, or mutate an external system without explicit authorization.
- During long work, provide status within 60 seconds. Do not retry one failing command or cycle through test/review fixes more than three times.
- Store long logs and evidence under `reports/ai/`; never store secrets or unredacted sensitive data there.

The full mandatory process is defined in [docs/ai/development-workflow.md](docs/ai/development-workflow.md).

## GitNexus-First

涉及代码理解或修改时，必须遵守 [GitNexus 工作流](docs/ai/gitnexus-workflow.md)。

修改函数、方法、类、接口、DTO、API 合同、数据库映射或公共组件前必须执行 `impact`；`HIGH` 或 `CRITICAL` 必须先告警并缩小范围；提交前必须执行 `detect-changes`。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **LeanTPM** (8236 symbols, 20521 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/LeanTPM/context` | Codebase overview, check index freshness |
| `gitnexus://repo/LeanTPM/clusters` | All functional areas |
| `gitnexus://repo/LeanTPM/processes` | All execution flows |
| `gitnexus://repo/LeanTPM/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
