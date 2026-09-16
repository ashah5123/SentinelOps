import { test, expect } from "@playwright/test";
import { loginAs } from "./fixtures";

// Scenario 1: user authentication (Authorization Code + PKCE against the real local Keycloak).
test("user can sign in and reach the dashboard", async ({ page }) => {
  await loginAs(page, "viewer-demo");
  await expect(page.getByRole("status").filter({ hasText: "System status" })).toBeVisible();
});

test("an unauthenticated visitor is shown a sign-in prompt, not a broken page", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});

// Scenario 6: session expiration and recovery.
test("an expired session shows a clear message and lets the user sign in again", async ({
  page,
}) => {
  await loginAs(page, "viewer-demo");

  // Simulate the access token expiring by clearing the session-storage-backed OIDC user state
  // (never localStorage — see src/auth/AuthProvider.tsx) and forcing a request that will 401.
  await page.evaluate(() => sessionStorage.clear());
  await page.goto("/incidents");

  await expect(page.getByRole("heading", { name: /session expired|signed out/i })).toBeVisible();
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(/realms\/sentinelops\/protocol\/openid-connect\/auth/);
});
