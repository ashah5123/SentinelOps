import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AsyncBoundary } from "./AsyncBoundary";
import type { AsyncState } from "../hooks/useAsyncData";

describe("AsyncBoundary", () => {
  it("shows the loading state before any data has arrived", () => {
    const state: AsyncState<string[]> = { status: "loading", data: null };
    render(
      <AsyncBoundary state={state} loadingLabel="Loading things…">
        {(data) => <div>{data.join(",")}</div>}
      </AsyncBoundary>,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Loading things…");
  });

  it("shows the empty state when the loaded data is empty per emptyCheck", () => {
    const state: AsyncState<string[]> = { status: "success", data: [] };
    render(
      <AsyncBoundary state={state} emptyCheck={(d) => d.length === 0} emptyLabel="Nothing here.">
        {(data) => <div>{data.join(",")}</div>}
      </AsyncBoundary>,
    );
    expect(screen.getByText("Nothing here.")).toBeInTheDocument();
  });

  it("shows the error state with a retry action when the fetch failed with no prior data", () => {
    const onRetry = vi.fn();
    const state: AsyncState<string[]> = { status: "error", data: null, error: "It broke." };
    render(
      <AsyncBoundary state={state} onRetry={onRetry}>
        {(data) => <div>{data.join(",")}</div>}
      </AsyncBoundary>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("It broke.");
    screen.getByRole("button", { name: "Retry" }).click();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("renders the success content when data is present", () => {
    const state: AsyncState<string[]> = { status: "success", data: ["a", "b"] };
    render(<AsyncBoundary state={state}>{(data) => <div>{data.join(",")}</div>}</AsyncBoundary>);
    expect(screen.getByText("a,b")).toBeInTheDocument();
  });

  it("keeps showing previously-loaded data (not the loading state) during a background refetch", () => {
    const state: AsyncState<string[]> = { status: "loading", data: ["a", "b"] };
    render(<AsyncBoundary state={state}>{(data) => <div>{data.join(",")}</div>}</AsyncBoundary>);
    expect(screen.getByText("a,b")).toBeInTheDocument();
  });
});
