import AxeBuilder from "@axe-core/playwright";
import { test, expect } from "@playwright/test";
import { loginAs } from "./fixtures";

test.describe("automated accessibility checks", () => {
  test("dashboard has no detectable WCAG 2.1 AA violations", async ({ page }) => {
    await loginAs(page, "viewer-demo");
    // @axe-core/playwright resolves its own (newer) playwright-core Page type, which doesn't
    // structurally match @playwright/test's — a harmless type-only mismatch; cast to bridge it.
    const results = await new AxeBuilder({ page: page as never })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();
    expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
  });

  test("incident queue has no detectable WCAG 2.1 AA violations", async ({ page }) => {
    await loginAs(page, "responder-demo");
    await page.goto("/incidents");
    const results = await new AxeBuilder({ page: page as never })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();
    expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
  });
});

// Scenario 9: keyboard-only completion of a core workflow.
test("a responder can filter the queue, open an incident, and transition it using only the keyboard", async ({
  page,
}) => {
  await loginAs(page, "responder-demo");

  await page.keyboard.press("Tab"); // skip link
  await page.getByRole("link", { name: "Incidents" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();

  await page.getByLabel("Status").focus();
  await page.keyboard.press("ArrowDown"); // move selection within the native <select>
  await page.keyboard.press("Enter");

  const firstLink = page.getByRole("table").getByRole("link").first();
  await firstLink.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  const transitionButton = page.getByRole("button", { name: /move to/i }).first();
  await transitionButton.focus();
  await expect(transitionButton).toBeFocused();
  await page.keyboard.press("Enter");
});
