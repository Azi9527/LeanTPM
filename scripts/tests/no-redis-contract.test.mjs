import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const currentFile = fileURLToPath(import.meta.url)
const repositoryRoot = path.resolve(path.dirname(currentFile), '..', '..')

const ignoredDirectoryNames = new Set([
  '.git',
  'dist',
  'node_modules',
  'target',
  'target-codex',
  'target-codex-release',
  'target-release',
  'target-repro-a',
  'target-repro-b',
  'unpackage',
])

// These files are immutable or frozen delivery evidence, not active runtime or
// operational contracts. Additions require an exact reviewed path; do not use
// directory, prefix, or wildcard exemptions.
const historicalDocumentAllowlist = new Set([
  'docs/02-第一阶段交付说明.md',
  'docs/03-V1开发计划.md',
  'docs/04-M0基础能力交付记录.md',
  'docs/09-M4-OEE管理交付记录.md',
  'docs/10-M5-可视化中心交付记录.md',
  'docs/13-M7-V1发布验收记录.md',
  'docs/15-客户需求整改交付与测试报告.md',
  'docs/19-客户会后整改交付与测试报告.md',
  'docs/23-LeanTPM-APP-迁移验收测试报告.md',
])

const scannerAllowlist = new Set([
  'scripts/tests/no-redis-contract.test.mjs',
  // This planned scanner necessarily names the forbidden contracts it checks.
  'scripts/Test-NoRedisResiduals.ps1',
])

const historicalCaptchaSeed =
  'backend/src/main/resources/db/migration/V2__system_parameters_and_number_rules.sql'
const historicalCaptchaRemoval =
  'backend/src/main/resources/db/migration/V50__remove_obsolete_login_challenge_toggle.sql'

function relativePath(file) {
  return path.relative(repositoryRoot, file).split(path.sep).join('/')
}

function collectFiles(entries) {
  const files = []
  const visit = (entry) => {
    // HBuilderX can expose node_modules as a Windows junction. Dirent reports
    // that entry as a reparse point instead of a normal directory, so the
    // child.isDirectory() guard below is not sufficient on the canonical D
    // drive workspace. Reject known generated/dependency directory names
    // before following stat() through any junction.
    if (ignoredDirectoryNames.has(path.basename(path.normalize(entry)))) return
    const absolute = path.resolve(repositoryRoot, entry)
    if (!fs.existsSync(absolute)) return
    const stat = fs.statSync(absolute)
    if (stat.isFile()) {
      files.push(absolute)
      return
    }
    for (const child of fs.readdirSync(absolute, { withFileTypes: true })) {
      if (child.isDirectory() && ignoredDirectoryNames.has(child.name)) continue
      visit(path.join(entry, child.name))
    }
  }
  entries.forEach(visit)
  return files.sort((left, right) => relativePath(left).localeCompare(relativePath(right)))
}

function violations(files, rules, allowlist = new Set(), exactLineAllowances = []) {
  const result = []
  const consumed = new Map(exactLineAllowances.map((allowance) => [allowance, 0]))
  for (const file of files) {
    const relative = relativePath(file)
    if (allowlist.has(relative)) continue
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/u)
    lines.forEach((line, index) => {
      for (const rule of rules) {
        if (rule.pattern.test(line)) {
          const allowance = exactLineAllowances.find((candidate) =>
            candidate.path === relative &&
            candidate.rule === rule.name &&
            candidate.pattern.test(line) &&
            consumed.get(candidate) < candidate.expectedCount)
          if (allowance) {
            consumed.set(allowance, consumed.get(allowance) + 1)
            continue
          }
          result.push({ path: relative, line: index + 1, rule: rule.name })
        }
      }
    })
  }
  for (const allowance of exactLineAllowances) {
    if (consumed.get(allowance) !== allowance.expectedCount) {
      result.push({ path: allowance.path, line: 0, rule: `${allowance.rule}-allowance-count` })
    }
  }
  return result
}

function assertNoViolations(label, actual) {
  const preview = actual
    .slice(0, 120)
    .map((item) => `  ${item.path}:${item.line} [${item.rule}]`)
    .join('\n')
  const suffix = actual.length > 120 ? `\n  ... ${actual.length - 120} more` : ''
  assert.equal(actual.length, 0, `${label}: ${actual.length} residual(s)\n${preview}${suffix}`)
}

