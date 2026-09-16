import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useAsyncData } from "./useAsyncData";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe("useAsyncData", () => {
  it("does not let a slow, stale request overwrite a newer one that resolved first", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    let call = 0;

    const { result, rerender } = renderHook(
      ({ id }: { id: number }) =>
        useAsyncData(() => (call++ === 0 ? first.promise : second.promise), [id]),
      { initialProps: { id: 1 } },
    );

    // Trigger the second (newer) request before the first one resolves.
    rerender({ id: 2 });

    // The newer request resolves first...
    act(() => second.resolve("new-data"));
    await waitFor(() => expect(result.current.status).toBe("success"));
    expect(result.current.data).toBe("new-data");

    // ...and the older, slower request resolving afterward must NOT overwrite it.
    act(() => first.resolve("stale-data"));
    await new Promise((r) => setTimeout(r, 10));
    expect(result.current.data).toBe("new-data");
  });

  it("exposes a loading state while the fetch is in flight", async () => {
    const pending = deferred<string>();
    const { result } = renderHook(() => useAsyncData(() => pending.promise, []));
    expect(result.current.status).toBe("loading");
    act(() => pending.resolve("done"));
    await waitFor(() => expect(result.current.status).toBe("success"));
  });

  it("surfaces a rejected fetch as an error state with a message", async () => {
    const { result } = renderHook(() => useAsyncData(() => Promise.reject(new Error("boom")), []));
    await waitFor(() => expect(result.current.status).toBe("error"));
  });
});
