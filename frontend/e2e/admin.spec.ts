import { test, expect } from "@playwright/test";
import { loginAs } from "./fixtures";

// Scenario 7: administrator viewing and replaying an eligible failed event.
// Scenario 8 (folded in): the audit trail reflects the replay action.
test("admin can view the recovery page and replay an eligible dead-letter topic", async ({
  page,
}) => {
  await loginAs(page, "admin-demo");
  await page.getByRole("link", { name: "Recovery" }).click();
  await expect(page.getByRole("heading", { name: "Administrative recovery" })).toBeVisible();

  await page
    .getByRole("row", { name: /telemetry\.anomaly\.v1\.dlq/ })
    .getByRole("button", { name: "Replay eligible events" })
    .click();
  await page.getByRole("button", { name: "Replay" }).click(); // confirm dialog

  await expect(page.getByText(/replayed, \d+ failed/)).toBeVisible();

  // The replay is audited — the "recent replay actions" list on this same page reflects it.
  await expect(
    page.getByRole("listitem").filter({ hasText: "DEAD_LETTER_REPLAYED" }).first(),
  ).toBeVisible();
});

test("admin can read the per-incident audit trail that other roles cannot", async ({ page }) => {
  await loginAs(page, "admin-demo");
  await page.goto("/incidents");
  await page.getByRole("table").getByRole("link").first().click();
  await expect(page.getByRole("heading", { name: "Audit history" })).toBeVisible();
});
