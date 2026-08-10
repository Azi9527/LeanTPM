import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const masterDataApi = readFileSync(
  new URL('../src/api/masterData.ts', import.meta.url),
  'utf8',
)
const organizationView = readFileSync(
  new URL('../src/views/master-data/organizations/OrganizationView.vue', import.meta.url),
  'utf8',
)
const userListView = readFileSync(
  new URL('../src/views/system/users/UserListView.vue', import.meta.url),
  'utf8',
)

test('organization contract exposes an explicit section type', () => {
  assert.match(masterDataApi, /organizationType:[^\n]*'SECTION'/)
  assert.match(organizationView, /SECTION:\s*'工段'/)
  assert.match(organizationView, /SECTION:\s*'TEAM'/)
})

test('organization type selector always exposes the complete type set', () => {
  for (const type of [
    'ENTERPRISE',
    'FACTORY',
    'DEPARTMENT',
    'WORKSHOP',
    'LINE',
    'SECTION',
    'TEAM',
  ]) {
    assert.match(organizationView, new RegExp(`\\b${type}:`))
  }
  assert.match(
    organizationView,
    /v-for="\(label, value\) in typeLabels"/,
  )
})

test('personnel relationship labels a section manager as section leader', () => {
  assert.match(userListView, /SECTION:\s*'工段'/)
  assert.match(
    userListView,
    /organizationType === 'SECTION'\) return '工段长'/,
  )
  assert.match(
    userListView,
    /\['WORKSHOP', 'LINE', 'SECTION'\]\.includes/,
  )
})
