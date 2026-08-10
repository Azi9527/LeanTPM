(function exposeReleaseTracker(root, factory) {
  const api = factory();
  root.LeanTpmReleaseTracker = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis === "undefined" ? window : globalThis, function releaseTrackerFactory() {
  "use strict";

  const TRACKABLE_STATES = new Set(["QUEUED", "AGENT_VERIFIED"]);
  const TERMINAL_STATES = new Set(["DEPLOYED", "FAILED"]);

  function isReleaseTerminal(state) {
    return TERMINAL_STATES.has(state);
  }

  function shouldTrackRelease({ authenticated, visible, state }) {
    return Boolean(authenticated && visible && TRACKABLE_STATES.has(state));
  }

  function createReleaseTracker({
    poll,
    intervalMs = 5000,
    schedule = setTimeout,
    cancel = clearTimeout,
    onError = () => {}
  }) {
    if (typeof poll !== "function") {
      throw new TypeError("Release tracker requires a poll function");
    }
    if (!Number.isInteger(intervalMs) || intervalMs < 1000) {
      throw new TypeError("Release tracker interval must be at least 1000 ms");
    }

    let activeReleaseId = null;
    let timer = null;
    let generation = 0;

    function stop() {
      generation += 1;
      activeReleaseId = null;
      if (timer !== null) {
        cancel(timer);
        timer = null;
      }
    }

    function arm(delay) {
      const expectedGeneration = generation;
      timer = schedule(() => tick(expectedGeneration), delay);
    }

    async function tick(expectedGeneration) {
      if (expectedGeneration !== generation || !activeReleaseId) {
        return;
      }
      timer = null;
      const releaseId = activeReleaseId;
      try {
        const releaseState = await poll(releaseId);
        if (expectedGeneration !== generation || releaseId !== activeReleaseId) {
          return;
        }
        if (TRACKABLE_STATES.has(releaseState)) {
          arm(intervalMs);
        } else {
          stop();
        }
      } catch (error) {
        if (expectedGeneration !== generation || releaseId !== activeReleaseId) {
          return;
        }
        onError(error);
        arm(intervalMs);
      }
    }

    function start(releaseId) {
      if (typeof releaseId !== "string" || !releaseId.trim()) {
        throw new TypeError("Release tracker requires a release id");
      }
      stop();
      activeReleaseId = releaseId;
      generation += 1;
      arm(0);
    }

    return Object.freeze({
      isRunning: () => activeReleaseId !== null,
      start,
      stop
    });
  }

  return Object.freeze({
    createReleaseTracker,
    isReleaseTerminal,
    shouldTrackRelease
  });
});
