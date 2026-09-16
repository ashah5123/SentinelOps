import { test, expect } from "@playwright/test";
import { loginAs } from "./fixtures";

// Scenario 2: viewer opening an incident without mutation access.
test("viewer can open an incident but sees no mutating actions", async ({ page }) => {
  await loginAs(page, "viewer-demo");
  await page.getByRole("link", { name: "Incidents" }).click();
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();

  const firstIncidentLink = page.getByRole("table").getByRole("link").first();
  await firstIncidentLink.click();

  await expect(page.getByText(/cannot perform lifecycle actions/i)).toBeVisible();
  await expect(page.getByRole("button", { name: /move to/i })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Assign" })).toHaveCount(0);
});

test("viewer is denied the admin recovery route", async ({ page }) => {
  await loginAs(page, "viewer-demo");
  await page.goto("/admin/recovery");
  await expect(page.getByRole("heading", { name: "Access restricted" })).toBeVisible();
});
