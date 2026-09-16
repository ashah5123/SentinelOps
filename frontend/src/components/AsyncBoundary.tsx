import type { ReactNode } from "react";
import type { AsyncState } from "../hooks/useAsyncData";

interface Props<T> {
  state: AsyncState<T>;
  onRetry?: () => void;
  loadingLabel?: string;
  emptyCheck?: (data: T) => boolean;
  emptyLabel?: string;
  children: (data: T) => ReactNode;
}

/** Every asynchronous screen's required loading/empty/error/recovery states, in one place. */
export function AsyncBoundary<T>({
  state,
  onRetry,
  loadingLabel = "Loading…",
  emptyCheck,
  emptyLabel = "Nothing to show.",
  children,
}: Props<T>) {
  if (state.status === "loading" && state.data === null) {
    return (
      <div role="status" aria-live="polite" className="async-loading">
        {loadingLabel}
      </div>
    );
  }

  if (state.status === "error" && state.data === null) {
    return (
      <div role="alert" className="async-error">
        <p>{state.error}</p>
        {onRetry && (
          <button type="button" onClick={onRetry}>
            Retry
          </button>
        )}
      </div>
    );
  }

  if (state.data === null) {
    return null;
  }

  if (emptyCheck?.(state.data)) {
    return (
      <div className="async-empty" role="status">
        {emptyLabel}
      </div>
    );
  }

  return <>{children(state.data)}</>;
}
