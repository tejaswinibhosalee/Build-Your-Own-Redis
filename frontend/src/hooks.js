import { useEffect, useRef, useState } from 'react';

/**
 * Calls `fetcher` right away and then every `intervalMs`, and returns the
 * latest result. Used for stats, the key list and the inspector.
 *
 *  - Pauses while the browser tab is hidden (no wasted requests).
 *  - Never runs two fetches of the same thing at once.
 *  - Re-runs immediately whenever `trigger` changes, so the UI updates
 *    instantly after you run a command instead of waiting for the timer.
 */
export function usePolling(fetcher, intervalMs, { enabled = true, trigger = 0 } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const fetcherRef = useRef(fetcher);
  const inFlight = useRef(false);

  // Always call the newest version of `fetcher` (it may capture new filter values).
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;

    const run = async (force) => {
      if (inFlight.current || (document.hidden && !force)) return;
      inFlight.current = true;
      try {
        const result = await fetcherRef.current();
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e);
      } finally {
        inFlight.current = false;
      }
    };

    run(true);
    const id = setInterval(() => run(false), intervalMs);
    const onVisible = () => !document.hidden && run(true);
    document.addEventListener('visibilitychange', onVisible);
    // Cleanup: React calls this when the component goes away or inputs change.
    return () => {
      cancelled = true;
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [enabled, intervalMs, trigger]);

  return { data, error };
}

/** Returns Date.now(), refreshed every `intervalMs` - drives live TTL countdowns. */
export function useNow(intervalMs = 250) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
