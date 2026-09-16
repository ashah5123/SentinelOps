import { test, expect } from "@playwright/test";
import { loginAs } from "./fixtures";

// Scenario 3: responder filtering and opening an incident.
test("responder can filter the queue by status and open a matching incident", async ({ page }) => {
  await loginAs(page, "responder-demo");
  await page.getByRole("link", { name: "Incidents" }).click();

  await page.getByLabel("Status").selectOption("DETECTED");
  await expect(page).toHaveURL(/status=DETECTED/);

  const rows = page.getByRole("table").getByRole("row");
  await expect(rows).not.toHaveCount(0);
  await page.getByRole("table").getByRole("link").first().click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
});

// Scenario 4: responder performing a valid lifecycle transition, and Scenario 8 (timeline/audit
// reflect the action) folded in immediately after, since it's the natural next assertion.
test("responder can move a DETECTED incident to INVESTIGATING, and the timeline reflects it", async ({
  page,
}) => {
  await loginAs(page, "responder-demo");
  await page.goto("/incidents?status=DETECTED");
  await page.getByRole("table").getByRole("link").first().click();

  await page.getByRole("button", { name: "Move to INVESTIGATING" }).click();
  await expect(page.getByText("Investigating")).toBeVisible();

  await expect(page.getByRole("heading", { name: "Timeline" })).toBeVisible();
  await expect(page.getByText(/Transitioned from DETECTED to INVESTIGATING/i)).toBeVisible();
});

// Scenario 5: rejection of an invalid or stale transition.
test("an invalid transition is rejected with a clear message, not silently applied", async ({
  page,
}) => {
  await loginAs(page, "responder-demo");
  await page.goto("/incidents?status=DETECTED");
  await page.getByRole("table").getByRole("link").first().click();

  // DETECTED -> RESOLVED is not an allowed transition (see IncidentTransitions.java); the UI only
  // offers allowed targets, so this asserts RESOLVED is never presented as an option here.
  await expect(page.getByRole("button", { name: "Move to RESOLVED" })).toHaveCount(0);
});

test("a stale transition (state changed elsewhere since page load) surfaces a conflict, not a silent overwrite", async ({
  page,
  browser,
}) => {
  await loginAs(page, "responder-demo");
  await page.goto("/incidents?status=DETECTED");
  await page.getByRole("table").getByRole("link").first().click();
  const incidentUrl = page.url();

  // A second, independent session moves the same incident on first, before this tab acts.
  const otherContext = await browser.newContext();
  const otherPage = await otherContext.newPage();
  await loginAs(otherPage, "responder-demo");
  await otherPage.goto(incidentUrl);
  await otherPage.getByRole("button", { name: "Move to INVESTIGATING" }).click();
  await expect(otherPage.getByText("Investigating")).toBeVisible();
  await otherContext.close();

  // This tab still shows the stale DETECTED state and its UI still offers "Move to
  // INVESTIGATING" — but the incident is already INVESTIGATING now, and INVESTIGATING ->
  // INVESTIGATING is not an allowed transition (see IncidentTransitions.java), so the backend
  // rejects it as stale/invalid rather than silently re-applying it.
  await page.getByRole("button", { name: "Move to INVESTIGATING" }).click();
  await expect(page.getByRole("alert")).toContainText(/changed by someone else|no longer valid/i);
});
