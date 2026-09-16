import { describe, expect, it } from "vitest";
import { DEFAULT_FILTERS, filtersFromSearchParams, filtersToSearchParams } from "./filters";

describe("filtersFromSearchParams", () => {
  it("returns defaults when the URL has no query parameters", () => {
    const filters = filtersFromSearchParams(new URLSearchParams(""));
    expect(filters).toEqual(DEFAULT_FILTERS);
  });

  it("parses known, valid filter values", () => {
    const filters = filtersFromSearchParams(
      new URLSearchParams("status=INVESTIGATING&severity=SEV1&page=2&size=50"),
    );
    expect(filters.status).toBe("INVESTIGATING");
    expect(filters.severity).toBe("SEV1");
    expect(filters.page).toBe(2);
    expect(filters.size).toBe(50);
  });

  it("silently drops an unrecognized status value rather than sending it to the API", () => {
    const filters = filtersFromSearchParams(new URLSearchParams("status=NOT_A_REAL_STATUS"));
    expect(filters.status).toBeUndefined();
  });

  it("silently drops an unrecognized severity value", () => {
    const filters = filtersFromSearchParams(new URLSearchParams("severity=SEV9"));
    expect(filters.severity).toBeUndefined();
  });

  it("only accepts an allowlisted sort expression", () => {
    const allowed = filtersFromSearchParams(new URLSearchParams("sort=severity,asc"));
    expect(allowed.sort).toBe("severity,asc");

    const disallowed = filtersFromSearchParams(
      new URLSearchParams("sort=title,asc"), // not in ALLOWED_SORTS
    );
    expect(disallowed.sort).toBe(DEFAULT_FILTERS.sort);
  });

  it("rejects a negative or non-numeric page", () => {
    expect(filtersFromSearchParams(new URLSearchParams("page=-1")).page).toBe(0);
    expect(filtersFromSearchParams(new URLSearchParams("page=abc")).page).toBe(0);
  });

  it("caps size to a sane bound", () => {
    expect(filtersFromSearchParams(new URLSearchParams("size=100000")).size).toBe(20);
  });

  it("parses the unassigned flag only when exactly 'true'", () => {
    expect(filtersFromSearchParams(new URLSearchParams("unassigned=true")).unassigned).toBe(true);
    expect(
      filtersFromSearchParams(new URLSearchParams("unassigned=yes")).unassigned,
    ).toBeUndefined();
  });

  it("rejects an unparseable detectedFrom/detectedTo date", () => {
    const filters = filtersFromSearchParams(new URLSearchParams("detectedFrom=not-a-date"));
    expect(filters.detectedFrom).toBeUndefined();
  });
});

describe("filtersToSearchParams", () => {
  it("omits fields that equal the default (a clean shareable URL for the default view)", () => {
    const params = filtersToSearchParams(DEFAULT_FILTERS);
    expect(params.toString()).toBe("");
  });

  it("round-trips a non-default filter set", () => {
    const original = { ...DEFAULT_FILTERS, status: "MITIGATING" as const, page: 3 };
    const params = filtersToSearchParams(original);
    const roundTripped = filtersFromSearchParams(params);
    expect(roundTripped.status).toBe("MITIGATING");
    expect(roundTripped.page).toBe(3);
  });
});
