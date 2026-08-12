import test from 'node:test'
import assert from 'node:assert/strict'

import {
  afterPhotoMinimumChange,
  afterPhotoRequiredChange,
} from '../src/utils/inspection-item-photo-rule.ts'

test('enabling required photos raises a zero minimum to one immediately', () => {
  assert.deepEqual(afterPhotoRequiredChange(true, 0), {
    photoRequired: true,
    photoMinCount: 1,
  })
})

test('setting the minimum to zero disables required photos immediately', () => {
  assert.deepEqual(afterPhotoMinimumChange(true, 0), {
    photoRequired: false,
    photoMinCount: 0,
  })
})

test('a positive minimum enables required photos because it is a real minimum', () => {
  assert.deepEqual(afterPhotoMinimumChange(false, 2), {
    photoRequired: true,
    photoMinCount: 2,
  })
})