const redisRuntimeRules = [
  { name: 'redis-name', pattern: /\bredis\b/iu },
  { name: 'redis-java-client', pattern: /StringRedisTemplate|DefaultRedisScript/iu },
  { name: 'redis-environment', pattern: /LEANTPM_REDIS_[A-Z0-9_]+/u },
  { name: 'redis-error-contract', pattern: /REDIS_UNAVAILABLE/u },
]

const captchaRules = [
  { name: 'captcha-name-or-api', pattern: /captcha/iu },
  { name: 'captcha-parameter', pattern: /security\.captcha\.enabled/iu },
  { name: 'captcha-ui-text', pattern: /验证码/u },
]

const negativeCaptchaAssertionAllowances = [
  {
    path: 'backend/src/test/java/com/leantpm/integration/MySqlMigrationIntegrationTest.java',
    rule: 'captcha-name-or-api',
    pattern: /^\s*WHERE tenant_id = 1 AND parameter_key = 'security\.captcha\.enabled'\s*$/u,
    expectedCount: 2,
  },
  {
    path: 'backend/src/test/java/com/leantpm/integration/MySqlMigrationIntegrationTest.java',
    rule: 'captcha-parameter',
    pattern: /^\s*WHERE tenant_id = 1 AND parameter_key = 'security\.captcha\.enabled'\s*$/u,
    expectedCount: 2,
  },
  {
    path: 'backend/src/test/java/com/leantpm/security/session/V50ObsoleteLoginContractMigrationTest.java',
    rule: 'captcha-name-or-api',
    pattern: /^\s*\+ "WHERE parameter_key = 'security\.captcha\.enabled';\\n\\n"\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'backend/src/test/java/com/leantpm/security/session/V50ObsoleteLoginContractMigrationTest.java',
    rule: 'captcha-parameter',
    pattern: /^\s*\+ "WHERE parameter_key = 'security\.captcha\.enabled';\\n\\n"\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'LeanTPM-APP/tests/auth-contract.test.js',
    rule: 'captcha-name-or-api',
    pattern: /^test\('APP login sends only username and password and exposes no captcha API', async \(\) => \{$/u,
    expectedCount: 1,
  },
  {
    path: 'LeanTPM-APP/tests/auth-contract.test.js',
    rule: 'captcha-name-or-api',
    pattern: /^\s*assert\.equal\('captcha' in authApi, false\)\s*$/u,
    expectedCount: 1,
  },
  ...[
    /^\s*assert\.doesNotMatch\(api, \/captcha\/i\)\s*$/u,
    /^\s*assert\.doesNotMatch\(store, \/captcha\/i\)\s*$/u,
    /^\s*assert\.doesNotMatch\(view, \/captcha\|.*\/i\)\s*$/u,
    /^\s*assert\.doesNotMatch\(setup, \/captcha\/i\)\s*$/u,
  ].map((pattern) => ({
    path: 'frontend/tests/no-captcha-contract.test.mjs',
    rule: 'captcha-name-or-api',
    pattern,
    expectedCount: 1,
  })),
  {
    path: 'frontend/tests/no-captcha-contract.test.mjs',
    rule: 'captcha-ui-text',
    pattern: /^\s*assert\.doesNotMatch\(view, \/captcha\|.*\/i\)\s*$/u,
    expectedCount: 1,
  },
]

const negativeReleaseAssertionAllowances = [
  {
    path: 'scripts/tests/release-platform.test.mjs',
    rule: 'redis-name',
    pattern: /^\s*assert\.doesNotMatch\(production, \/spring:.*redis:\/\)\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/tests/auth-e2e-contract.test.mjs',
    rule: 'captcha-name-or-api',
    pattern: /^test\('isolated auth E2E exercises refresh replay, restart persistence and removed captcha route', \(\) => \{$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/tests/auth-e2e-contract.test.mjs',
    rule: 'captcha-name-or-api',
    pattern: /^\s*assert\.match\(script, \/\\\/auth\\\/captcha\/\)\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/tests/auth-e2e-contract.test.mjs',
    rule: 'captcha-name-or-api',
    pattern: /^\s*assert\.match\(script, \/CAPTCHA_ENDPOINT_REMOVED_404=PASS\/\)\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/verify-auth-e2e.ps1',
    rule: 'captcha-name-or-api',
    pattern: /^\s*Invoke-WebRequest .*\$baseUrl\/auth\/captcha.*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/verify-auth-e2e.ps1',
    rule: 'captcha-name-or-api',
    pattern: /^\s*Write-Output 'CAPTCHA_ENDPOINT_REMOVED_404=PASS'\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/tests/release-platform.test.mjs',
    rule: 'redis-name',
    pattern: /^\s*assert\.match\(verifier, \/no-redis-contract\\\.test\\\.mjs\/\)\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/verify-release.ps1',
    rule: 'redis-name',
    pattern: /^\s*Invoke-ReleaseStep 'No-Redis cross-client authentication contracts' \{$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/verify-release.ps1',
    rule: 'redis-name',
    pattern: /^\s*throw 'node\.exe is required for no-Redis contract tests'\s*$/u,
    expectedCount: 1,
  },
  {
    path: 'scripts/verify-release.ps1',
    rule: 'redis-name',
    pattern: /^\s*\(Join-Path \$repositoryRoot 'scripts\\tests\\no-redis-contract\.test\.mjs'\) `$/u,
    expectedCount: 1,
  },
]

test('backend runtime has no Redis dependency, client, health, environment, or error contract', () => {
  const files = collectFiles([
    'backend/pom.xml',
    'backend/src/main/java',
    'backend/src/main/resources/application.yml',
    'backend/src/main/resources/application-prod.yml',
  ])
  assertNoViolations('backend Redis contract', violations(files, redisRuntimeRules))
})

test('captcha handler, DTO, login fields, UI, API, and active tests are absent', () => {
  const files = collectFiles([
    'backend/src/main/java',
    'backend/src/test/java',
    'frontend/src',
    'frontend/tests',
    'LeanTPM-APP/api',
    'LeanTPM-APP/pages',
    'LeanTPM-APP/stores',
    'LeanTPM-APP/tests',
    'LeanTPM-APP/utils',
  ])
  assertNoViolations(
    'captcha runtime/client contract',
    violations(files, captchaRules, new Set(), negativeCaptchaAssertionAllowances),
  )
})

test('PC and APP runtime and tests contain no Redis contract', () => {
  const files = collectFiles(['frontend/src', 'frontend/tests', 'LeanTPM-APP'])
  assertNoViolations('PC and APP Redis contract', violations(files, redisRuntimeRules))
})

test('Windows and release assets contain no Redis or captcha config, Secret, health, or E2E contract', () => {
  const files = collectFiles(['deploy/windows', 'scripts'])
  assertNoViolations(
    'deployment and release contract',
    violations(
      files,
      [...redisRuntimeRules, ...captchaRules],
      scannerAllowlist,
      negativeReleaseAssertionAllowances,
    ),
  )
})

test('only the immutable V2 migration retains the historical captcha seed', () => {
  const migrations = collectFiles(['backend/src/main/resources/db/migration'])
  const removalAllowance = [{
    path: historicalCaptchaRemoval,
    rule: 'captcha-name-or-api',
    pattern: /^WHERE parameter_key = 'security\.captcha\.enabled';$/u,
    expectedCount: 1,
  }, {
    path: historicalCaptchaRemoval,
    rule: 'captcha-parameter',
    pattern: /^WHERE parameter_key = 'security\.captcha\.enabled';$/u,
    expectedCount: 1,
  }]
  const residuals = violations(
    migrations,
    captchaRules,
    new Set([historicalCaptchaSeed]),
    removalAllowance,
  )
  assertNoViolations('migration captcha allowlist', residuals)

  const seed = fs.readFileSync(path.resolve(repositoryRoot, historicalCaptchaSeed), 'utf8')
  const historicalMatches = seed.match(/security\.captcha\.enabled/giu) ?? []
  assert.equal(
    historicalMatches.length,
    1,
    `${historicalCaptchaSeed} must retain exactly one immutable historical captcha key`,
  )

  const removal = fs.readFileSync(path.resolve(repositoryRoot, historicalCaptchaRemoval), 'utf8')
  assert.match(
    removal,
    /DELETE FROM system_parameter\s+WHERE parameter_key = 'security\.captcha\.enabled';/u,
  )
  assert.equal((removal.match(/security\.captcha\.enabled/gu) ?? []).length, 1)
})

test('active operational documentation contains no Redis or captcha requirement', () => {
  const files = collectFiles(['docs']).filter((file) => file.endsWith('.md'))
  assertNoViolations(
    'operational documentation contract',
    violations(files, [...redisRuntimeRules, ...captchaRules], historicalDocumentAllowlist),
  )
})
