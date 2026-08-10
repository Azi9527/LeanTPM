const assert = require("node:assert/strict");
const test = require("node:test");

const {
  createReleaseTracker,
  isReleaseTerminal,
  shouldTrackRelease
} = require("../../main/resources/static/release-tracker.js");

test("tracks queued release through agent verification and stops at deployed", async () => {
  const scheduled = [];
  const states = ["QUEUED", "AGENT_VERIFIED", "DEPLOYED"];
  const observations = [];
  const tracker = createReleaseTracker({
    intervalMs: 5000,
    poll: async (releaseId) => {
      observations.push(releaseId);
      return states.shift();
    },
    schedule: (callback, delay) => {
      scheduled.push({ callback, delay });
      return scheduled.length;
    },
    cancel: () => {}
  });

  tracker.start("release-001");
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delay, 0);

  await scheduled.shift().callback();
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delay, 5000);

  await scheduled.shift().callback();
  assert.equal(scheduled.length, 1);

  await scheduled.shift().callback();
  assert.equal(scheduled.length, 0);
  assert.equal(tracker.isRunning(), false);
  assert.deepEqual(observations, ["release-001", "release-001", "release-001"]);
});

test("retries a transient refresh failure without converting it to release failure", async () => {
  const scheduled = [];
  const errors = [];
  let attempts = 0;
  const tracker = createReleaseTracker({
    intervalMs: 4000,
    poll: async () => {
      attempts += 1;
      if (attempts === 1) {
        throw new Error("temporary outage");
      }
      return "FAILED";
    },
    onError: (error) => errors.push(error.message),
    schedule: (callback, delay) => {
      scheduled.push({ callback, delay });
      return scheduled.length;
    },
    cancel: () => {}
  });

  tracker.start("release-002");
  await scheduled.shift().callback();
  assert.deepEqual(errors, ["temporary outage"]);
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delay, 4000);

  await scheduled.shift().callback();
  assert.equal(scheduled.length, 0);
  assert.equal(tracker.isRunning(), false);
});

test("does not track unauthenticated, hidden, empty, or terminal releases", () => {
  assert.equal(shouldTrackRelease({ authenticated: true, visible: true, state: "QUEUED" }), true);
  assert.equal(shouldTrackRelease({ authenticated: true, visible: true, state: "AGENT_VERIFIED" }), true);
  assert.equal(shouldTrackRelease({ authenticated: false, visible: true, state: "QUEUED" }), false);
  assert.equal(shouldTrackRelease({ authenticated: true, visible: false, state: "QUEUED" }), false);
  assert.equal(shouldTrackRelease({ authenticated: true, visible: true, state: "DEPLOYED" }), false);
  assert.equal(shouldTrackRelease({ authenticated: true, visible: true, state: "FAILED" }), false);
  assert.equal(shouldTrackRelease({ authenticated: true, visible: true, state: null }), false);
  assert.equal(isReleaseTerminal("DEPLOYED"), true);
  assert.equal(isReleaseTerminal("FAILED"), true);
});
