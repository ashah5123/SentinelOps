import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, RequestCancelledError } from "../api/client";

export type AsyncState<T> =
  | { status: "loading"; data: T | null }
  | { status: "success"; data: T }
  | { status: "error"; data: T | null; error: string; errorCode?: string };

/**
 * Fetches with an AbortController per call (cancelled on re-fetch/unmount) and, as a second
 * layer of defense against a slow older request resolving after a newer one, an incrementing
 * request-id guard — so a stale response can never overwrite newer data, even if abort itself
 * doesn't take effect in time (e.g. a request already past its network phase).
 */
export function useAsyncData<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: unknown[],
): AsyncState<T> & { refetch: () => void } {
  const [state, setState] = useState<AsyncState<T>>({ status: "loading", data: null });
  const requestIdRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const [refetchToken, setRefetchToken] = useState(0);

  const run = useCallback(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const thisRequestId = ++requestIdRef.current;

    setState((prev) => ({ status: "loading", data: prev.data }));
    fetcher(controller.signal)
      .then((data) => {
        if (requestIdRef.current !== thisRequestId) return; // a newer request has already started
        setState({ status: "success", data });
      })
      .catch((err) => {
        if (err instanceof RequestCancelledError) return;
        if (requestIdRef.current !== thisRequestId) return;
        const message = err instanceof ApiError ? err.message : "Something went wrong.";
        const errorCode = err instanceof ApiError ? err.problem?.errorCode : undefined;
        setState((prev) => ({ status: "error", data: prev.data, error: message, errorCode }));
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    run();
    return () => abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run, refetchToken]);

  return { ...state, refetch: () => setRefetchToken((t) => t + 1) };
}
