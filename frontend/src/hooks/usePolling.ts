import { useEffect, useRef, useState } from "react";

export interface PollingOptions {
  /** Normal interval between successful polls. */
  baseIntervalMs: number;
  /** Ceiling for the backed-off interval after repeated failures. */
  maxIntervalMs: number;
  /** Set to false to stop polling entirely (e.g. user signed out, or left this page). */
  enabled: boolean;
}

export interface PollingState {
  /** True once at least one poll has failed and a retry is scheduled with backoff. */
  isBackingOff: boolean;
  /** Timestamp of the last successful poll — used to show "data as of" / staleness. */
  lastSuccessAt: number | null;
  /** Triggers an immediate poll outside the normal schedule (the "manual refresh" action). */
  refreshNow: () => void;
}

/**
 * Bounded polling with exponential backoff and jitter — used in place of a real-time
 * subsystem the backend doesn't provide (no SSE/WebSocket endpoint exists; see
 * docs/api/incident-service.md). On success, the interval resets to baseIntervalMs; on failure,
 * it doubles (capped at maxIntervalMs) with +/-20% jitter so many tabs/users don't all retry in
 * lockstep. Stops entirely when `enabled` is false or the component unmounts.
 */
export function usePolling(callback: () => Promise<void>, options: PollingOptions): PollingState {
  const [isBackingOff, setIsBackingOff] = useState(false);
  const [lastSuccessAt, setLastSuccessAt] = useState<number | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const intervalRef = useRef(options.baseIntervalMs);
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  const scheduleNext = (delayMs: number) => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(poll, delayMs);
  };

  const poll = async () => {
    try {
      await callbackRef.current();
      intervalRef.current = options.baseIntervalMs;
      setIsBackingOff(false);
      setLastSuccessAt(Date.now());
    } catch {
      intervalRef.current = Math.min(intervalRef.current * 2, options.maxIntervalMs);
      setIsBackingOff(true);
    }
    if (options.enabled) {
      const jitter = 0.8 + Math.random() * 0.4; // +/-20%
      scheduleNext(intervalRef.current * jitter);
    }
  };

  useEffect(() => {
    if (!options.enabled) {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      return;
    }
    intervalRef.current = options.baseIntervalMs;
    scheduleNext(options.baseIntervalMs);
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options.enabled, options.baseIntervalMs, options.maxIntervalMs]);

  return {
    isBackingOff,
    lastSuccessAt,
    refreshNow: () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      void poll();
    },
  };
}
